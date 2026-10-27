package net.jonesie.traveldiary.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFinderTest {

    private val home = -41.2865 to 174.7762

    @Test
    fun findsTheSpotMostTripsStartAndEnd() {
        val nearHome = List(8) { i -> home.first + i * 0.0005 to home.second }
        val elsewhere = listOf(-42.0 to 173.0, -41.0 to 174.0, -45.0 to 170.0, -44.0 to 171.0)
        val found = HomeFinder.detect(nearHome + elsewhere)
        assertNotNull(found)
        assertTrue(RouteShape.distanceKm(home.first, home.second, found!!.first, found.second) < 1.0)
    }

    @Test
    fun tooFewTripsMeansNoHome() {
        assertNull(HomeFinder.detect(List(4) { home }))
    }

    @Test
    fun pointsSpreadEverywhereMeanNoHome() {
        val spread = List(12) { i -> -40.0 - i to 170.0 + i }
        assertNull(HomeFinder.detect(spread))
    }

    @Test
    fun theCentreIsTheAverageOfTheCluster() {
        val cluster = listOf(-43.0 to 172.0, -43.002 to 172.0, -43.0 to 172.002, -43.002 to 172.002, -43.001 to 172.001, -43.001 to 172.001)
        val found = HomeFinder.detect(cluster)!!
        assertEquals(-43.001, found.first, 0.0005)
        assertEquals(172.001, found.second, 0.0005)
    }
}
