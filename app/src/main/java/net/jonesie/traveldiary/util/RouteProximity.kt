package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint

/** Answers "was this spot within a few km of the trip's route?", measuring to the line, not just its recorded points. */
class RouteProximity private constructor(
    private val lines: List<List<DoubleArray>>,
    private val points: List<DoubleArray>
) {
    private val minLat: Double
    private val maxLat: Double
    private val minLon: Double
    private val maxLon: Double

    init {
        val all = lines.flatten() + points
        minLat = all.minOfOrNull { it[0] } ?: 0.0
        maxLat = all.maxOfOrNull { it[0] } ?: 0.0
        minLon = all.minOfOrNull { it[1] } ?: 0.0
        maxLon = all.maxOfOrNull { it[1] } ?: 0.0
    }

    val isEmpty get() = lines.isEmpty() && points.isEmpty()

    fun isNear(lat: Double, lon: Double, radiusKm: Double): Boolean {
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLat = radiusKm / 110.0
        val dLon = radiusKm / (111.0 * cosLat)
        if (lat < minLat - dLat || lat > maxLat + dLat || lon < minLon - dLon || lon > maxLon + dLon) return false

        // Flat-earth kilometres around the photo, which is plenty accurate at a few km.
        fun x(pointLon: Double) = (pointLon - lon) * 111.0 * cosLat
        fun y(pointLat: Double) = (pointLat - lat) * 110.0
        val r2 = radiusKm * radiusKm

        for (p in points) {
            val px = x(p[1]); val py = y(p[0])
            if (px * px + py * py <= r2) return true
        }
        for (line in lines) {
            for (i in 1 until line.size) {
                val ax = x(line[i - 1][1]); val ay = y(line[i - 1][0])
                val bx = x(line[i][1]); val by = y(line[i][0])
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
                val tc = t.coerceIn(0.0, 1.0)
                val cx = ax + tc * dx; val cy = ay + tc * dy
                if (cx * cx + cy * cy <= r2) return true
            }
        }
        return false
    }

    companion object {
        fun of(trail: List<RoutePoint>, legs: List<Leg>): RouteProximity {
            val lines = RouteShape.splitAtGaps(trail).map { part -> part.map { doubleArrayOf(it.lat, it.lon) } }
            // Leg ends cover stops and flights, which may have little or no trail.
            val stops = legs.flatMap { leg ->
                listOfNotNull(
                    if (leg.originLat != null && leg.originLon != null) doubleArrayOf(leg.originLat, leg.originLon) else null,
                    if (leg.destinationLat != null && leg.destinationLon != null) doubleArrayOf(leg.destinationLat, leg.destinationLon) else null
                )
            }
            return RouteProximity(lines, stops)
        }
    }
}
