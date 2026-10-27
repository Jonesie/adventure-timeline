package net.jonesie.traveldiary.ui

import android.Manifest
import android.graphics.drawable.GradientDrawable
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.util.LocationHelper
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Returns a function that finds the phone's location (asking for permission first) and reports it. */
@Composable
fun rememberLocateAction(onLocated: (GeoPoint) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onLocated)

    fun locate() {
        Toast.makeText(context, "Finding your location…", Toast.LENGTH_SHORT).show()
        LocationHelper.locate(context) { location ->
            if (location == null) {
                Toast.makeText(context, "Couldn't get your location. Check that location is turned on.", Toast.LENGTH_LONG).show()
            } else {
                latest(GeoPoint(location.latitude, location.longitude))
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else Toast.makeText(context, "Location permission is needed to centre on you", Toast.LENGTH_LONG).show()
    }
    return {
        if (LocationHelper.hasPermission(context)) locate() else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
fun LocateButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.MyLocation, contentDescription = "Centre on my location")
    }
}

/** Centres the map on [point], zooming in if the map is currently zoomed far out. */
fun centreOn(mapView: MapView, point: GeoPoint) {
    if (mapView.zoomLevelDouble < 13.0) mapView.controller.setZoom(13.0)
    mapView.controller.animateTo(point)
}

/** A blue dot for "you are here". */
fun addMyLocationMarker(mapView: MapView, point: GeoPoint) {
    val density = mapView.resources.displayMetrics.density
    val dot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFF1A73E8.toInt())
        setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
        setSize((18 * density).toInt(), (18 * density).toInt())
    }
    mapView.overlays.add(Marker(mapView).apply {
        position = point
        icon = dot
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        title = "You are here"
    })
}

/** A photo or video that has a known location, for pinning on a map. [index] is its place in the trip's viewer order. */
class PhotoPin(val tripId: Long, val index: Int, val item: MediaItem, val point: GeoPoint)

fun photoPinsFor(tripId: Long, media: List<MediaItem>): List<PhotoPin> =
    media.mapIndexedNotNull { index, item ->
        if (item.lat != null && item.lon != null) PhotoPin(tripId, index, item, GeoPoint(item.lat, item.lon)) else null
    }

/** Small coloured badges: teal for photos, purple for videos. Tapping one calls [onOpen]. */
fun addPhotoPins(mapView: MapView, pins: List<PhotoPin>, onOpen: (PhotoPin) -> Unit) {
    val density = mapView.resources.displayMetrics.density
    fun badge(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 5 * density
        setColor(color)
        setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
        setSize((16 * density).toInt(), (16 * density).toInt())
    }
    val photo = badge(0xFF00897B.toInt())
    val video = badge(0xFF7B1FA2.toInt())
    pins.forEach { pin ->
        mapView.overlays.add(Marker(mapView).apply {
            position = pin.point
            icon = if (pin.item.type == MediaType.VIDEO) video else photo
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> onOpen(pin); true }
        })
    }
}

/**
 * Reads photo/video locations into the diary once per visit so they can be pinned. Gallery photos
 * need the "location info in photos" permission, which is asked for a single time.
 */
@Composable
fun BackfillMediaLocations(viewModel: TravelDiaryViewModel, onFound: () -> Unit) {
    val scope = rememberCoroutineScope()
    val run: () -> Unit = {
        scope.launch { if (viewModel.backfillMediaLocations() > 0) onFound() }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { run() }
    LaunchedEffect(Unit) {
        if (!viewModel.askedForMediaLocation && viewModel.galleryLocationPermissionNeeded()) {
            viewModel.askedForMediaLocation = true
            ask.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            run()
        }
    }
}
