package net.jonesie.traveldiary.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.rememberUpdatedState
import net.jonesie.traveldiary.data.MediaItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.util.RouteShape
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date

/** A stretch of the route drawn in one colour. [level] is the leg's difficulty, 0 when not rated. */
private data class RouteSegment(val points: List<GeoPoint>, val level: Int, val dashed: Boolean = false)

/**
 * Splits the recorded GPS trail wherever the leg underneath it changes difficulty. Each piece
 * starts on the previous piece's last point so the line stays continuous.
 */
private fun segmentsFromTrail(trail: List<RoutePoint>, legs: List<Leg>): List<RouteSegment> {
    val timedLegs = legs.filter { it.endTime != null }.sortedBy { it.startTime }
    val segments = mutableListOf<RouteSegment>()
    var current = mutableListOf<GeoPoint>()
    var currentLevel = 0
    var legIndex = 0
    var previous: RoutePoint? = null

    for (point in trail.sortedBy { it.timestamp }) {
        // Don't draw a straight line across a stretch the phone never tracked.
        if (previous != null && RouteShape.isGap(previous, point)) {
            if (current.size > 1) segments += RouteSegment(current, currentLevel)
            current = mutableListOf()
        }
        previous = point
        while (legIndex < timedLegs.size && timedLegs[legIndex].endTime!! < point.timestamp) legIndex++
        val leg = timedLegs.getOrNull(legIndex)?.takeIf { it.startTime <= point.timestamp }
        val level = leg?.difficulty ?: 0

        if (current.isNotEmpty() && level != currentLevel) {
            segments += RouteSegment(current, currentLevel)
            current = mutableListOf(current.last())
        }
        currentLevel = level
        current.add(GeoPoint(point.lat, point.lon))
    }
    if (current.size > 1 || (current.isNotEmpty() && segments.isEmpty())) {
        segments += RouteSegment(current, currentLevel)
    }
    // A flight has little or no trail; a dashed straight line is the honest way to show it.
    RouteShape.sparseLegs(trail, legs, TransportType.PLANE).forEach { leg ->
        segments += RouteSegment(
            listOf(GeoPoint(leg.originLat!!, leg.originLon!!), GeoPoint(leg.destinationLat!!, leg.destinationLon!!)),
            leg.difficulty, dashed = true
        )
    }
    return segments
}

/** Fallback when no GPS trail was imported: a straight line per leg between its endpoints. */
private fun segmentsFromLegs(legs: List<Leg>): List<RouteSegment> =
    legs.sortedBy { it.startTime }.mapNotNull { leg ->
        val points = listOfNotNull(
            if (leg.originLat != null && leg.originLon != null) GeoPoint(leg.originLat, leg.originLon) else null,
            if (leg.destinationLat != null && leg.destinationLon != null) GeoPoint(leg.destinationLat, leg.destinationLon) else null
        )
        if (points.isEmpty()) null else RouteSegment(points, leg.difficulty, dashed = true)
    }

/** A stop along the route: where one leg ends and the next begins. */
internal data class Waypoint(val point: GeoPoint, val label: String, val time: Long?)

private fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) * Math.sin(dLon / 2).let { it * it }
    return 6_371_000.0 * 2 * Math.asin(Math.sqrt(h))
}

/**
 * The trip's start plus the end of every leg, in order. Timeline splits one stop into several
 * tiny legs, so a stop within 100 m of the previous one is skipped to keep the map readable.
 */
internal fun buildWaypoints(legs: List<Leg>): List<Waypoint> {
    val result = mutableListOf<Waypoint>()
    fun add(lat: Double?, lon: Double?, label: String, time: Long?) {
        if (lat == null || lon == null) return
        val point = GeoPoint(lat, lon)
        if (result.isNotEmpty() && metersBetween(result.last().point, point) < 100) return
        result += Waypoint(point, label, time)
    }
    val sorted = legs.sortedBy { it.startTime }
    sorted.firstOrNull { it.originLat != null }?.let { add(it.originLat, it.originLon, it.originName.ifBlank { "Start" }, it.startTime) }
    sorted.forEach { add(it.destinationLat, it.destinationLon, it.destinationName.ifBlank { "Stop" }, it.endTime ?: it.startTime) }
    return result
}

/** Remembers what the map currently shows, so a pinch-zoom isn't undone on recomposition. */
private class RouteMapState {
    var drawn: List<RouteSegment>? = null
    var drawnWaypoints: List<Waypoint>? = null
    var fittedTo: List<GeoPoint>? = null
    var drawnLocation: GeoPoint? = null
    var drawnPins: List<PhotoPin>? = null
}

private fun drawOverlays(
    mapView: MapView,
    segments: List<RouteSegment>,
    allPoints: List<GeoPoint>,
    waypoints: List<Waypoint>,
    myLocation: GeoPoint?,
    pins: List<PhotoPin>,
    onOpenPin: (PhotoPin) -> Unit,
    dark: Boolean
) {
    val density = mapView.resources.displayMetrics.density
    mapView.overlays.clear()

    // White outlines first, so one piece's outline never paints over its neighbour's colour.
    segments.filter { it.level > 0 }.forEach { segment ->
        mapView.overlays.add(Polyline().apply {
            setPoints(segment.points)
            outlinePaint.color = android.graphics.Color.WHITE
            outlinePaint.strokeWidth = 7f * density
            if (segment.dashed) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f * density, 12f * density), 0f)
        })
    }
    segments.forEach { segment ->
        val color = difficultyColor(segment.level)
        mapView.overlays.add(Polyline().apply {
            setPoints(segment.points)
            outlinePaint.color = color?.toArgb() ?: if (dark) 0xFFE0E0E0.toInt() else android.graphics.Color.BLACK
            outlinePaint.strokeWidth = (if (color != null) 4f else 3.5f) * density
            if (segment.dashed) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f * density, 12f * density), 0f)
        })
    }

    if (waypoints.isNotEmpty()) {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
            setStroke((2 * density).toInt(), 0xFF333333.toInt())
            setSize((14 * density).toInt(), (14 * density).toInt())
        }
        val timeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        waypoints.forEachIndexed { index, waypoint ->
            mapView.overlays.add(Marker(mapView).apply {
                position = waypoint.point
                icon = dot
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "${index + 1}. ${waypoint.label}"
                snippet = waypoint.time?.let { timeFormat.format(Date(it)) }
            })
        }
    }

    allPoints.firstOrNull()?.let { start ->
        mapView.overlays.add(Marker(mapView).apply { position = start; title = "Start" })
    }
    allPoints.lastOrNull()?.takeIf { it != allPoints.firstOrNull() }?.let { end ->
        mapView.overlays.add(Marker(mapView).apply { position = end; title = "End" })
    }
    addPhotoPins(mapView, pins, onOpenPin)
    myLocation?.let { addMyLocationMarker(mapView, it) }
    mapView.invalidate()
}

private fun fitBounds(mapView: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        mapView.controller.setCenter(points.first())
        mapView.controller.setZoom(14.0)
    } else {
        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 50)
    }
    mapView.invalidate()
}

@Composable
fun RouteMap(
    tripId: Long,
    viewModel: TravelDiaryViewModel,
    legs: List<Leg>,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp),
    media: List<MediaItem> = emptyList(),
    onOpenMedia: ((Int) -> Unit)? = null,
    onMapView: ((MapView) -> Unit)? = null,
    onMapExport: ((MapExport) -> Unit)? = null
) {
    // Newly read locations flow back through the trip's media list, so nothing to do when it finishes.
    BackfillMediaLocations(viewModel) {}
    var routePoints by remember(tripId) { mutableStateOf<List<RoutePoint>>(emptyList()) }
    LaunchedEffect(tripId) { routePoints = viewModel.routePointsFor(tripId) }

    val segments = remember(routePoints, legs) {
        if (routePoints.isNotEmpty()) segmentsFromTrail(routePoints, legs) else segmentsFromLegs(legs)
    }
    val allPoints = remember(segments) { segments.flatMap { it.points } }
    if (allPoints.isEmpty()) return

    val waypoints = remember(legs) { buildWaypoints(legs) }
    var showWaypoints by rememberSaveable { mutableStateOf(false) }
    val shownWaypoints = if (showWaypoints) waypoints else emptyList()
    val allPins = remember(media) { photoPinsFor(tripId, media) }
    var showPhotos by rememberSaveable { mutableStateOf(true) }
    val shownPins = if (showPhotos) allPins else emptyList()
    val latestOpen by rememberUpdatedState(onOpenMedia)

    val state = remember { RouteMapState() }
    val dark = isDarkTheme()
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var myLocation by remember { mutableStateOf<GeoPoint?>(null) }
    val locate = rememberLocateAction { point ->
        myLocation = point
        mapRef?.let { centreOn(it, point) }
    }

    // clipToBounds: osmdroid scales its canvas during zoom, and Compose's AndroidView host
    // doesn't clip its child, so without this the tiles and route spill over neighbouring UI.
    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    mapRef = this
                    onMapView?.invoke(this)
                    applyMapTheme(this, dark)
                }
            },
            update = { mapView ->
                if (state.drawn != segments || state.drawnWaypoints != shownWaypoints || state.drawnLocation != myLocation || state.drawnPins != shownPins) {
                    drawOverlays(mapView, segments, allPoints, shownWaypoints, myLocation, shownPins, { pin -> latestOpen?.invoke(pin.index) }, dark)
                    state.drawnPins = shownPins
                    onMapExport?.invoke(
                        MapExport(
                            lines = segments.filter { it.points.size > 1 }.map { seg ->
                                ExportLine(seg.points, difficultyColor(seg.level)?.toArgb() ?: 0xFF111111.toInt(), seg.dashed)
                            },
                            waypoints = shownWaypoints.map { it.point },
                            pins = shownPins.map { ExportPin(it.point, it.item.type == net.jonesie.traveldiary.data.MediaType.VIDEO) },
                            start = allPoints.firstOrNull(),
                            end = allPoints.lastOrNull()?.takeIf { it != allPoints.firstOrNull() }
                        )
                    )
                    state.drawn = segments
                    state.drawnWaypoints = shownWaypoints
                    state.drawnLocation = myLocation
                    // Only re-fit when the route itself changed, not when just a rating did.
                    if (state.fittedTo != allPoints) {
                        state.fittedTo = allPoints
                        mapView.doOnLayout { fitBounds(mapView, allPoints) }
                    }
                }
            }
        )
        LocateButton(onClick = locate, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
        ) {
            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
            )
            if (allPins.isNotEmpty()) {
                FilterChip(
                    selected = showPhotos,
                    onClick = { showPhotos = !showPhotos },
                    label = { Text("Photos") },
                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                    colors = chipColors
                )
            }
            if (waypoints.isNotEmpty()) {
                FilterChip(
                    selected = showWaypoints,
                    onClick = { showWaypoints = !showWaypoints },
                    label = { Text("Waypoints") },
                    leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    colors = chipColors
                )
            }
        }
        if (segments.any { it.level > 0 }) {
            DifficultyLegend(
                showUnrated = segments.any { it.level == 0 },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            )
        }
    }
}

@Composable
private fun DifficultyLegend(showUnrated: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Difficulty", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
            for (level in 1..5) {
                val color = difficultyColor(level)!!
                Box(
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(color),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$level",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (color.luminance() > 0.4f) Color.Black else Color.White
                    )
                }
                if (level < 5) Box(modifier = Modifier.width(3.dp))
            }
            if (showUnrated) {
                Box(modifier = Modifier.padding(start = 8.dp).size(width = 16.dp, height = 3.dp).background(MaterialTheme.colorScheme.onSurface))
                Text("Not rated", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
