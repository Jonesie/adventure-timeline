package net.jonesie.traveldiary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jonesie.traveldiary.util.DiaryBackup
import net.jonesie.traveldiary.util.DuplicateCopies
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: TravelDiaryViewModel, onBack: () -> Unit) {
    val progress by viewModel.backupProgress.collectAsState()
    val backupSettings by viewModel.backupSettings.collectAsState()
    var showReminderOptions by remember { mutableStateOf(false) }
    var includeCameraRoll by remember { mutableStateOf(false) }
    var showBackupOptions by remember { mutableStateOf(false) }
    var estimate by remember { mutableStateOf<DiaryBackup.Estimate?>(null) }
    LaunchedEffect(showBackupOptions) {
        if (showBackupOptions) {
            includeCameraRoll = false
            estimate = viewModel.backupEstimate()
        }
    }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var copyMatches by remember { mutableStateOf<List<DuplicateCopies.Match>?>(null) }
    val scope = rememberCoroutineScope()

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, includeCameraRoll) { result = it }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restoreBackup(uri) { result = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Backup")
            SettingRow(
                icon = { Icon(Icons.Filled.Save, contentDescription = null) },
                title = "Back up diary",
                subtitle = "Save every trip, route, note and attached file to one zip. Put it on Drive or your computer.\n" +
                    (backupSettings.lastBackup?.let { "Last backed up ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" }
                        ?: "Never backed up"),
                onClick = { showBackupOptions = true }
            )
            SettingRow(
                icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                title = "Backup reminder",
                subtitle = if (backupSettings.reminderDays == 0) "Off" else "Remind me every ${backupSettings.reminderDays} days",
                onClick = { showReminderOptions = true }
            )
            SettingRow(
                icon = { Icon(Icons.Filled.Restore, contentDescription = null) },
                title = "Restore from backup",
                subtitle = "Replace the diary with a backup made by this app.",
                onClick = { confirmRestore = true }
            )

            SectionHeader("Data")
            SettingRow(
                icon = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
                title = "Free up space",
                subtitle = "Find photos and videos the diary holds a second copy of, although the original is still in your gallery, and link to the original instead.",
                onClick = {
                    scope.launch {
                        val found = viewModel.findDuplicateCopies()
                        if (found.isEmpty()) result = "No duplicate copies found." else copyMatches = found
                    }
                }
            )
            SettingRow(
                icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = "Delete all trips",
                subtitle = "Removes every trip, leg, photo, audio and impression.",
                onClick = { confirmDeleteAll = true }
            )
        }
    }

    if (showBackupOptions) {
        AlertDialog(
            onDismissRequest = { showBackupOptions = false },
            title = { Text("Back up diary") },
            text = {
                Column {
                    Text("Trips, routes, legs, ratings, notes, voice notes and photos or videos you took or imported through the app are always included.")
                    estimate?.let {
                        Text(
                            "Backup size: about " + sizeText(it.appFilesBytes + if (includeCameraRoll) it.cameraRollBytes else 0L) + ".",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { includeCameraRoll = !includeCameraRoll }.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = includeCameraRoll, onCheckedChange = { includeCameraRoll = it })
                        Text(
                            "Also copy camera-roll photos and videos added to trips" +
                                (estimate?.let { " (adds " + sizeText(it.cameraRollBytes) + ")" } ?: "") +
                                ". They are normally left in your gallery."
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackupOptions = false
                    createBackup.launch("travel-diary-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip")
                }) { Text("Choose where to save") }
            },
            dismissButton = { TextButton(onClick = { showBackupOptions = false }) { Text("Cancel") } }
        )
    }

    if (showReminderOptions) {
        AlertDialog(
            onDismissRequest = { showReminderOptions = false },
            title = { Text("Backup reminder") },
            text = {
                Column {
                    listOf(7 to "Every 7 days", 14 to "Every 14 days", 30 to "Every 30 days", 0 to "Off").forEach { (days, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.setReminderDays(days)
                                showReminderOptions = false
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = backupSettings.reminderDays == days, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showReminderOptions = false }) { Text("Close") } }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore from backup?") },
            text = { Text("This replaces everything currently in the diary with the backup's contents. Anything added since the backup is lost. Back up first if unsure.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                }) { Text("Choose backup file") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } }
        )
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
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }

    copyMatches?.let { matches ->
        val gb = matches.sumOf { it.bytes } / 1_000_000_000.0
        AlertDialog(
            onDismissRequest = { copyMatches = null },
            title = { Text("Free up space?") },
            text = {
                Text(
                    "${matches.size} files (%.1f GB) in the diary are exact copies of originals still in your gallery. ".format(gb) +
                        "The diary will link to the originals and the copies will be deleted. " +
                        "If you later delete an original from your gallery, it will disappear from the diary too — so back up first if you plan to."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    copyMatches = null
                    viewModel.replaceCopiesWithLinks(matches) { freed ->
                        result = "Freed %.1f GB.".format(freed / 1_000_000_000.0)
                    }
                }) { Text("Replace copies") }
            },
            dismissButton = { TextButton(onClick = { copyMatches = null }) { Text("Cancel") } }
        )
    }

    progress?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Please wait") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(message)
                }
            },
            confirmButton = {}
        )
    }

    result?.let { message ->
        AlertDialog(
            onDismissRequest = { result = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } }
        )
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    else -> "under 1 MB"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
