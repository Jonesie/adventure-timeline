package net.jonesie.traveldiary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import net.jonesie.traveldiary.data.Companion

/** "2 trips, 1 incident", or "Not on any trip or incident". */
internal fun describeCompanionUsage(usage: CompanionUsage?): String {
    val trips = usage?.trips ?: 0
    val incidents = usage?.incidents ?: 0
    val parts = listOfNotNull(
        if (trips > 0) "$trips ${if (trips == 1) "trip" else "trips"}" else null,
        if (incidents > 0) "$incidents ${if (incidents == 1) "incident" else "incidents"}" else null
    )
    return if (parts.isEmpty()) "Not on any trip or incident" else parts.joinToString(", ")
}

/** Everyone who has been named as a companion, each with a delete button that asks first. */
@Composable
fun CompanionsDialog(viewModel: TravelDiaryViewModel, onDismiss: () -> Unit) {
    val companions by viewModel.companions.collectAsState()
    val usage by viewModel.companionUsage.collectAsState()
    var pendingDelete by remember { mutableStateOf<Companion?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Companions") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Everyone you've named on a trip or incident. Deleting a companion removes them from every trip and incident. " +
                        "The trips and incidents themselves are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (companions.isEmpty()) Text("No companions yet.")
                companions.forEach { person ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(person.name)
                            Text(
                                describeCompanionUsage(usage[person.id]),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { pendingDelete = person }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${person.name}", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )

    pendingDelete?.let { person ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${person.name}?") },
            text = {
                val used = usage[person.id]
                Text(
                    if (used == null) "They aren't on any trip or incident, so nothing else changes."
                    else "They'll be removed from ${describeCompanionUsage(used)}. The trips and incidents stay, " +
                        "and any text you wrote in them is kept. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCompanion(person.id)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}
