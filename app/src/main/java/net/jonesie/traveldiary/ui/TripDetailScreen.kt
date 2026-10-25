package net.jonesie.traveldiary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.TripDetail
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: Long,
    viewModel: TravelDiaryViewModel,
    onBack: () -> Unit,
    onAddLeg: () -> Unit,
    onCapturePhoto: () -> Unit,
    onCaptureVideo: () -> Unit,
    onPickMedia: () -> Unit,
    onAutoAddMedia: () -> Unit,
    onOpenMedia: (Int) -> Unit,
    onRecordAudio: () -> Unit,
    onImportAudio: () -> Unit,
    onAddImpression: () -> Unit,
    onEdit: () -> Unit,
    onOpenMap: () -> Unit
) {
    val detail by remember(tripId) { viewModel.tripDetail(tripId) }.collectAsState(initial = null)
    val trip = detail
    val importProgress by viewModel.mediaImportProgress.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var editingGroup by remember { mutableStateOf<LegGroup?>(null) }
    var showBulkTransport by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                IconButton(onClick = onAddLeg) {
                    Icon(Icons.Filled.AddRoad, contentDescription = "Add leg")
                }
                IconButton(onClick = onCapturePhoto) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Capture photo")
                }
                IconButton(onClick = onCaptureVideo) {
                    Icon(Icons.Filled.Videocam, contentDescription = "Record video")
                }
                IconButton(onClick = onRecordAudio) {
                    Icon(Icons.Filled.Mic, contentDescription = "Record audio")
                }
                IconButton(onClick = onAddImpression) {
                    Icon(Icons.Filled.EditNote, contentDescription = "Add impression")
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Import audio…") },
                        leadingIcon = { Icon(Icons.Filled.AudioFile, contentDescription = null) },
                        onClick = { overflowExpanded = false; onImportAudio() }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit trip") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { overflowExpanded = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Change transport…") },
                        leadingIcon = { Icon(Icons.Filled.TwoWheeler, contentDescription = null) },
                        onClick = { overflowExpanded = false; showBulkTransport = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete trip") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { overflowExpanded = false; confirmDelete = true }
                    )
                }
            }
        }
    ) { padding ->
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this trip?") },
                text = { Text("This removes its legs, media, and impressions. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteTrip(tripId)
                        confirmDelete = false
                        onBack()
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
            )
        }
        if (trip == null) return@Scaffold
        val legGroups = remember(trip.legs) { groupConsecutiveLegs(trip.legs) }

        editingGroup?.let { group ->
            TransportPickerDialog(
                selected = group.transportType,
                onPick = { type ->
                    viewModel.setLegsTransport(group.legIds, type)
                    editingGroup = null
                },
                onDismiss = { editingGroup = null }
            )
        }
        if (showBulkTransport) {
            BulkTransportDialog(
                presentTypes = trip.legs.map { it.transportType }.distinct(),
                onApply = { from, to ->
                    viewModel.changeTripTransport(tripId, from, to)
                    showBulkTransport = false
                },
                onDismiss = { showBulkTransport = false }
            )
        }

        val tabs = listOf("Overview", "Legs", "Media", "Notes")

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                trip.trip.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            )
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
            // The map lives in a plain Column (never inside a scrolling list) so the native
            // MapView's own pan/zoom gestures don't compete with a scroll container.
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                when (selectedTab) {
                    0 -> OverviewTab(tripId, viewModel, trip, onOpenMap, onRate = { viewModel.setTripRating(tripId, it) })
                    1 -> LegsTab(legGroups, onEditGroup = { editingGroup = it })
                    2 -> MediaTab(trip, importProgress, onPickMedia, onAutoAddMedia, onOpenMedia)
                    else -> NotesTab(trip)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.OverviewTab(
    tripId: Long,
    viewModel: TravelDiaryViewModel,
    trip: TripDetail,
    onOpenMap: () -> Unit,
    onRate: (Int) -> Unit
) {
    val dateFormat = DateFormat.getDateInstance()
    val dates = dateFormat.format(Date(trip.trip.startDate)) +
        (trip.trip.endDate?.let { " – " + dateFormat.format(Date(it)) } ?: "")
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(dates, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        // Tapping the current rating again clears it back to "not rated".
        for (star in 1..5) {
            Icon(
                imageVector = if (star <= trip.trip.rating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "$star star" + if (star > 1) "s" else "",
                tint = if (star <= trip.trip.rating) ratingColor(maxOf(trip.trip.rating, 4))!! else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onRate(if (star == trip.trip.rating) 0 else star) }
                    .padding(4.dp)
            )
        }
    }

    if (trip.legs.isEmpty()) {
        Text("No route yet — add a leg, or import this trip from Google Timeline.")
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
    } else {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            RouteMap(tripId, viewModel, trip.legs, modifier = Modifier.fillMaxSize())
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                IconButton(onClick = onOpenMap) {
                    Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen map")
                }
            }
        }
    }

    SectionHeader("Companions")
    Text(
        if (trip.companions.isEmpty()) "None logged" else trip.companions.joinToString(", ") { it.name },
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun LegsTab(legGroups: List<LegGroup>, onEditGroup: (LegGroup) -> Unit) {
    if (legGroups.isEmpty()) {
        Text("No legs logged yet", modifier = Modifier.padding(top = 16.dp))
        return
    }
    Text("Tap a leg to change its transport", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(legGroups) { group -> LegRow(group, onClick = { onEditGroup(group) }) }
    }
}

@Composable
private fun NotesTab(trip: TripDetail) {
    if (trip.impressions.isEmpty()) {
        Text("No notes or impressions logged yet", modifier = Modifier.padding(top = 16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(trip.impressions) { impression ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(impression.text)
                Text(
                    DateFormat.getDateTimeInstance().format(Date(impression.timestamp)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private data class LegGroup(
    val transportType: TransportType,
    val originName: String,
    val destinationName: String,
    val startTime: Long,
    val endTime: Long?,
    val count: Int,
    val legIds: List<Long>
)

/** Adjacent legs with the same transport type and endpoints collapse into one row with a time range. */
private fun groupConsecutiveLegs(legs: List<Leg>): List<LegGroup> {
    val sorted = legs.sortedBy { it.startTime }
    val groups = mutableListOf<LegGroup>()
    for (leg in sorted) {
        val last = groups.lastOrNull()
        if (last != null && last.transportType == leg.transportType &&
            last.originName == leg.originName && last.destinationName == leg.destinationName
        ) {
            groups[groups.lastIndex] = last.copy(endTime = leg.endTime, count = last.count + 1, legIds = last.legIds + leg.id)
        } else {
            groups.add(LegGroup(leg.transportType, leg.originName, leg.destinationName, leg.startTime, leg.endTime, 1, listOf(leg.id)))
        }
    }
    return groups
}

private fun transportIcon(type: TransportType): ImageVector = when (type) {
    TransportType.WALK -> Icons.Filled.DirectionsWalk
    TransportType.BIKE -> Icons.Filled.DirectionsBike
    TransportType.MOTORBIKE -> Icons.Filled.TwoWheeler
    TransportType.CAR -> Icons.Filled.DirectionsCar
    TransportType.BUS -> Icons.Filled.DirectionsBus
    TransportType.TRAIN -> Icons.Filled.Train
    TransportType.PLANE -> Icons.Filled.Flight
    TransportType.BOAT -> Icons.Filled.DirectionsBoat
    TransportType.OTHER -> Icons.Filled.MoreHoriz
}

@Composable
private fun LegRow(group: LegGroup, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Icon(transportIcon(group.transportType), contentDescription = group.transportType.name, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text("${group.originName} → ${group.destinationName}" + if (group.count > 1) " (×${group.count})" else "")
            val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
            val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
            val sameDay = group.endTime != null &&
                dateFormat.format(Date(group.startTime)) == dateFormat.format(Date(group.endTime))
            val timeRange = dateFormat.format(Date(group.startTime)) + ", " + timeFormat.format(Date(group.startTime)) +
                (group.endTime?.let {
                    " – " + (if (sameDay) "" else dateFormat.format(Date(it)) + ", ") + timeFormat.format(Date(it))
                } ?: "")
            Text(timeRange, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

private fun TransportType.label() = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun TransportPickerDialog(selected: TransportType, onPick: (TransportType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change transport") },
        text = {
            Column {
                TransportType.entries.forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(type) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = type == selected, onClick = { onPick(type) })
                        Icon(transportIcon(type), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(type.label())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkTransportDialog(
    presentTypes: List<TransportType>,
    onApply: (from: TransportType?, to: TransportType) -> Unit,
    onDismiss: () -> Unit
) {
    var from by remember { mutableStateOf(if (TransportType.CAR in presentTypes) TransportType.CAR else null) }
    var to by remember { mutableStateOf<TransportType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change transport for this trip") },
        text = {
            Column {
                Text("Change", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = from == null, onClick = { from = null }, label = { Text("All legs") })
                    presentTypes.forEach { type ->
                        FilterChip(selected = from == type, onClick = { from = type }, label = { Text(type.label()) })
                    }
                }
                Text("to", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    TransportType.entries.forEach { type ->
                        FilterChip(selected = to == type, onClick = { to = type }, label = { Text(type.label()) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { to?.let { onApply(from, it) } },
                enabled = to != null && to != from
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
