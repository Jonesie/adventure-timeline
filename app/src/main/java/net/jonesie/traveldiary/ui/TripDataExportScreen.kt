package net.jonesie.traveldiary.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.util.TripExport
import net.jonesie.traveldiary.util.TripExportOptions
import org.osmdroid.views.MapView

/**
 * Packs a trip into one zip for use in a video editor: its data as JSON, and the photos (optionally videos and
 * voice notes) named in the order they were taken. Kept apart from the Share tab, which is for posting to people.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDataExportScreen(tripId: Long, viewModel: TravelDiaryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isDarkTheme()
    val detail by remember(tripId) { viewModel.tripDetail(tripId) }.collectAsState(initial = null)
    val trip = detail

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapExport by remember { mutableStateOf<MapExport?>(null) }
    var includePhotos by remember { mutableStateOf(true) }
    var includeVideos by remember { mutableStateOf(false) }
    var includeAudio by remember { mutableStateOf(false) }
    var includeMap by remember { mutableStateOf(true) }
    var hideHome by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<String?>(null) }
    val homeSettings by viewModel.homeSettings.collectAsState()
    var homeCentre by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(homeSettings) { homeCentre = viewModel.homeCentre() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export trip data") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (trip == null) return@Scaffold
        val photoCount = trip.media.count { it.type == MediaType.PHOTO }
        val videoCount = trip.media.count { it.type == MediaType.VIDEO }
        val audioCount = trip.media.count { it.type == MediaType.AUDIO }

        Column(modifier = Modifier.fillMaxSize().padding(padding).contentWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(trip.trip.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "One zip with everything about this trip as JSON (legs, route, stops, place names, notes, incidents and where " +
                        "each photo was taken) and the photos themselves, named in the order they were taken. Ready to use with other " +
                        "pictures and clips in a video.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
                SwitchRow("Photos ($photoCount)", includePhotos) { includePhotos = it }
                SwitchRow("Videos ($videoCount), can be large", includeVideos) { includeVideos = it }
                SwitchRow("Voice notes ($audioCount)", includeAudio) { includeAudio = it }
                SwitchRow("Picture of the route map", includeMap) { includeMap = it }
                if (includeMap && homeCentre != null) {
                    SwitchRow("Hide my home area (${homeSettings.radiusKm} km) from the map picture", hideHome) { hideHome = it }
                }
            }

            if (includeMap) {
                Text(
                    "Map picture: pan or zoom to frame it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // Outside the scrolling part, so the map's own pan and zoom gestures work.
                RouteMap(
                    tripId, viewModel, trip.legs,
                    modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp),
                    media = trip.sortedMedia(),
                    onMapView = { mapView = it },
                    onMapExport = { mapExport = it }
                )
            }

            Button(
                enabled = progress == null,
                onClick = {
                    progress = "Preparing…"
                    scope.launch {
                        try {
                            val shownExport = homeCentre?.takeIf { hideHome }?.let { (lat, lon) ->
                                mapExport?.withoutArea(lat, lon, homeSettings.radiusKm.toDouble())
                            } ?: mapExport
                            val snapshot = if (includeMap) mapView?.let { captureMapImage(it, shownExport, dark) } else null
                            val result = TripExport.export(
                                context, trip,
                                TripExportOptions(includePhotos, includeVideos, includeAudio, snapshot)
                            ) { progress = it }
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
                            progress = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)
            ) {
                if (progress != null) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  $progress")
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Export trip data (zip)")
                }
            }
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
