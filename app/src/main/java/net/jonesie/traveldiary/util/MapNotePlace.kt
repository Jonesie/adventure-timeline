package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MapNote
import net.jonesie.traveldiary.data.RoutePoint

/** Working out which leg a pinned note belongs to, and the order notes sit in along a route. */
object MapNotePlace {
    /** A note further than this from the trip's route isn't matched to a leg. */
    const val MAX_LEG_DISTANCE_METRES = 20_000.0

    /**
     * The leg a place on the map belongs to. With a GPS trail, the nearest trail point says when the rider was
     * there, and the leg covering (or nearest to) that moment is the answer. Without one, it is the leg whose
     * straight line from start to end passes closest. Null when nothing is near enough.
     */
    fun legFor(lat: Double, lon: Double, trail: List<RoutePoint>, legs: List<Leg>): Leg? {
        if (legs.isEmpty()) return null
        if (trail.isNotEmpty()) {
            val route = trail.sortedBy { it.timestamp }
            val nearest = route.minByOrNull { RouteShape.distanceKm(lat, lon, it.lat, it.lon) } ?: return null
            if (RouteShape.distanceKm(lat, lon, nearest.lat, nearest.lon) * 1000.0 <= MAX_LEG_DISTANCE_METRES) {
                IncidentPlace.fit(nearest.timestamp, emptyList(), legs).leg?.let { return it }
            }
        }
        return legs
            .mapNotNull { leg ->
                val fromLat = leg.originLat; val fromLon = leg.originLon
                val toLat = leg.destinationLat; val toLon = leg.destinationLon
                val line = listOfNotNull(
                    if (fromLat != null && fromLon != null) fromLat to fromLon else null,
                    if (toLat != null && toLon != null) toLat to toLon else null
                )
                RouteSnap.nearest(lat, lon, line)?.let { leg to it.distanceMetres }
            }
            .filter { it.second <= MAX_LEG_DISTANCE_METRES }
            .minByOrNull { it.second }
            ?.first
    }

    /** Notes in the order the route passes them. With no route to measure along, they keep the order they were made in. */
    fun alongRoute(notes: List<MapNote>, route: List<Pair<Double, Double>>): List<MapNote> =
        if (route.size < 2) notes.sortedWith(compareBy({ it.createdAt }, { it.id }))
        else notes.sortedWith(compareBy({ RouteSnap.fractionAlong(it.lat, it.lon, route) ?: 2.0 }, { it.createdAt }, { it.id }))

    /** The note text as one tidy line: whitespace collapsed and cut to [MapNote.MAX_TEXT] characters. */
    fun clean(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().take(MapNote.MAX_TEXT).trim()
}
