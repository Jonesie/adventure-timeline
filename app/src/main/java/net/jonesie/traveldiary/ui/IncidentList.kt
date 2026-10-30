package net.jonesie.traveldiary.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.ImageLoader
import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.util.LocationHelper
import net.jonesie.traveldiary.util.MediaFiles
import java.text.DateFormat
import java.util.Date

/** Photo, video and pick-from-phone handlers that attach what they capture to an incident. */
class IncidentMediaActions(
    val photo: (Incident) -> Unit,
    val video: (Incident) -> Unit,
    val pick: (Incident) -> Unit
)

@Composable
fun rememberIncidentMediaActions(viewModel: TravelDiaryViewModel): IncidentMediaActions {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Incident?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun attach(type: MediaType, uri: Uri) {
        val incident = pending ?: return
        val loc = LocationHelper.lastKnownLocation(context)
        viewModel.addMedia(incident.tripId, null, type, uri.toString(), "", loc?.latitude ?: incident.lat, loc?.longitude ?: incident.lon, incident.id)
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingUri?.takeIf { ok }?.let { attach(MediaType.PHOTO, it) }
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        pendingUri?.takeIf { ok }?.let { attach(MediaType.VIDEO, it) }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val incident = pending ?: return@rememberLauncherForActivityResult
        viewModel.importFiles(incident.tripId, uris, null, incident.id) { _, _ -> }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke()
        afterPermission = null
    }

    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else {
            afterPermission = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    return remember {
        IncidentMediaActions(
            photo = { incident ->
                pending = incident
                withCamera {
                    val uri = MediaFiles.newPhotoUri(context)
                    pendingUri = uri
                    takePicture.launch(uri)
                }
            },
            video = { incident ->
                pending = incident
                withCamera {
                    val uri = MediaFiles.newVideoUri(context)
                    pendingUri = uri
                    captureVideo.launch(uri)
                }
            },
            pick = { incident ->
                pending = incident
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        )
    }
}

/** The leg of the trip that was under way when this happened, if any. */
private fun legAt(legs: List<Leg>, timestamp: Long): Leg? =
    legs.firstOrNull { it.startTime <= timestamp && timestamp <= (it.endTime ?: it.startTime) }

@Composable
fun IncidentCard(
    incident: Incident,
    tripTitle: String?,
    leg: Leg?,
    who: String,
    media: List<MediaItem>,
    imageLoader: ImageLoader,
    actions: IncidentMediaActions,
    onEdit: () -> Unit,
    onOpenMedia: (Int) -> Unit
) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(severityColor(incident.severity) ?: MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        incident.category.icon(),
                        contentDescription = null,
                        tint = if (severityColor(incident.severity) != null) androidx.compose.ui.graphics.Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(incident.category.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(incident.timestamp)) +
                            (severityLabel(incident.severity)?.let { "  ·  $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (tripTitle != null || leg != null) {
                Text(
                    listOfNotNull(
                        tripTitle?.let { "Trip: $it" },
                        leg?.let { "On the leg ${it.originName} → ${it.destinationName}" }
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (incident.tripId == null) {
                Text(
                    "Not in a trip yet. It joins one when a trip covering this time is imported.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (who.isNotEmpty()) {
                Text("Who: $who", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            incident.reason?.let {
                Text("Reason: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (incident.notes.isNotBlank()) {
                Text(incident.notes, modifier = Modifier.padding(top = 8.dp))
            }

            if (media.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    itemsIndexed(media, key = { _, item -> item.id }) { index, item ->
                        Box(modifier = Modifier.size(84.dp)) { MediaTile(item, imageLoader, onClick = { onOpenMedia(index) }) }
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { actions.photo(incident) }) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Add a photo of this incident")
                }
                IconButton(onClick = { actions.video(incident) }) {
                    Icon(Icons.Filled.Videocam, contentDescription = "Record a video of this incident")
                }
                IconButton(onClick = { actions.pick(incident) }) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add photos or videos from your phone")
                }
            }
        }
    }
}

/** Every incident, with or without a trip. The third tab of the main screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentsScreen(
    viewModel: TravelDiaryViewModel,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenMedia: (incidentId: Long, index: Int) -> Unit,
    onShowMap: () -> Unit,
    onShowList: () -> Unit
) {
    val incidents by viewModel.incidents.collectAsState()
    val allMedia by viewModel.incidentMedia.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val people by viewModel.incidentPeople.collectAsState()
    val imageLoader = rememberMediaImageLoader()
    val actions = rememberIncidentMediaActions(viewModel)
    LaunchedEffect(Unit) { viewModel.assignIncidentsToTrips() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Incidents") }) },
        bottomBar = { MainTabBar(selected = MainTab.INCIDENTS, onMap = onShowMap, onList = onShowList, onIncidents = {}) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Log an incident") }
        }
    ) { padding ->
        if (incidents.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("Nothing has gone wrong yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Log a spill, a wrong turn or a river crossing gone sideways with the + button, even before the trip " +
                        "is recorded. It attaches itself to the trip once that exists, and you can add photos and videos too.",
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedButton(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) { Text("Log an incident") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp)
            ) {
                items(incidents, key = { it.id }) { incident ->
                    val media = allMedia.filter { it.incidentId == incident.id }.sortedBy { it.timestamp }
                    IncidentCard(
                        incident = incident,
                        tripTitle = trips.firstOrNull { it.id == incident.tripId }?.title,
                        leg = null,
                        who = whoLabel(incident, people[incident.id].orEmpty()),
                        media = media,
                        imageLoader = imageLoader,
                        actions = actions,
                        onEdit = { onEdit(incident.id) },
                        onOpenMedia = { onOpenMedia(incident.id, it) }
                    )
                }
            }
        }
    }
}

/** "Me", "Me, Sam" or "Sam, Alex": who an incident involved. Empty when nobody was recorded. */
fun whoLabel(incident: Incident, others: List<net.jonesie.traveldiary.data.Companion>): String =
    (listOfNotNull("Me".takeIf { incident.involvesMe }) + others.map { it.name }).joinToString(", ")

/** A trip's own incidents (the ones that happened during it). */
@Composable
fun IncidentsTab(
    trip: net.jonesie.traveldiary.data.TripDetail,
    viewModel: TravelDiaryViewModel,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenMedia: (incidentId: Long, index: Int) -> Unit
) {
    val imageLoader = rememberMediaImageLoader()
    val actions = rememberIncidentMediaActions(viewModel)
    val incidents = remember(trip.incidents) { trip.incidents.sortedBy { it.timestamp } }
    val people by viewModel.incidentPeople.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Log an incident")
        }
        if (incidents.isEmpty()) {
            Text(
                "No incidents on this trip. Anything you logged on the road joins automatically when its time falls " +
                    "within the trip's dates.",
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(incidents, key = { it.id }) { incident ->
                    val media = trip.media.filter { it.incidentId == incident.id }.sortedBy { it.timestamp }
                    IncidentCard(
                        incident = incident,
                        tripTitle = null,
                        leg = legAt(trip.legs, incident.timestamp),
                        who = whoLabel(incident, people[incident.id].orEmpty()),
                        media = media,
                        imageLoader = imageLoader,
                        actions = actions,
                        onEdit = { onEdit(incident.id) },
                        onOpenMedia = { onOpenMedia(incident.id, it) }
                    )
                }
            }
        }
    }
}
