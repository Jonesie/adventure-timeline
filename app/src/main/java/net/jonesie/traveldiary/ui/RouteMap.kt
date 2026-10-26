package net.jonesie.traveldiary.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** A stretch of the route drawn in one colour. [level] is the leg's difficulty, 0 when not rated. */
private data class RouteSegment(val points: List<GeoPoint>, val level: Int)

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

    for (point in trail.sortedBy { it.timestamp }) {
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
    return segments
}

/** Fallback when no GPS trail was imported: a straight line per leg between its endpoints. */
private fun segmentsFromLegs(legs: List<Leg>): List<RouteSegment> =
    legs.sortedBy { it.startTime }.mapNotNull { leg ->
        val points = listOfNotNull(
            if (leg.originLat != null && leg.originLon != null) GeoPoint(leg.originLat, leg.originLon) else null,
            if (leg.destinationLat != null && leg.destinationLon != null) GeoPoint(leg.destinationLat, leg.destinationLon) else null
        )
        if (points.isEmpty()) null else RouteSegment(points, leg.difficulty)
    }

/** Remembers what the map currently shows, so a pinch-zoom isn't undone on recomposition. */
private class RouteMapState {
    var drawn: List<RouteSegment>? = null
    var fittedTo: List<GeoPoint>? = null
}

private fun drawOverlays(mapView: MapView, segments: List<RouteSegment>, allPoints: List<GeoPoint>) {
    val density = mapView.resources.displayMetrics.density
    mapView.overlays.clear()

    // White outlines first, so one piece's outline never paints over its neighbour's colour.
    segments.filter { it.level > 0 }.forEach { segment ->
        mapView.overlays.add(Polyline().apply {
            setPoints(segment.points)
            outlinePaint.color = android.graphics.Color.WHITE
            outlinePaint.strokeWidth = 7f * density
        })
    }
    segments.forEach { segment ->
        val color = difficultyColor(segment.level)
        mapView.overlays.add(Polyline().apply {
            setPoints(segment.points)
            outlinePaint.color = color?.toArgb() ?: android.graphics.Color.BLACK
            outlinePaint.strokeWidth = (if (color != null) 4f else 3.5f) * density
        })
    }

    allPoints.firstOrNull()?.let { start ->
        mapView.overlays.add(Marker(mapView).apply { position = start; title = "Start" })
    }
    allPoints.lastOrNull()?.takeIf { it != allPoints.firstOrNull() }?.let { end ->
        mapView.overlays.add(Marker(mapView).apply { position = end; title = "End" })
    }
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
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp)
) {
    var routePoints by remember(tripId) { mutableStateOf<List<RoutePoint>>(emptyList()) }
    LaunchedEffect(tripId) { routePoints = viewModel.routePointsFor(tripId) }

    val segments = remember(routePoints, legs) {
        if (routePoints.isNotEmpty()) segmentsFromTrail(routePoints, legs) else segmentsFromLegs(legs)
    }
    val allPoints = remember(segments) { segments.flatMap { it.points } }
    if (allPoints.isEmpty()) return

    val state = remember { RouteMapState() }

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
                }
            },
            update = { mapView ->
                if (state.drawn != segments) {
                    drawOverlays(mapView, segments, allPoints)
                    state.drawn = segments
                    // Only re-fit when the route itself changed, not when just a rating did.
                    if (state.fittedTo != allPoints) {
                        state.fittedTo = allPoints
                        mapView.doOnLayout { fitBounds(mapView, allPoints) }
                    }
                }
            }
        )
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
                Box(modifier = Modifier.padding(start = 8.dp).size(width = 16.dp, height = 3.dp).background(Color.Black))
                Text("Not rated", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
