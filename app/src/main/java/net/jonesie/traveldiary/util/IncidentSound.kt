package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
import kotlin.math.min

/**
 * The sound effects played when the trip animation reaches an incident. Clips live in `res/raw` (see
 * `tools/generate_sfx.py`); which one belongs to which category is decided here, away from Android, so it can be tested.
 * [durationMs] is the length of the clip, so a second sound can be timed to follow it.
 */
enum class IncidentSound(val durationMs: Long) {
    CRASH(1100), SCREECH(1200), SPLASH(1300), SPUTTER(3200), THUNDER(2600), ALERT(750), SCREAM(1200), SIREN(2400),
    SCREAM_SPLASH(2400)
}

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

/** A sound to play [delayMs] after the incident is reached. */
data class IncidentCue(val sound: IncidentSound, val delayMs: Long)

/** Severity 3 on the incident screen. */
private const val SERIOUS = 3

/** The siren starts no later than this, so it has finished by the time the incident leaves the screen. */
private val SIREN_LEAD_MS = TripPlayback.INCIDENT_DWELL_MS - IncidentSound.SIREN.durationMs

/**
 * What to play for this incident: its category's sound and, if it was serious, a siren after it. An injury already
 * is a siren, so it isn't played twice.
 */
fun Incident.cues(): List<IncidentCue> {
    val first = category.sound()
    val cues = mutableListOf(IncidentCue(first, 0))
    if (severity >= SERIOUS && first != IncidentSound.SIREN) {
        cues += IncidentCue(IncidentSound.SIREN, min(first.durationMs, SIREN_LEAD_MS))
    }
    return cues
}
