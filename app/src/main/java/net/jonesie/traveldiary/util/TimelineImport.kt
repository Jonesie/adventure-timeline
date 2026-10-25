package net.jonesie.traveldiary.util

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import net.jonesie.traveldiary.data.TransportType
import java.io.InputStreamReader
import java.time.OffsetDateTime

data class TripCandidate(
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Int
)

data class ActivityLeg(
    val startTime: Long,
    val endTime: Long,
    val transportType: TransportType,
    val originLat: Double?,
    val originLon: Double?,
    val destinationLat: Double?,
    val destinationLon: Double?
)

data class PathPoint(
    val timestamp: Long,
    val lat: Double,
    val lon: Double
)

data class ParsedTimeline(
    val trips: List<TripCandidate>,
    val legs: List<ActivityLeg>,
    val pathPoints: List<PathPoint>
)

/**
 * Streaming parse of a Google Timeline export (Settings > Location > Location services >
 * Timeline > Export Timeline Data). Only reads the "semanticSegments" array and only keeps
 * the "timelineMemory" (Google's own trip detection) and "activity" (route/transport) entries
 * — "visit" and the (much larger) "rawSignals" section are skipped entirely so this stays cheap
 * even on a 40MB+ export.
 */
object TimelineImport {

    fun parse(context: Context, uri: Uri): ParsedTimeline {
        val trips = mutableListOf<TripCandidate>()
        val legs = mutableListOf<ActivityLeg>()
        val pathPoints = mutableListOf<PathPoint>()

        context.contentResolver.openInputStream(uri)?.use { input ->
            JsonReader(InputStreamReader(input)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "semanticSegments") {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            readSegment(reader, trips, legs, pathPoints)
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return ParsedTimeline(trips, legs, pathPoints)
    }

    private fun readSegment(
        reader: JsonReader,
        trips: MutableList<TripCandidate>,
        legs: MutableList<ActivityLeg>,
        pathPoints: MutableList<PathPoint>
    ) {
        var startTime: Long? = null
        var endTime: Long? = null
        var tripDistanceKm: Int? = null
        var isTrip = false
        var activityType: String? = null
        var originLat: Double? = null
        var originLon: Double? = null
        var destLat: Double? = null
        var destLon: Double? = null
        val segmentPath = mutableListOf<PathPoint>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "startTime" -> startTime = parseInstant(reader.nextString())
                "endTime" -> endTime = parseInstant(reader.nextString())
                "timelinePath" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var pointLatLng: Pair<Double, Double>? = null
                        var pointTime: Long? = null
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "point" -> pointLatLng = parseLatLng(reader.nextString())
                                "time" -> pointTime = parseInstant(reader.nextString())
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (pointLatLng != null && pointTime != null) {
                            segmentPath.add(PathPoint(pointTime, pointLatLng.first, pointLatLng.second))
                        }
                    }
                    reader.endArray()
                }
                "timelineMemory" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "trip") {
                            isTrip = true
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "distanceFromOriginKms" -> tripDistanceKm = reader.nextInt()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "activity" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "start" -> {
                                val (lat, lon) = readLatLngObject(reader)
                                originLat = lat; originLon = lon
                            }
                            "end" -> {
                                val (lat, lon) = readLatLngObject(reader)
                                destLat = lat; destLon = lon
                            }
                            "topCandidate" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    if (reader.nextName() == "type") activityType = reader.nextString()
                                    else reader.skipValue()
                                }
                                reader.endObject()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (startTime == null || endTime == null) return

        pathPoints.addAll(segmentPath)

        if (isTrip) {
            trips.add(TripCandidate(startTime, endTime, tripDistanceKm ?: 0))
        }
        if (activityType != null) {
            legs.add(
                ActivityLeg(
                    startTime = startTime,
                    endTime = endTime,
                    transportType = mapTransportType(activityType),
                    originLat = originLat,
                    originLon = originLon,
                    destinationLat = destLat,
                    destinationLon = destLon
                )
            )
        }
    }

    /** Reads an object of the shape {"latLng": "-43.1234°, 172.1234°"}, leaving unrelated fields skipped. */
    private fun readLatLngObject(reader: JsonReader): Pair<Double?, Double?> {
        var lat: Double? = null
        var lon: Double? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "latLng") {
                val parsed = parseLatLng(reader.nextString())
                lat = parsed?.first; lon = parsed?.second
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return lat to lon
    }

    private fun parseLatLng(value: String): Pair<Double, Double>? {
        val parts = value.replace("°", "").split(",").map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun parseInstant(value: String): Long? =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

    private fun mapTransportType(googleType: String): TransportType = when (googleType) {
        "WALKING", "RUNNING", "HIKING" -> TransportType.WALK
        "CYCLING" -> TransportType.BIKE
        "MOTORCYCLING" -> TransportType.MOTORBIKE
        "IN_PASSENGER_VEHICLE", "IN_VEHICLE" -> TransportType.CAR
        "IN_BUS" -> TransportType.BUS
        "IN_TRAIN", "IN_SUBWAY", "IN_TRAM" -> TransportType.TRAIN
        "FLYING" -> TransportType.PLANE
        "IN_FERRY", "SAILING" -> TransportType.BOAT
        else -> TransportType.OTHER
    }
}
