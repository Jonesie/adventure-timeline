package net.jonesie.traveldiary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.data.Trip
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripListScreen(
    viewModel: TravelDiaryViewModel,
    onAddTrip: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    onNearby: () -> Unit,
    onImportTimeline: () -> Unit
) {
    val trips by viewModel.trips.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Travel Diary") },
                actions = {
                    IconButton(onClick = onImportTimeline) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = "Import from Google Timeline")
                    }
                    IconButton(onClick = onNearby) {
                        Icon(Icons.Filled.ExploreOff, contentDescription = "Have I been here before?")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete all trips") },
                            onClick = {
                                menuExpanded = false
                                confirmDeleteAll = true
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTrip) {
                Icon(Icons.Filled.Add, contentDescription = "Add trip")
            }
        }
    ) { padding ->
        if (trips.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
                Text("No trips yet — tap + to log one.", modifier = Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(trip, onClick = { onOpenTrip(trip.id) })
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all trips?") },
            text = { Text("This removes every trip, leg, photo, audio and impression. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllTrips()
                    confirmDeleteAll = false
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TripRow(trip: Trip, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = ratingCardColors(trip.rating)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(trip.title, style = MaterialTheme.typography.titleMedium)
            val dateText = DateFormat.getDateInstance().format(Date(trip.startDate)) +
                (trip.endDate?.let { " – " + DateFormat.getDateInstance().format(Date(it)) } ?: "")
            Text(dateText, style = MaterialTheme.typography.bodySmall)
            if (trip.rating > 0) Text(starString(trip.rating), style = MaterialTheme.typography.titleMedium)
        }
    }
}
