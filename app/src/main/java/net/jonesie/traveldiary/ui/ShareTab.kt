package net.jonesie.traveldiary.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.TripDetail
import net.jonesie.traveldiary.util.ShareComposer
import net.jonesie.traveldiary.util.ShareFormat
import net.jonesie.traveldiary.util.ShareOptions
import net.jonesie.traveldiary.util.TripExport
import net.jonesie.traveldiary.util.TripExportOptions
import org.osmdroid.views.MapView

/** Keeps a shared picture or PDF a sensible length. */
private const val MAX_SHARE_PHOTOS = 7

/**
 * Builds a shareable summary of the trip (map, notes, chosen photos, optionally companions) and
 * hands it to the phone's share sheet. The map is whatever the preview shows, so pan and zoom it
 * to frame what you want to send.
 */
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
    var includeDates by remember { mutableStateOf(true) }
    var includeRating by remember { mutableStateOf(true) }
    var includeCompanions by remember { mutableStateOf(false) }
    var includeTripNotes by remember { mutableStateOf(trip.trip.notes.isNotBlank()) }
    var includeImpressions by remember { mutableStateOf(false) }
    var selectedPhotos by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var format by remember { mutableStateOf(ShareFormat.IMAGE) }
    var busy by remember { mutableStateOf(false) }
    val homeSettings by viewModel.homeSettings.collectAsState()
    var homeCentre by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(homeSettings) { homeCentre = viewModel.homeCentre() }
    var hideHome by remember { mutableStateOf(true) }

    val photos = remember(trip.media) { trip.sortedMedia().filter { it.type == MediaType.PHOTO } }

    val videoCount = trip.media.count { it.type == MediaType.VIDEO }
    val audioCount = trip.media.count { it.type == MediaType.AUDIO }
    var exportPhotos by remember { mutableStateOf(true) }
    var exportVideos by remember { mutableStateOf(false) }
    var exportAudio by remember { mutableStateOf(false) }
    var exportMap by remember { mutableStateOf(true) }
    var exportProgress by remember { mutableStateOf<String?>(null) }

    if (includeMap) {
        // Not inside the scrolling list below, so the map's own pan/zoom gestures work.
        RouteMap(
            tripId, viewModel, trip.legs,
            modifier = Modifier.fillMaxWidth().height(280.dp).padding(top = 8.dp),
            media = trip.sortedMedia(),
            onMapView = { mapView = it },
            onMapExport = { mapExport = it }
        )
    }

    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        item {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message (shown under the map)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        item { SwitchRow("Map (as shown above)", includeMap) { includeMap = it } }
        if (homeCentre != null) {
            item { SwitchRow("Hide my home area (${homeSettings.radiusKm} km) from the map", hideHome) { hideHome = it } }
        }
        item { SwitchRow("Dates", includeDates) { includeDates = it } }
        item { SwitchRow("Star rating", includeRating) { includeRating = it } }
        item { SwitchRow("Companions" + if (trip.companions.isEmpty()) " (none logged)" else "", includeCompanions) { includeCompanions = it } }
        item { SwitchRow("Trip notes" + if (trip.trip.notes.isBlank()) " (none)" else "", includeTripNotes) { includeTripNotes = it } }
        item { SwitchRow("Impressions (${trip.impressions.size})", includeImpressions) { includeImpressions = it } }
        item {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Share as", style = MaterialTheme.typography.labelLarge)
                ShareFormat.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                }
            }
        }
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
        item {
            Text("Trip data for a video", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
            Text(
                "One zip with everything about this trip as JSON (legs, route, stops, place names, notes, incidents and where each " +
                    "photo was taken) and the photos themselves, named in the order they were taken. Ready to use with other " +
                    "pictures and clips in a video.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { SwitchRow("Photos (${photos.size})", exportPhotos) { exportPhotos = it } }
        item { SwitchRow("Videos ($videoCount), can be large", exportVideos) { exportVideos = it } }
        item { SwitchRow("Voice notes ($audioCount)", exportAudio) { exportAudio = it } }
        item { SwitchRow("Picture of the route map" + if (!includeMap) " (turn the map on above)" else "", exportMap && includeMap) { exportMap = it } }
        item {
            Button(
                enabled = !busy && exportProgress == null,
                onClick = {
                    exportProgress = "Preparing…"
                    scope.launch {
                        try {
                            val shownExport = homeCentre?.takeIf { hideHome }?.let { (lat, lon) ->
                                mapExport?.withoutArea(lat, lon, homeSettings.radiusKm.toDouble())
                            } ?: mapExport
                            val snapshot = if (includeMap && exportMap) mapView?.let { captureMapImage(it, shownExport, dark) } else null
                            val result = TripExport.export(
                                context, trip,
                                TripExportOptions(exportPhotos, exportVideos, exportAudio, snapshot)
                            ) { exportProgress = it }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", result.file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, trip.trip.title + " (trip data)")
                                clipData = ClipData.newRawUri(trip.trip.title, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            if (result.mediaMissing > 0) {
                                Toast.makeText(context, "${result.mediaMissing} files couldn't be read and are listed as missing", Toast.LENGTH_LONG).show()
                            }
                            context.startActivity(Intent.createChooser(send, "Share trip data"))
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Couldn't export the trip", Toast.LENGTH_LONG).show()
                        } finally {
                            exportProgress = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
            ) {
                if (exportProgress != null) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  $exportProgress")
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Export trip data (zip)")
                }
            }
        }
    }

    Button(
        enabled = !busy && exportProgress == null,
        onClick = {
            busy = true
            scope.launch {
                try {
                    val shownExport = homeCentre?.takeIf { hideHome }?.let { (lat, lon) ->
                        mapExport?.withoutArea(lat, lon, homeSettings.radiusKm.toDouble())
                    } ?: mapExport
                    val snapshot = if (includeMap) mapView?.let { captureMapImage(it, shownExport, dark) } else null
                    val options = ShareOptions(
                        title = title,
                        message = message,
                        includeDates = includeDates,
                        includeRating = includeRating,
                        includeMap = includeMap,
                        includeCompanions = includeCompanions,
                        includeTripNotes = includeTripNotes,
                        includeImpressions = includeImpressions,
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
