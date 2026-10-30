package net.jonesie.traveldiary.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WrongLocation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import net.jonesie.traveldiary.data.IncidentCategory

fun IncidentCategory.label(): String = when (this) {
    IncidentCategory.FELL_OFF -> "Fell off or tipped over"
    IncidentCategory.OFF_ROAD -> "Ran off the road"
    IncidentCategory.CRASH -> "Crash or collision"
    IncidentCategory.NEAR_MISS -> "Near miss"
    IncidentCategory.WATER -> "River or water mishap"
    IncidentCategory.STUCK -> "Got stuck"
    IncidentCategory.LOST -> "Got lost or wrong turn"
    IncidentCategory.BREAKDOWN -> "Breakdown"
    IncidentCategory.FLAT_TYRE -> "Flat tyre"
    IncidentCategory.OUT_OF_FUEL -> "Ran out of fuel"
    IncidentCategory.INJURY -> "Injury or illness"
    IncidentCategory.WEATHER -> "Bad weather"
    IncidentCategory.ROAD_CLOSED -> "Road closed or slip"
    IncidentCategory.GEAR -> "Gear lost or damaged"
    IncidentCategory.WILDLIFE -> "Wildlife encounter"
    IncidentCategory.DELAY -> "Delay or missed connection"
    IncidentCategory.OTHER -> "Other"
}

fun IncidentCategory.icon(): ImageVector = when (this) {
    IncidentCategory.FELL_OFF -> Icons.Filled.TrendingDown
    IncidentCategory.OFF_ROAD -> Icons.Filled.WrongLocation
    IncidentCategory.CRASH -> Icons.Filled.CarCrash
    IncidentCategory.NEAR_MISS -> Icons.Filled.ReportProblem
    IncidentCategory.WATER -> Icons.Filled.Waves
    IncidentCategory.STUCK -> Icons.Filled.PanTool
    IncidentCategory.LOST -> Icons.Filled.ExploreOff
    IncidentCategory.BREAKDOWN -> Icons.Filled.Build
    IncidentCategory.FLAT_TYRE -> Icons.Filled.TireRepair
    IncidentCategory.OUT_OF_FUEL -> Icons.Filled.LocalGasStation
    IncidentCategory.INJURY -> Icons.Filled.MedicalServices
    IncidentCategory.WEATHER -> Icons.Filled.Thunderstorm
    IncidentCategory.ROAD_CLOSED -> Icons.Filled.Block
    IncidentCategory.GEAR -> Icons.Filled.Backpack
    IncidentCategory.WILDLIFE -> Icons.Filled.Pets
    IncidentCategory.DELAY -> Icons.Filled.Schedule
    IncidentCategory.OTHER -> Icons.Filled.MoreHoriz
}

/** 0 = not set, 1 = minor, 2 = moderate, 3 = serious. */
fun severityLabel(severity: Int): String? = when (severity) {
    1 -> "Minor"
    2 -> "Moderate"
    3 -> "Serious"
    else -> null
}

/** Amber, orange, red: a glance at the list shows which ones hurt. */
fun severityColor(severity: Int): Color? = when (severity) {
    1 -> Color(0xFFE0B13A)
    2 -> Color(0xFFE8731A)
    3 -> Color(0xFFC62828)
    else -> null
}

