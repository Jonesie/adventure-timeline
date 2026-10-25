package net.jonesie.traveldiary.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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

/** Fallback when no imported breadcrumb trail exists for this trip: straight lines between leg endpoints. */
private fun legEndpointPoints(legs: List<Leg>): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    legs.sortedBy { it.startTime }.forEach { leg ->
        if (leg.originLat != null && leg.originLon != null) points.add(GeoPoint(leg.originLat, leg.originLon))
        if (leg.destinationLat != null && leg.destinationLon != null) points.add(GeoPoint(leg.destinationLat, leg.destinationLon))
    }
    return points
}

/** Tracks which point set the map was last fit to, so manual pinch/pan isn't undone on every recomposition. */
private class RouteMapState {
    var lastFittedPoints: List<GeoPoint>? = null
}

private fun drawOverlays(mapView: MapView, points: List<GeoPoint>) {
    mapView.overlays.clear()
    if (points.isEmpty()) return
    mapView.overlays.add(Polyline().apply { setPoints(points) })
    points.firstOrNull()?.let { start ->
        mapView.overlays.add(Marker(mapView).apply { position = start; title = "Start" })
    }
    points.lastOrNull()?.takeIf { it != points.firstOrNull() }?.let { end ->
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

    val points = if (routePoints.isNotEmpty()) {
        routePoints.map { GeoPoint(it.lat, it.lon) }
    } else {
        legEndpointPoints(legs)
    }
    if (points.isEmpty()) return

    val state = remember { RouteMapState() }

    AndroidView(
        // clipToBounds: osmdroid scales its canvas during zoom, and Compose's AndroidView host
        // doesn't clip its child, so without this the tiles and route spill over neighbouring UI.
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
            }
        },
        update = { mapView ->
            // Only (re)draw and re-fit when this trip's actual route data changes — not on
            // every recomposition — otherwise a pinch-zoom gesture gets immediately snapped
            // back to the fitted bounds on the next recompose, which looks like the map
            // fighting the user's zoom instead of responding to it.
            if (state.lastFittedPoints != points) {
                drawOverlays(mapView, points)
                mapView.doOnLayout { fitBounds(mapView, points) }
                state.lastFittedPoints = points
            }
        }
    )
}
