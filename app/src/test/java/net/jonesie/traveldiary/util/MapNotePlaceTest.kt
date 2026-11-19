package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MapNote
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNotePlaceTest {
    private val hour = 3_600_000L
    private val t0 = 1_790_000_000_000L

    private fun leg(id: Long, startH: Int, endH: Int, from: Pair<Double, Double>?, to: Pair<Double, Double>?) = Leg(
        id = id, tripId = 1, transportType = TransportType.MOTORBIKE, originName = "A", destinationName = "B",
        startTime = t0 + startH * hour, endTime = t0 + endH * hour,
        originLat = from?.first, originLon = from?.second, destinationLat = to?.first, destinationLon = to?.second
    )

    private fun note(id: Long, lat: Double, lon: Double, createdAt: Long = id) =
        MapNote(id = id, tripId = 1, lat = lat, lon = lon, text = "n$id", createdAt = createdAt)

    // Two legs: east along latitude -43 for an hour, then north.
    private val first = leg(1, 0, 1, -43.0 to 172.0, -43.0 to 172.1)
    private val second = leg(2, 2, 3, -43.0 to 172.1, -42.9 to 172.1)
    private val trail = listOf(
        RoutePoint(tripId = 1, lat = -43.0, lon = 172.0, timestamp = t0),
        RoutePoint(tripId = 1, lat = -43.0, lon = 172.05, timestamp = t0 + hour / 2),
        RoutePoint(tripId = 1, lat = -43.0, lon = 172.1, timestamp = t0 + hour),
        RoutePoint(tripId = 1, lat = -42.95, lon = 172.1, timestamp = t0 + 5 * hour / 2),
        RoutePoint(tripId = 1, lat = -42.9, lon = 172.1, timestamp = t0 + 3 * hour)
    )

    @Test
    fun withATrailTheTimeTheRiderWasThereDecidesTheLeg() {
        assertEquals(1L, MapNotePlace.legFor(-43.001, 172.05, trail, listOf(first, second))?.id)
        assertEquals(2L, MapNotePlace.legFor(-42.951, 172.1, trail, listOf(first, second))?.id)
    }

    @Test
    fun withoutATrailTheNearestLegLineDecides() {
        assertEquals(1L, MapNotePlace.legFor(-43.001, 172.05, emptyList(), listOf(first, second))?.id)
        assertEquals(2L, MapNotePlace.legFor(-42.95, 172.101, emptyList(), listOf(first, second))?.id)
    }

    @Test
    fun aPlaceFarFromEveryLegGetsNoLeg() {
        assertNull(MapNotePlace.legFor(51.5, -0.1, emptyList(), listOf(first, second)))
        assertNull(MapNotePlace.legFor(-43.0, 172.0, trail, emptyList()))
    }

    @Test
    fun legsWithoutCoordinatesAreSkippedWhenThereIsNoTrail() {
        val bare = leg(3, 0, 1, null, null)
        assertNull(MapNotePlace.legFor(-43.0, 172.0, emptyList(), listOf(bare)))
        assertEquals(1L, MapNotePlace.legFor(-43.0, 172.05, emptyList(), listOf(bare, first))?.id)
    }

    @Test
    fun notesComeInTheOrderTheRoutePassesThem() {
        val route = listOf(-43.0 to 172.0, -43.0 to 172.1, -42.9 to 172.1)
        val late = note(1, -42.92, 172.1)
        val early = note(2, -43.0, 172.02)
        val middle = note(3, -43.0, 172.09)
        assertEquals(listOf(2L, 3L, 1L), MapNotePlace.alongRoute(listOf(late, early, middle), route).map { it.id })
    }

    @Test
    fun withNoRouteNotesKeepTheOrderTheyWereMade() {
        val a = note(1, 0.0, 0.0, createdAt = 300)
        val b = note(2, 0.0, 0.0, createdAt = 100)
        assertEquals(listOf(2L, 1L), MapNotePlace.alongRoute(listOf(a, b), emptyList()).map { it.id })
    }

    @Test
    fun theFractionAlongARouteRunsFromZeroToOne() {
        val route = listOf(-43.0 to 172.0, -43.0 to 172.1)
        assertEquals(0.0, RouteSnap.fractionAlong(-43.0, 171.9, route)!!, 1e-9)
        assertEquals(0.5, RouteSnap.fractionAlong(-43.01, 172.05, route)!!, 0.01)
        assertEquals(1.0, RouteSnap.fractionAlong(-43.0, 172.2, route)!!, 1e-9)
        assertNull(RouteSnap.fractionAlong(-43.0, 172.0, emptyList()))
    }

    @Test
    fun theTextIsTidiedAndKeptShort() {
        assertEquals("Road washed out near the bridge", MapNotePlace.clean("  Road washed out\n near the   bridge \n"))
        assertTrue(MapNotePlace.clean("x".repeat(500)).length <= MapNote.MAX_TEXT)
    }
}
