package net.jonesie.traveldiary.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.jonesie.traveldiary.data.MapNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMapScreen(tripId: Long, viewModel: TravelDiaryViewModel, onBack: () -> Unit, onPlay: () -> Unit, onOpenMedia: (Int) -> Unit) {
    val detail by remember(tripId) { viewModel.tripDetail(tripId) }.collectAsState(initial = null)
    val trip = detail
    var noteEditor by remember { mutableStateOf<MapNote?>(null) }
    var addingNote by remember { mutableStateOf(false) }

    if (addingNote || noteEditor != null) {
        MapNoteDialog(
            tripId = tripId, viewModel = viewModel, note = noteEditor, legId = null, initialPlace = null,
            startWithPicker = noteEditor == null,
            onDismiss = { addingNote = false; noteEditor = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trip?.trip?.title ?: "Map", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (trip != null) {
                        IconButton(onClick = { addingNote = true }) {
                            Icon(Icons.Filled.AddLocationAlt, contentDescription = "Add a note on the map")
                        }
                    }
                    if (trip?.legs?.isNotEmpty() == true) {
                        IconButton(onClick = onPlay) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play trip")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (trip != null) {
            RouteMap(
                tripId = tripId,
                viewModel = viewModel,
                legs = trip.legs,
                modifier = Modifier.fillMaxSize().padding(padding),
                media = trip.sortedMedia(),
                incidents = trip.incidents,
                onOpenMedia = onOpenMedia,
                mapNotes = trip.mapNotes,
                onOpenNote = { noteEditor = it }
            )
        }
    }
}
