package net.jonesie.traveldiary.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.util.ContactsHelper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTripScreen(
    onBack: () -> Unit,
    onSave: (title: String, notes: String, companions: List<String>, startDate: Long, endDate: Long?) -> Unit,
    initialTitle: String = "",
    initialNotes: String = "",
    initialCompanions: String = "",
    initialStartDate: Long = System.currentTimeMillis(),
    initialEndDate: Long? = null,
    screenTitle: String = "New trip",
    saveLabel: String = "Save trip"
) {
    var title by remember { mutableStateOf(initialTitle) }
    var notes by remember { mutableStateOf(initialNotes) }
    var companions by remember { mutableStateOf(initialCompanions) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var endDate by remember { mutableStateOf(initialEndDate) }

    // Suggest people from the phone's contacts while typing a companion's name.
    val context = LocalContext.current
    var hasContacts by remember { mutableStateOf(ContactsHelper.hasPermission(context)) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasContacts = it }
    val typing = companions.substringAfterLast(',').trim()
    var suggestions by remember { mutableStateOf<List<ContactsHelper.Contact>>(emptyList()) }
    LaunchedEffect(typing, hasContacts) {
        suggestions = if (hasContacts && typing.length >= 2) {
            withContext(Dispatchers.IO) { ContactsHelper.search(context, typing) }
                .filterNot { it.name.equals(typing, ignoreCase = true) }
        } else emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (e.g. Queenstown long weekend)") },
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            DateField(label = "Start date", millis = startDate, onPick = { startDate = it })
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            DateField(label = "End date (optional)", millis = endDate, onPick = { endDate = it })
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            OutlinedTextField(
                value = companions,
                onValueChange = { companions = it },
                label = { Text("Companions (comma-separated)") },
                modifier = Modifier.fillMaxWidth()
            )
            if (!hasContacts) {
                TextButton(onClick = { askContacts.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text("Suggest from my contacts")
                }
            } else if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    suggestions.forEach { contact ->
                        SuggestionChip(
                            onClick = {
                                val before = companions.substringBeforeLast(',', "").trim()
                                companions = (if (before.isEmpty()) "" else "$before, ") + contact.name + ", "
                            },
                            label = { Text(contact.name) },
                            icon = { Icon(Icons.Filled.Person, contentDescription = null) }
                        )
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
            Button(
                onClick = {
                    onSave(
                        title.trim(),
                        notes.trim(),
                        companions.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        startDate,
                        endDate
                    )
                },
                enabled = title.isNotBlank()
            ) {
                Text(saveLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, millis: Long?, onPick: (Long) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val display = millis?.let { DateFormat.getDateInstance().format(Date(it)) } ?: "Not set"

    Box {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { showDialog = true }
        )
    }

    if (showDialog) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(onPick)
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}
