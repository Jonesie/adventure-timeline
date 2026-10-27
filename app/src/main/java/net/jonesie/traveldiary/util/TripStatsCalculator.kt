package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Impression
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.Trip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TransportStat(val type: TransportType, val km: Double, val hours: Double, val legs: Int)
data class YearStat(val year: Int, val trips: Int, val km: Double)
data class TripDistance(val trip: Trip, val km: Double)

data class TripStats(
    val trips: Int,
    val daysOnTheRoad: Int,
    val totalKm: Double,
    val legs: Int,
    val photos: Int,
    val videos: Int,
    val voiceNotes: Int,
    val notes: Int,
    val byTransport: List<TransportStat>,
    val byYear: List<YearStat>,
    /** Index 0 = not rated, 1..5 = that many stars. */
    val ratingCounts: List<Int>,
    val averageRating: Double?,
    val averageDifficulty: Double?,
    val averageEnjoyment: Double?,
    val ratedLegs: Int,
    val longestTrip: TripDistance?,
    val topRated: List<Trip>
)

/** Numbers for the stats screen, worked out from what's stored. Distances are approximate (GPS trail points). */
object TripStatsCalculator {

    fun compute(
        trips: List<Trip>,
        legs: List<Leg>,
        trails: Map<Long, List<RoutePoint>>,
        media: List<MediaItem>,
        impressions: List<Impression>
    ): TripStats {
        val legsByTrip = legs.groupBy { it.tripId }
        val kmByTransport = HashMap<TransportType, Double>()
        val tripKm = HashMap<Long, Double>()

        trips.forEach { trip ->
            val tripLegs = legsByTrip[trip.id].orEmpty()
            val trail = trails[trip.id].orEmpty()
            var km = 0.0
            RouteShape.splitByTransport(trail, tripLegs).forEach { piece ->
                var d = 0.0
                for (i in 1 until piece.points.size) {
                    val a = piece.points[i - 1]
                    val b = piece.points[i]
                    d += RouteShape.distanceKm(a.lat, a.lon, b.lat, b.lon)
                }
                km += d
                kmByTransport.merge(piece.transport ?: TransportType.OTHER, d, Double::plus)
            }
            // Flights usually have no trail, so count the straight line between the airports.
            RouteShape.sparseLegs(trail, tripLegs, TransportType.PLANE).forEach { leg ->
                val d = RouteShape.distanceKm(leg.originLat!!, leg.originLon!!, leg.destinationLat!!, leg.destinationLon!!)
                km += d
                kmByTransport.merge(TransportType.PLANE, d, Double::plus)
            }
            tripKm[trip.id] = km
        }

        val hoursByTransport = HashMap<TransportType, Double>()
        val legCount = HashMap<TransportType, Int>()
        legs.forEach { leg ->
            legCount.merge(leg.transportType, 1, Int::plus)
            leg.endTime?.let { end ->
                if (end > leg.startTime) hoursByTransport.merge(leg.transportType, (end - leg.startTime) / 3_600_000.0, Double::plus)
            }
        }
        val transports = (kmByTransport.keys + hoursByTransport.keys + legCount.keys).distinct()
            .map { TransportStat(it, kmByTransport[it] ?: 0.0, hoursByTransport[it] ?: 0.0, legCount[it] ?: 0) }
            .sortedWith(compareByDescending<TransportStat> { it.km }.thenByDescending { it.hours })

        val zone = ZoneId.systemDefault()
        fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val days = HashSet<LocalDate>()
        trips.forEach { trip ->
            val start = day(trip.startDate)
            val end = day(trip.endDate ?: trip.startDate).let { if (it.isBefore(start)) start else it }
            var d = start
            var guard = 0
            while (!d.isAfter(end) && guard++ < 400) { days += d; d = d.plusDays(1) }
        }

        val byYear = trips.groupBy { day(it.startDate).year }.toSortedMap().map { (year, list) ->
            YearStat(year, list.size, list.sumOf { tripKm[it.id] ?: 0.0 })
        }

        val rated = trips.filter { it.rating > 0 }
        val ratingCounts = List(6) { stars -> trips.count { it.rating == stars } }
        val ratedLegs = legs.filter { it.difficulty > 0 || it.enjoyment > 0 }

        return TripStats(
            trips = trips.size,
            daysOnTheRoad = days.size,
            totalKm = tripKm.values.sum(),
            legs = legs.size,
            photos = media.count { it.type == MediaType.PHOTO },
            videos = media.count { it.type == MediaType.VIDEO },
            voiceNotes = media.count { it.type == MediaType.AUDIO },
            notes = impressions.size,
            byTransport = transports,
            byYear = byYear,
            ratingCounts = ratingCounts,
            averageRating = rated.takeIf { it.isNotEmpty() }?.map { it.rating }?.average(),
            averageDifficulty = legs.filter { it.difficulty > 0 }.takeIf { it.isNotEmpty() }?.map { it.difficulty }?.average(),
            averageEnjoyment = legs.filter { it.enjoyment > 0 }.takeIf { it.isNotEmpty() }?.map { it.enjoyment }?.average(),
            ratedLegs = ratedLegs.size,
            longestTrip = trips.maxByOrNull { tripKm[it.id] ?: 0.0 }?.let { TripDistance(it, tripKm[it.id] ?: 0.0) }
                ?.takeIf { it.km > 0 },
            topRated = trips.filter { it.rating >= 4 }.sortedWith(compareByDescending<Trip> { it.rating }.thenByDescending { it.startDate }).take(5)
        )
    }
}
