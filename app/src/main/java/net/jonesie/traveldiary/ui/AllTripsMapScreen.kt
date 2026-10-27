package net.jonesie.traveldiary.ui

import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import net.jonesie.traveldiary.data.TransportType
import org.osmdroid.views.overlay.Marker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import androidx.compose.runtime.SideEffect
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date

private val UNRATED_COLOR = Color(0xFF1E6FD9)

private fun routeColor(rating: Int): Color = ratingColor(rating) ?: UNRATED_COLOR

/** A waypoint tagged with the trip it belongs to. */
private class TripWaypoint(val tripTitle: String, val waypoint: Waypoint)

/** What the map currently shows, so a pinch-zoom isn't undone by recomposition. */
private class AllTripsMapState {
    var drawnRoutes: List<TripRoute>? = null
    var drawnSelection: Long? = null
    var drawnWaypoints: List<TripWaypoint>? = null
    var drawnLocation: GeoPoint? = null
    var drawnPins: List<PhotoPin>? = null
    var fitted = false
}

private fun draw(
    mapView: MapView,
    routes: List<TripRoute>,
    selectedId: Long?,
    waypoints: List<TripWaypoint>,
    myLocation: GeoPoint?,
    pins: List<PhotoPin>,
    onOpenPin: (PhotoPin) -> Unit,
    onTap: (GeoPoint) -> Unit
) {
    val density = mapView.resources.displayMetrics.density
    mapView.overlays.clear()
    mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onTap(p); return true }
        override fun longPressHelper(p: GeoPoint): Boolean = false
    }))
    fun dash(paint: android.graphics.Paint, dashed: Boolean) {
        if (dashed) paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f * density, 12f * density), 0f)
    }
    // The selected trip goes last so it sits on top of the others.
    val ordered = routes.filter { it.trip.id != selectedId } + routes.filter { it.trip.id == selectedId }
    ordered.forEach { route ->
        val selected = route.trip.id == selectedId
        route.lines.forEach { line ->
            mapView.overlays.add(Polyline().apply {
                setPoints(line.points)
                outlinePaint.color = android.graphics.Color.WHITE
                outlinePaint.strokeWidth = (if (selected) 10f else 6f) * density
                dash(outlinePaint, line.dashed)
            })
            mapView.overlays.add(Polyline().apply {
                setPoints(line.points)
                outlinePaint.color = routeColor(route.trip.rating).toArgb()
                outlinePaint.strokeWidth = (if (selected) 6f else 3.5f) * density
                dash(outlinePaint, line.dashed)
            })
        }
    }
    if (waypoints.isNotEmpty()) {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
            setStroke((2 * density).toInt(), 0xFF333333.toInt())
            setSize((12 * density).toInt(), (12 * density).toInt())
        }
        val timeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        waypoints.forEach { tw ->
            mapView.overlays.add(Marker(mapView).apply {
                position = tw.waypoint.point
                icon = dot
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = tw.waypoint.label
                snippet = tw.tripTitle + (tw.waypoint.time?.let { " · " + timeFormat.format(Date(it)) } ?: "")
            })
        }
    }
    addPhotoPins(mapView, pins, onOpenPin)
    myLocation?.let { addMyLocationMarker(mapView, it) }
    mapView.invalidate()
}

/** The trip whose route passes closest to [tap] on screen, if any is within [maxPx]. */
private fun nearestTrip(mapView: MapView, routes: List<TripRoute>, tap: GeoPoint, maxPx: Float): Long? {
    val projection = mapView.projection
    val t = projection.toPixels(tap, Point())
    var best: Long? = null
    var bestDistance = maxPx
    val a = Point()
    val b = Point()
    for (route in routes) {
        for (line in route.lines) {
            val pts = line.points
            if (pts.size < 2) continue
            projection.toPixels(pts[0], a)
            for (i in 1 until pts.size) {
                projection.toPixels(pts[i], b)
                val d = distanceToSegment(t.x.toFloat(), t.y.toFloat(), a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat())
                if (d < bestDistance) {
                    bestDistance = d
                    best = route.trip.id
                }
                a.set(b.x, b.y)
            }
        }
    }
    return best
}

private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    val cx = ax + t * dx
    val cy = ay + t * dy
    return Math.hypot((px - cx).toDouble(), (py - cy).toDouble()).toFloat()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTripsMapScreen(
    viewModel: TravelDiaryViewModel,
    onOpenTrip: (Long) -> Unit,
    onOpenMedia: (Long, Int) -> Unit,
    onShowList: () -> Unit,
    onNearby: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var routes by remember { mutableStateOf<List<TripRoute>?>(null) }
    val saved = viewModel.mapViewState
    var selectedId by remember { mutableStateOf(saved.selectedTripId) }
    var showWaypoints by remember { mutableStateOf(saved.showWaypoints) }
    var types by remember { mutableStateOf(saved.types) } // empty = every transport
    var myLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var showPhotos by remember { mutableStateOf(saved.showPhotos) }
    SideEffect {
        saved.selectedTripId = selectedId
        saved.showWaypoints = showWaypoints
        saved.types = types
        saved.showPhotos = showPhotos
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { routes = viewModel.allTripRoutes() }
    // Photo locations are read in the background; reload once they're in so pins appear.
    BackfillMediaLocations(viewModel) { scope.launch { routes = viewModel.allTripRoutes() } }
    val locate = rememberLocateAction { point ->
        myLocation = point
        mapRef?.let { centreOn(it, point) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Travel Diary") },
                actions = {
                    IconButton(onClick = onOpenStats) { Icon(Icons.Filled.BarChart, contentDescription = "Trip stats") }
                    IconButton(onClick = onNearby) { Icon(Icons.Filled.ExploreOff, contentDescription = "Have I been here before?") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            )
        },
        bottomBar = { MainTabBar(selected = MainTab.MAP, onMap = {}, onList = onShowList) }
    ) { padding ->
        val loaded = routes
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loaded == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                return@Box
            }
            val withRoutes = loaded.filter { it.lines.isNotEmpty() }
            if (withRoutes.isEmpty()) {
                Text("No trips with a recorded route yet.", modifier = Modifier.align(Alignment.Center).padding(16.dp))
                return@Box
            }
            val presentTypes = remember(withRoutes) {
                withRoutes.flatMap { r -> r.lines.mapNotNull { it.transport } }.distinct().sortedBy { it.ordinal }
            }
            val visible = remember(withRoutes, types) {
                if (types.isEmpty()) withRoutes
                else withRoutes.map { r -> r.copy(lines = r.lines.filter { it.transport in types }) }
                    .filter { it.lines.isNotEmpty() }
            }
            val waypoints = remember(visible, types, showWaypoints) {
                if (!showWaypoints) emptyList()
                else visible.flatMap { r ->
                    buildWaypoints(r.legs.filter { types.isEmpty() || it.transportType in types })
                        .map { TripWaypoint(r.trip.title, it) }
                }
            }
            val pins = remember(visible, showPhotos) {
                if (showPhotos) visible.flatMap { photoPinsFor(it.trip.id, it.media) } else emptyList()
            }
            val hasPins = remember(withRoutes) { withRoutes.any { r -> r.media.any { it.lat != null } } }
            TripsMap(
                saved = saved,
                routes = visible,
                fitTo = withRoutes,
                selectedId = selectedId,
                waypoints = waypoints,
                myLocation = myLocation,
                pins = pins,
                onOpenPin = { onOpenMedia(it.tripId, it.index) },
                onMapReady = { mapRef = it },
                onSelect = { selectedId = it }
            )

            Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Selected chips are solid and carry a tick, so it's obvious what is being shown.
                    val chipColors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    )
                    val tick: @Composable () -> Unit = { Icon(Icons.Filled.Check, contentDescription = null) }
                    FilterChip(
                        selected = showWaypoints,
                        onClick = { showWaypoints = !showWaypoints },
                        label = { Text("Waypoints") },
                        leadingIcon = if (showWaypoints) tick else { { Icon(Icons.Filled.Place, contentDescription = null) } },
                        colors = chipColors
                    )
                    if (hasPins) {
                        FilterChip(
                            selected = showPhotos,
                            onClick = { showPhotos = !showPhotos },
                            label = { Text("Photos") },
                            leadingIcon = if (showPhotos) tick else { { Icon(Icons.Filled.Image, contentDescription = null) } },
                            colors = chipColors
                        )
                    }
                    FilterChip(
                        selected = types.isEmpty(),
                        onClick = { types = emptySet() },
                        label = { Text("All transport") },
                        leadingIcon = if (types.isEmpty()) tick else null,
                        colors = chipColors
                    )
                    presentTypes.forEach { type ->
                        FilterChip(
                            selected = type in types,
                            onClick = { types = if (type in types) types - type else types + type },
                            label = { Text(type.displayName()) },
                            leadingIcon = if (type in types) tick else null,
                            colors = chipColors
                        )
                    }
                }
                RatingLegend(modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }

            if (visible.isEmpty()) {
                Text("No routes for the selected transport.", modifier = Modifier.align(Alignment.Center).padding(16.dp))
            }

            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    LocateButton(onClick = locate, modifier = Modifier.align(Alignment.CenterEnd))
                }
                val selected = visible.firstOrNull { it.trip.id == selectedId }
                if (selected != null) {
                    SelectedTripCard(selected, onOpen = { onOpenTrip(selected.trip.id) })
                } else {
                    val missing = loaded.size - withRoutes.size
                    if (missing > 0) {
                        Text(
                            "$missing trip(s) have no recorded route and aren't shown.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(8.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TripsMap(
    saved: MapViewState,
    routes: List<TripRoute>,
    fitTo: List<TripRoute>,
    selectedId: Long?,
    waypoints: List<TripWaypoint>,
    myLocation: GeoPoint?,
    pins: List<PhotoPin>,
    onOpenPin: (PhotoPin) -> Unit,
    onMapReady: (MapView) -> Unit,
    onSelect: (Long?) -> Unit
) {
    val state = remember { AllTripsMapState().also { it.fitted = saved.hasCamera } }
    val latestRoutes by rememberUpdatedState(routes)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestOpenPin by rememberUpdatedState(onOpenPin)
    val dark = isDarkTheme()
    val allPoints = remember(fitTo) { fitTo.flatMap { r -> r.lines.flatMap { it.points } } }

    // clipToBounds: see RouteMap — osmdroid's canvas scaling otherwise spills over neighbouring UI.
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    applyMapTheme(this, dark)
                    // Come back to where the map was left, and keep noting where it is.
                    if (saved.hasCamera) {
                        controller.setZoom(saved.zoom!!)
                        controller.setCenter(GeoPoint(saved.latitude!!, saved.longitude!!))
                    }
                    addMapListener(object : MapListener {
                        private fun remember(): Boolean {
                            val centre = mapCenter
                            saved.updateCamera(centre.latitude, centre.longitude, zoomLevelDouble)
                            return false
                        }
                        override fun onScroll(event: ScrollEvent?) = remember()
                        override fun onZoom(event: ZoomEvent?) = remember()
                    })
                    onMapReady(this)
                }
            },
            update = { mapView ->
                if (state.drawnRoutes != routes || state.drawnSelection != selectedId ||
                    state.drawnWaypoints != waypoints || state.drawnLocation != myLocation || state.drawnPins != pins
                ) {
                    draw(mapView, routes, selectedId, waypoints, myLocation, pins, latestOpenPin) { tap ->
                        latestSelect(nearestTrip(mapView, latestRoutes, tap, 28f * mapView.resources.displayMetrics.density))
                    }
                    state.drawnRoutes = routes
                    state.drawnSelection = selectedId
                    state.drawnWaypoints = waypoints
                    state.drawnLocation = myLocation
                    state.drawnPins = pins
                    // Fit once, to every trip, so toggling a filter doesn't move the map.
                    if (!state.fitted) {
                        state.fitted = true
                        mapView.doOnLayout {
                            if (allPoints.size > 1) mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(allPoints), false, 80)
                            mapView.invalidate()
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun SelectedTripCard(route: TripRoute, onOpen: () -> Unit) {
    val trip = route.trip
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(trip.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    dates.format(Date(trip.startDate)) + (trip.endDate?.takeIf { it != trip.startDate }?.let { " – " + dates.format(Date(it)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
                if (trip.rating > 0) Text(starString(trip.rating), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onOpen, modifier = Modifier.padding(start = 8.dp)) { Text("Open") }
        }
    }
}

@Composable
private fun RatingLegend(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = modifier
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rating", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
            for (rating in 1..5) {
                val color = routeColor(rating)
                Box(
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(color),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$rating",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (color.luminance() > 0.4f) Color.Black else Color.White
                    )
                }
                if (rating < 5) Box(modifier = Modifier.width(3.dp))
            }
            Box(modifier = Modifier.padding(start = 8.dp).size(width = 16.dp, height = 3.dp).background(UNRATED_COLOR))
            Text("Not rated", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}
