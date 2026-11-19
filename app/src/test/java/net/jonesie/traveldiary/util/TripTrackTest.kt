package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.MapNote
import net.jonesie.traveldiary.data.MapNoteCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackTest {
    private val minute = 60_000L
    private val start = 1_790_000_000_000L

    private fun p(lat: Double, lon: Double, min: Long) = RoutePoint(tripId = 1, lat = lat, lon = lon, timestamp = start + min * minute)

    private val trail = listOf(p(-43.50, 172.60, 0), p(-43.51, 172.61, 1), p(-43.52, 172.62, 2))

    private val leg = Leg(
        tripId = 1, transportType = TransportType.MOTORBIKE, originName = "Christchurch", destinationName = "Hanmer & Co",
        startTime = start, endTime = start + 2 * minute,
        originLat = -43.50, originLon = 172.60, destinationLat = -43.52, destinationLon = 172.62
    )

    @Test
    fun gpxHasTheTrackWithTimesAndEscapedNames() {
        val gpx = TripTrack.gpx("Ride <1>", start, trail, listOf(leg), emptyList(), null)
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<name>Ride &lt;1&gt;</name>"))
        assertEquals(3, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("<trkpt lat=\"-43.510000\" lon=\"172.610000\"><time>"))
        assertTrue(gpx.contains("<wpt lat=\"-43.520000\" lon=\"172.620000\">"))
        assertTrue(gpx.contains("<name>Hanmer &amp; Co</name>"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun incidentsAreWaypointsOnlyWhenPassedIn() {
        val incident = Incident(category = IncidentCategory.STUCK, severity = 3, notes = "Deep mud", timestamp = start, lat = -43.51, lon = 172.61)
        val with = TripTrack.gpx("Ride", start, trail, emptyList(), listOf(incident), null)
        assertTrue(with.contains("<name>⚠ Got stuck (serious)</name><desc>Deep mud</desc>"))
        val without = TripTrack.gpx("Ride", start, trail, emptyList(), emptyList(), null)
        assertFalse(without.contains("Got stuck"))
    }

    @Test
    fun theHomeAreaIsLeftOutAndCutsTheTrack() {
        val route = listOf(p(-43.50, 172.60, 0), p(-43.505, 172.605, 1), p(-43.60, 172.70, 2), p(-43.61, 172.71, 3))
        val gpx = TripTrack.gpx("Ride", start, route, emptyList(), emptyList(), HiddenArea(-43.50, 172.60, 2.0))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertFalse(gpx.contains("-43.500000"))
        assertEquals(1, Regex("<trkseg>").findAll(gpx).count())

        val middle = listOf(p(-43.60, 172.70, 0), p(-43.61, 172.71, 1), p(-43.50, 172.60, 2), p(-43.62, 172.72, 3), p(-43.63, 172.73, 4))
        val twoPieces = TripTrack.gpx("Ride", start, middle, emptyList(), emptyList(), HiddenArea(-43.50, 172.60, 2.0))
        assertEquals(2, Regex("<trkseg>").findAll(twoPieces).count())
    }

    @Test
    fun withNoTrailTheTrackJoinsTheLegEnds() {
        val gpx = TripTrack.gpx("Flight", start, emptyList(), listOf(leg), emptyList(), null)
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun kmlHasALineStringWithLonLatOrder() {
        val kml = TripTrack.kml("Ride", start, trail, listOf(leg), emptyList(), null)
        assertTrue(kml.contains("<LineString><tessellate>1</tessellate><coordinates>172.600000,-43.500000,0 172.610000,-43.510000,0 172.620000,-43.520000,0</coordinates></LineString>"))
        assertTrue(kml.contains("<Point><coordinates>172.620000,-43.520000,0</coordinates></Point>"))
        assertTrue(kml.trimEnd().endsWith("</kml>"))
    }

    @Test
    fun anEmptyTripStillMakesAValidFile() {
        val gpx = TripTrack.gpx("Nothing", start, emptyList(), emptyList(), emptyList(), null)
        assertFalse(gpx.contains("<trk>"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    private fun mapNote(id: Long, lat: Double, lon: Double, text: String, category: MapNoteCategory? = null, shareable: Boolean = true) =
        MapNote(id = id, tripId = 1, lat = lat, lon = lon, text = text, category = category, createdAt = 1000L + id, shareable = shareable)

    @Test
    fun shareableNotesAreWaypointsTypedAsNotes() {
        val notes = listOf(
            mapNote(1, -43.51, 172.61, "Road <washed> out", MapNoteCategory.HAZARD),
            mapNote(2, -43.52, 172.62, "Lots of potholes"),
            mapNote(3, -43.50, 172.60, "Keep this to myself", shareable = false)
        )
        val gpx = TripTrack.gpx("Ride", start, trail, emptyList(), emptyList(), null, notes)
        assertTrue(gpx.contains("<name>Road &lt;washed&gt; out</name><desc>Hazard</desc><type>note/hazard</type>"))
        assertTrue(gpx.contains("<name>Lots of potholes</name><type>note</type>"))
        assertFalse(gpx.contains("Keep this to myself"))
        // A note's time is when it was written, not when the rider passed, so none is written.
        assertFalse(Regex("<wpt[^>]*><time>[^<]*</time><name>Lots").containsMatchIn(gpx))
    }

    @Test
    fun notesInsideTheHiddenHomeAreLeftOut() {
        val notes = listOf(mapNote(1, -43.50, 172.60, "Near home"), mapNote(2, -43.52, 172.62, "Far away"))
        val gpx = TripTrack.gpx("Ride", start, trail, emptyList(), emptyList(), HiddenArea(-43.50, 172.60, 2.0), notes)
        assertFalse(gpx.contains("Near home"))
        assertTrue(gpx.contains("Far away"))
    }

    @Test
    fun kmlHasPlacemarksForNotes() {
        val kml = TripTrack.kml("Ride", start, trail, emptyList(), emptyList(), null, listOf(mapNote(1, -43.51, 172.61, "Gate locked after 5")))
        assertTrue(kml.contains("<name>Gate locked after 5</name>"))
        assertTrue(kml.contains("<Point><coordinates>172.610000,-43.510000,0</coordinates></Point>"))
    }
}
