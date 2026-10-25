package net.jonesie.traveldiary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.util.ActivityLeg
import net.jonesie.traveldiary.util.ParsedTimeline
import net.jonesie.traveldiary.util.TimelineImport
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTimelineScreen(
    viewModel: TravelDiaryViewModel,
    onBack: () -> Unit,
    onImported: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parsed by remember { mutableStateOf<ParsedTimeline?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        parsing = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { TimelineImport.parse(context, uri) } }
            result.onSuccess {
                parsed = it
                selected = it.trips.indices.toSet()
            }.onFailure {
                error = it.message ?: "Couldn't read that file"
            }
            parsing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from Google Timeline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (parsed == null) {
                Text(
                    "Pick the Timeline.json file you exported from your phone's " +
                        "Settings → Location → Location services → Timeline → Export Timeline Data."
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Button(onClick = { pickFile.launch("application/json") }, enabled = !parsing) {
                    Text(if (parsing) "Reading…" else "Choose Timeline.json")
                }
                if (parsing) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                    CircularProgressIndicator()
                }
                error?.let {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                    Text("Error: $it", color = MaterialTheme.colorScheme.error)
                }
            } else {
                val trips = parsed!!.trips
                Text("Found ${trips.size} trips Google detected. Choose which to import:")
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(trips.size) { index ->
                        val trip = trips[index]
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Checkbox(
                                checked = selected.contains(index),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + index else selected - index
                                }
                            )
                            Column {
                                val start = DateFormat.getDateInstance().format(Date(trip.startTime))
                                val end = DateFormat.getDateInstance().format(Date(trip.endTime))
                                Text("$start – $end")
                                Text("~${trip.distanceKm} km from home", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                importProgress?.let { progress ->
                    Text("Trip ${progress.tripIndex} of ${progress.tripsTotal}: ${progress.tripDateRange}")
                    Text(progress.status, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
                }
                Button(
                    onClick = {
                        importing = true
                        val chosen = selected.sorted().map { parsed!!.trips[it] }
                        scope.launch {
                            chosen.forEachIndexed { index, trip ->
                                val dateRange = DateFormat.getDateInstance().format(Date(trip.startTime)) + " – " +
                                    DateFormat.getDateInstance().format(Date(trip.endTime))
                                val legsInRange = parsed!!.legs.filter { leg: ActivityLeg ->
                                    leg.startTime >= trip.startTime && leg.endTime <= trip.endTime
                                }
                                val pathInRange = parsed!!.pathPoints.filter { point ->
                                    point.timestamp >= trip.startTime && point.timestamp <= trip.endTime
                                }
                                val fallbackTitle = "Trip, " + DateFormat.getDateInstance().format(Date(trip.startTime))
                                viewModel.importTimelineTrip(trip.startTime, trip.endTime, legsInRange, pathInRange, fallbackTitle) { status ->
                                    importProgress = ImportProgress(index + 1, chosen.size, dateRange, status)
                                }
                            }
                            importing = false
                            importProgress = null
                            onImported()
                        }
                    },
                    enabled = selected.isNotEmpty() && !importing
                ) {
                    Text(if (importing) "Importing…" else "Import ${selected.size} trip(s)")
                }
            }
        }
    }
}

private data class ImportProgress(
    val tripIndex: Int,
    val tripsTotal: Int,
    val tripDateRange: String,
    val status: String
)
