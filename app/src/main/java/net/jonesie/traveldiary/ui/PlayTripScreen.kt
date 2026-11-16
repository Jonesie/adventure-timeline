package net.jonesie.traveldiary.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.util.AccentClock
import net.jonesie.traveldiary.util.FinishSound
import net.jonesie.traveldiary.util.TripPlayback
import net.jonesie.traveldiary.util.ambient
import net.jonesie.traveldiary.util.cues
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

private val SPEEDS = listOf(1f, 2f, 4f)

/**
 * Plays a trip back on the map: the route draws itself with the vehicle travelling along it, photos pop up with
 * their captions where they were taken, and incidents stop the show with a sound effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayTripScreen(tripId: Long, viewModel: TravelDiaryViewModel, onBack: () -> Unit) {
    val detail by remember(tripId) { viewModel.tripDetail(tripId) }.collectAsState(initial = null)
    val trip = detail
    val timeline by produceState<TripPlayback.Timeline?>(null, tripId, trip) {
        if (trip == null) return@produceState
        val trail = viewModel.routePointsFor(tripId)
        value = withContext(Dispatchers.Default) { TripPlayback.build(trail, trip.legs, trip.media, trip.incidents) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.trip?.title ?: "Play trip", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        val ready = timeline
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                ready == null -> if (trip != null) Text("Getting the route ready…", modifier = Modifier.align(Alignment.Center))
                ready.isEmpty -> Text(
                    "There's no route to play yet. Import this trip from Google Timeline, or add a leg with places.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> PlayerContent(ready)
            }
        }
    }
}

@Composable
private fun PlayerContent(timeline: TripPlayback.Timeline) {
    val context = LocalContext.current
    val view = LocalView.current
    val dark = isDarkTheme()
    val imageLoader = rememberMediaImageLoader()

    val session = remember(timeline) { TripPlayback.Session(timeline) }
    var playing by remember(timeline) { mutableStateOf(true) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var soundOn by rememberSaveable { mutableStateOf(true) }
    var unit by remember(timeline) { mutableFloatStateOf(0f) }
    var active by remember(timeline) { mutableStateOf<TripPlayback.Event?>(null) }
    var finished by remember(timeline) { mutableStateOf(false) }
    var transport by remember(timeline) { mutableStateOf<TransportType?>(null) }
    val accents = remember(timeline) { AccentClock() }
    var lastFinish by remember { mutableStateOf<FinishSound?>(null) }
    val latestSpeed by rememberUpdatedState(speed)
    val latestSound by rememberUpdatedState(soundOn)

    val effects = remember { SoundEffects(context) }
    DisposableEffect(effects) { onDispose { effects.release() } }

    var overlays by remember(timeline) { mutableStateOf<PlaybackOverlays?>(null) }

    // The screen stays on while playing, and the animation stops if the app goes to the background.
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) playing = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The transport's sound loops while travelling and goes quiet at a stop, a pause, or when muted.
    LaunchedEffect(transport, playing, soundOn, active) {
        effects.setLoop(if (playing && soundOn && active == null) transport?.ambient() else null)
    }

    LaunchedEffect(session, overlays, playing) {
        val shown = overlays ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        if (session.finished) { session.restart(); finished = false }
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            // A long pause (the app stalled) shouldn't make the vehicle leap.
            val dt = (now - last).coerceAtMost(100L)
            val reachedNow = session.advance(dt, latestSpeed)
            last = now
            shown.render(session.unit, session.reached)
            unit = session.unit.toFloat()
            active = session.active
            transport = shown.transport
            if (latestSound && session.active == null) accents.tick(dt, shown.transport)?.let { effects.play(it) }
            reachedNow?.incident?.cues()?.forEach { cue ->
                // The siren for a serious incident follows the first sound; pausing cancels it with the rest.
                launch {
                    if (cue.delayMs > 0) delay(cue.delayMs)
                    if (latestSound) effects.play(cue.sound)
                }
            }
            if (session.finished) {
                playing = false
                finished = true
                if (latestSound) {
                    val celebration = FinishSound.pick(Random.Default, lastFinish)
                    lastFinish = celebration
                    effects.play(celebration)
                }
                break
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        applyMapTheme(this, dark)
                        val shown = PlaybackOverlays(this, timeline, dark)
                        shown.render(0.0, 0)
                        doOnLayout { fitBounds(this, shown.allPoints) }
                        overlays = shown
                    }
                }
            )
            AnimatedContent(
                targetState = active,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                label = "event"
            ) { event ->
                when {
                    event?.photo != null -> PhotoCard(event.photo, imageLoader)
                    event?.incident != null -> IncidentCard(event.incident)
                }
            }
        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Slider(
                    value = unit,
                    onValueChange = {
                        session.seek(it.toDouble())
                        unit = session.unit.toFloat()
                        active = null
                        finished = false
                        overlays?.render(session.unit, session.reached)
                        transport = overlays?.transport
                    },
                    valueRange = 0f..timeline.totalUnits.toFloat()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = {
                        if (finished) { session.restart(); finished = false; active = null }
                        playing = !playing
                    }) {
                        Icon(
                            when {
                                finished -> Icons.Filled.Replay
                                playing -> Icons.Filled.Pause
                                else -> Icons.Filled.PlayArrow
                            },
                            contentDescription = when {
                                finished -> "Play again"
                                playing -> "Pause"
                                else -> "Play"
                            }
                        )
                    }
                    SPEEDS.forEach { s ->
                        FilterChip(selected = speed == s, onClick = { speed = s }, label = { Text("${s.toInt()}x") })
                    }
                    Box(modifier = Modifier.weight(1f))
                    IconButton(onClick = { soundOn = !soundOn }) {
                        Icon(
                            if (soundOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = if (soundOn) "Mute sound effects" else "Turn on sound effects"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoCard(photo: net.jonesie.traveldiary.data.MediaItem, imageLoader: coil.ImageLoader) {
    Card(modifier = Modifier.fillMaxWidth(0.8f)) {
        AsyncImage(
            model = android.net.Uri.parse(photo.uri),
            imageLoader = imageLoader,
            contentDescription = photo.caption.ifBlank { "Photo from the trip" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(200.dp)
        )
        Text(
            photo.caption.ifBlank { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(photo.timestamp)) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun IncidentCard(incident: Incident) {
    val colour = severityColor(incident.severity) ?: MaterialTheme.colorScheme.error
    Card(modifier = Modifier.fillMaxWidth(0.8f)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(incident.category.icon(), contentDescription = null, tint = colour, modifier = Modifier.size(36.dp))
            Column {
                Text(incident.category.label(), style = MaterialTheme.typography.titleMedium)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(incident.timestamp)) +
                        (severityLabel(incident.severity)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium
                )
                if (incident.notes.isNotBlank()) {
                    Text(incident.notes, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The map's drawing for the animation: the route ahead, the route travelled, the vehicle and the pins reached so far. */
private class PlaybackOverlays(private val mapView: MapView, private val timeline: TripPlayback.Timeline, dark: Boolean) {
    private val density = mapView.resources.displayMetrics.density
    private val geo: List<List<GeoPoint>> = timeline.pieces.map { p -> p.vertices.map { GeoPoint(it.lat, it.lon) } }
    val allPoints: List<GeoPoint> = geo.flatten()

    private val travelled: List<Polyline>
    private val vehicle: Marker
    private var vehicleTransport: TransportType? = null

    /** What the vehicle is travelling by right now. */
    var transport: TransportType? = null
        private set
    private val eventMarkers = mutableListOf<List<org.osmdroid.views.overlay.Overlay>>()

    /** What each travelled piece currently shows: nothing, all of it, or part of it. */
    private val shown = IntArray(timeline.pieces.size) { EMPTY }

    init {
        val ahead = if (dark) 0xFF8A8A8A.toInt() else 0xFF9E9E9E.toInt()
        val colour = if (dark) 0xFF64B5F6.toInt() else 0xFF1565C0.toInt()
        fun line(piece: TripPlayback.Piece, points: List<GeoPoint>, paintColour: Int, width: Float) = Polyline().apply {
            setPoints(points)
            outlinePaint.color = paintColour
            outlinePaint.strokeWidth = width * density
            if (piece.dashed) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f * density, 12f * density), 0f)
        }
        timeline.pieces.forEachIndexed { i, p -> mapView.overlays.add(line(p, geo[i], ahead, 2.5f)) }
        travelled = timeline.pieces.mapIndexed { i, p ->
            line(p, geo[i], colour, 5f).also { it.isEnabled = false; mapView.overlays.add(it) }
        }
        addOsmCredit(mapView, dark)
        vehicle = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
        mapView.overlays.add(vehicle)
    }

    fun render(unit: Double, reached: Int) {
        val position = timeline.positionAt(unit) ?: return
        transport = position.transport
        for (i in travelled.indices) {
            when {
                i < position.pieceIndex -> if (shown[i] != FULL) { travelled[i].setPoints(geo[i]); travelled[i].isEnabled = true; shown[i] = FULL }
                i > position.pieceIndex -> if (shown[i] != EMPTY) { travelled[i].isEnabled = false; shown[i] = EMPTY }
            }
        }
        val here = GeoPoint(position.lat, position.lon)
        travelled[position.pieceIndex].apply {
            setPoints(geo[position.pieceIndex].subList(0, position.passed) + here)
            isEnabled = true
        }
        shown[position.pieceIndex] = PARTIAL

        syncEventMarkers(reached)
        if (vehicleTransport != position.transport || vehicle.icon == null) {
            vehicleTransport = position.transport
            vehicle.icon = VehicleBadge(density, position.transport)
        }
        vehicle.position = here
        mapView.invalidate()
    }

    /** Pins for the photos and incidents already passed; scrubbing back takes the later ones away again. */
    private fun syncEventMarkers(reached: Int) {
        while (eventMarkers.size > reached) mapView.overlays.removeAll(eventMarkers.removeAt(eventMarkers.lastIndex).toSet())
        while (eventMarkers.size < reached) {
            val event = timeline.events[eventMarkers.size]
            val before = mapView.overlays.size
            val position = timeline.positionAt(event.unit)
            when {
                event.incident != null && position != null ->
                    addIncidentMarkers(mapView, listOf(event.incident.copy(lat = position.lat, lon = position.lon)))
                event.photo != null && position != null -> mapView.overlays.add(photoDot(position))
            }
            eventMarkers += mapView.overlays.drop(before)
        }
        // Keep the vehicle on top of the pins.
        if (mapView.overlays.last() !== vehicle) {
            mapView.overlays.remove(vehicle)
            mapView.overlays.add(vehicle)
        }
    }

    private fun photoDot(position: TripPlayback.Position) = Marker(mapView).apply {
        this.position = GeoPoint(position.lat, position.lon)
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF00897B.toInt())
            setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
            setSize((14 * density).toInt(), (14 * density).toInt())
        }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
    }

    private companion object {
        const val EMPTY = 0
        const val FULL = 1
        const val PARTIAL = 2
    }
}

private fun TransportType?.emoji(): String = when (this) {
    TransportType.WALK -> "🚶"
    TransportType.BIKE -> "🚲"
    TransportType.MOTORBIKE -> "🏍️"
    TransportType.CAR -> "🚗"
    TransportType.FOUR_WD -> "🚙"
    TransportType.BUS -> "🚌"
    TransportType.TRAIN -> "🚆"
    TransportType.PLANE -> "✈️"
    TransportType.BOAT -> "⛴️"
    TransportType.OTHER, null -> "📍"
}

/** The travelling vehicle: a white disc with the transport's emoji, drawn in code so it needs no images. */
private class VehicleBadge(density: Float, transport: TransportType?) : Drawable() {
    private val size = (36 * density).toInt()
    private val emoji = transport.emoji()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 20f * density }

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r = size / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawText(emoji, cx, cy + glyph.textSize * 0.35f, glyph)
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
