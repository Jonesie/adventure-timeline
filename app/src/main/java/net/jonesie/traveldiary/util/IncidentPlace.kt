package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import kotlin.math.abs

/**
 * Works out where an incident that wasn't tagged with a current location happened, and which leg it was on,
 * from nothing but its time and the trip it belongs to.
 */
object IncidentPlace {
    /** Where the place came from, so the screen can say so. */
    enum class Source {
        /** A recorded GPS point close to that moment. */
        TRAIL,
        /** The leg that covers that moment, the place being worked out from how far through it the moment falls. */
        LEG,
        /** No leg covers the moment: the end of the nearest one. */
        NEAREST_LEG,
        /** Nothing fits well enough; no place is guessed. */
        NONE
    }

    class Fit(val lat: Double?, val lon: Double?, val leg: Leg?, val source: Source) {
        val hasPlace: Boolean get() = lat != null && lon != null
    }

    /**
     * The place to save: one chosen on the map wins, then the phone's current location, then a place the incident
     * already had, then the one worked out from the trip ([fit]). Null when none of them has a place.
     */
    fun choose(picked: Pair<Double, Double>?, current: Pair<Double, Double>?, existing: Pair<Double, Double>?, fit: Fit?): Pair<Double, Double>? =
        picked ?: current ?: existing ?: if (fit != null && fit.lat != null && fit.lon != null) fit.lat to fit.lon else null

    const val MAX_TRAIL_GAP_MS = 30 * 60_000L
    const val MAX_LEG_GAP_MS = 2 * 60 * 60_000L

    /**
     * In order: the trail if it has a point within [maxTrailGapMs] of [timestamp]; else the leg covering it, with
     * the place on the line between the leg's start and end in proportion to the time; else the nearest leg
     * within [maxLegGapMs] (its start if the moment is before it, its end if after). The leg is matched whenever
     * one fits, even if the place came from the trail or the leg has no coordinates.
     */
    fun fit(
        timestamp: Long,
        trail: List<RoutePoint>,
        legs: List<Leg>,
        maxTrailGapMs: Long = MAX_TRAIL_GAP_MS,
        maxLegGapMs: Long = MAX_LEG_GAP_MS
    ): Fit {
        val covering = legs
            .filter { timestamp >= it.startTime && timestamp <= (it.endTime ?: it.startTime) }
            .maxByOrNull { it.startTime }
        val nearest = covering ?: legs
            .minByOrNull { distanceMs(timestamp, it) }
            ?.takeIf { distanceMs(timestamp, it) <= maxLegGapMs }
        val leg = nearest

        trail.minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { abs(it.timestamp - timestamp) <= maxTrailGapMs }
            ?.let { return Fit(it.lat, it.lon, leg, Source.TRAIL) }

        if (leg == null) return Fit(null, null, null, Source.NONE)

        val hasStart = leg.originLat != null && leg.originLon != null
        val hasEnd = leg.destinationLat != null && leg.destinationLon != null
        if (covering != null) {
            val end = leg.endTime
            return when {
                hasStart && hasEnd && end != null && end > leg.startTime -> {
                    val f = (timestamp - leg.startTime).toDouble() / (end - leg.startTime)
                    Fit(
                        leg.originLat!! + (leg.destinationLat!! - leg.originLat) * f,
                        leg.originLon!! + (leg.destinationLon!! - leg.originLon) * f,
                        leg, Source.LEG
                    )
                }
                hasStart -> Fit(leg.originLat, leg.originLon, leg, Source.LEG)
                hasEnd -> Fit(leg.destinationLat, leg.destinationLon, leg, Source.LEG)
                else -> Fit(null, null, leg, Source.NONE)
            }
        }
        val before = timestamp < leg.startTime
        val (lat, lon) = when {
            before && hasStart -> leg.originLat to leg.originLon
            !before && hasEnd -> leg.destinationLat to leg.destinationLon
            hasStart -> leg.originLat to leg.originLon
            hasEnd -> leg.destinationLat to leg.destinationLon
            else -> null to null
        }
        return if (lat == null || lon == null) Fit(null, null, leg, Source.NONE) else Fit(lat, lon, leg, Source.NEAREST_LEG)
    }

    private fun distanceMs(t: Long, leg: Leg): Long {
        val end = leg.endTime ?: leg.startTime
        return when {
            t < leg.startTime -> leg.startTime - t
            t > end -> t - end
            else -> 0L
        }
    }
}
