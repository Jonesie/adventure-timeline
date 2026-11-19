package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.MapNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPlaybackTest {

    private val minute = 60_000L

    private fun point(lat: Double, lon: Double, t: Long) = RoutePoint(tripId = 1, lat = lat, lon = lon, timestamp = t)

    /** A ride heading north at about 1 km a minute, one point a minute. */
    private fun ride(startMinute: Long = 0, points: Int = 31, lat0: Double = -43.5) =
        List(points) { i -> point(lat0 + i * 0.009, 172.6, (startMinute + i) * minute) }

    private fun leg(type: TransportType, startMin: Long, endMin: Long, plane: Boolean = false) = Leg(
        tripId = 1, transportType = type, originName = "a", destinationName = "b",
        startTime = startMin * minute, endTime = endMin * minute,
        originLat = if (plane) -43.49 else null, originLon = if (plane) 172.53 else null,
        destinationLat = if (plane) -41.33 else null, destinationLon = if (plane) 174.81 else null
    )

    private fun photo(t: Long, lat: Double? = null, lon: Double? = null, type: MediaType = MediaType.PHOTO) =
        MediaItem(tripId = 1, type = type, uri = "content://p/$t", timestamp = t, lat = lat, lon = lon)

    private fun incident(t: Long, lat: Double? = null, lon: Double? = null) =
        Incident(tripId = 1, category = IncidentCategory.CRASH, timestamp = t, lat = lat, lon = lon)

    @Test
    fun aGapInTheTrailBecomesTwoPiecesAndANightCostsNothing() {
        val trail = ride(0, 10) + ride(600, 10, lat0 = -42.0)
        val timeline = TripPlayback.build(trail, listOf(leg(TransportType.CAR, 0, 700)), emptyList(), emptyList())
        assertEquals(2, timeline.pieces.size)
        // The ten hours between the pieces take no time to play: the second starts exactly where the first ends.
        val first = timeline.pieces[0]
        assertEquals(first.startUnit + first.units, timeline.pieces[1].startUnit, 1e-9)
    }

    @Test
    fun aFlightWithNoTrailIsADashedStraightPiece() {
        val trail = ride(0, 10)
        val legs = listOf(leg(TransportType.CAR, 0, 9), leg(TransportType.PLANE, 100, 160, plane = true))
        val timeline = TripPlayback.build(trail, legs, emptyList(), emptyList())
        val flight = timeline.pieces.single { it.dashed }
        assertEquals(TransportType.PLANE, flight.transport)
        assertEquals(2, flight.vertices.size)
        assertTrue(flight.lengthKm > 200)
    }

    @Test
    fun withNoTrailEveryLegIsAStraightPiece() {
        val timeline = TripPlayback.build(emptyList(), listOf(leg(TransportType.PLANE, 0, 60, plane = true)), emptyList(), emptyList())
        assertEquals(1, timeline.pieces.size)
        assertTrue(timeline.pieces[0].dashed)
    }

    @Test
    fun nothingToPlayWithoutARoute() {
        val timeline = TripPlayback.build(emptyList(), emptyList(), listOf(photo(0, -43.5, 172.6)), emptyList())
        assertTrue(timeline.isEmpty)
        assertNull(timeline.positionAt(0.0))
        assertTrue(timeline.events.isEmpty())
    }

    @Test
    fun aLongPieceIsWorthMoreThanAShortOneButNotInProportion() {
        val short = TripPlayback.build(ride(0, 6), emptyList(), emptyList(), emptyList())
        val long = TripPlayback.build(ride(0, 91), emptyList(), emptyList(), emptyList())
        val ratio = long.totalUnits / short.totalUnits
        assertTrue("was $ratio", ratio > 2 && ratio < 10)
    }

    @Test
    fun positionRunsFromTheStartToTheEndOfTheRoute() {
        val trail = ride(0, 11)
        val timeline = TripPlayback.build(trail, emptyList(), emptyList(), emptyList())
        val start = timeline.positionAt(0.0)!!
        val end = timeline.positionAt(timeline.totalUnits)!!
        val middle = timeline.positionAt(timeline.totalUnits / 2)!!
        assertEquals(trail.first().lat, start.lat, 1e-9)
        assertEquals(trail.last().lat, end.lat, 1e-9)
        assertEquals((trail.first().lat + trail.last().lat) / 2, middle.lat, 1e-6)
        assertEquals(1, start.passed)
    }

    @Test
    fun positionReportsTheTransportOfTheLegUnderneath() {
        val trail = ride(0, 21)
        val legs = listOf(leg(TransportType.WALK, 0, 10), leg(TransportType.MOTORBIKE, 10, 20))
        val timeline = TripPlayback.build(trail, legs, emptyList(), emptyList())
        assertEquals(2, timeline.pieces.size)
        assertEquals(TransportType.WALK, timeline.positionAt(0.0)!!.transport)
        assertEquals(TransportType.MOTORBIKE, timeline.positionAt(timeline.totalUnits)!!.transport)
        assertEquals(1, timeline.positionAt(timeline.totalUnits)!!.pieceIndex)
    }

    @Test
    fun aHugeTrailIsThinnedButKeepsItsEnds() {
        val trail = List(9000) { i -> point(-43.5 + i * 0.0001, 172.6, i * 1000L) }
        val timeline = TripPlayback.build(trail, emptyList(), emptyList(), emptyList())
        val vertices = timeline.pieces.sumOf { it.vertices.size }
        assertTrue("was $vertices", vertices <= TripPlayback.MAX_VERTICES + timeline.pieces.size)
        assertEquals(trail.first().lat, timeline.pieces.first().vertices.first().lat, 1e-9)
        assertEquals(trail.last().lat, timeline.pieces.last().vertices.last().lat, 1e-9)
    }

    @Test
    fun aPhotoIsPlacedByItsTimeAlongTheRoute() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), listOf(photo(15 * minute)), emptyList())
        val event = timeline.events.single()
        assertEquals(timeline.totalUnits / 2, event.unit, timeline.totalUnits * 0.05)
    }

    @Test
    fun aPhotoOutsideTheTripFallsBackToItsLocation() {
        val trail = ride(0, 31)
        val nearTheEnd = trail.last()
        val timeline = TripPlayback.build(
            trail, emptyList(),
            listOf(photo(1000 * 60 * minute, nearTheEnd.lat, nearTheEnd.lon)), emptyList()
        )
        assertEquals(timeline.totalUnits, timeline.events.single().unit, timeline.totalUnits * 0.05)
    }

    @Test
    fun aPhotoFarFromTheRouteAndOutsideTheTripIsLeftOut() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), listOf(photo(1000 * 60 * minute, 10.0, 20.0)), emptyList())
        assertTrue(timeline.events.isEmpty())
    }

    @Test
    fun aPhotoWithNeitherTimeNorPlaceThatFitsIsLeftOut() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), listOf(photo(1000 * 60 * minute)), emptyList())
        assertTrue(timeline.events.isEmpty())
    }

    @Test
    fun videosAreNotShown() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), listOf(photo(10 * minute, type = MediaType.VIDEO)), emptyList())
        assertTrue(timeline.events.isEmpty())
    }

    @Test
    fun anIncidentWithoutALocationIsPlacedByTime() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), emptyList(), listOf(incident(20 * minute)))
        val event = timeline.events.single()
        assertNotNull(event.incident)
        assertEquals(TripPlayback.INCIDENT_DWELL_MS, event.dwellMs)
        assertEquals(timeline.totalUnits * 2 / 3, event.unit, timeline.totalUnits * 0.05)
    }

    @Test
    fun aLongPhotoRollIsSpreadEvenlyDownToTheLimit() {
        val photos = List(100) { i -> photo(i * minute / 4 + 1) }
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), photos, emptyList())
        assertEquals(TripPlayback.MAX_PHOTOS, timeline.events.size)
        assertEquals(photos.first().timestamp, timeline.events.first().photo!!.timestamp)
        assertEquals(photos.last().timestamp, timeline.events.last().photo!!.timestamp)
    }

    @Test
    fun eventsComeOutInOrderAlongTheRoute() {
        val timeline = TripPlayback.build(
            ride(0, 31), emptyList(),
            listOf(photo(25 * minute), photo(5 * minute)), listOf(incident(15 * minute))
        )
        assertEquals(listOf(5 * minute, 15 * minute, 25 * minute), timeline.events.map { it.time })
    }

    @Test
    fun theSessionStopsAtAnEventForItsDwellThenCarriesOn() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), emptyList(), listOf(incident(15 * minute)))
        val session = TripPlayback.Session(timeline)

        var reached: TripPlayback.Event? = null
        var guard = 0
        while (reached == null && guard++ < 10_000) reached = session.advance(16, 1f)
        assertNotNull(reached)
        assertEquals(reached, session.active)
        assertEquals(1, session.reached)

        val stoppedAt = session.unit
        session.advance(1000, 1f)
        assertEquals(stoppedAt, session.unit, 0.0)
        assertNotNull(session.active)

        session.advance(TripPlayback.INCIDENT_DWELL_MS, 1f)
        assertNull(session.active)
        session.advance(16, 1f)
        assertTrue(session.unit > stoppedAt)
    }

    @Test
    fun anEventStaysUpForTheSameTimeAtAnySpeed() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), listOf(photo(15 * minute)), emptyList())
        listOf(1f, 2f, 4f).forEach { speed ->
            val session = TripPlayback.Session(timeline)
            var guard = 0
            while (session.active == null && guard++ < 10_000) session.advance(16, speed)
            assertNotNull("speed $speed", session.active)

            var shownMs = 0L
            while (session.active != null) { session.advance(16, speed); shownMs += 16 }
            assertEquals("speed $speed", TripPlayback.PHOTO_DWELL_MS.toDouble(), shownMs.toDouble(), 16.0)
        }
    }

    @Test
    fun fasterSpeedGetsThereSooner() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), emptyList(), emptyList())
        val slow = TripPlayback.Session(timeline).also { it.advance(1000, 1f) }
        val fast = TripPlayback.Session(timeline).also { it.advance(1000, 4f) }
        assertEquals(slow.unit * 4, fast.unit, 1e-9)
    }

    @Test
    fun theSessionFinishesAtTheEndAndRestarts() {
        val timeline = TripPlayback.build(ride(0, 31), emptyList(), emptyList(), emptyList())
        val session = TripPlayback.Session(timeline)
        assertFalse(session.finished)
        repeat(1000) { session.advance(1000, 4f) }
        assertTrue(session.finished)
        assertEquals(timeline.totalUnits, session.unit, 1e-9)

        session.restart()
        assertFalse(session.finished)
        assertEquals(0.0, session.unit, 0.0)
    }

    @Test
    fun seekingBackReplaysLaterEventsAndSeekingPastSkipsEarlierOnes() {
        val timeline = TripPlayback.build(
            ride(0, 31), emptyList(), emptyList(),
            listOf(incident(5 * minute), incident(25 * minute))
        )
        val session = TripPlayback.Session(timeline)
        session.seek(timeline.totalUnits * 0.5)
        assertEquals(1, session.reached)
        assertNull(session.active)
        session.seek(0.0)
        assertEquals(0, session.reached)
        session.seek(timeline.totalUnits)
        assertEquals(2, session.reached)
    }

    @Test
    fun aLongTripPlaysForLongerButWithinLimits() {
        val short = TripPlayback.build(ride(0, 6), emptyList(), emptyList(), emptyList())
        val long = TripPlayback.build(ride(0, 3000), emptyList(), emptyList(), emptyList())
        assertTrue(short.movingMs >= 15_000)
        assertTrue(long.movingMs <= 60_000)
        assertTrue(long.movingMs > short.movingMs)
    }

    @Test
    fun aNoteStopsWhereTheRoutePassesClosestToIt() {
        val trail = ride(0, 31)
        // Half way up the ride, 200 m to the side of it.
        val note = MapNote(id = 5, tripId = 1, lat = -43.5 + 15 * 0.009, lon = 172.6 + 0.002, text = "Road washed out", createdAt = 1)
        val timeline = TripPlayback.build(trail, listOf(leg(TransportType.MOTORBIKE, 0, 40)), emptyList(), emptyList(), listOf(note))
        val event = timeline.events.single()
        assertEquals(note, event.note)
        assertEquals(TripPlayback.NOTE_DWELL_MS, event.dwellMs)
        assertEquals(timeline.totalUnits / 2, event.unit, timeline.totalUnits * 0.05)
    }

    @Test
    fun aNoteFarFromTheRouteIsLeftOutOfThePlayback() {
        val far = MapNote(id = 6, tripId = 1, lat = 10.0, lon = 10.0, text = "Elsewhere", createdAt = 1)
        val timeline = TripPlayback.build(ride(0, 31), listOf(leg(TransportType.MOTORBIKE, 0, 40)), emptyList(), emptyList(), listOf(far))
        assertTrue(timeline.events.isEmpty())
    }

    @Test
    fun notesAndIncidentsAreShownInRouteOrder() {
        val trail = ride(0, 31)
        val early = MapNote(id = 1, tripId = 1, lat = -43.5 + 3 * 0.009, lon = 172.6, text = "Early", createdAt = 1)
        val timeline = TripPlayback.build(
            trail, listOf(leg(TransportType.MOTORBIKE, 0, 40)), emptyList(),
            listOf(incident(t = 20 * minute, lat = -43.5 + 20 * 0.009, lon = 172.6)), listOf(early)
        )
        assertNotNull(timeline.events[0].note)
        assertNotNull(timeline.events[1].incident)
    }
}
