package net.jonesie.traveldiary.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.MapNote
import net.jonesie.traveldiary.data.MapNoteCategory
import net.jonesie.traveldiary.util.LocationHelper
import net.jonesie.traveldiary.util.MapNotePlace
import net.jonesie.traveldiary.util.ReverseGeocoder

/**
 * Adds a note to a place on a trip's route, or edits or deletes one. The place is chosen on a full-screen map or
 * taken from where the phone is; which leg it belongs to is worked out from the place. [legId] is the leg it is
 * being added from (the map starts at that leg's start), and [startWithPicker] opens the map straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapNoteDialog(
    tripId: Long,
    viewModel: TravelDiaryViewModel,
    note: MapNote?,
    legId: Long?,
    initialPlace: Pair<Double, Double>?,
    startWithPicker: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(note?.text ?: "") }
    var category by remember { mutableStateOf(note?.category) }
    var shareable by remember { mutableStateOf(note?.shareable ?: true) }
    var place by remember { mutableStateOf(note?.let { it.lat to it.lon } ?: initialPlace) }
    var placeName by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(startWithPicker) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    LaunchedEffect(place) {
        val p = place
        placeName = if (p == null) null else withContext(Dispatchers.IO) {
            runCatching { ReverseGeocoder.lookup(context, p.first, p.second)?.format(false) }.getOrNull()
        }
    }

    val locate = rememberLocatePermission { granted ->
        if (granted) {
            locating = true
            LocationHelper.locate(context) { here ->
                locating = false
                if (here != null) place = here.latitude to here.longitude
                else Toast.makeText(context, "Couldn't get your location. Check that location is turned on.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Location permission is needed to use your location", Toast.LENGTH_LONG).show()
        }
    }

    if (picking) {
        Dialog(onDismissRequest = { picking = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            MapPlacePicker(
                tripId = tripId,
                viewModel = viewModel,
                initial = place,
                what = "the note",
                onDone = { lat, lon -> place = lat to lon; picking = false },
                onCancel = { picking = false; if (place == null && note == null && startWithPicker) onDismiss() }
            )
        }
    }

    if (confirmDelete && note != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text(note.text) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteMapNote(note.id); confirmDelete = false; onDismiss() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
        return
    }

    val clean = MapNotePlace.clean(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "Add a note" else "Edit note") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MapNote.MAX_TEXT * 2) },
                    label = { Text("What should people know?") },
                    placeholder = { Text("Road washed out, lots of potholes, gate locked after 5…") },
                    supportingText = { Text("${clean.length}/${MapNote.MAX_TEXT}") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Kind (optional)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapNoteCategory.entries.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = if (category == c) null else c },
                            label = { Text(c.label()) },
                            leadingIcon = { Icon(c.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
                Text("Where", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Text(
                    place?.let { (lat, lon) -> placeName?.let { "Near $it" } ?: "%.4f, %.4f".format(lat, lon) } ?: "No place chosen yet",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = { picking = true }) { Text(if (place == null) "Choose on map" else "Move on map") }
                    OutlinedButton(enabled = !locating, onClick = locate) { Text(if (locating) "Finding…" else "Use my location") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include when sharing", style = MaterialTheme.typography.bodyMedium)
                        Text("Posts, shared maps and GPX/KML files. Off keeps it to yourself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = shareable, onCheckedChange = { shareable = it })
                }
                if (note != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Delete this note", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = clean.isNotEmpty() && place != null && !saving,
                onClick = {
                    val p = place ?: return@TextButton
                    saving = true
                    scope.launch {
                        viewModel.saveMapNote(note, tripId, p.first, p.second, clean, category, shareable, legId)
                        onDismiss()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Returns a function that asks for the location permission if it isn't held yet, then reports whether it is. */
@Composable
private fun rememberLocatePermission(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { latest(it) }
    return { if (LocationHelper.hasPermission(context)) latest(true) else launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
}
