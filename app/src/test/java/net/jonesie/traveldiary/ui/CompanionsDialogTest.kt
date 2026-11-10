package net.jonesie.traveldiary.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionsDialogTest {
    @Test fun unusedCompanion() {
        assertEquals("Not on any trip or incident", describeCompanionUsage(null))
        assertEquals("Not on any trip or incident", describeCompanionUsage(CompanionUsage(0, 0)))
    }

    @Test fun singularAndPlural() {
        assertEquals("1 trip", describeCompanionUsage(CompanionUsage(1, 0)))
        assertEquals("2 incidents", describeCompanionUsage(CompanionUsage(0, 2)))
        assertEquals("3 trips, 1 incident", describeCompanionUsage(CompanionUsage(3, 1)))
    }
}
