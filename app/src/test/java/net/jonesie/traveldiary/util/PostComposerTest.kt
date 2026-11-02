package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Companion
import net.jonesie.traveldiary.data.Impression
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.Trip
import net.jonesie.traveldiary.data.TripDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PostComposerTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = 1_790_000_000_000L

    private fun leg(type: TransportType, from: String, to: String, startOffsetH: Long, hours: Long) = Leg(
        tripId = 1, transportType = type, originName = from, destinationName = to,
        startTime = start + startOffsetH * hour, endTime = start + (startOffsetH + hours) * hour
    )

    private fun trip(
        legs: List<Leg> = emptyList(),
        companions: List<Companion> = emptyList(),
        impressions: List<Impression> = emptyList(),
        rating: Int = 0,
        notes: String = "",
        days: Long = 0
    ) = TripDetail(
        trip = Trip(id = 1, title = "Hanmer ride", startDate = start, endDate = start + days * day, notes = notes, rating = rating),
        companions = companions, legs = legs, media = emptyList(), impressions = impressions, incidents = emptyList()
    )

    private fun options(
        message: String = "", dates: Boolean = false, rating: Boolean = true, route: Boolean = true,
        companions: Boolean = true, notes: Boolean = true, impressions: Boolean = true
    ) = PostOptions("", message, dates, rating, route, companions, notes, impressions)

    @Test
    fun titleAloneWhenNothingElseIsWanted() {
        val text = PostComposer.buildText(trip(), options(route = false, rating = false, companions = false, notes = false, impressions = false), null)
        assertEquals("Hanmer ride", text)
    }

    @Test
    fun factsLineHasDaysDistanceTransportAndStars() {
        val legs = listOf(
            leg(TransportType.MOTORBIKE, "Christchurch", "Hanmer Springs", 0, 3),
            leg(TransportType.CAR, "Hanmer Springs", "Christchurch", 30, 1)
        )
        val text = PostComposer.buildText(trip(legs, rating = 4, days = 2), options(), 412.4, Locale.US)
        assertEquals("Hanmer ride\n3 days · 412 km · motorbike, car · ★★★★☆\n\nRoute: Christchurch → Hanmer Springs → Christchurch", text)
    }

    @Test
    fun anOwnMessageGoesBelowTheFacts() {
        val text = PostComposer.buildText(trip(), options(message = "  Best weekend of the year  ", route = false, rating = false), null)
        assertEquals("Hanmer ride\n\nBest weekend of the year", text)
    }

    @Test
    fun companionsAreJoinedInEnglish() {
        val one = PostComposer.buildText(trip(companions = listOf(Companion(name = "Sam"))), options(route = false), null)
        assertTrue(one.endsWith("With Sam"))
        val three = PostComposer.buildText(
            trip(companions = listOf(Companion(name = "Sam"), Companion(name = "Alex"), Companion(name = "Kim"))), options(route = false), null
        )
        assertTrue(three.endsWith("With Sam, Alex and Kim"))
    }

    @Test
    fun theTimelineImportStampIsNotPosted() {
        val text = PostComposer.buildText(trip(notes = "Imported from Google Timeline"), options(route = false), null)
        assertEquals("Hanmer ride", text)
        val kept = PostComposer.buildText(trip(notes = "Imported from Google Timeline\nLovely day"), options(route = false), null)
        assertTrue(kept.endsWith("Lovely day"))
    }

    @Test
    fun impressionsAreBulletsInTimeOrder() {
        val impressions = listOf(
            Impression(tripId = 1, text = "Second", timestamp = start + 2),
            Impression(tripId = 1, text = "First", timestamp = start + 1)
        )
        val text = PostComposer.buildText(trip(impressions = impressions), options(route = false), null)
        assertTrue(text.endsWith("• First\n• Second"))
    }

    @Test
    fun switchedOffPartsAreLeftOut() {
        val t = trip(
            listOf(leg(TransportType.CAR, "A", "B", 0, 1)), listOf(Companion(name = "Sam")),
            listOf(Impression(tripId = 1, text = "Nice", timestamp = start)), rating = 5, notes = "Some notes"
        )
        val text = PostComposer.buildText(t, options(rating = false, route = false, companions = false, notes = false, impressions = false), 50.0)
        assertEquals("Hanmer ride", text)
    }

    @Test
    fun theFooterLinksToTheApp() {
        val text = PostComposer.buildText(trip(), PostOptions("", "", false, false, false, false, false, false, includeFooter = true), null)
        assertEquals("Hanmer ride\n\nMade with Adventure Timeline\nhttps://jonesie.github.io/adventure-timeline/", text)
    }

    @Test
    fun placeChainJoinsStopsInOrderAndDropsRepeats() {
        val legs = listOf(
            leg(TransportType.CAR, "A", "B", 0, 1),
            leg(TransportType.CAR, "B", "B", 2, 1),
            leg(TransportType.CAR, "B", "C", 4, 1)
        )
        assertEquals(listOf("A", "B", "C"), PostComposer.placeChain(legs))
    }

    @Test
    fun aLongChainKeepsTheStartAndTheEnd() {
        val names = ('A'..'L').map { it.toString() }
        val legs = names.zipWithNext().mapIndexed { i, (from, to) -> leg(TransportType.CAR, from, to, i * 2L, 1) }
        assertEquals(listOf("A", "B", "C", "D", "…", "J", "K", "L"), PostComposer.placeChain(legs))
    }

    @Test
    fun distanceReadsSensibly() {
        assertEquals(null, PostComposer.distanceText(0.2))
        assertEquals("3.4 km", PostComposer.distanceText(3.44))
        assertEquals("412 km", PostComposer.distanceText(412.4))
    }

    @Test
    fun dayCountTouchesEveryCalendarDay() {
        assertEquals(1, PostComposer.dayCount(start, null))
        assertEquals(1, PostComposer.dayCount(start, start))
        assertEquals(3, PostComposer.dayCount(start, start + 2 * day))
    }

    @Test
    fun trailKmDoesNotCountAcrossAGap() {
        fun p(lat: Double, lon: Double, t: Long) = RoutePoint(tripId = 1, lat = lat, lon = lon, timestamp = t)
        val minute = 60_000L
        // Two short pieces about 1.1 km each, then an hour later 100+ km away: the jump must not be measured.
        val trail = listOf(
            p(-43.50, 172.60, 0), p(-43.51, 172.60, minute),
            p(-42.50, 173.60, 120 * minute), p(-42.51, 173.60, 121 * minute)
        )
        val km = RouteShape.trailKm(trail)
        assertTrue("was $km", km in 2.0..2.5)
        assertFalse(km > 50)
    }
}
