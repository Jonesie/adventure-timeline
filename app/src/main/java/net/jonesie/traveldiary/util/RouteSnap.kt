package net.jonesie.traveldiary.util

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

/** Putting a tapped point on a route: the nearest point on the line, and how close is close enough to jump to it. */
object RouteSnap {
    /** The nearest place on the route to a point, and how far away it is. */
    class Nearest(val lat: Double, val lon: Double, val distanceMetres: Double, val segment: Int = 0, val t: Double = 0.0)

    /** A pin further than this from the trip's route is worth a warning (wrong trip, or wrong place). */
    const val FAR_FROM_ROUTE_METRES = 25_000.0

    private const val METRES_PER_DEGREE = 111_320.0

    /** The point on the polyline [route] (lat to lon pairs) nearest to [lat], [lon]; null for an empty route. */
    fun nearest(lat: Double, lon: Double, route: List<Pair<Double, Double>>): Nearest? {
        if (route.isEmpty()) return null
        val cosLat = cos(Math.toRadians(lat))
        // Flat metres around the tapped point: plenty accurate over the distances a tap can be from a road.
        fun x(lonDeg: Double) = (lonDeg - lon) * METRES_PER_DEGREE * cosLat
        fun y(latDeg: Double) = (latDeg - lat) * METRES_PER_DEGREE
        var bestDistance = Double.MAX_VALUE
        var bestX = 0.0
        var bestY = 0.0
        var bestSegment = 0
        var bestT = 0.0
        if (route.size == 1) {
            bestX = x(route[0].second); bestY = y(route[0].first); bestDistance = hypot(bestX, bestY)
        }
        for (i in 0 until route.size - 1) {
            val ax = x(route[i].second); val ay = y(route[i].first)
            val bx = x(route[i + 1].second); val by = y(route[i + 1].first)
            val dx = bx - ax; val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = hypot(px, py)
            if (d < bestDistance) { bestDistance = d; bestX = px; bestY = py; bestSegment = i; bestT = t }
        }
        return Nearest(
            lat + bestY / METRES_PER_DEGREE,
            lon + if (cosLat == 0.0) 0.0 else bestX / (METRES_PER_DEGREE * cosLat),
            bestDistance, bestSegment, bestT
        )
    }

    /** The place to put a pin: on the route if the tap was within [maxMetres] of it, else where it was tapped. */
    fun snap(lat: Double, lon: Double, route: List<Pair<Double, Double>>, maxMetres: Double): Pair<Double, Double> =
        nearest(lat, lon, route)?.takeIf { it.distanceMetres <= maxMetres }?.let { it.lat to it.lon } ?: (lat to lon)

    /**
     * How far "near the route" is on screen: [pixels] worth of map at this latitude and zoom, so zooming
     * in lets the pin go anywhere and zooming out makes the route easier to hit.
     */
    fun snapRadiusMetres(lat: Double, zoom: Double, pixels: Double = 30.0): Double =
        (pixels * 156_543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)).coerceIn(15.0, 2_000.0)

    /**
     * How far along the route the nearest point to [lat], [lon] is, from 0.0 (the start) to 1.0 (the end), so things
     * pinned beside a route can be put in the order the route passes them. Null for an empty route.
     */
    fun fractionAlong(lat: Double, lon: Double, route: List<Pair<Double, Double>>): Double? {
        val near = nearest(lat, lon, route) ?: return null
        if (route.size < 2) return 0.0
        val lengths = DoubleArray(route.size - 1) { RouteShape.distanceKm(route[it].first, route[it].second, route[it + 1].first, route[it + 1].second) }
        val total = lengths.sum()
        if (total <= 0.0) return 0.0
        var before = 0.0
        for (i in 0 until near.segment) before += lengths[i]
        return ((before + lengths[near.segment] * near.t) / total).coerceIn(0.0, 1.0)
    }
}
