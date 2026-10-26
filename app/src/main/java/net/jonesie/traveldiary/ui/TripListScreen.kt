package net.jonesie.traveldiary.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.util.LocationHelper
import net.jonesie.traveldiary.util.ReverseGeocoder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.data.MediaType
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
    onImportTimeline: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val trips by viewModel.trips.collectAsState()
    val mediaKinds by viewModel.mediaKindsByTrip.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(SearchMode.TITLE) }
    var query by rememberSaveable { mutableStateOf("") }
    var radiusKm by rememberSaveable { mutableIntStateOf(5) }
    var center by remember { mutableStateOf<SearchCenter?>(null) }
    var nearTripIds by remember { mutableStateOf<Set<Long>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(center, radiusKm) {
        nearTripIds = center?.let { viewModel.tripIdsNear(it.lat, it.lon, radiusKm.toDouble()) }
    }
    LaunchedEffect(searching, mode) {
        if (searching && mode != SearchMode.NEAR_ME) runCatching { focusRequester.requestFocus() }
    }

    fun clearLocation() {
        center = null
        nearTripIds = null
        status = null
    }

    fun lookUpPlace() {
        val text = query.trim()
        if (text.isEmpty()) return
        status = "Looking up “$text”…"
        scope.launch {
            val found = withContext(Dispatchers.IO) { ReverseGeocoder.search(context, text) }
            if (found == null) {
                center = null
                status = "Couldn't find “$text”. Place lookup needs a network connection."
            } else {
                center = SearchCenter(found.lat, found.lon, text)
                status = null
            }
        }
    }

    fun useMyLocation() {
        val here = LocationHelper.lastKnownLocation(context)
        if (here == null) {
            center = null
            status = "Couldn't get your location yet. Check location is on and try again."
        } else {
            center = SearchCenter(here.latitude, here.longitude, "your current location")
            status = null
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useMyLocation() else status = "Location permission is needed to search near you."
    }

    fun searchNearMe() {
        if (LocationHelper.hasPermission(context)) useMyLocation()
        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val visibleTrips = remember(trips, searching, mode, query, nearTripIds) {
        when {
            !searching -> trips
            mode == SearchMode.TITLE ->
                if (query.isBlank()) trips else trips.filter { it.title.contains(query.trim(), ignoreCase = true) }
            else -> nearTripIds?.let { ids -> trips.filter { it.id in ids } } ?: trips
        }
    }
    val message = status ?: when {
        !searching -> null
        mode == SearchMode.TITLE && query.isNotBlank() -> "${visibleTrips.size} of ${trips.size} trips"
        mode != SearchMode.TITLE && center != null && nearTripIds != null ->
            "${visibleTrips.size} trip(s) within $radiusKm km of ${center!!.label}"
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Travel Diary") },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) {
                            query = ""
                            mode = SearchMode.TITLE
                            clearLocation()
                        }
                    }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Close search" else "Search trips"
                        )
                    }
                    IconButton(onClick = onImportTimeline) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = "Import from Google Timeline")
                    }
                    IconButton(onClick = onNearby) {
                        Icon(Icons.Filled.ExploreOff, contentDescription = "Have I been here before?")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (searching) {
                TripSearchPanel(
                    mode = mode,
                    onModeChange = { newMode ->
                        mode = newMode
                        clearLocation()
                        if (newMode == SearchMode.NEAR_ME) searchNearMe()
                    },
                    query = query,
                    onQueryChange = { query = it },
                    radiusKm = radiusKm,
                    onRadiusChange = { radiusKm = it },
                    message = message,
                    focusRequester = focusRequester,
                    onLookUp = { lookUpPlace() },
                    onNearMe = { searchNearMe() }
                )
            }
            when {
                trips.isEmpty() -> Text("No trips yet — tap + to log one.", modifier = Modifier.padding(16.dp))
                visibleTrips.isEmpty() -> Text("No trips match your search.", modifier = Modifier.padding(16.dp))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleTrips, key = { it.id }) { trip ->
                        TripRow(trip, kinds = mediaKinds[trip.id].orEmpty(), onClick = { onOpenTrip(trip.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TripRow(trip: Trip, kinds: Set<MediaType>, onClick: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                // Icons take the card's text colour, so they stay readable on every rating colour.
                if (MediaType.PHOTO in kinds || MediaType.VIDEO in kinds) {
                    Icon(Icons.Filled.Image, contentDescription = "Has photos or videos", modifier = Modifier.size(18.dp))
                }
                if (MediaType.AUDIO in kinds) {
                    Icon(Icons.Filled.Mic, contentDescription = "Has voice notes", modifier = Modifier.padding(start = 8.dp).size(18.dp))
                }
            }
            if (trip.rating > 0) Text(starString(trip.rating), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private enum class SearchMode { TITLE, PLACE, NEAR_ME }

private data class SearchCenter(val lat: Double, val lon: Double, val label: String)

@Composable
private fun TripSearchPanel(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    radiusKm: Int,
    onRadiusChange: (Int) -> Unit,
    message: String?,
    focusRequester: FocusRequester,
    onLookUp: () -> Unit,
    onNearMe: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == SearchMode.TITLE, onClick = { onModeChange(SearchMode.TITLE) }, label = { Text("Title") })
            FilterChip(selected = mode == SearchMode.PLACE, onClick = { onModeChange(SearchMode.PLACE) }, label = { Text("Place") })
            FilterChip(selected = mode == SearchMode.NEAR_ME, onClick = { onModeChange(SearchMode.NEAR_ME) }, label = { Text("Near me") })
        }
        if (mode != SearchMode.NEAR_ME) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(if (mode == SearchMode.TITLE) "Search trip titles" else "Place name, e.g. Kaikōura") },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                        if (mode == SearchMode.PLACE) {
                            IconButton(onClick = onLookUp) {
                                Icon(Icons.Filled.Search, contentDescription = "Find trips near this place")
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = if (mode == SearchMode.PLACE) ImeAction.Search else ImeAction.Done),
                keyboardActions = KeyboardActions(onSearch = { onLookUp() }),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focusRequester)
            )
        }
        if (mode != SearchMode.TITLE) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Within", style = MaterialTheme.typography.labelLarge)
                listOf(1, 5, 25).forEach { km ->
                    FilterChip(selected = radiusKm == km, onClick = { onRadiusChange(km) }, label = { Text("$km km") })
                }
                if (mode == SearchMode.NEAR_ME) TextButton(onClick = onNearMe) { Text("Refresh") }
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
