package net.jonesie.traveldiary.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.ui.graphics.vector.ImageVector
import net.jonesie.traveldiary.data.MapNoteCategory

fun MapNoteCategory.label(): String = when (this) {
    MapNoteCategory.HAZARD -> "Hazard"
    MapNoteCategory.ROAD -> "Road condition"
    MapNoteCategory.FUEL -> "Fuel"
    MapNoteCategory.FOOD -> "Food and drink"
    MapNoteCategory.VIEW -> "View"
    MapNoteCategory.OTHER -> "Other"
}

fun MapNoteCategory.icon(): ImageVector = when (this) {
    MapNoteCategory.HAZARD -> Icons.Filled.ReportProblem
    MapNoteCategory.ROAD -> Icons.Filled.Terrain
    MapNoteCategory.FUEL -> Icons.Filled.LocalGasStation
    MapNoteCategory.FOOD -> Icons.Filled.Restaurant
    MapNoteCategory.VIEW -> Icons.Filled.Panorama
    MapNoteCategory.OTHER -> Icons.Filled.Info
}
