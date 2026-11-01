package net.jonesie.traveldiary.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun newerPatch() = assertTrue(UpdateCheck.isNewer("0.0.6", "0.0.5"))
    @Test fun comparesNumbersNotText() = assertTrue(UpdateCheck.isNewer("0.0.10", "0.0.9"))
    @Test fun sameVersionIsNotNewer() = assertFalse(UpdateCheck.isNewer("v0.0.5", "0.0.5"))
    @Test fun olderIsNotNewer() = assertFalse(UpdateCheck.isNewer("0.0.4", "0.0.5"))
    @Test fun majorBeatsMinor() = assertTrue(UpdateCheck.isNewer("1.0", "0.9.9"))
    @Test fun missingPartsCountAsZero() = assertFalse(UpdateCheck.isNewer("1.0", "1.0.0"))
}
