package net.jonesie.traveldiary.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
import net.jonesie.traveldiary.data.IncidentReason
import net.jonesie.traveldiary.util.LocationHelper
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * Log what went wrong (or nearly did). With [incidentId] null it adds one, otherwise it edits that incident.
 * [tripId] is only set when logging from inside a trip; otherwise the incident stands alone and joins
 * whichever trip covers its time once that trip exists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddIncidentScreen(
    tripId: Long?,
    incidentId: Long?,
    viewModel: TravelDiaryViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var existing by remember { mutableStateOf<Incident?>(null) }
    var category by remember { mutableStateOf<IncidentCategory?>(null) }
    var severity by remember { mutableStateOf(0) }
    var reason by remember { mutableStateOf<IncidentReason?>(null) }
    var notes by remember { mutableStateOf("") }
    var timestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tagLocation by remember { mutableStateOf(incidentId == null) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(incidentId) {
        if (incidentId != null) {
            viewModel.incident(incidentId)?.let {
                existing = it
                category = it.category
                severity = it.severity
                reason = it.reason
                notes = it.notes
                timestamp = it.timestamp
            }
        }
    }

    // Tagging "where I am now" only makes sense for something that just happened.
    val recent = System.currentTimeMillis() - timestamp < 3 * 60 * 60_000L
    val zone = ZoneId.systemDefault()
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zone)

    fun save() {
        val chosen = category ?: return
        saving = true
        scope.launch {
            var lat = existing?.lat
            var lon = existing?.lon
            if (recent && tagLocation && LocationHelper.hasPermission(context)) {
                LocationHelper.lastKnownLocation(context)?.let { lat = it.latitude; lon = it.longitude }
            } else if (lat == null && tripId != null) {
                viewModel.routePositionAt(tripId, timestamp)?.let { lat = it.first; lon = it.second }
            }
            val saved = (existing ?: Incident(tripId = tripId, category = chosen, timestamp = timestamp)).copy(
                category = chosen, severity = severity, notes = notes.trim(), timestamp = timestamp, lat = lat, lon = lon,
                reason = reason
            )
            if (existing == null) viewModel.addIncident(saved) else viewModel.updateIncident(saved)
            Toast.makeText(context, "Incident saved", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { save() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (incidentId == null) "Log an incident" else "Edit incident") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (incidentId != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete incident")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("What happened?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            var typeMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
                OutlinedTextField(
                    value = category?.label() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Choose a type") },
                    leadingIcon = category?.let { { Icon(it.icon(), contentDescription = null) } },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    IncidentCategory.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            leadingIcon = { Icon(option.icon(), contentDescription = null) },
                            onClick = { category = option; typeMenu = false }
                        )
                    }
                }
            }

            Text("How bad was it?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (level in 1..3) {
                    // Tapping the chosen level again clears it.
                    FilterChip(
                        selected = severity == level,
                        onClick = { severity = if (severity == level) 0 else level },
                        label = { Text(severityLabel(level)!!) }
                    )
                }
            }

            Text("Reason (optional)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            var reasonMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = reasonMenu, onExpandedChange = { reasonMenu = it }) {
                OutlinedTextField(
                    value = reason?.label() ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Leave blank, or say why") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reasonMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = reasonMenu, onDismissRequest = { reasonMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Leave blank", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { reason = null; reasonMenu = false }
                    )
                    IncidentReason.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label()) }, onClick = { reason = option; reasonMenu = false })
                    }
                }
            }

            Text("When?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp)))
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp)))
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Details (optional)") },
                placeholder = { Text("What went wrong? Anything you'd do differently?") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            )

            if (recent) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Checkbox(checked = tagLocation, onCheckedChange = { tagLocation = it })
                    Text("Tag with my current location")
                }
            } else {
                Text(
                    "The place is taken from your trip's route at that time once the trip is imported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Text(
                "You can add photos and videos from the Incidents tab once it's saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Button(
                enabled = category != null && !saving,
                onClick = {
                    // Ask for location once, then save either way: a missing fix never blocks the log.
                    if (recent && tagLocation && !LocationHelper.hasPermission(context)) {
                        locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    } else save()
                },
                modifier = Modifier.padding(top = 20.dp, bottom = 24.dp)
            ) { Text("Save") }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dateTime.toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        // The picker speaks UTC midnight; keep the time of day already chosen.
                        val day = Instant.ofEpochMilli(picked).atZone(ZoneId.of("UTC")).toLocalDate()
                        timestamp = LocalDateTime.of(day, dateTime.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val state = rememberTimePickerState(
            initialHour = dateTime.hour,
            initialMinute = dateTime.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    timestamp = LocalDateTime.of(dateTime.toLocalDate(), LocalTime.of(state.hour, state.minute))
                        .atZone(zone).toInstant().toEpochMilli()
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }

    if (confirmDelete && incidentId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this incident?") },
            text = { Text("Photos and videos you added stay in the trip's media. If it isn't in a trip yet, they are deleted with it.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteIncident(incidentId)
                    confirmDelete = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
