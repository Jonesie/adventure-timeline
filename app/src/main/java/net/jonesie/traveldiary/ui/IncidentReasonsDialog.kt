package net.jonesie.traveldiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.util.IncidentReasons

/**
 * Edit the choices in an incident's Reason dropdown: rename, remove, add, or go back to the originals.
 * Renaming carries over to incidents that already use the reason; removing leaves them as recorded.
 */
@Composable
fun IncidentReasonsDialog(viewModel: TravelDiaryViewModel, onDismiss: () -> Unit) {
    val saved by viewModel.incidentReasons.collectAsState()
    // Each row is (the reason as it was saved, or null if new) to (what is typed now).
    var rows by remember { mutableStateOf(saved.map<String, Pair<String?, String>> { it to it }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Incident reasons") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "These are the choices in an incident's Reason dropdown. Renaming one updates the incidents that use it. " +
                        "Removing one leaves those incidents as they are.",
                    style = MaterialTheme.typography.bodySmall
                )
                rows.forEachIndexed { index, (original, current) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = current,
                            onValueChange = { typed -> rows = rows.toMutableList().also { it[index] = original to typed.replace("\n", " ") } },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove this reason")
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { rows = rows + (null to "") }) { Text("Add a reason") }
                    TextButton(onClick = { rows = IncidentReasons.DEFAULTS.map<String, Pair<String?, String>> { null to it } }) {
                        Text("Original list")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                rows.forEach { (original, current) ->
                    val typed = current.trim()
                    if (original != null && typed.isNotEmpty() && typed != original) viewModel.renameIncidentReason(original, typed)
                }
                viewModel.saveIncidentReasons(rows.map { it.second })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
