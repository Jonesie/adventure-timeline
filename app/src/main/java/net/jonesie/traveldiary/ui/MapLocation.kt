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

/** OpenStreetMap's licence and tile policy require this credit to be visible on every map using its tiles. */
const val OSM_CREDIT = "© OpenStreetMap contributors"

fun addOsmCredit(mapView: MapView, dark: Boolean) {
    mapView.overlays.add(
        org.osmdroid.views.overlay.CopyrightOverlay(mapView.context).apply {
            setCopyrightNotice(OSM_CREDIT)
            setAlignBottom(true)
            setAlignRight(false)
            setTextColor(if (dark) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY)
        }
    )
}

/** Dark theme: invert the (light) map tiles so the map isn't a glaring white rectangle. */
fun applyMapTheme(mapView: MapView, dark: Boolean) {
    mapView.overlayManager.tilesOverlay.setColorFilter(if (dark) org.osmdroid.views.overlay.TilesOverlay.INVERT_COLORS else null)
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

/** The map as drawn, in plain data, so the share picture can redraw it larger and crisper than the screen. */
class ExportLine(val points: List<GeoPoint>, val color: Int, val dashed: Boolean)
class ExportPin(val point: GeoPoint, val video: Boolean)
class MapExport(
    val lines: List<ExportLine>,
    val waypoints: List<GeoPoint>,
    val pins: List<ExportPin>,
    val start: GeoPoint?,
    val end: GeoPoint?
)

/**
 * The same map with everything inside a circle left out: route pieces, stops and photo pins, and the
 * start/end dots. Used to keep where you live out of a shared picture.
 */
fun MapExport.withoutArea(centreLat: Double, centreLon: Double, radiusKm: Double): MapExport {
    fun inside(p: GeoPoint) =
        net.jonesie.traveldiary.util.RouteShape.distanceKm(centreLat, centreLon, p.latitude, p.longitude) <= radiusKm
    val keptLines = lines.flatMap { line ->
        val parts = mutableListOf<MutableList<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        line.points.forEach { p ->
            if (inside(p)) {
                if (current.isNotEmpty()) { parts += current; current = mutableListOf() }
            } else current.add(p)
        }
        if (current.isNotEmpty()) parts += current
        parts.filter { it.size > 1 }.map { ExportLine(it, line.color, line.dashed) }
    }
    return MapExport(
        lines = keptLines,
        waypoints = waypoints.filterNot(::inside),
        pins = pins.filterNot { inside(it.point) },
        start = start?.takeUnless(::inside),
        end = end?.takeUnless(::inside)
    )
}

/**
 * A picture of the map for sharing: the tiles as they're framed on screen, scaled up by [scale], with
 * the route and markers redrawn on top at full sharpness (so they stay readable when zoomed).
 */
fun captureMapImage(mapView: MapView, export: MapExport?, dark: Boolean, scale: Float = 2f): android.graphics.Bitmap? {
    if (mapView.width == 0 || mapView.height == 0) return null
    val bitmap = android.graphics.Bitmap.createBitmap(
        (mapView.width * scale).toInt(), (mapView.height * scale).toInt(), android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bitmap)

    // Tiles only: set the on-screen overlays aside while drawing, always with the normal light tiles.
    val savedOverlays = ArrayList(mapView.overlays)
    mapView.overlays.clear()
    if (dark) applyMapTheme(mapView, false)
    try {
        canvas.save()
        canvas.scale(scale, scale)
        mapView.draw(canvas)
        canvas.restore()
    } finally {
        mapView.overlays.addAll(savedOverlays)
        if (dark) applyMapTheme(mapView, true)
    }
    // The credit belongs on the shared picture as well.
    val creditPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.DKGRAY
        textSize = 11f * mapView.resources.displayMetrics.density * scale
    }
    val pad = 4f * mapView.resources.displayMetrics.density * scale
    val textWidth = creditPaint.measureText(OSM_CREDIT)
    val backdrop = android.graphics.Paint().apply { color = 0xB3FFFFFF.toInt() }
    canvas.drawRect(
        bitmap.width - textWidth - 2 * pad, bitmap.height - creditPaint.textSize - 2 * pad,
        bitmap.width.toFloat(), bitmap.height.toFloat(), backdrop
    )
    canvas.drawText(OSM_CREDIT, bitmap.width - textWidth - pad, bitmap.height - pad - creditPaint.descent(), creditPaint)

    if (export == null) return bitmap

    val density = mapView.resources.displayMetrics.density * scale
    val projection = mapView.projection
    val scratch = android.graphics.Point()
    fun px(point: GeoPoint): android.graphics.PointF {
        projection.toPixels(point, scratch)
        return android.graphics.PointF(scratch.x * scale, scratch.y * scale)
    }
    fun stroke(color: Int, widthDp: Float, dashed: Boolean) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        this.color = color
        strokeWidth = widthDp * density
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        if (dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(16f * density, 10f * density), 0f)
    }
    fun fill(color: Int) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    val white = android.graphics.Color.WHITE
    export.lines.forEach { line ->
        if (line.points.size < 2) return@forEach
        val path = android.graphics.Path()
        line.points.forEachIndexed { i, g -> val p = px(g); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        canvas.drawPath(path, stroke(white, 9f, line.dashed))
        canvas.drawPath(path, stroke(line.color, 5.5f, line.dashed))
    }
    export.waypoints.forEach { g ->
        val p = px(g)
        canvas.drawCircle(p.x, p.y, 5f * density, fill(white))
        canvas.drawCircle(p.x, p.y, 5f * density, stroke(0xFF333333.toInt(), 1.5f, false))
    }
    export.pins.forEach { pin ->
        val p = px(pin.point)
        val half = 6.5f * density
        val rect = android.graphics.RectF(p.x - half, p.y - half, p.x + half, p.y + half)
        canvas.drawRoundRect(rect, 3f * density, 3f * density, fill(if (pin.video) 0xFF7B1FA2.toInt() else 0xFF00897B.toInt()))
        canvas.drawRoundRect(rect, 3f * density, 3f * density, stroke(white, 1.5f, false))
    }
    export.start?.let { g ->
        val p = px(g)
        canvas.drawCircle(p.x, p.y, 8f * density, fill(white))
        canvas.drawCircle(p.x, p.y, 6f * density, fill(0xFF2E7D32.toInt()))
    }
    export.end?.let { g ->
        val p = px(g)
        canvas.drawCircle(p.x, p.y, 8f * density, fill(white))
        canvas.drawCircle(p.x, p.y, 6f * density, fill(0xFFC62828.toInt()))
    }
    return bitmap
}
