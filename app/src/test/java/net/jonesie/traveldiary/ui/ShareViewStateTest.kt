package net.jonesie.traveldiary.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareViewStateTest {

    @Test
    fun defaultsMatchTheOriginalShareTab() {
        val state = ShareViewState(FakePrefs())
        assertTrue(state.includeDates)
        assertTrue(state.includeRating)
        assertTrue(state.includeRoute)
        assertFalse(state.includeCompanions)
        assertFalse(state.includeLegs)
        assertFalse(state.includeIncidents)
    }

    @Test
    fun togglesPersistAcrossRestarts() {
        val prefs = FakePrefs()
        val first = ShareViewState(prefs)
        first.includeDates = false
        first.includeCompanions = true
        first.includeIncidents = true

        val restarted = ShareViewState(prefs)
        assertFalse(restarted.includeDates)
        assertTrue(restarted.includeCompanions)
        assertTrue(restarted.includeIncidents)
        assertTrue(restarted.includeRating)
    }
}
