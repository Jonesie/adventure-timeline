package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.ui.placeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentPlaceTest {
    private val hour = 3_600_000L
    private val t0 = 1_790_000_000_000L

    private fun leg(id: Long, startH: Double, endH: Double?, from: Pair<Double, Double>?, to: Pair<Double, Double>?, name: String = "A") = Leg(
        id = id, tripId = 1, transportType = TransportType.MOTORBIKE, originName = name, destinationName = "B",
        startTime = t0 + (startH * hour).toLong(), endTime = endH?.let { t0 + (it * hour).toLong() },
        originLat = from?.first, originLon = from?.second, destinationLat = to?.first, destinationLon = to?.second
    )

    private fun at(h: Double) = t0 + (h * hour).toLong()
    private fun p(lat: Double, lon: Double, h: Double) = RoutePoint(tripId = 1, lat = lat, lon = lon, timestamp = at(h))

    private val first = leg(10, 0.0, 2.0, -43.0 to 172.0, -43.4 to 172.4)
    private val second = leg(11, 3.0, 4.0, -43.4 to 172.4, -43.6 to 172.8)

    @Test
    fun insideALegIsPlacedPartWayAlongIt() {
        val fit = IncidentPlace.fit(at(0.5), emptyList(), listOf(first, second))
        assertEquals(IncidentPlace.Source.LEG, fit.source)
        assertEquals(10L, fit.leg?.id)
        assertEquals(-43.1, fit.lat!!, 1e-9)
        assertEquals(172.1, fit.lon!!, 1e-9)
    }

    @Test
    fun theTrailIsPreferredToTheLegsButTheLegIsStillMatched() {
        val fit = IncidentPlace.fit(at(0.5), listOf(p(-43.05, 172.07, 0.45)), listOf(first, second))
        assertEquals(IncidentPlace.Source.TRAIL, fit.source)
        assertEquals(-43.05, fit.lat!!, 1e-9)
        assertEquals(10L, fit.leg?.id)
    }

    @Test
    fun aTrailPointTooFarInTimeIsIgnored() {
        val fit = IncidentPlace.fit(at(0.5), listOf(p(-50.0, 100.0, 1.5)), listOf(first))
        assertEquals(IncidentPlace.Source.LEG, fit.source)
    }

    @Test
    fun betweenLegsTheNearerOneEndIsUsed() {
        // 2.2 h is 0.2 h after the first leg and 0.8 h before the second: end of the first.
        val after = IncidentPlace.fit(at(2.2), emptyList(), listOf(first, second))
        assertEquals(IncidentPlace.Source.NEAREST_LEG, after.source)
        assertEquals(10L, after.leg?.id)
        assertEquals(-43.4, after.lat!!, 1e-9)
        // 2.9 h: start of the second.
        val before = IncidentPlace.fit(at(2.9), emptyList(), listOf(first, second))
        assertEquals(11L, before.leg?.id)
        assertEquals(-43.4, before.lat!!, 1e-9)
    }

    @Test
    fun aLegTooFarAwayInTimeIsNotUsed() {
        val fit = IncidentPlace.fit(at(10.0), emptyList(), listOf(first, second))
        assertEquals(IncidentPlace.Source.NONE, fit.source)
        assertNull(fit.leg)
        assertFalse(fit.hasPlace)
    }

    @Test
    fun noLegsAndNoTrailMeansNoPlace() {
        val fit = IncidentPlace.fit(at(1.0), emptyList(), emptyList())
        assertEquals(IncidentPlace.Source.NONE, fit.source)
        assertFalse(fit.hasPlace)
    }

    @Test
    fun aLegWithoutCoordinatesIsMatchedButGivesNoPlace() {
        val bare = leg(12, 0.0, 2.0, null, null)
        val fit = IncidentPlace.fit(at(1.0), emptyList(), listOf(bare))
        assertEquals(12L, fit.leg?.id)
        assertEquals(IncidentPlace.Source.NONE, fit.source)
        assertFalse(fit.hasPlace)
    }

    @Test
    fun aLegWithOnlyOneEndUsesThatEnd() {
        val onlyStart = leg(13, 0.0, 2.0, -43.0 to 172.0, null)
        val fit = IncidentPlace.fit(at(1.0), emptyList(), listOf(onlyStart))
        assertEquals(-43.0, fit.lat!!, 1e-9)
        val onlyEnd = leg(14, 0.0, 2.0, null, -44.0 to 173.0)
        assertEquals(-44.0, IncidentPlace.fit(at(1.0), emptyList(), listOf(onlyEnd)).lat!!, 1e-9)
    }

    @Test
    fun anOpenEndedLegUsesItsStart() {
        val open = leg(15, 1.0, null, -43.0 to 172.0, -44.0 to 173.0)
        val fit = IncidentPlace.fit(at(1.0), emptyList(), listOf(open))
        assertEquals(IncidentPlace.Source.LEG, fit.source)
        assertEquals(-43.0, fit.lat!!, 1e-9)
    }

    @Test
    fun whenLegsOverlapTheLaterStartingOneWins() {
        val early = leg(20, 0.0, 3.0, -43.0 to 172.0, -44.0 to 172.0)
        val late = leg(21, 1.0, 2.0, -45.0 to 172.0, -46.0 to 172.0)
        assertEquals(21L, IncidentPlace.fit(at(1.5), emptyList(), listOf(early, late)).leg?.id)
    }

    @Test
    fun theNoteSaysWhereThePlaceCameFrom() {
        assertEquals("It keeps the location it already has.", placeNote(true, true, null))
        assertTrue(placeNote(false, false, null).contains("once a trip covers that time"))
        val fit = IncidentPlace.fit(at(0.5), emptyList(), listOf(first))
        assertEquals("Placed along your leg A → B, judged from its times.", placeNote(true, false, fit))
        assertTrue(placeNote(true, false, IncidentPlace.fit(at(0.5), listOf(p(-43.0, 172.0, 0.5)), emptyList())).startsWith("Placed on your trip's route"))
        assertTrue(placeNote(true, false, IncidentPlace.fit(at(30.0), emptyList(), listOf(first))).contains("without a location"))
    }

    @Test
    fun aPlaceChosenOnTheMapWinsOverEverythingElse() {
        val fit = IncidentPlace.fit(at(0.5), emptyList(), listOf(first))
        val map = 1.0 to 2.0
        assertEquals(map, IncidentPlace.choose(map, 3.0 to 4.0, 5.0 to 6.0, fit))
        assertEquals(3.0 to 4.0, IncidentPlace.choose(null, 3.0 to 4.0, 5.0 to 6.0, fit))
        assertEquals(5.0 to 6.0, IncidentPlace.choose(null, null, 5.0 to 6.0, fit))
        assertEquals(fit.lat!! to fit.lon!!, IncidentPlace.choose(null, null, null, fit))
        assertNull(IncidentPlace.choose(null, null, null, IncidentPlace.fit(at(30.0), emptyList(), emptyList())))
    }
}
