package net.jonesie.traveldiary.ui

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import net.jonesie.traveldiary.util.LocationHelper
import net.jonesie.traveldiary.util.RouteSnap
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * A full-screen map for choosing a place (where an incident happened, or what a note is about): tap to put the pin down, or press and hold the pin to
 * move it. The trip's route is drawn, and the pin can jump onto it ("Snap to route"). Nothing is saved here;
 * [onDone] hands back the chosen place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPlacePicker(
    tripId: Long?,
    viewModel: TravelDiaryViewModel,
    initial: Pair<Double, Double>?,
    what: String = "the incident",
    onDone: (lat: Double, lon: Double) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val dark = isDarkTheme()
    var route by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    LaunchedEffect(tripId) { route = tripId?.let { viewModel.routeForPicker(it) } ?: emptyList() }

    var pin by remember { mutableStateOf(initial?.let { GeoPoint(it.first, it.second) }) }
    // A tap or drag that lands on the same spot still needs the pin redrawn, so each placing bumps this.
    var version by remember { mutableIntStateOf(0) }
    var snapOn by rememberSaveable { mutableStateOf(true) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var framed by remember { mutableStateOf(initial != null) }

    val density = context.resources.displayMetrics.density
    fun place(point: GeoPoint, zoom: Double) {
        val target = if (snapOn && route.isNotEmpty()) {
            RouteSnap.snap(point.latitude, point.longitude, route, RouteSnap.snapRadiusMetres(point.latitude, zoom, pixels = 24.0 * density))
                .let { GeoPoint(it.first, it.second) }
        } else point
        pin = target
        version++
    }

    // Frame the trip's route once it has loaded, unless the pin already says where to look.
    LaunchedEffect(route, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        if (framed) return@LaunchedEffect
        if (route.isNotEmpty()) {
            framed = true
            map.doOnLayout { fitBounds(map, route.map { GeoPoint(it.first, it.second) }) }
        }
    }

    val locate = rememberLocateAction { point -> mapRef?.let { centreOn(it, point) } }
    val farMetres = pin?.let { p -> RouteSnap.nearest(p.latitude, p.longitude, route)?.distanceMetres }
    val farFromRoute = farMetres != null && farMetres > RouteSnap.FAR_FROM_ROUTE_METRES

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose location") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
                actions = {
                    TextButton(enabled = pin != null, onClick = { pin?.let { onDone(it.latitude, it.longitude) } }) { Text("Done") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).clipToBounds()) {
            val shownVersion = version
            val shownPin = pin
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        applyMapTheme(this, dark)
                        mapRef = this
                        doOnLayout {
                            val start = initial?.let { GeoPoint(it.first, it.second) }
                                ?: if (LocationHelper.hasPermission(ctx)) LocationHelper.lastKnownLocation(ctx)?.let { GeoPoint(it.latitude, it.longitude) } else null
                            if (start != null) { controller.setZoom(if (initial != null) 15.0 else 13.0); controller.setCenter(start) }
                            else if (route.isEmpty()) { controller.setZoom(3.0) }
                        }
                    }
                },
                update = { mapView ->
                    mapView.tag = shownVersion
                    mapView.overlays.clear()
                    // First, so the pin and the route get touches before a tap on empty map does.
                    mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { place(p, mapView.zoomLevelDouble); return true }
                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    }))
                    if (route.size > 1) {
                        mapView.overlays.add(Polyline(mapView).apply {
                            setPoints(route.map { GeoPoint(it.first, it.second) })
                            outlinePaint.color = 0xFF1565C0.toInt()
                            outlinePaint.strokeWidth = 6f * density
                        })
                    }
                    shownPin?.let { p ->
                        val disc = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0xFFC62828.toInt())
                            setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
                            setSize((30 * density).toInt(), (30 * density).toInt())
                        }
                        mapView.overlays.add(Marker(mapView).apply {
                            position = p
                            icon = disc
                            isDraggable = true
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setOnMarkerClickListener { _, _ -> true }
                            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                                override fun onMarkerDrag(marker: Marker) {}
                                override fun onMarkerDragStart(marker: Marker) {}
                                override fun onMarkerDragEnd(marker: Marker) { place(marker.position, mapView.zoomLevelDouble) }
                            })
                        })
                    }
                    addOsmCredit(mapView, dark)
                    mapView.invalidate()
                }
            )
            LocateButton(onClick = locate, modifier = Modifier.align(Alignment.CenterStart).padding(8.dp))
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (pin == null) "Tap the map to place $what. Press and hold the pin to move it."
                        else "Press and hold the pin to move it, or tap to place it again.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (route.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = snapOn, onClick = { snapOn = !snapOn }, label = { Text("Snap to route") })
                        }
                    }
                    if (farFromRoute) {
                        Text(
                            "That's a long way from this trip's route. Check it's the right place.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
