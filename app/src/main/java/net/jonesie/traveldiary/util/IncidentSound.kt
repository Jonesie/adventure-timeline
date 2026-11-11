package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.IncidentCategory

/**
 * The sound effects played when the trip animation reaches an incident. Clips live in `res/raw` (see
 * `tools/generate_sfx.py`); which one belongs to which category is decided here, away from Android, so it can be tested.
 */
enum class IncidentSound { CRASH, SCREECH, SPLASH, SPUTTER, THUNDER, ALERT, SCREAM, SIREN, SCREAM_SPLASH }

fun IncidentCategory.sound(): IncidentSound = when (this) {
    IncidentCategory.OFF_ROAD, IncidentCategory.CRASH -> IncidentSound.CRASH
    IncidentCategory.FELL_OFF -> IncidentSound.SCREAM
    IncidentCategory.NEAR_MISS -> IncidentSound.SCREECH
    IncidentCategory.WATER -> IncidentSound.SCREAM_SPLASH
    IncidentCategory.INJURY -> IncidentSound.SIREN
    IncidentCategory.STUCK, IncidentCategory.BREAKDOWN, IncidentCategory.FLAT_TYRE, IncidentCategory.OUT_OF_FUEL -> IncidentSound.SPUTTER
    IncidentCategory.WEATHER -> IncidentSound.THUNDER
    IncidentCategory.LOST, IncidentCategory.ROAD_CLOSED, IncidentCategory.GEAR,
    IncidentCategory.WILDLIFE, IncidentCategory.DELAY, IncidentCategory.OTHER -> IncidentSound.ALERT
}
