package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType

/** Helpers for turning a recorded GPS trail into lines that don't invent movement. */
object RouteShape {

    private const val GAP_MS = 15 * 60 * 1000L
    private const val GAP_KM = 3.0

    /**
     * True when the phone recorded nothing for a long while and ended up well away from where it
     * was — joining those two points would draw a straight line over ground nobody tracked.
     * Long pauses in one place (lunch, a stay) are not gaps.
     */
    fun isGap(a: RoutePoint, b: RoutePoint): Boolean =
        b.timestamp - a.timestamp > GAP_MS && distanceKm(a.lat, a.lon, b.lat, b.lon) > GAP_KM

    /** The trail cut into continuous pieces wherever [isGap]. */
    fun splitAtGaps(trail: List<RoutePoint>): List<List<RoutePoint>> {
        val sorted = trail.sortedBy { it.timestamp }
        val parts = mutableListOf<MutableList<RoutePoint>>()
        sorted.forEachIndexed { i, p ->
            if (i == 0 || isGap(sorted[i - 1], p)) parts += mutableListOf<RoutePoint>()
            parts.last() += p
        }
        return parts
    }

    class TransportPiece(val transport: TransportType?, val points: List<RoutePoint>)

    /**
     * The trail cut into pieces wherever there is a gap or the leg underneath it changes transport.
     * Each piece starts on the previous piece's last point so lines stay joined. Pieces with
     * only one point are dropped (nothing to draw).
     */
    fun splitByTransport(trail: List<RoutePoint>, legs: List<Leg>): List<TransportPiece> {
        val timed = legs.filter { it.endTime != null }.sortedBy { it.startTime }
        val pieces = mutableListOf<TransportPiece>()
        var current = mutableListOf<RoutePoint>()
        var currentType: TransportType? = null
        var legIndex = 0
        var previous: RoutePoint? = null
        for (point in trail.sortedBy { it.timestamp }) {
            while (legIndex < timed.size && timed[legIndex].endTime!! < point.timestamp) legIndex++
            val type = timed.getOrNull(legIndex)?.takeIf { it.startTime <= point.timestamp }?.transportType
            val gap = previous != null && isGap(previous, point)
            if (current.isNotEmpty() && (gap || type != currentType)) {
                pieces += TransportPiece(currentType, current)
                current = if (gap) mutableListOf() else mutableListOf(current.last())
            }
            currentType = type
            current.add(point)
            previous = point
        }
        if (current.isNotEmpty()) pieces += TransportPiece(currentType, current)
        return pieces.filter { it.points.size > 1 }
    }

    /** Legs of [type] that have coordinates but almost no trail under them (e.g. a flight). */
    fun sparseLegs(trail: List<RoutePoint>, legs: List<Leg>, type: TransportType): List<Leg> =
        legs.filter { leg ->
            leg.transportType == type && leg.endTime != null &&
                leg.originLat != null && leg.originLon != null && leg.destinationLat != null && leg.destinationLon != null &&
                trail.count { it.timestamp in leg.startTime..leg.endTime!! } < 3
        }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 6371.0 * 2 * Math.asin(Math.sqrt(h))
    }
}
