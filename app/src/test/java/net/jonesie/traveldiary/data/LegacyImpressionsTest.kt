package net.jonesie.traveldiary.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.DateFormat
import java.util.Date

class LegacyImpressionsTest {
    private fun stamp(t: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

    @Test
    fun impressionsBecomeDatedLinesOldestFirstAfterTheExistingNote() {
        val folded = foldLegacyImpressions("Great trip", listOf(2_000_000_000L to "Second ", 1_000_000_000L to "First"))
        assertEquals("Great trip\n\n${stamp(1_000_000_000L)}: First\n${stamp(2_000_000_000L)}: Second", folded)
    }

    @Test
    fun anEmptyNoteIsJustTheLines() {
        assertEquals("${stamp(1_000_000_000L)}: Only", foldLegacyImpressions("  ", listOf(1_000_000_000L to "Only")))
    }

    @Test
    fun blankImpressionsChangeNothing() {
        assertEquals("Keep", foldLegacyImpressions("Keep", listOf(5L to "   ")))
    }
}
