package net.jonesie.traveldiary.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.TripDetail
import net.jonesie.traveldiary.util.PostComposer
import net.jonesie.traveldiary.util.PostOptions
import net.jonesie.traveldiary.util.RouteShape
import net.jonesie.traveldiary.util.HiddenArea
import net.jonesie.traveldiary.util.TripTrack
import net.jonesie.traveldiary.util.ShareComposer
import net.jonesie.traveldiary.util.ShareFormat
import net.jonesie.traveldiary.util.ShareOptions
import org.osmdroid.views.MapView
import java.io.File

/** Keeps a shared picture or PDF a sensible length. */
private const val MAX_SHARE_PHOTOS = 7

/**
 * Builds a shareable summary of the trip (map, notes, chosen photos, optionally companions) and
 * hands it to the phone's share sheet. The map is whatever the preview shows, so pan and zoom it
 * to frame what you want to send.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.ShareTab(tripId: Long, viewModel: TravelDiaryViewModel, trip: TripDetail) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageLoader = rememberMediaImageLoader()
    val dark = isDarkTheme()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var title by remember(trip.trip.title) { mutableStateOf(trip.trip.title) }
    var message by remember { mutableStateOf("") }
    var mapExport by remember { mutableStateOf<MapExport?>(null) }
    var includeMap by remember { mutableStateOf(true) }
    var mapShown by rememberSaveable { mutableStateOf(true) }
    var includeDates by remember { mutableStateOf(true) }
    var includeRating by remember { mutableStateOf(true) }
    var includeCompanions by remember { mutableStateOf(false) }
    var includeNotes by remember { mutableStateOf(PostComposer.cleanNotes(trip.trip.notes).isNotEmpty()) }
    var selectedPhotos by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var format by remember { mutableStateOf(ShareFormat.POST) }
    var includeRoute by remember { mutableStateOf(true) }
    var includeLegs by remember { mutableStateOf(false) }
    var includeIncidents by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var routeKm by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(tripId, trip.legs) {
        routeKm = RouteShape.trailKm(viewModel.routePointsFor(tripId)).takeIf { it > 0 }
    }
    var busy by remember { mutableStateOf(false) }
    val homeSettings by viewModel.homeSettings.collectAsState()
    var homeCentre by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(homeSettings) { homeCentre = viewModel.homeCentre() }
    var hideHome by remember { mutableStateOf(true) }
    val hiddenArea = remember(homeCentre, hideHome, homeSettings.radiusKm) {
        homeCentre?.takeIf { hideHome }?.let { (lat, lon) -> HiddenArea(lat, lon, homeSettings.radiusKm.toDouble()) }
    }

    val photos = remember(trip.media) { trip.sortedMedia().filter { it.type == MediaType.PHOTO } }
    val postContent = remember(trip, title, message, includeDates, includeRating, includeRoute, includeLegs, includeIncidents, includeCompanions, includeNotes, hiddenArea, routeKm) {
        PostComposer.build(
            trip,
            PostOptions(title, message, includeDates, includeRating, includeRoute, includeLegs, includeIncidents, includeCompanions, includeNotes, hiddenArea),
            routeKm
        )
    }
    val postText = postContent.toText()


    if (includeMap) {
        val mapHeight by animateDpAsState(if (mapShown) 280.dp else 0.dp, label = "mapHeight")
        Row(
            modifier = Modifier.fillMaxWidth().clickable { mapShown = !mapShown }.padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (mapShown) "Map" else "Map (hidden, still shared as you framed it)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (mapShown) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (mapShown) "Hide map" else "Show map"
            )
        }
        // Not inside the scrolling list below, so the map's own pan/zoom gestures work. When hidden it stays laid
        // out at full size and is only clipped away, so the picture that gets shared keeps the framing you chose.
        Box(modifier = Modifier.fillMaxWidth().height(mapHeight).clipToBounds()) {
            RouteMap(
                tripId, viewModel, trip.legs,
                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true).fillMaxWidth().height(280.dp).padding(top = 8.dp),
                media = trip.sortedMedia(),
                onMapView = { mapView = it },
                onMapExport = { mapExport = it },
                hidden = hiddenArea
            )
        }
    }

    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text("Share as", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareFormat.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label, maxLines = 1) })
                }
            }
        }
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        if (format.isTrack) {
            item { SectionHeader("Track") }
            if (homeCentre != null) {
                item { SwitchRow("Hide my home area (${homeSettings.radiusKm} km) from the track", hideHome) { hideHome = it } }
            }
            item { SwitchRow("Incidents (${trip.incidents.size} logged, as waypoints)", includeIncidents) { includeIncidents = it } }
            item {
                Text(
                    "The route you recorded, with the places you went and any incidents as waypoints, for mapping apps, GPS units and Google Earth.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            item { SectionHeader("Map") }
            item { SwitchRow("Map (as you framed it)", includeMap) { includeMap = it } }
            if (homeCentre != null) {
                item { SwitchRow("Hide my home area (${homeSettings.radiusKm} km) from the map and leg list", hideHome) { hideHome = it } }
            }
            item { SectionHeader("Trip facts") }
            item { SwitchRow("Dates", includeDates) { includeDates = it } }
            item { SwitchRow("Star rating", includeRating) { includeRating = it } }
            if (format == ShareFormat.POST) {
                item { SwitchRow("Route summary (days, distance, transport)", includeRoute) { includeRoute = it } }
                item { SwitchRow("Each leg (from → to, transport)" + if (trip.legs.isEmpty()) " (none logged)" else "", includeLegs) { includeLegs = it } }
                item { SwitchRow("Incidents (${trip.incidents.size} logged, highlighted in the post)", includeIncidents) { includeIncidents = it } }
            }
            item { SwitchRow("Companions" + if (trip.companions.isEmpty()) " (none logged)" else "", includeCompanions) { includeCompanions = it } }
            item { SectionHeader("Words") }
            item {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Your message") },
                    supportingText = {
                        Text(
                            (if (format == ShareFormat.POST) "Goes in the post" else "Shown under the map") +
                                ". Written just for this share; it isn't saved on the trip."
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            item { SwitchRow("Trip notes" + if (PostComposer.cleanNotes(trip.trip.notes).isEmpty()) " (none)" else "", includeNotes) { includeNotes = it } }
        }
        if (format == ShareFormat.POST) {
            item {
                Text(
                    "The post goes as a picture of the text and map, then each chosen photo, then a closing card with the link. " +
                        "The text is copied to your clipboard too, for apps that take a caption: paste it in.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { showPreview = true }) { Text("Preview post text") }
            }
        }
        if (!format.isTrack) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (photos.isEmpty()) "Photos" else "Photos (${selectedPhotos.size} of up to $MAX_SHARE_PHOTOS)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (photos.isNotEmpty()) {
                        TextButton(onClick = {
                            selectedPhotos = if (selectedPhotos.isNotEmpty()) emptySet()
                            else photos.take(MAX_SHARE_PHOTOS).map { it.id }.toSet()
                        }) { Text(if (selectedPhotos.isNotEmpty()) "Clear" else "Pick first $MAX_SHARE_PHOTOS") }
                    }
                }
            }
            if (photos.isEmpty()) {
                item { Text("No photos on this trip yet. Videos can't be included.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(photos.chunked(3)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { photo ->
                            val chosen = photo.id in selectedPhotos
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        if (chosen) selectedPhotos = selectedPhotos - photo.id
                                        else if (selectedPhotos.size >= MAX_SHARE_PHOTOS) {
                                            Toast.makeText(context, "Up to $MAX_SHARE_PHOTOS photos can be shared", Toast.LENGTH_SHORT).show()
                                        } else selectedPhotos = selectedPhotos + photo.id
                                    }
                            ) {
                                AsyncImage(
                                    model = android.net.Uri.parse(photo.uri),
                                    imageLoader = imageLoader,
                                    contentDescription = "photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (chosen) 0.6f else 1f)
                                )
                                if (chosen) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "Chosen",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                                    )
                                }
                            }
                        }
                        repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) {} }
                    }
                }
            }
        }
    }

    if (showPreview) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("Post text") },
            text = {
                SelectionContainer {
                    Text(postText, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { showPreview = false }) { Text("Close") } }
        )
    }
    if (!format.isTrack) {
        Text(
            "Every share ends with a link to Adventure Timeline.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    Button(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                try {
                    if (format.isTrack) {
                        val name = title.trim().ifEmpty { trip.trip.title }
                        val trail = viewModel.routePointsFor(tripId)
                        val incidents = if (includeIncidents) trip.incidents else emptyList()
                        val content = if (format == ShareFormat.GPX) TripTrack.gpx(name, trip.trip.startDate, trail, trip.legs, incidents, hiddenArea)
                        else TripTrack.kml(name, trip.trip.startDate, trail, trip.legs, incidents, hiddenArea)
                        shareTrack(context, name, format, content)
                        return@launch
                    }
                    val snapshot = if (includeMap) mapView?.let { captureMapImage(it, mapExport, dark) } else null
                    if (format == ShareFormat.POST) {
                        val files = PostComposer.writeImages(context, postContent, snapshot, photos.filter { it.id in selectedPhotos })
                        sharePost(context, title.trim().ifEmpty { trip.trip.title }, PostComposer.withFooter(postText), files)
                        return@launch
                    }
                    val options = ShareOptions(
                        title = title,
                        message = message,
                        includeDates = includeDates,
                        includeRating = includeRating,
                        includeMap = includeMap,
                        includeCompanions = includeCompanions,
                        includeNotes = includeNotes,
                        photos = photos.filter { it.id in selectedPhotos },
                        format = format
                    )
                    val result = ShareComposer.compose(context, trip, snapshot, options)
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", result.file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = format.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, trip.trip.title)
                        putExtra(Intent.EXTRA_TEXT, result.text)
                        clipData = ClipData.newRawUri(trip.trip.title, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share trip"))
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Couldn't prepare the share", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("  Preparing…")
        } else {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Share")
        }
    }
}

/**
 * Opens the share sheet with the written post and its pictures. The text is also put on the clipboard, because
 * Facebook and Instagram ignore text that comes with a share.
 */
private suspend fun shareTrack(context: Context, title: String, format: ShareFormat, content: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val safeName = title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).ifEmpty { "trip" }
        File(dir, "$safeName.${format.extension}").apply { writeText(content, Charsets.UTF_8) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share track"))
}

private fun sharePost(context: Context, title: String, text: String, files: List<File>) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(title, text))

    val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) }
    val send = when (uris.size) {
        0 -> Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        1 -> Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
        else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }.apply {
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
        if (uris.isNotEmpty()) {
            clipData = ClipData.newRawUri(title, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(send, "Share post"))
    Toast.makeText(context, "Post text copied. If the app leaves it out, paste it into the caption.", Toast.LENGTH_LONG).show()
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
