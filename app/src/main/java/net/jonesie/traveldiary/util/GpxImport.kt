package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.MapNote
import net.jonesie.traveldiary.data.MapNoteCategory
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory

/** One recorded position from a GPX file. [time] is null when the file doesn't say when. */
class GpxPoint(val lat: Double, val lon: Double, val time: Long?, val track: Int = 0)

/** A waypoint that Adventure Timeline itself wrote as a map note (its `<type>` starts with "note"), read back in. */
class GpxNote(val lat: Double, val lon: Double, val text: String, val category: MapNoteCategory?)

/** A stretch of the route that becomes one leg of the trip. Times are epoch millis. */
class GpxLeg(
    val transport: TransportType,
    val startTime: Long, val endTime: Long,
    val originLat: Double, val originLon: Double,
    val destinationLat: Double, val destinationLon: Double
)

/** What a GPX file holds that a trip can use: the route, and the name and time span if it has them. */
class GpxRoute(
    val name: String?,
    val points: List<GpxPoint>,
    val trackTypes: List<String?> = emptyList(),
    val notes: List<GpxNote> = emptyList()
) {
    val hasTimes: Boolean get() = points.isNotEmpty() && points.all { it.time != null }
    val startTime: Long? get() = points.firstNotNullOfOrNull { it.time }
    val endTime: Long? get() = points.lastOrNull { it.time != null }?.time

    /**
     * Cuts a continuous piece of trail where the rider stopped for a while (fuel, lunch, a lookout): a leg ends
     * where the stop began and the next starts where it ended. Bits too short to be a leg are folded into a
     * neighbour so a stop at the very start or end, or two stops close together, don't make fragments.
     */
    private fun splitAtStops(piece: List<RoutePoint>): List<List<RoutePoint>> {
        val ranges = ArrayList<IntRange>()
        var legStart = 0
        var i = 0
        while (i < piece.size) {
            var j = i
            while (j + 1 < piece.size && metres(piece[i], piece[j + 1]) <= GpxImport.STOP_RADIUS_METRES) j++
            if (piece[j].timestamp - piece[i].timestamp >= GpxImport.STOP_MIN_MS) {
                if (i > legStart) ranges += legStart..i
                legStart = j
                i = j + 1
            } else i++
        }
        if (legStart < piece.size - 1) ranges += legStart until piece.size
        fun km(r: IntRange) = RouteShape.trailKm(piece.subList(r.first, r.last + 1))
        while (ranges.size > 1) {
            val short = ranges.indexOfFirst { km(it) < GpxImport.MIN_LEG_KM }
            if (short < 0) break
            if (short == 0) ranges[1] = ranges[0].first..ranges[1].last else ranges[short - 1] = ranges[short - 1].first..ranges[short].last
            ranges.removeAt(short)
        }
        return ranges.filter { it.last > it.first }.map { piece.subList(it.first, it.last + 1) }.ifEmpty { listOf(piece) }
    }

    private fun metres(a: RoutePoint, b: RoutePoint) = RouteShape.distanceKm(a.lat, a.lon, b.lat, b.lon) * 1000.0

    /** One timestamp per point. A file without times for every point is spaced one second apart from [fallbackStart]. */
    private fun timestamps(fallbackStart: Long): LongArray {
        val timed = hasTimes
        return LongArray(points.size) { i -> if (timed) points[i].time!! else fallbackStart + i * 1000L }
    }

    /** The points as a trip's trail. */
    fun toRoutePoints(tripId: Long, fallbackStart: Long): List<RoutePoint> {
        val at = timestamps(fallbackStart)
        return points.mapIndexed { i, p -> RoutePoint(tripId = tripId, lat = p.lat, lon = p.lon, timestamp = at[i]) }
    }

    /**
     * The legs this route implies: one per track in the file, and a track with times is cut again wherever
     * the recording stopped and restarted somewhere else (the overnight stop of a multi-day ride). A guess
     * at the transport comes from the track's `<type>`, else from how fast it moved, and can be edited later.
     */
    fun toLegs(fallbackStart: Long): List<GpxLeg> {
        val at = timestamps(fallbackStart)
        val trail = points.mapIndexed { i, p -> RoutePoint(tripId = 0, lat = p.lat, lon = p.lon, timestamp = at[i]) }
        val legs = ArrayList<GpxLeg>()
        points.indices.groupBy { points[it].track }.toSortedMap().forEach { (track, indices) ->
            val whole = indices.map { trail[it] }
            // A sparsely recorded track can look like all gaps; then it is one leg rather than none.
            val pieces = (if (hasTimes) RouteShape.splitAtGaps(whole).filter { it.size >= 2 } else emptyList())
                .ifEmpty { listOf(whole) }
            pieces.filter { it.size >= 2 }.flatMap { if (hasTimes) splitAtStops(it) else listOf(it) }.forEach { piece ->
                val first = piece.first()
                val last = piece.last()
                legs += GpxLeg(
                    transport = GpxImport.guessTransport(trackTypes.getOrNull(track), RouteShape.trailKm(piece), last.timestamp - first.timestamp, hasTimes),
                    startTime = first.timestamp, endTime = last.timestamp,
                    originLat = first.lat, originLon = first.lon, destinationLat = last.lat, destinationLon = last.lon
                )
            }
        }
        return legs
    }
}

/**
 * Reads the route out of a GPX file (anything that exports GPX: Garmin, Strava, Komoot, OsmAnd, this
 * app's own export). Everything happens on the phone. All tracks and segments are joined in file order;
 * a file with only a planned `<rte>` is used if there is no track. Points that sit within [minSpacingMetres]
 * of the last kept one are dropped, so a long 1 Hz recording doesn't bloat the database or the map.
 */
object GpxImport {
    const val DEFAULT_MIN_SPACING_METRES = 5.0

    /** Staying within this of one spot for this long is a stop; the ride before and after are separate legs. */
    const val STOP_RADIUS_METRES = 60.0
    const val STOP_MIN_MS = 5 * 60_000L

    /** A leg shorter than this is folded into the one next to it. */
    const val MIN_LEG_KM = 2.0

    class InvalidGpx(message: String) : Exception(message)

    /** The `<type>` a map note is written with in a GPX file: "note", or "note/hazard" when it has a kind. */
    fun noteType(category: MapNoteCategory?): String = if (category == null) "note" else "note/" + category.name.lowercase()

    private fun noteCategory(type: String): MapNoteCategory? =
        type.substringAfter('/', "").trim().uppercase().let { name -> MapNoteCategory.entries.firstOrNull { it.name == name } }

    /** Throws [InvalidGpx] for a file that isn't GPX, is malformed, or has no route points. */
    fun parse(input: InputStream, minSpacingMetres: Double = DEFAULT_MIN_SPACING_METRES): GpxRoute {
        val handler = Handler(minSpacingMetres)
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            // A route file has no business declaring entities; refuse them. Not every parser supports the flag.
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (e: InvalidGpx) {
            throw e
        } catch (e: Exception) {
            throw InvalidGpx("That doesn't look like a GPX file.")
        }
        if (!handler.sawGpx) throw InvalidGpx("That doesn't look like a GPX file.")
        val points = handler.result()
        if (points.isEmpty()) throw InvalidGpx("That GPX file has no route in it, only waypoints.")
        return GpxRoute(handler.name, points, handler.types(), handler.notes)
    }

    /**
     * What the track was, from its `<type>` when it says ("cycling", "motorcycling", "running"...), otherwise
     * from its average speed: walking pace is [TransportType.WALK], anything quicker is taken to be a motorbike
     * (this is mostly a ride diary). Wrong guesses are one tap to change on the leg.
     */
    fun guessTransport(type: String?, km: Double, durationMs: Long, hasTimes: Boolean): TransportType {
        val t = type?.lowercase().orEmpty()
        return when {
            t.contains("motor") -> TransportType.MOTORBIKE
            t.contains("cycl") || t.contains("bik") -> TransportType.BIKE
            t.contains("run") || t.contains("walk") || t.contains("hik") -> TransportType.WALK
            t.contains("driv") || t == "car" -> TransportType.CAR
            t.contains("4wd") || t.contains("offroad") -> TransportType.FOUR_WD
            t.contains("boat") || t.contains("sail") || t.contains("kayak") || t.contains("canoe") || t.contains("row") -> TransportType.BOAT
            t.contains("train") -> TransportType.TRAIN
            t.contains("bus") -> TransportType.BUS
            t.contains("fly") || t.contains("flight") -> TransportType.PLANE
            hasTimes && durationMs > 0 && km / (durationMs / 3_600_000.0) < 7.0 -> TransportType.WALK
            else -> TransportType.MOTORBIKE
        }
    }

    /** GPX times are ISO 8601, nearly always UTC ("Z"); some devices leave the zone off, which we read as UTC. */
    fun parseTime(text: String): Long? {
        val s = text.trim()
        if (s.isEmpty()) return null
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    Instant.parse(if (s.endsWith("Z")) s else s + "Z").toEpochMilli()
                } catch (_: Exception) { null }
            }
        }
    }

    private class Handler(private val minSpacingMetres: Double) : DefaultHandler() {
        var sawGpx = false
        var name: String? = null

        private val track = ArrayList<GpxPoint>()
        private val route = ArrayList<GpxPoint>()
        private var lastTrack: GpxPoint? = null
        private var lastRoute: GpxPoint? = null
        private var pendingTrack: GpxPoint? = null
        private var pendingRoute: GpxPoint? = null
        private var trackNo = -1
        private var routeNo = -1
        val trackTypes = ArrayList<String?>()
        val notes = ArrayList<GpxNote>()
        private var inWpt = false
        private var wptLat = 0.0
        private var wptLon = 0.0
        private var wptBad = false
        private var wptName: String? = null
        private var wptType: String? = null
        private var wptDesc: String? = null
        private val routeTypes = ArrayList<String?>()

        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private var lat = 0.0
        private var lon = 0.0
        private var time: Long? = null
        private var inPoint = false
        private var badPoint = false

        fun result(): List<GpxPoint> = if (track.isNotEmpty()) track else route

        /** The route used: its points' track numbers index into this. */
        fun types(): List<String?> = if (track.isNotEmpty()) trackTypes else routeTypes

        // Each track keeps its first and final point, so it starts and ends where it did.
        private fun finishTrack() {
            pendingTrack?.let { track.add(it) }
            pendingTrack = null
            lastTrack = null
        }

        private fun finishRoute() {
            pendingRoute?.let { route.add(it) }
            pendingRoute = null
            lastRoute = null
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val tag = qName.substringAfter(':')
            if (path.isEmpty()) {
                if (tag != "gpx") throw InvalidGpx("That doesn't look like a GPX file.")
                sawGpx = true
            }
            path.add(tag)
            text.setLength(0)
            if (tag == "wpt") {
                inWpt = true
                wptName = null; wptType = null; wptDesc = null
                val la = attrs.getValue("lat")?.toDoubleOrNull()
                val lo = attrs.getValue("lon")?.toDoubleOrNull()
                wptBad = la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0
                wptLat = la ?: 0.0
                wptLon = lo ?: 0.0
            }
            if (tag == "trk") { trackNo++; trackTypes.add(null) }
            if (tag == "rte") { routeNo++; routeTypes.add(null) }
            if (tag == "trkpt" || tag == "rtept") {
                inPoint = true
                time = null
                val la = attrs.getValue("lat")?.toDoubleOrNull()
                val lo = attrs.getValue("lon")?.toDoubleOrNull()
                badPoint = la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0 || (la == 0.0 && lo == 0.0)
                lat = la ?: 0.0
                lon = lo ?: 0.0
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (text.length < 200) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val tag = qName.substringAfter(':')
            when (tag) {
                "time" -> if (inPoint) time = parseTime(text.toString())
                "name" -> if (inWpt && path.getOrNull(path.size - 2) == "wpt") wptName = text.toString().trim() else if (name == null && !inPoint) {
                    // Prefer the file's own name, then the first track's.
                    val parent = path.getOrNull(path.size - 2)
                    if (parent == "metadata" || parent == "trk") text.toString().trim().takeIf { it.isNotEmpty() }?.let { name = it }
                }
                "desc" -> if (inWpt && path.getOrNull(path.size - 2) == "wpt") wptDesc = text.toString().trim()
                "wpt" -> {
                    inWpt = false
                    val type = wptType
                    if (!wptBad && type != null && type.trim().lowercase().startsWith("note")) {
                        // The name holds the note's text; a file from another app may use the description instead.
                        val body = MapNotePlace.clean(wptName?.takeIf { it.isNotEmpty() } ?: wptDesc.orEmpty())
                        if (body.isNotEmpty()) notes += GpxNote(wptLat, wptLon, body, noteCategory(type))
                    }
                }
                "type" -> if (inWpt && path.getOrNull(path.size - 2) == "wpt") wptType = text.toString().trim() else if (!inPoint) {
                    val v = text.toString().trim().takeIf { it.isNotEmpty() }
                    when (path.getOrNull(path.size - 2)) {
                        "trk" -> if (trackNo >= 0) trackTypes[trackNo] = v
                        "rte" -> if (routeNo >= 0) routeTypes[routeNo] = v
                    }
                }
                "trkpt" -> { inPoint = false; if (!badPoint) add(track, GpxPoint(lat, lon, time, trackNo.coerceAtLeast(0)), true) }
                "rtept" -> { inPoint = false; if (!badPoint) add(route, GpxPoint(lat, lon, time, routeNo.coerceAtLeast(0)), false) }
                "trk" -> finishTrack()
                "rte" -> finishRoute()
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }

        private fun add(list: ArrayList<GpxPoint>, p: GpxPoint, isTrack: Boolean) {
            val last = if (isTrack) lastTrack else lastRoute
            if (last != null && RouteShape.distanceKm(last.lat, last.lon, p.lat, p.lon) * 1000.0 < minSpacingMetres) {
                if (isTrack) pendingTrack = p else pendingRoute = p
                return
            }
            list.add(p)
            if (isTrack) { lastTrack = p; pendingTrack = null } else { lastRoute = p; pendingRoute = null }
        }
    }
}
