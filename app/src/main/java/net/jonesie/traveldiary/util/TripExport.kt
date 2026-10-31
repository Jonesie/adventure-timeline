package net.jonesie.traveldiary.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TravelDiaryDatabase
import net.jonesie.traveldiary.data.TripDetail
import net.jonesie.traveldiary.ui.label
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class TripExportOptions(
    val includePhotos: Boolean = true,
    val includeVideos: Boolean = false,
    val includeAudio: Boolean = false,
    /** A picture of the route map, when the Share tab has one to hand. */
    val mapImage: Bitmap? = null
)

class TripExportResult(val file: File, val mediaFiles: Int, val mediaMissing: Int, val placesLookedUp: Int)

/**
 * Packs one trip into a zip for use outside the app, e.g. cutting a video: `trip.json` holds everything
 * known about the trip (legs, route, stops, place names, notes, incidents, a list of the media with
 * where and when each was taken), and `media/` holds the files, named in the order they were taken.
 */
object TripExport {
    const val JSON_NAME = "trip.json"
    const val MAP_NAME = "route-map.png"
    private const val FORMAT = "adventure-timeline-trip"
    private const val FORMAT_VERSION = 1L
    /** Reverse lookups need the network and take a moment each, so only so many are tried. */
    private const val MAX_PLACE_LOOKUPS = 80

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val isoFormat = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

    fun iso(millis: Long): String = isoFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** A photo with no GPS fix is sometimes stored as exactly 0,0, which is not a place anyone took it. */
    internal fun hasPosition(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && !(Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6)

    /** Where the trail was closest in time to [time], if it was recorded within [maxGapMs] of it. */
    internal fun trailPointAt(trail: List<RoutePoint>, time: Long, maxGapMs: Long = 30 * 60_000L): RoutePoint? =
        trail.minByOrNull { Math.abs(it.timestamp - time) }?.takeIf { Math.abs(it.timestamp - time) <= maxGapMs }

    /** Distance along the trail during the leg, or the straight line between its ends when there is no trail (a flight). */
    internal fun legDistanceKm(leg: Leg, legPoints: List<RoutePoint>): Double? {
        if (legPoints.size >= 3) {
            var km = 0.0
            for (i in 1 until legPoints.size) {
                km += RouteShape.distanceKm(legPoints[i - 1].lat, legPoints[i - 1].lon, legPoints[i].lat, legPoints[i].lon)
            }
            return km
        }
        val a = leg.originLat; val b = leg.originLon; val c = leg.destinationLat; val d = leg.destinationLon
        return if (a != null && b != null && c != null && d != null) RouteShape.distanceKm(a, b, c, d) else null
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(40).trim('-').ifBlank { "trip" }

    suspend fun export(context: Context, trip: TripDetail, options: TripExportOptions, onProgress: (String) -> Unit = {}): TripExportResult =
        withContext(Dispatchers.IO) {
            val dao = TravelDiaryDatabase.get(context).dao()
            val resolver = context.contentResolver
            val t = trip.trip
            val legs = trip.legs.sortedBy { it.startTime }
            val trail = dao.getRoutePointsForTrip(t.id).sortedBy { it.timestamp }
            val wanted = trip.media.filter {
                when (it.type) {
                    MediaType.PHOTO -> options.includePhotos
                    MediaType.VIDEO -> options.includeVideos
                    MediaType.AUDIO -> options.includeAudio
                }
            }.sortedBy { it.timestamp }

            // Place names for anything with a position. Photos taken close together share a lookup.
            val places = HashMap<String, String?>()
            var lookups = 0
            fun placeFor(lat: Double?, lon: Double?): String? {
                if (lat == null || lon == null || !hasPosition(lat, lon)) return null
                val key = "%.3f,%.3f".format(Locale.US, lat, lon)
                if (places.containsKey(key)) return places[key]
                if (lookups >= MAX_PLACE_LOOKUPS) return null
                lookups++
                return ReverseGeocoder.lookup(context, lat, lon)?.format(overseas = true).also { places[key] = it }
            }

            fun legIndexOf(legId: Long?): Long? = legId?.let { id -> legs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1L) }

            // Name each file by when it was taken, so the files sort into trip order in an editor.
            class Entry(val item: MediaItem, val name: String)
            val entries = wanted.mapIndexed { index, item ->
                val mime = runCatching { resolver.getType(Uri.parse(item.uri)) }.getOrNull()
                val ext = MediaFiles.ownedFile(context, item.uri)?.extension?.takeIf { it.isNotBlank() } ?: extensionFor(mime, item.type)
                val stamp = stampFormat.format(Instant.ofEpochMilli(item.timestamp).atZone(zone))
                Entry(item, "%03d_%s.%s".format(Locale.US, index + 1, stamp, ext))
            }

            val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "${safeName(t.title)}-trip-data.zip")
            val missing = HashSet<Long>()
            var written = 0

            ZipOutputStream(BufferedOutputStream(out.outputStream())).use { zip ->
                // The JSON goes first, but which files turned out to be unreadable is only known after
                // they are copied, so check each can be opened before writing it.
                val readable = entries.filter { e ->
                    val ok = runCatching { resolver.openInputStream(Uri.parse(e.item.uri))?.use { true } ?: false }.getOrDefault(false)
                    if (!ok) missing += e.item.id
                    ok
                }

                onProgress("Writing trip data…")
                zip.putNextEntry(ZipEntry(JSON_NAME))
                val w = JsonWriter(OutputStreamWriter(zip, Charsets.UTF_8))
                w.setIndent("  ")
                w.beginObject()
                w.name("format").value(FORMAT)
                w.name("version").value(FORMAT_VERSION)
                w.put("exportedAt", iso(System.currentTimeMillis()))
                w.put("timeZone", zone.id)

                // --- trip ---
                w.name("trip").beginObject()
                w.put("title", t.title)
                w.putTime("start", t.startDate)
                t.endDate?.let { w.putTime("end", it) }
                w.put("rating", t.rating.toLong())
                w.put("notes", t.notes)
                w.name("companions").beginArray(); trip.companions.forEach { w.value(it.name) }; w.endArray()
                w.endObject()

                // --- legs ---
                var totalKm = 0.0
                w.name("legs").beginArray()
                legs.forEachIndexed { index, leg ->
                    val end = leg.endTime
                    val points = if (end != null) trail.filter { it.timestamp in leg.startTime..end } else emptyList()
                    val km = legDistanceKm(leg, points)
                    if (km != null) totalKm += km
                    w.beginObject()
                    w.put("index", index + 1L)
                    w.put("transport", leg.transportType.name)
                    w.putTime("start", leg.startTime)
                    end?.let { w.putTime("end", it) }
                    if (end != null && end > leg.startTime) w.put("durationMinutes", Math.round((end - leg.startTime) / 60_000.0))
                    km?.let { w.put("distanceKm", Math.round(it * 100) / 100.0) }
                    w.put("difficulty", leg.difficulty.toLong())
                    w.put("enjoyment", leg.enjoyment.toLong())
                    w.name("from").beginObject()
                    w.put("name", leg.originName); w.put("lat", leg.originLat); w.put("lon", leg.originLon)
                    w.endObject()
                    w.name("to").beginObject()
                    w.put("name", leg.destinationName); w.put("lat", leg.destinationLat); w.put("lon", leg.destinationLon)
                    w.endObject()
                    w.endObject()
                }
                w.endArray()
                w.put("totalDistanceKm", Math.round(totalKm * 100) / 100.0)

                // --- stops (where one leg ends and the next starts) ---
                w.name("stops").beginArray()
                var stop = 0L
                fun stopOf(name: String, lat: Double?, lon: Double?, time: Long?) {
                    if (lat == null || lon == null) return
                    w.beginObject()
                    w.put("index", ++stop)
                    w.put("name", name); w.put("lat", lat); w.put("lon", lon)
                    time?.let { w.putTime("time", it) }
                    w.endObject()
                }
                legs.firstOrNull { it.originLat != null }?.let { stopOf(it.originName.ifBlank { "Start" }, it.originLat, it.originLon, it.startTime) }
                legs.forEach { stopOf(it.destinationName.ifBlank { "Stop" }, it.destinationLat, it.destinationLon, it.endTime ?: it.startTime) }
                w.endArray()

                // --- route: every recorded point as [lat, lon, time in ms] ---
                w.name("route").beginObject()
                w.put("pointCount", trail.size.toLong())
                w.put("format", "[lat, lon, epochMillis]")
                w.name("points").beginArray()
                trail.forEach { p ->
                    w.beginArray(); w.value(p.lat); w.value(p.lon); w.value(p.timestamp); w.endArray()
                }
                w.endArray()
                w.endObject()

                // --- notes ---
                w.name("impressions").beginArray()
                trip.impressions.sortedBy { it.timestamp }.forEach { i ->
                    w.beginObject()
                    w.putTime("time", i.timestamp)
                    w.put("text", i.text)
                    w.put("leg", legIndexOf(i.legId))
                    w.put("lat", i.lat); w.put("lon", i.lon)
                    w.put("place", placeFor(i.lat, i.lon))
                    w.endObject()
                }
                w.endArray()

                // --- incidents ---
                w.name("incidents").beginArray()
                trip.incidents.sortedBy { it.timestamp }.forEach { inc ->
                    w.beginObject()
                    w.putTime("time", inc.timestamp)
                    w.put("category", inc.category.name)
                    w.put("categoryLabel", inc.category.label())
                    w.put("severity", inc.severity.toLong())
                    w.put("reason", inc.reason)
                    w.put("notes", inc.notes)
                    w.put("leg", legIndexOf(inc.legId))
                    w.put("lat", inc.lat); w.put("lon", inc.lon)
                    w.put("place", placeFor(inc.lat, inc.lon))
                    w.name("involvesMe").value(inc.involvesMe)
                    w.name("people").beginArray(); dao.getIncidentPeopleNames(inc.id).forEach { w.value(it) }; w.endArray()
                    w.name("mediaFiles").beginArray()
                    readable.filter { it.item.incidentId == inc.id }.forEach { w.value(MEDIA_DIR + it.name) }
                    w.endArray()
                    w.endObject()
                }
                w.endArray()

                // --- media, in the order taken ---
                onProgress("Looking up place names…")
                w.name("media").beginArray()
                entries.forEach { e ->
                    val m = e.item
                    w.beginObject()
                    w.put("file", if (m.id in missing) null else MEDIA_DIR + e.name)
                    w.put("type", m.type.name)
                    w.putTime("time", m.timestamp)
                    w.put("caption", m.caption)
                    // The file's own position if it has one, else where the trail was at that moment.
                    val own = hasPosition(m.lat, m.lon)
                    val from = if (own) null else trailPointAt(trail, m.timestamp)
                    val lat = if (own) m.lat else from?.lat
                    val lon = if (own) m.lon else from?.lon
                    w.put("lat", lat); w.put("lon", lon)
                    if (lat != null) w.put("locationSource", if (own) "file" else "route")
                    w.put("place", placeFor(lat, lon))
                    w.put("leg", legIndexOf(m.legId))
                    w.name("incident").value(m.incidentId != null)
                    if (m.id in missing) w.name("missing").value(true)
                    w.endObject()
                }
                w.endArray()

                // The photos and videos in the trip that were left out, so the count still adds up.
                w.name("mediaNotIncluded").beginObject()
                w.put("photos", trip.media.count { it.type == MediaType.PHOTO && !options.includePhotos }.toLong())
                w.put("videos", trip.media.count { it.type == MediaType.VIDEO && !options.includeVideos }.toLong())
                w.put("audio", trip.media.count { it.type == MediaType.AUDIO && !options.includeAudio }.toLong())
                w.endObject()

                if (options.mapImage != null) w.put("routeMapImage", MAP_NAME)
                w.endObject()
                w.flush() // not close(): that would close the zip stream
                zip.closeEntry()

                options.mapImage?.let { bmp ->
                    zip.putNextEntry(ZipEntry(MAP_NAME))
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                }

                // Photos, videos and audio are already compressed, so don't spend time deflating them.
                zip.setLevel(Deflater.NO_COMPRESSION)
                readable.forEachIndexed { index, e ->
                    onProgress("Adding media… ${index + 1} of ${readable.size}")
                    val input = resolver.openInputStream(Uri.parse(e.item.uri)) ?: return@forEachIndexed
                    zip.putNextEntry(ZipEntry(MEDIA_DIR + e.name))
                    input.use { it.copyTo(zip) }
                    zip.closeEntry()
                    written++
                }
            }
            check(out.length() > 0) { "The export came out empty" }
            TripExportResult(out, written, missing.size, lookups)
        }

    private const val MEDIA_DIR = "media/"

    private fun extensionFor(mime: String?, type: MediaType): String = when {
        mime == "image/jpeg" -> "jpg"
        mime == "image/png" -> "png"
        mime == "image/webp" -> "webp"
        mime == "image/heic" || mime == "image/heif" -> "heic"
        mime == "video/mp4" -> "mp4"
        mime == "video/quicktime" -> "mov"
        mime == "audio/mp4" || mime == "audio/m4a" -> "m4a"
        mime == "audio/mpeg" -> "mp3"
        type == MediaType.PHOTO -> "jpg"
        type == MediaType.VIDEO -> "mp4"
        else -> "m4a"
    }

    private fun JsonWriter.put(name: String, value: String?) { if (value != null) name(name).value(value) }
    private fun JsonWriter.put(name: String, value: Long?) { if (value != null) name(name).value(value) }
    private fun JsonWriter.put(name: String, value: Double?) { if (value != null) name(name).value(value) }

    /** A moment as both an exact millisecond count and a readable ISO time with its UTC offset. */
    private fun JsonWriter.putTime(prefix: String, millis: Long) {
        name(if (prefix == "time") "time" else prefix + "Time").value(iso(millis))
        name(if (prefix == "time") "timeMillis" else prefix + "Millis").value(millis)
    }
}
