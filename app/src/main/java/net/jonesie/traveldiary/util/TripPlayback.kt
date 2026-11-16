package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The animated "play the trip" view, without any drawing: turns a trip into a path to travel along and the
 * photos and incidents to stop at on the way, and keeps the clock that moves along it.
 *
 * Progress is measured in "units" rather than distance or time. A piece of route is worth the square root of
 * its length, so a 400 km day doesn't swallow the 5 km walk after it, and nights and untracked gaps cost
 * nothing at all.
 */
object TripPlayback {

    /** Keeps the drawn line cheap to redraw on every frame. */
    const val MAX_VERTICES = 2500

    /** A long photo roll would make the animation drag on, so only an even spread of this many are shown. */
    const val MAX_PHOTOS = 25

    const val PHOTO_DWELL_MS = 2400L
    const val INCIDENT_DWELL_MS = 3600L

    private const val TIME_SLACK_MS = 60 * 60 * 1000L
    private const val MAX_PLACE_KM = 10.0
    private const val MIN_PIECE_KM = 0.2

    class Vertex(val lat: Double, val lon: Double, val time: Long?)

    /** One continuous stretch of the route. [dashed] ones are straight lines for stretches with no trail (a flight). */
    class Piece(
        val vertices: List<Vertex>,
        val transport: TransportType?,
        val dashed: Boolean,
        val startUnit: Double,
        val units: Double,
        /** Kilometres from the first vertex to each vertex. */
        val cumulativeKm: DoubleArray
    ) {
        val lengthKm: Double get() = cumulativeKm.last()

        fun unitAt(vertex: Int): Double =
            if (lengthKm <= 0.0) startUnit else startUnit + units * (cumulativeKm[vertex] / lengthKm)
    }

    class Event(
        val unit: Double,
        val dwellMs: Long,
        val photo: MediaItem? = null,
        val incident: Incident? = null
    ) {
        val time: Long get() = photo?.timestamp ?: incident?.timestamp ?: 0L
    }

    /** Where the vehicle is. [passed] is how many of the piece's vertices it has already gone by. */
    data class Position(
        val lat: Double,
        val lon: Double,
        val pieceIndex: Int,
        val passed: Int,
        val transport: TransportType?,
        val dashed: Boolean
    )

    class Timeline(val pieces: List<Piece>, val events: List<Event>) {
        val totalUnits: Double = pieces.sumOf { it.units }
        val totalKm: Double = pieces.sumOf { it.lengthKm }
        val isEmpty: Boolean get() = pieces.isEmpty()

        /** How long the travelling alone takes at normal speed; stops at photos and incidents come on top. */
        val movingMs: Long = (10_000 + 3_000 * sqrt(totalKm)).coerceIn(15_000.0, 60_000.0).toLong()

        fun positionAt(unit: Double): Position? {
            if (pieces.isEmpty()) return null
            val u = unit.coerceIn(0.0, totalUnits)
            val index = max(0, pieces.indexOfLast { it.startUnit <= u })
            val piece = pieces[index]
            val fraction = if (piece.units > 0) ((u - piece.startUnit) / piece.units).coerceIn(0.0, 1.0) else 0.0
            val distance = fraction * piece.lengthKm

            var found = java.util.Arrays.binarySearch(piece.cumulativeKm, distance)
            if (found < 0) found = -found - 2
            val from = found.coerceIn(0, piece.vertices.size - 2)
            val a = piece.vertices[from]
            val b = piece.vertices[from + 1]
            val span = piece.cumulativeKm[from + 1] - piece.cumulativeKm[from]
            val t = if (span > 0) ((distance - piece.cumulativeKm[from]) / span).coerceIn(0.0, 1.0) else 0.0
            return Position(
                lat = a.lat + (b.lat - a.lat) * t,
                lon = a.lon + (b.lon - a.lon) * t,
                pieceIndex = index,
                passed = from + 1,
                transport = piece.transport,
                dashed = piece.dashed
            )
        }
    }

    private class RawPiece(val vertices: List<Vertex>, val transport: TransportType?, val dashed: Boolean)

    fun build(trail: List<RoutePoint>, legs: List<Leg>, media: List<MediaItem>, incidents: List<Incident>): Timeline {
        val raw = mutableListOf<RawPiece>()
        RouteShape.splitByTransport(trail, legs).forEach { piece ->
            raw += RawPiece(piece.points.map { Vertex(it.lat, it.lon, it.timestamp) }, piece.transport, dashed = false)
        }
        // A flight has little or no trail; with no trail at all, every leg is a straight line between its ends.
        val untracked = if (trail.isEmpty()) legs else RouteShape.sparseLegs(trail, legs, TransportType.PLANE)
        untracked.forEach { leg ->
            val fromLat = leg.originLat; val fromLon = leg.originLon
            val toLat = leg.destinationLat; val toLon = leg.destinationLon
            if (fromLat != null && fromLon != null && toLat != null && toLon != null) {
                raw += RawPiece(
                    listOf(Vertex(fromLat, fromLon, leg.startTime), Vertex(toLat, toLon, leg.endTime ?: leg.startTime)),
                    leg.transportType, dashed = true
                )
            }
        }
        raw.sortBy { it.vertices.first().time ?: Long.MAX_VALUE }

        val stride = max(1, ceil(raw.sumOf { it.vertices.size }.toDouble() / MAX_VERTICES).toInt())
        var startUnit = 0.0
        val pieces = raw.map { r ->
            val vertices = thin(r.vertices, stride)
            val cumulative = DoubleArray(vertices.size)
            for (i in 1 until vertices.size) {
                cumulative[i] = cumulative[i - 1] + RouteShape.distanceKm(vertices[i - 1].lat, vertices[i - 1].lon, vertices[i].lat, vertices[i].lon)
            }
            val units = sqrt(max(cumulative.last(), MIN_PIECE_KM))
            Piece(vertices, r.transport, r.dashed, startUnit, units, cumulative).also { startUnit += units }
        }

        return Timeline(pieces, buildEvents(pieces, media, incidents))
    }

    /** Every [stride]th vertex, always keeping the first and the last. */
    private fun thin(vertices: List<Vertex>, stride: Int): List<Vertex> {
        if (stride <= 1 || vertices.size <= 2) return vertices
        return vertices.filterIndexed { i, _ -> i % stride == 0 || i == vertices.lastIndex }
    }

    private fun buildEvents(pieces: List<Piece>, media: List<MediaItem>, incidents: List<Incident>): List<Event> {
        if (pieces.isEmpty()) return emptyList()
        val locator = Locator(pieces)

        val photos = media.filter { it.type == MediaType.PHOTO }
            .mapNotNull { item -> locator.unitFor(item.timestamp, item.lat, item.lon)?.let { Event(it, PHOTO_DWELL_MS, photo = item) } }
            .sortedWith(compareBy({ it.unit }, { it.time }))
        val shownPhotos = if (photos.size <= MAX_PHOTOS) photos else List(MAX_PHOTOS) { i ->
            photos[Math.round(i * (photos.size - 1).toDouble() / (MAX_PHOTOS - 1)).toInt()]
        }
        val shownIncidents = incidents.mapNotNull { incident ->
            locator.unitFor(incident.timestamp, incident.lat, incident.lon)?.let { Event(it, INCIDENT_DWELL_MS, incident = incident) }
        }
        return (shownPhotos + shownIncidents).sortedWith(compareBy({ it.unit }, { it.time }))
    }

    /**
     * Finds where on the route something happened. Its time is trusted first, as long as it falls within
     * the recorded trip; otherwise its location, if that is close to the route.
     */
    private class Locator(private val pieces: List<Piece>) {
        private class Knot(val time: Long, val unit: Double)

        private val byTime: List<Knot> = pieces
            .flatMap { p -> p.vertices.mapIndexedNotNull { i, v -> v.time?.let { Knot(it, p.unitAt(i)) } } }
            .sortedBy { it.time }

        fun unitFor(time: Long, lat: Double?, lon: Double?): Double? {
            if (byTime.isNotEmpty() && time >= byTime.first().time - TIME_SLACK_MS && time <= byTime.last().time + TIME_SLACK_MS) {
                return nearestByTime(time).unit
            }
            if (lat == null || lon == null) return null
            var best: Double? = null
            var bestKm = MAX_PLACE_KM
            pieces.forEach { p ->
                p.vertices.forEachIndexed { i, v ->
                    val km = RouteShape.distanceKm(lat, lon, v.lat, v.lon)
                    if (km <= bestKm) { bestKm = km; best = p.unitAt(i) }
                }
            }
            return best
        }

        private fun nearestByTime(time: Long): Knot {
            var found = java.util.Collections.binarySearch(byTime, Knot(time, 0.0), compareBy { it.time })
            if (found >= 0) return byTime[found]
            found = -found - 1
            val after = byTime.getOrNull(found)
            val before = byTime.getOrNull(found - 1)
            return when {
                before == null -> after!!
                after == null -> before
                time - before.time <= after.time - time -> before
                else -> after
            }
        }
    }

    /** The playback clock: moves along the timeline, stopping for a moment at each photo and incident. */
    class Session(private val timeline: Timeline) {
        var unit: Double = 0.0
            private set

        /** The photo or incident being shown right now, if any. */
        var active: Event? = null
            private set

        /** How many events have been reached so far, so the map can show their pins. */
        var reached: Int = 0
            private set

        private var dwellLeftMs = 0.0
        private val msPerUnit = timeline.movingMs / timeline.totalUnits

        val finished: Boolean
            get() = unit >= timeline.totalUnits && active == null && reached >= timeline.events.size

        /**
         * Moves on by [dtMs] of real time at [speed]x, returning the event just reached, if one was. Speed only
         * affects the travelling: a photo or incident stays up for the same time at any speed, so it can be read.
         */
        fun advance(dtMs: Long, speed: Float): Event? {
            val step = dtMs * speed.toDouble()
            if (active != null) {
                dwellLeftMs -= dtMs
                if (dwellLeftMs <= 0) { active = null; dwellLeftMs = 0.0 }
                return null
            }
            if (finished) return null
            val target = min(unit + step / msPerUnit, timeline.totalUnits)
            val next = timeline.events.getOrNull(reached)
            if (next != null && next.unit <= target) {
                unit = max(unit, next.unit)
                active = next
                dwellLeftMs = next.dwellMs.toDouble()
                reached++
                return next
            }
            unit = target
            return null
        }

        fun seek(to: Double) {
            unit = to.coerceIn(0.0, timeline.totalUnits)
            active = null
            dwellLeftMs = 0.0
            reached = timeline.events.indexOfFirst { it.unit >= unit }.let { if (it < 0) timeline.events.size else it }
        }

        fun restart() = seek(0.0)
    }
}
