package net.jonesie.traveldiary.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class MainTab { MAP, LIST, INCIDENTS }

/** The top-level views: the all-trips map (opens first), the list of trips, and logged incidents. */
@Composable
fun MainTabBar(selected: MainTab, onMap: () -> Unit, onList: () -> Unit, onIncidents: () -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.MAP,
            onClick = onMap,
            icon = { Icon(Icons.Filled.Map, contentDescription = null) },
            label = { Text("Map") }
        )
        NavigationBarItem(
            selected = selected == MainTab.LIST,
            onClick = onList,
            icon = { Icon(Icons.Filled.ViewList, contentDescription = null) },
            label = { Text("Trips") }
        )
        NavigationBarItem(
            selected = selected == MainTab.INCIDENTS,
            onClick = onIncidents,
            icon = { Icon(Icons.Filled.ReportProblem, contentDescription = null) },
            label = { Text("Incidents") }
        )
    }
}
