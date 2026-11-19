package net.jonesie.traveldiary.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSnapTest {
    // A road running due east along latitude -43, 0.01 degrees of longitude is about 810 m there.
    private val road = listOf(-43.0 to 172.00, -43.0 to 172.01, -43.0 to 172.02)

    @Test
    fun theNearestPointIsThePerpendicularFootOnTheLine() {
        // 0.001 degrees of latitude north of the road is about 111 m, over the middle of the first piece.
        val n = RouteSnap.nearest(-42.999, 172.005, road)!!
        assertEquals(-43.0, n.lat, 1e-6)
        assertEquals(172.005, n.lon, 1e-6)
        assertEquals(111.3, n.distanceMetres, 1.0)
    }

    @Test
    fun beyondTheEndOfTheRouteItIsTheEndPoint() {
        val n = RouteSnap.nearest(-43.0, 172.03, road)!!
        assertEquals(172.02, n.lon, 1e-6)
        assertTrue(n.distanceMetres > 800)
    }

    @Test
    fun aSinglePointRouteAndAnEmptyOne() {
        assertEquals(0.0, RouteSnap.nearest(-43.0, 172.0, listOf(-43.0 to 172.0))!!.distanceMetres, 1e-6)
        assertNull(RouteSnap.nearest(-43.0, 172.0, emptyList()))
    }

    @Test
    fun aTapNearTheRouteJumpsOntoItButAFarOneStaysPut() {
        val near = RouteSnap.snap(-42.9999, 172.005, road, maxMetres = 50.0)
        assertEquals(-43.0, near.first, 1e-6)
        val far = RouteSnap.snap(-42.99, 172.005, road, maxMetres = 50.0)
        assertEquals(-42.99 to 172.005, far)
        assertEquals(-42.99 to 172.005, RouteSnap.snap(-42.99, 172.005, emptyList(), 50.0))
    }

    @Test
    fun theSnapDistanceShrinksAsYouZoomIn() {
        val wide = RouteSnap.snapRadiusMetres(-43.0, 10.0)
        val close = RouteSnap.snapRadiusMetres(-43.0, 15.0)
        assertTrue(wide > close)
        assertTrue(close >= 15.0)
        // At zoom 15 near 43 degrees south a map pixel is about 3.5 m, so 30 px is about 105 m.
        assertEquals(105.0, close, 5.0)
        assertTrue(RouteSnap.snapRadiusMetres(-43.0, 2.0) <= 2_000.0)
        assertNotNull(wide)
    }

    @Test
    fun farFromTheRouteIsMeasuredInMetres() {
        // Another country, say: well beyond the warning distance.
        val n = RouteSnap.nearest(51.5, -0.1, road)!!
        assertTrue(n.distanceMetres > RouteSnap.FAR_FROM_ROUTE_METRES)
    }
}
