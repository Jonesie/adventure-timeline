package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import java.time.Instant
import java.util.Locale

/** A trip's route as a GPX or KML file, for mapping apps, GPS units and Google Earth. */
object TripTrack {

    private class Waypoint(val lat: Double, val lon: Double, val name: String, val description: String?, val time: Long?)

    private class Track(val title: String, val startTime: Long, val segments: List<List<RoutePoint>>, val waypoints: List<Waypoint>)

    fun gpx(title: String, startTime: Long, trail: List<RoutePoint>, legs: List<Leg>, incidents: List<Incident>, hidden: HiddenArea?): String {
        val t = build(title, startTime, trail, legs, incidents, hidden)
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"Adventure Timeline\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.append("  <metadata><name>").append(escape(t.title)).append("</name><time>").append(utc(t.startTime)).append("</time></metadata>\n")
        t.waypoints.forEach { w ->
            out.append("  <wpt lat=\"").append(coord(w.lat)).append("\" lon=\"").append(coord(w.lon)).append("\">")
            w.time?.let { out.append("<time>").append(utc(it)).append("</time>") }
            out.append("<name>").append(escape(w.name)).append("</name>")
            w.description?.let { out.append("<desc>").append(escape(it)).append("</desc>") }
            out.append("</wpt>\n")
        }
        if (t.segments.isNotEmpty()) {
            out.append("  <trk>\n    <name>").append(escape(t.title)).append("</name>\n")
            t.segments.forEach { segment ->
                out.append("    <trkseg>\n")
                segment.forEach { p ->
                    out.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\"><time>")
                        .append(utc(p.timestamp)).append("</time></trkpt>\n")
                }
                out.append("    </trkseg>\n")
            }
            out.append("  </trk>\n")
        }
        out.append("</gpx>\n")
        return out.toString()
    }

    fun kml(title: String, startTime: Long, trail: List<RoutePoint>, legs: List<Leg>, incidents: List<Incident>, hidden: HiddenArea?): String {
        val t = build(title, startTime, trail, legs, incidents, hidden)
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n  <name>").append(escape(t.title)).append("</name>\n")
        if (t.segments.isNotEmpty()) {
            out.append("  <Placemark>\n    <name>").append(escape(t.title)).append("</name>\n    <MultiGeometry>\n")
            t.segments.forEach { segment ->
                out.append("      <LineString><tessellate>1</tessellate><coordinates>")
                out.append(segment.joinToString(" ") { coord(it.lon) + "," + coord(it.lat) + ",0" })
                out.append("</coordinates></LineString>\n")
            }
            out.append("    </MultiGeometry>\n  </Placemark>\n")
        }
        t.waypoints.forEach { w ->
            out.append("  <Placemark>\n    <name>").append(escape(w.name)).append("</name>\n")
            w.description?.let { out.append("    <description>").append(escape(it)).append("</description>\n") }
            w.time?.let { out.append("    <TimeStamp><when>").append(utc(it)).append("</when></TimeStamp>\n") }
            out.append("    <Point><coordinates>").append(coord(w.lon)).append(',').append(coord(w.lat)).append(",0</coordinates></Point>\n  </Placemark>\n")
        }
        out.append("</Document>\n</kml>\n")
        return out.toString()
    }

    private fun build(title: String, startTime: Long, trail: List<RoutePoint>, legs: List<Leg>, incidents: List<Incident>, hidden: HiddenArea?): Track {
        fun visible(lat: Double, lon: Double) = TripExport.hasPosition(lat, lon) && (hidden == null || !hidden.contains(lat, lon))

        val sortedLegs = legs.sortedBy { it.startTime }
        val points = trail.sortedBy { it.timestamp }.ifEmpty { legEnds(sortedLegs) }

        // A new piece starts at a gap in the recording and wherever the home area cut points out, so no line crosses either.
        val segments = mutableListOf<MutableList<RoutePoint>>()
        var previous: RoutePoint? = null
        var cut = true
        points.forEach { p ->
            if (!visible(p.lat, p.lon)) {
                cut = true
            } else {
                if (cut || (previous != null && RouteShape.isGap(previous!!, p))) segments.add(mutableListOf())
                segments.last() += p
                previous = p
                cut = false
            }
        }

        val waypoints = mutableListOf<Waypoint>()
        sortedLegs.forEachIndexed { i, leg ->
            val transport = leg.transportType.name.lowercase().replace('_', ' ')
            val ol = leg.originLat; val on = leg.originLon
            if (i == 0 && ol != null && on != null && visible(ol, on) && leg.originName.isNotBlank()) {
                waypoints += Waypoint(ol, on, leg.originName.trim(), "Start", leg.startTime)
            }
            val dl = leg.destinationLat; val dn = leg.destinationLon
            if (dl != null && dn != null && visible(dl, dn) && leg.destinationName.isNotBlank()) {
                waypoints += Waypoint(dl, dn, leg.destinationName.trim(), "Arrived by $transport", leg.endTime ?: leg.startTime)
            }
        }
        incidents.sortedBy { it.timestamp }.forEach { incident ->
            val lat = incident.lat; val lon = incident.lon
            if (lat != null && lon != null && visible(lat, lon)) {
                waypoints += Waypoint(lat, lon, "⚠ " + PostComposer.incidentLine(incident).substringBefore(':'), incident.notes.trim().ifEmpty { null }, incident.timestamp)
            }
        }
        return Track(title, startTime, segments.filter { it.size > 1 }, waypoints)
    }

    /** With no recorded trail (a flight, say) the track joins the legs' ends. */
    private fun legEnds(legs: List<Leg>): List<RoutePoint> = legs.flatMap { leg ->
        listOfNotNull(
            leg.originLat?.let { a -> leg.originLon?.let { b -> RoutePoint(tripId = leg.tripId, lat = a, lon = b, timestamp = leg.startTime) } },
            leg.destinationLat?.let { a -> leg.destinationLon?.let { b -> RoutePoint(tripId = leg.tripId, lat = a, lon = b, timestamp = leg.endTime ?: leg.startTime) } }
        )
    }

    private fun coord(value: Double) = "%.6f".format(Locale.US, value)

    private fun utc(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    internal fun escape(text: String): String = buildString {
        text.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (it >= ' ' || it == '\t' || it == '\n' || it == '\r') append(it)
            }
        }
    }
}
