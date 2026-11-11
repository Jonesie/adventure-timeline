package net.jonesie.traveldiary.util

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
        assertEquals(IncidentSound.CRASH, IncidentCategory.FELL_OFF.sound())
        assertEquals(IncidentSound.SPLASH, IncidentCategory.WATER.sound())
        assertEquals(IncidentSound.THUNDER, IncidentCategory.WEATHER.sound())
        assertEquals(IncidentSound.SPUTTER, IncidentCategory.OUT_OF_FUEL.sound())
    }

    @Test
    fun differentKindsOfTroubleDontAllSoundTheSame() {
        assertTrue(IncidentCategory.entries.map { it.sound() }.toSet().size >= 5)
    }
}
