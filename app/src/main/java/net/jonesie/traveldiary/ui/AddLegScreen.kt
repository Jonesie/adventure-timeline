package net.jonesie.traveldiary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.data.TransportType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLegScreen(
    onBack: () -> Unit,
    onSave: (transportType: TransportType, origin: String, destination: String) -> Unit
) {
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var transportType by remember { mutableStateOf(TransportType.CAR) }
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add leg") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = transportType.displayName(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Transport") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    TransportType.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.displayName()) }, onClick = {
                            transportType = type
                            expanded = false
                        })
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            OutlinedTextField(
                value = origin,
                onValueChange = { origin = it },
                label = { Text("From") },
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("To") },
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
            Button(
                onClick = { onSave(transportType, origin.trim(), destination.trim()) },
                enabled = origin.isNotBlank() && destination.isNotBlank()
            ) {
                Text("Save leg")
            }
        }
    }
}
