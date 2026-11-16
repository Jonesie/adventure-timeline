package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentSoundTest {

    @Test
    fun everyCategoryHasASound() {
        IncidentCategory.entries.forEach { it.sound() }
    }

    @Test
    fun theObviousOnesSoundRight() {
        assertEquals(IncidentSound.CRASH, IncidentCategory.CRASH.sound())
        assertEquals(IncidentSound.SCREAM, IncidentCategory.FELL_OFF.sound())
        assertEquals(IncidentSound.SCREAM_SPLASH, IncidentCategory.WATER.sound())
        assertEquals(IncidentSound.SIREN, IncidentCategory.INJURY.sound())
        assertEquals(IncidentSound.THUNDER, IncidentCategory.WEATHER.sound())
        assertEquals(IncidentSound.SPUTTER, IncidentCategory.OUT_OF_FUEL.sound())
    }

    @Test
    fun differentKindsOfTroubleDontAllSoundTheSame() {
        assertTrue(IncidentCategory.entries.map { it.sound() }.toSet().size >= 6)
    }

    private fun incident(category: IncidentCategory, severity: Int) =
        Incident(category = category, severity = severity, timestamp = 0)

    @Test
    fun aSeriousIncidentAddsASirenAfterItsOwnSound() {
        val cues = incident(IncidentCategory.CRASH, severity = 3).cues()
        assertEquals(listOf(IncidentSound.CRASH, IncidentSound.SIREN), cues.map { it.sound })
        assertEquals(0L, cues[0].delayMs)
        assertEquals(IncidentSound.CRASH.durationMs, cues[1].delayMs)
    }

    @Test
    fun lessSeriousIncidentsGetOnlyTheirOwnSound() {
        (0..2).forEach { severity ->
            assertEquals(listOf(IncidentSound.CRASH), incident(IncidentCategory.CRASH, severity).cues().map { it.sound })
        }
    }

    @Test
    fun aSeriousInjuryIsNotASirenTwice() {
        assertEquals(listOf(IncidentSound.SIREN), incident(IncidentCategory.INJURY, severity = 3).cues().map { it.sound })
    }

    @Test
    fun theSirenAlwaysFinishesWhileTheIncidentIsStillOnScreen() {
        IncidentCategory.entries.forEach { category ->
            incident(category, severity = 3).cues().forEach { cue ->
                assertTrue("$category ${cue.sound}", cue.delayMs + cue.sound.durationMs <= TripPlayback.INCIDENT_DWELL_MS || cue.delayMs == 0L)
            }
        }
    }
}
