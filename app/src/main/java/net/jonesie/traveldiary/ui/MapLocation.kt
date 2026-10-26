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
