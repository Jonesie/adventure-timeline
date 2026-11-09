package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Companion
import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
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
        rating: Int = 0,
        notes: String = "",
        days: Long = 0,
        incidents: List<Incident> = emptyList()
    ) = TripDetail(
        trip = Trip(id = 1, title = "Hanmer ride", startDate = start, endDate = start + days * day, notes = notes, rating = rating),
        companions = companions, legs = legs, media = emptyList(), incidents = incidents
    )

    private fun options(
        message: String = "", dates: Boolean = false, rating: Boolean = true, route: Boolean = true, legs: Boolean = false,
        incidents: Boolean = false, companions: Boolean = true, notes: Boolean = true
    ) = PostOptions("", message, dates, rating, route, legs, incidents, companions, notes)

    @Test
    fun titleAloneWhenNothingElseIsWanted() {
        val text = PostComposer.buildText(trip(), options(route = false, rating = false, companions = false, notes = false), null)
        assertEquals("Hanmer ride", text)
    }

    @Test
    fun factsLineHasDaysDistanceTransportAndStars() {
        val legs = listOf(
            leg(TransportType.MOTORBIKE, "Christchurch", "Hanmer Springs", 0, 3),
            leg(TransportType.CAR, "Hanmer Springs", "Christchurch", 30, 1)
        )
        val text = PostComposer.buildText(trip(legs, rating = 4, days = 2), options(), 412.4, Locale.US)
        assertEquals("Hanmer ride\n3 days · 412 km · motorbike, car · ★★★★☆", text)
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
    fun switchedOffPartsAreLeftOut() {
        val t = trip(
            listOf(leg(TransportType.CAR, "A", "B", 0, 1)), listOf(Companion(name = "Sam")),
            rating = 5, notes = "Some notes"
        )
        val text = PostComposer.buildText(t, options(rating = false, route = false, companions = false, notes = false), 50.0)
        assertEquals("Hanmer ride", text)
    }

    @Test
    fun thePreviewHasNoFooterButThePostedTextDoes() {
        val text = PostComposer.buildText(trip(), PostOptions("", "", false, false, false, false, false, false, false), null)
        assertEquals("Hanmer ride", text)
        assertEquals("Hanmer ride\n\nMade with Adventure Timeline\nhttps://jonesie.github.io/adventure-timeline/", PostComposer.withFooter(text))
    }

    @Test
    fun legsAreListedSeparatelyFromTheRouteSummary() {
        val legs = listOf(
            leg(TransportType.CAR, "Hanmer Springs", "Christchurch", 30, 1),
            leg(TransportType.MOTORBIKE, "Christchurch", "Hanmer Springs", 0, 3)
        )
        val legsOnly = PostComposer.buildText(trip(legs), options(route = false, rating = false, legs = true, companions = false, notes = false), null)
        assertEquals("Hanmer ride\n\nLegs:\n• Christchurch → Hanmer Springs · motorbike\n• Hanmer Springs → Christchurch · car", legsOnly)

        val summaryOnly = PostComposer.buildText(trip(legs, days = 1), options(rating = false, legs = false, companions = false, notes = false), 100.0, Locale.US)
        assertTrue(summaryOnly.contains("2 days · 100 km · motorbike, car"))
        assertFalse(summaryOnly.contains("Legs:"))

        val both = PostComposer.buildText(trip(legs, days = 1), options(rating = false, legs = true, companions = false, notes = false), 100.0, Locale.US)
        assertTrue(both.contains("2 days · 100 km · motorbike, car"))
        assertTrue(both.contains("Legs:"))
    }

    @Test
    fun incidentsAreHighlightedInTimeOrderWithoutTheReasonOrPeople() {
        val incidents = listOf(
            Incident(category = IncidentCategory.WATER, severity = 2, notes = "Deep crossing\n  bike drowned", timestamp = start + 2, reason = "Stupidity"),
            Incident(category = IncidentCategory.FLAT_TYRE, timestamp = start + 1)
        )
        val on = PostComposer.buildText(trip(incidents = incidents), options(route = false, rating = false, incidents = true, companions = false, notes = false), null)
        assertEquals("Hanmer ride\n\n⚠ Incidents:\n⚠ Flat tyre\n⚠ River or water mishap (moderate): Deep crossing bike drowned", on)
        val off = PostComposer.buildText(trip(incidents = incidents), options(route = false, rating = false, companions = false, notes = false), null)
        assertFalse(off.contains("Incidents"))
    }

    @Test
    fun theContentKeepsParagraphsSeparateAndMarksIncidents() {
        val incidents = listOf(Incident(category = IncidentCategory.STUCK, timestamp = start))
        val content = PostComposer.build(
            trip(incidents = incidents), options(message = "Hi", route = false, rating = false, incidents = true, companions = false, notes = false), null
        )
        assertEquals("Hanmer ride", content.title)
        assertEquals(null, content.facts)
        assertEquals(listOf("Hi", "⚠ Incidents:\n⚠ Got stuck"), content.paragraphs.map { it.text })
        assertEquals(listOf(false, true), content.paragraphs.map { it.accent })
        assertEquals("Hanmer ride\n\nHi\n\n⚠ Incidents:\n⚠ Got stuck", content.toText())
    }

    @Test
    fun placesInsideTheHiddenAreaAreWrittenAsHome() {
        val legs = listOf(
            leg(TransportType.MOTORBIKE, "12 Real Street", "Hanmer Springs", 0, 3).copy(
                originLat = -43.50, originLon = 172.60, destinationLat = -42.52, destinationLon = 172.83
            ),
            leg(TransportType.CAR, "Hanmer Springs", "12 Real Street", 30, 1).copy(
                originLat = -42.52, originLon = 172.83, destinationLat = -43.505, destinationLon = 172.605
            )
        )
        val lines = PostComposer.legLines(legs, HiddenArea(-43.50, 172.60, 2.0))
        assertEquals(listOf("home → Hanmer Springs · motorbike", "Hanmer Springs → home · car"), lines)
        assertEquals(listOf("12 Real Street → Hanmer Springs · motorbike", "Hanmer Springs → 12 Real Street · car"), PostComposer.legLines(legs))
    }

    @Test
    fun aLongLegListIsCut() {
        val legs = (0 until 20).map { leg(TransportType.CAR, "S$it", "S${it + 1}", it * 2L, 1) }
        val lines = PostComposer.legLines(legs)
        assertEquals(16, lines.size)
        assertEquals("S0 → S1 · car", lines.first())
        assertEquals("…and 5 more", lines.last())
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
