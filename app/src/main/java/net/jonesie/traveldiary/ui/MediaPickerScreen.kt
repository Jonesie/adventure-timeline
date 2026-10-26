package net.jonesie.traveldiary.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.util.CameraRoll
import net.jonesie.traveldiary.util.TripTimeWindow
import java.text.DateFormat
import java.util.Date

/**
 * Pick photos and videos from anywhere on the phone. Opens on the trip's own dates so the
 * likely candidates are on screen straight away; "All photos" widens it to the whole library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPickerScreen(tripId: Long, viewModel: TravelDiaryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val detail by remember(tripId) { viewModel.tripDetail(tripId) }.collectAsState(initial = null)
    val trip = detail
    if (trip == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val window = remember(trip.trip.startDate, trip.trip.endDate) { TripTimeWindow.of(trip.trip) }
    val alreadyAdded = remember(trip.media) { trip.media.map { it.uri }.toSet() }
    var allDates by rememberSaveable { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<CameraRoll.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val imageLoader = rememberMediaImageLoader()

    LaunchedEffect(window, allDates) {
        loading = true
        items = withContext(Dispatchers.IO) {
            CameraRoll.find(context, if (allDates) null else window, cameraOnly = false, newestFirst = allDates)
        }
        loading = false
    }

    // Photos and videos already on this phone are linked in place, not copied: copying
    // multi-gigabyte videos would fill the phone.
    fun addFromPhone(chosen: List<CameraRoll.Item>) {
        viewModel.addPhoneMedia(tripId, chosen)
        onBack()
    }

    fun importUris(uris: List<Uri>) {
        viewModel.importFiles(tripId, uris, null) { _, failed ->
            if (failed > 0) Toast.makeText(context, "$failed file(s) couldn't be imported", Toast.LENGTH_LONG).show()
        }
        onBack()
    }

    // For sources the app can't list itself, e.g. cloud photos.
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    val selectable = items.filter { it.uri.toString() !in alreadyAdded }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add from phone") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(
                        enabled = selected.isNotEmpty(),
                        onClick = { addFromPhone(items.filter { it.uri.toString() in selected }) }
                    ) { Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = !allDates, onClick = { allDates = false }, label = { Text("Trip dates") })
                FilterChip(selected = allDates, onClick = { allDates = true }, label = { Text("All photos") })
                Box(modifier = Modifier.weight(1f))
                TextButton(
                    enabled = selectable.isNotEmpty(),
                    onClick = {
                        selected = if (selected.size == selectable.size) emptySet()
                        else selectable.map { it.uri.toString() }.toSet()
                    }
                ) { Text(if (selectable.isNotEmpty() && selected.size == selectable.size) "Clear" else "Select all") }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
                Text(
                    if (allDates) "${items.size} items" else
                        "${dateFormat.format(Date(window.first))} – ${dateFormat.format(Date(window.last))} · ${items.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    systemPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }) { Text("Other sources…") }
            }

            when {
                loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                items.isEmpty() -> Text(
                    if (allDates) "No photos or videos found on this phone."
                    else "Nothing on this phone was taken during this trip. Try “All photos”.",
                    modifier = Modifier.padding(top = 24.dp)
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        val key = item.uri.toString()
                        PickerTile(
                            item = item,
                            imageLoader = imageLoader,
                            selected = key in selected,
                            added = key in alreadyAdded,
                            onToggle = { selected = if (key in selected) selected - key else selected + key }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerTile(
    item: CameraRoll.Item,
    imageLoader: ImageLoader,
    selected: Boolean,
    added: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !added, onClick = onToggle)
    ) {
        AsyncImage(
            model = item.uri,
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (added) 0.35f else if (selected) 0.7f else 1f)
        )
        if (item.type == MediaType.VIDEO) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
            )
        }
        when {
            added -> Text(
                "Added",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            selected -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}
