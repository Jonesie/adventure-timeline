package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShapeTest {

    private val minute = 60_000L

    private fun point(lat: Double, lon: Double, t: Long) = RoutePoint(tripId = 1, lat = lat, lon = lon, timestamp = t)

    private fun leg(type: TransportType, start: Long, end: Long, plane: Boolean = false) = Leg(
        tripId = 1, transportType = type, originName = "a", destinationName = "b", startTime = start, endTime = end,
        originLat = if (plane) -43.49 else null, originLon = if (plane) 172.53 else null,
        destinationLat = if (plane) -41.33 else null, destinationLon = if (plane) 174.81 else null
    )

    @Test
    fun distanceBetweenChristchurchAndWellingtonIsAbout300Km() {
        val km = RouteShape.distanceKm(-43.5321, 172.6362, -41.2865, 174.7762)
        assertTrue("was $km", km in 295.0..315.0)
    }

    @Test
    fun aLongPauseInOnePlaceIsNotAGap() {
        assertFalse(RouteShape.isGap(point(-43.5, 172.6, 0), point(-43.5001, 172.6001, 60 * minute)))
    }

    @Test
    fun aLongUntrackedJumpIsAGap() {
        assertTrue(RouteShape.isGap(point(-43.5, 172.6, 0), point(-43.4, 172.6, 20 * minute)))
    }

    @Test
    fun aFastJumpWithinNormalSamplingIsNotAGap() {
        assertFalse(RouteShape.isGap(point(-43.5, 172.6, 0), point(-43.4, 172.6, 5 * minute)))
    }

    @Test
    fun splitAtGapsCutsWhereNothingWasTracked() {
        val trail = listOf(
            point(-43.50, 172.60, 0),
            point(-43.49, 172.60, minute),
            point(-42.00, 172.60, 90 * minute)
        )
        val parts = RouteShape.splitAtGaps(trail)
        assertEquals(2, parts.size)
        assertEquals(2, parts[0].size)
        assertEquals(1, parts[1].size)
    }

    @Test
    fun splitByTransportStartsANewPieceWhereTheLegChanges() {
        val legs = listOf(leg(TransportType.CAR, 0, 250), leg(TransportType.WALK, 250, 600))
        val trail = listOf(100L, 200L, 300L, 400L, 500L).mapIndexed { i, t -> point(-43.5 + i * 0.001, 172.6, t) }
        val pieces = RouteShape.splitByTransport(trail, legs)
        assertEquals(2, pieces.size)
        assertEquals(TransportType.CAR, pieces[0].transport)
        assertEquals(2, pieces[0].points.size)
        assertEquals(TransportType.WALK, pieces[1].transport)
        // The second piece starts on the first piece's last point so the line stays joined.
        assertEquals(200L, pieces[1].points.first().timestamp)
    }

    @Test
    fun aFlightWithNoTrailIsSparse() {
        val flight = leg(TransportType.PLANE, 1000, 5000, plane = true)
        assertEquals(listOf(flight), RouteShape.sparseLegs(emptyList(), listOf(flight), TransportType.PLANE))
    }

    @Test
    fun aFlightWithAGoodTrailIsNotSparse() {
        val flight = leg(TransportType.PLANE, 1000, 5000, plane = true)
        val trail = listOf(1500L, 2500L, 3500L).map { point(-42.0, 173.0, it) }
        assertTrue(RouteShape.sparseLegs(trail, listOf(flight), TransportType.PLANE).isEmpty())
    }
}
