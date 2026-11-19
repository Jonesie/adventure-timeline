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
import androidx.compose.runtime.collectAsState
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
import net.jonesie.traveldiary.util.IncidentPlace
import net.jonesie.traveldiary.util.ReverseGeocoder
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.util.LocationHelper
import net.jonesie.traveldiary.util.TripTimeWindow
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
    var reason by remember { mutableStateOf<String?>(null) }
    // Who it happened to: the diary's owner, and/or any companions by name. A new incident starts as "Me".
    var involvesMe by remember { mutableStateOf(true) }
    var people by remember { mutableStateOf(setOf<String>()) }
    var newPerson by remember { mutableStateOf("") }
    val knownCompanions by viewModel.companions.collectAsState()
    val reasonChoices by viewModel.incidentReasons.collectAsState()
    var notes by remember { mutableStateOf("") }
    var timestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tagLocation by remember { mutableStateOf(incidentId == null) }
    // A place chosen on the map, which beats the other ways of placing it. Null until the user picks one.
    var pickedPlace by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selectedTripId by remember { mutableStateOf(tripId) }
    val trips by viewModel.trips.collectAsState()
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
                involvesMe = it.involvesMe
                people = viewModel.incidentPeopleNames(it.id).toSet()
                notes = it.notes
                timestamp = it.timestamp
                selectedTripId = it.tripId
            }
        }
    }

    // A picked trip only takes incidents from its own dates.
    val tripWindow = trips.firstOrNull { it.id == selectedTripId }?.let { TripTimeWindow.of(it) }
    val outsideTrip = tripWindow != null && timestamp !in tripWindow

    // Tagging "where I am now" only makes sense for something that just happened.
    val recent = System.currentTimeMillis() - timestamp < 3 * 60 * 60_000L
    val zone = ZoneId.systemDefault()
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zone)

    LaunchedEffect(pickedPlace) {
        val p = pickedPlace
        pickedName = if (p == null) null else withContext(Dispatchers.IO) {
            runCatching { ReverseGeocoder.lookup(context, p.first, p.second)?.format(false) }.getOrNull()
        }
    }

    // Where the place will come from if it isn't tagged with the current location.
    var placeFit by remember { mutableStateOf<IncidentPlace.Fit?>(null) }
    LaunchedEffect(selectedTripId, timestamp) {
        placeFit = selectedTripId?.let { viewModel.placeIncident(it, timestamp) }
    }

    fun save() {
        val chosen = category ?: return
        saving = true
        scope.launch {
            val current = if (pickedPlace == null && recent && tagLocation && LocationHelper.hasPermission(context)) {
                LocationHelper.lastKnownLocation(context)?.let { it.latitude to it.longitude }
            } else null
            // The place the trip's own route and legs suggest, for when nothing was chosen or tagged.
            val fit = selectedTripId?.let { viewModel.placeIncident(it, timestamp) }
            val place = IncidentPlace.choose(
                picked = pickedPlace, current = current,
                existing = existing?.let { e -> if (e.lat != null && e.lon != null) e.lat to e.lon else null },
                fit = fit
            )
            val lat = place?.first
            val lon = place?.second
            val saved = (existing ?: Incident(category = chosen, timestamp = timestamp)).copy(
                tripId = selectedTripId,
                // A leg belongs to its trip, so moving the incident to another trip drops it.
                legId = fit?.leg?.id ?: if (selectedTripId == existing?.tripId) existing?.legId else null,
                category = chosen, severity = severity, notes = notes.trim(), timestamp = timestamp, lat = lat, lon = lon,
                reason = reason, involvesMe = involvesMe
            )
            val named = (people + newPerson.trim()).filter { it.isNotEmpty() }
            if (existing == null) viewModel.addIncident(saved, named) else viewModel.updateIncident(saved, named)
            Toast.makeText(context, "Incident saved", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { save() }

    if (picking) {
        BackHandler { picking = false }
        IncidentMapPicker(
            tripId = selectedTripId,
            viewModel = viewModel,
            initial = pickedPlace ?: existing?.let { e -> if (e.lat != null && e.lon != null) e.lat to e.lon else null },
            onDone = { lat, lon ->
                pickedPlace = lat to lon
                tagLocation = false
                picking = false
            },
            onCancel = { picking = false }
        )
        return
    }

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
            modifier = Modifier.fillMaxSize().padding(padding).contentWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("When?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp)))
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp)))
                }
            }


            if (outsideTrip && tripWindow != null) {
                val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
                Text(
                    "That's outside the trip's dates (${fmt.format(Date(tripWindow.first))} to ${fmt.format(Date(tripWindow.last))}). " +
                        "Change the date, or pick a different trip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Text("Trip (optional)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            var tripMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = tripMenu, onExpandedChange = { tripMenu = it }) {
                OutlinedTextField(
                    value = trips.firstOrNull { it.id == selectedTripId }?.title ?: "",
                    onValueChange = {},
                    readOnly = true,
                    placeholder = { Text("Leave blank to match by date") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tripMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = tripMenu, onDismissRequest = { tripMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Leave blank", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { selectedTripId = null; tripMenu = false }
                    )
                    trips.forEach { option ->
                        DropdownMenuItem(text = { Text(option.title) }, onClick = { selectedTripId = option.id; tripMenu = false })
                    }
                }
            }
            Text(
                "Blank, it joins whichever trip covers that date once the trip is imported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text("What happened?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
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

            Text("Who was involved?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = involvesMe, onClick = { involvesMe = !involvesMe }, label = { Text("Me") })
                // Everyone already in the diary, plus anyone typed in below that isn't yet.
                (knownCompanions.map { it.name } + people).distinct().sorted().forEach { name ->
                    FilterChip(
                        selected = name in people,
                        onClick = { people = if (name in people) people - name else people + name },
                        label = { Text(name) }
                    )
                }
            }
            OutlinedTextField(
                value = newPerson,
                onValueChange = { newPerson = it },
                label = { Text("Someone else") },
                singleLine = true,
                trailingIcon = {
                    TextButton(
                        enabled = newPerson.isNotBlank(),
                        onClick = { people = people + newPerson.trim(); newPerson = "" }
                    ) { Text("Add") }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Text("Reason (optional)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            var reasonMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = reasonMenu, onExpandedChange = { reasonMenu = it }) {
                OutlinedTextField(
                    value = reason ?: "",
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
                    // A reason that has since been removed from the list still shows on the incident that used it.
                    (reasonChoices + listOfNotNull(reason)).distinct().forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { reason = option; reasonMenu = false })
                    }
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
                    Checkbox(checked = tagLocation, onCheckedChange = { tagLocation = it; if (it) pickedPlace = null })
                    Text("Tag with my current location")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { picking = true }) { Text(if (pickedPlace == null) "Choose on map" else "Move on map") }
                if (pickedPlace != null) {
                    TextButton(onClick = { pickedPlace = null }) { Text("Clear") }
                }
            }
            pickedPlace?.let { p ->
                Text(
                    "Placed on the map" + (pickedName?.let { " near $it" } ?: ": %.4f, %.4f".format(p.first, p.second)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (pickedPlace == null && !(recent && tagLocation)) {
                Text(
                    placeNote(selectedTripId != null, existing?.lat != null, placeFit),
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
                enabled = category != null && !saving && !outsideTrip,
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

/** Says where an incident's place will come from when it isn't tagged with the current location. */
internal fun placeNote(hasTrip: Boolean, hasOwnPlace: Boolean, fit: IncidentPlace.Fit?): String {
    if (hasOwnPlace) return "It keeps the location it already has."
    if (!hasTrip) return "Without a trip, the place is worked out from its route and legs once a trip covers that time."
    if (fit == null) return "Working out the place from the trip…"
    fun legName(): String {
        val leg = fit.leg ?: return "a leg"
        val from = leg.originName.ifBlank { null }
        val to = leg.destinationName.ifBlank { null }
        return if (from != null && to != null) "$from → $to" else "a leg"
    }
    return when (fit.source) {
        IncidentPlace.Source.TRAIL -> "Placed on your trip's route at that time."
        IncidentPlace.Source.LEG -> "Placed along your leg ${legName()}, judged from its times."
        IncidentPlace.Source.NEAREST_LEG -> "No leg covers that time, so it's placed at the end of the nearest one (${legName()})."
        IncidentPlace.Source.NONE ->
            if (fit.leg != null) "That leg has no place to use, so the incident is saved without a location."
            else "Nothing on the trip fits that time, so the incident is saved without a location."
    }
}
