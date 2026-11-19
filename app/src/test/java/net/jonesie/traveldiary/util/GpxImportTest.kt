package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GpxImportTest {
    private fun parse(xml: String, spacing: Double = 0.0) = GpxImport.parse(xml.byteInputStream(), spacing)

    private val gpx = """<?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Test" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>Alps loop</name></metadata>
          <wpt lat="46.0" lon="7.0"><name>Hut</name></wpt>
          <trk><name>Day one</name>
            <trkseg>
              <trkpt lat="46.000" lon="7.000"><ele>1000</ele><time>2026-07-01T08:00:00Z</time></trkpt>
              <trkpt lat="46.001" lon="7.001"><time>2026-07-01T08:01:00Z</time></trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="46.010" lon="7.010"><time>2026-07-02T09:00:00+00:00</time></trkpt>
            </trkseg>
          </trk>
        </gpx>"""

    @Test
    fun readsPointsTimesAndTheFileName() {
        val r = parse(gpx)
        assertEquals("Alps loop", r.name)
        assertEquals(3, r.points.size)
        assertEquals(46.001, r.points[1].lat, 1e-9)
        assertEquals(7.001, r.points[1].lon, 1e-9)
        assertTrue(r.hasTimes)
        assertEquals(1_782_892_800_000L, r.startTime)
        assertEquals(1_782_982_800_000L, r.endTime)
    }

    @Test
    fun fallsBackToTheTrackNameWhenThereIsNoMetadataName() {
        val r = parse("""<gpx><trk><name>Solo ride</name><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""")
        assertEquals("Solo ride", r.name)
        assertFalse(r.hasTimes)
        assertNull(r.startTime)
    }

    @Test
    fun waypointsAloneAreNotARoute() {
        try {
            parse("""<gpx><wpt lat="1" lon="2"><name>Camp</name></wpt></gpx>""")
            fail("expected InvalidGpx")
        } catch (e: GpxImport.InvalidGpx) {
            assertTrue(e.message!!.contains("no route"))
        }
    }

    @Test
    fun aPlannedRouteIsUsedWhenThereIsNoTrack() {
        val r = parse("""<gpx><rte><rtept lat="1" lon="2"/><rtept lat="1.1" lon="2.1"/></rte></gpx>""")
        assertEquals(2, r.points.size)
    }

    @Test
    fun theTrackWinsOverARouteInTheSameFile() {
        val r = parse("""<gpx><rte><rtept lat="5" lon="5"/></rte><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""")
        assertEquals(1, r.points.size)
        assertEquals(1.0, r.points[0].lat, 0.0)
    }

    @Test
    fun badPointsAreSkipped() {
        val r = parse("""<gpx><trk><trkseg>
            <trkpt lat="abc" lon="2"/><trkpt lat="95" lon="2"/><trkpt lat="0" lon="0"/><trkpt lat="3" lon="4"/>
        </trkseg></trk></gpx>""")
        assertEquals(1, r.points.size)
    }

    @Test
    fun notGpxOrBrokenXmlIsRejected() {
        listOf("hello", "<kml></kml>", "<gpx><trk><trkseg><trkpt lat=\"1\"").forEach { bad ->
            try {
                parse(bad)
                fail("expected InvalidGpx for $bad")
            } catch (e: GpxImport.InvalidGpx) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun entityDeclarationsAreRefused() {
        try {
            parse("""<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""")
            fail("expected InvalidGpx")
        } catch (e: GpxImport.InvalidGpx) {
            // refused
        }
    }

    @Test
    fun closePointsAreThinnedButTheLastPointIsKept() {
        // ~1.1 m apart: with 5 m spacing only the first and the final point survive.
        val pts = (0..9).joinToString("") { """<trkpt lat="${1.0 + it * 0.00001}" lon="2"/>""" }
        val r = GpxImport.parse("<gpx><trk><trkseg>$pts</trkseg></trk></gpx>".byteInputStream(), 5.0)
        assertTrue(r.points.size in 2..4)
        assertEquals(1.0, r.points.first().lat, 1e-9)
        assertEquals(1.00009, r.points.last().lat, 1e-9)
    }

    @Test
    fun pointsWithoutTimesAreSpacedFromTheTripStart() {
        val r = parse("""<gpx><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2"/></trkseg></trk></gpx>""")
        val trail = r.toRoutePoints(tripId = 7, fallbackStart = 50_000L)
        assertEquals(listOf(50_000L, 51_000L), trail.map { it.timestamp })
        assertTrue(trail.all { it.tripId == 7L })
    }

    @Test
    fun ourOwnExportComesBackIn() {
        val start = 1_790_000_000_000L
        val trail = listOf(
            RoutePoint(tripId = 1, lat = -43.50, lon = 172.60, timestamp = start),
            RoutePoint(tripId = 1, lat = -43.51, lon = 172.61, timestamp = start + 60_000),
            RoutePoint(tripId = 1, lat = -43.52, lon = 172.62, timestamp = start + 120_000)
        )
        val exported = TripTrack.gpx("Ride & Co", start, trail, emptyList(), emptyList(), null)
        val r = parse(exported)
        assertEquals("Ride & Co", r.name)
        assertEquals(3, r.points.size)
        assertEquals(start, r.startTime)
        assertEquals(start + 120_000, r.endTime)
        assertEquals(-43.52, r.points.last().lat, 1e-6)
    }

    @Test
    fun aTrackBecomesOneLegFromItsFirstToLastPoint() {
        val legs = parse(gpx).toLegs(fallbackStart = 0)
        // Day one is a minute apart; day two starts a day later and 1.5 km away, which is not a gap (under 3 km),
        // so the whole file is one leg.
        assertEquals(1, legs.size)
        assertEquals(46.000, legs[0].originLat, 1e-9)
        assertEquals(46.010, legs[0].destinationLat, 1e-9)
        assertEquals(1_782_892_800_000L, legs[0].startTime)
        assertEquals(1_782_982_800_000L, legs[0].endTime)
    }

    @Test
    fun eachTrackIsItsOwnLeg() {
        val xml = """<gpx>
            <trk><type>cycling</type><trkseg>
              <trkpt lat="1.00" lon="2"><time>2026-07-01T08:00:00Z</time></trkpt>
              <trkpt lat="1.01" lon="2"><time>2026-07-01T08:30:00Z</time></trkpt>
            </trkseg></trk>
            <trk><type>running</type><trkseg>
              <trkpt lat="1.01" lon="2.0001"><time>2026-07-01T09:00:00Z</time></trkpt>
              <trkpt lat="1.02" lon="2"><time>2026-07-01T10:00:00Z</time></trkpt>
            </trkseg></trk></gpx>"""
        val legs = parse(xml).toLegs(0)
        assertEquals(2, legs.size)
        assertEquals(net.jonesie.traveldiary.data.TransportType.BIKE, legs[0].transport)
        assertEquals(net.jonesie.traveldiary.data.TransportType.WALK, legs[1].transport)
    }

    @Test
    fun anOvernightStopFarFromWhereItEndedSplitsTheLeg() {
        val xml = """<gpx><trk><trkseg>
            <trkpt lat="1.00" lon="2"><time>2026-07-01T08:00:00Z</time></trkpt>
            <trkpt lat="1.01" lon="2"><time>2026-07-01T08:05:00Z</time></trkpt>
            <trkpt lat="1.30" lon="2"><time>2026-07-02T08:00:00Z</time></trkpt>
            <trkpt lat="1.31" lon="2"><time>2026-07-02T08:05:00Z</time></trkpt>
        </trkseg></trk></gpx>"""
        val legs = parse(xml).toLegs(0)
        assertEquals(2, legs.size)
        assertEquals(1.01, legs[0].destinationLat, 1e-9)
        assertEquals(1.30, legs[1].originLat, 1e-9)
    }

    @Test
    fun transportIsGuessedFromTheTypeThenFromSpeed() {
        val t = net.jonesie.traveldiary.data.TransportType.entries
        assertEquals(net.jonesie.traveldiary.data.TransportType.MOTORBIKE, GpxImport.guessTransport("Motorcycling", 50.0, 3_600_000, true))
        assertEquals(net.jonesie.traveldiary.data.TransportType.WALK, GpxImport.guessTransport(null, 4.0, 3_600_000, true))
        assertEquals(net.jonesie.traveldiary.data.TransportType.MOTORBIKE, GpxImport.guessTransport(null, 60.0, 3_600_000, true))
        assertEquals(net.jonesie.traveldiary.data.TransportType.MOTORBIKE, GpxImport.guessTransport(null, 4.0, 0, false))
        assertTrue(t.isNotEmpty())
    }

    @Test
    fun aSinglePointTrackMakesNoLeg() {
        assertTrue(parse("""<gpx><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""").toLegs(0).isEmpty())
    }

    @Test
    fun aSparselyRecordedTrackIsStillOneLeg() {
        val xml = """<gpx><trk><trkseg>
            <trkpt lat="1.0" lon="2"><time>2026-07-01T08:00:00Z</time></trkpt>
            <trkpt lat="1.5" lon="2"><time>2026-07-01T09:00:00Z</time></trkpt>
            <trkpt lat="2.0" lon="2"><time>2026-07-01T10:00:00Z</time></trkpt>
        </trkseg></trk></gpx>"""
        assertEquals(1, parse(xml).toLegs(0).size)
    }

    /** A ride north at ~1.1 km a minute with a stop in the middle: [stopMinutes] long, jittering within a few metres. */
    private fun rideWithStop(stopMinutes: Int, before: Int = 10, after: Int = 10): String {
        val sb = StringBuilder("<gpx><trk><trkseg>")
        var minute = 0
        var lat = 1.0
        fun pt(l: Double, m: Int, lonJitter: Double = 0.0) {
            val t = java.time.Instant.parse("2026-07-01T08:00:00Z").plusSeconds(m * 60L)
            sb.append("""<trkpt lat="$l" lon="${2 + lonJitter}"><time>$t</time></trkpt>""")
        }
        repeat(before) { pt(lat, minute++); lat += 0.01 }
        for (k in 0 until stopMinutes) pt(lat, minute++, if (k % 2 == 0) 0.0001 else 0.0)
        repeat(after) { lat += 0.01; pt(lat, minute++) }
        return sb.append("</trkseg></trk></gpx>").toString()
    }

    @Test
    fun aStopOfAFewMinutesSplitsTheRideIntoLegs() {
        val legs = parse(rideWithStop(stopMinutes = 8)).toLegs(0)
        assertEquals(2, legs.size)
        // The first leg ends where the stop began, the second starts where it ended.
        assertEquals(legs[0].destinationLat, legs[1].originLat, 0.0002)
        assertTrue(legs[0].endTime < legs[1].startTime)
    }

    @Test
    fun aMomentaryHaltIsNotAStop() {
        assertEquals(1, parse(rideWithStop(stopMinutes = 3)).toLegs(0).size)
    }

    @Test
    fun aRideTooShortToBeALegIsFoldedIntoItsNeighbour() {
        // Only ~1 km before the stop: not a leg of its own.
        val legs = parse(rideWithStop(stopMinutes = 8, before = 1, after = 10)).toLegs(0)
        assertEquals(1, legs.size)
    }

    @Test
    fun filesWithoutTimesAreNotSplitAtStops() {
        val xml = """<gpx><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1" lon="2"/><trkpt lat="1.05" lon="2"/></trkseg></trk></gpx>"""
        assertEquals(1, parse(xml).toLegs(0).size)
    }
}
