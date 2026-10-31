package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripExportTest {

    private fun point(lat: Double, lon: Double, t: Long) = RoutePoint(tripId = 1, lat = lat, lon = lon, timestamp = t)

    private fun leg(withEnds: Boolean) = Leg(
        tripId = 1, transportType = TransportType.CAR, originName = "a", destinationName = "b", startTime = 0, endTime = 1000,
        originLat = if (withEnds) -43.5321 else null, originLon = if (withEnds) 172.6362 else null,
        destinationLat = if (withEnds) -41.2865 else null, destinationLon = if (withEnds) 174.7762 else null
    )

    @Test
    fun legDistanceFollowsTheTrailWhenThereIsOne() {
        val trail = listOf(point(0.0, 0.0, 1), point(0.0, 1.0, 2), point(0.0, 2.0, 3))
        val km = TripExport.legDistanceKm(leg(withEnds = true), trail)!!
        // two degrees of longitude on the equator, not the straight line between the leg's ends
        assertEquals(222.4, km, 1.0)
    }

    @Test
    fun legWithoutATrailUsesTheStraightLineBetweenItsEnds() {
        val km = TripExport.legDistanceKm(leg(withEnds = true), emptyList())
        assertNotNull(km)
        assertTrue("was $km", km!! in 295.0..315.0)
    }

    @Test
    fun legWithNeitherTrailNorEndsHasNoDistance() {
        assertNull(TripExport.legDistanceKm(leg(withEnds = false), emptyList()))
    }

    @Test
    fun zeroZeroIsNotAPosition() {
        assertTrue(!TripExport.hasPosition(0.0, 0.0))
        assertTrue(!TripExport.hasPosition(null, 1.0))
        assertTrue(TripExport.hasPosition(-43.5, 172.6))
    }

    @Test
    fun trailPointIsOnlyUsedWhenCloseInTime() {
        val trail = listOf(point(1.0, 1.0, 0), point(2.0, 2.0, 60 * 60_000L))
        assertEquals(1.0, TripExport.trailPointAt(trail, 5 * 60_000L)!!.lat, 0.0)
        assertNull(TripExport.trailPointAt(listOf(point(1.0, 1.0, 0)), 2 * 60 * 60_000L))
    }
}
