package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProximityTest {

    private fun point(lat: Double, lon: Double, t: Long) = RoutePoint(tripId = 1, lat = lat, lon = lon, timestamp = t)

    // Two points about 11 km apart along the equator, one minute apart.
    private val trail = listOf(point(0.0, 0.0, 0), point(0.0, 0.1, 60_000))

    @Test
    fun aPhotoBesideTheMiddleOfALongSegmentIsNear() {
        val route = RouteProximity.of(trail, emptyList())
        // No recorded point is close, but the line itself passes 0.3 km away.
        assertTrue(route.isNear(0.003, 0.05, radiusKm = 1.0))
    }

    @Test
    fun aPhotoFarFromTheRouteIsNotNear() {
        val route = RouteProximity.of(trail, emptyList())
        assertFalse(route.isNear(0.1, 0.05, radiusKm = 2.0))
    }

    @Test
    fun aWiderRadiusCatchesMore() {
        val route = RouteProximity.of(trail, emptyList())
        assertFalse(route.isNear(0.03, 0.05, radiusKm = 2.0))
        assertTrue(route.isNear(0.03, 0.05, radiusKm = 5.0))
    }

    @Test
    fun legStopsCountWhenThereIsNoTrail() {
        val flight = Leg(
            tripId = 1, transportType = TransportType.PLANE, originName = "a", destinationName = "b",
            startTime = 0, endTime = 1, originLat = 10.0, originLon = 10.0, destinationLat = 20.0, destinationLon = 20.0
        )
        val route = RouteProximity.of(emptyList(), listOf(flight))
        assertTrue(route.isNear(10.001, 10.001, radiusKm = 1.0))
        assertFalse(route.isNear(15.0, 15.0, radiusKm = 1.0))
    }

    @Test
    fun anEmptyRouteMatchesNothing() {
        val route = RouteProximity.of(emptyList(), emptyList())
        assertTrue(route.isEmpty)
        assertFalse(route.isNear(0.0, 0.0, radiusKm = 100.0))
    }
}
