package net.jonesie.traveldiary.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class FinishSoundTest {

    @Test
    fun neverRepeatsThePreviousOne() {
        val random = Random(42)
        var previous: FinishSound? = null
        repeat(500) {
            val next = FinishSound.pick(random, previous)
            assertNotEquals(previous, next)
            previous = next
        }
    }

    @Test
    fun eventuallyUsesEveryOne() {
        val random = Random(7)
        val heard = mutableSetOf<FinishSound>()
        var previous: FinishSound? = null
        repeat(200) { previous = FinishSound.pick(random, previous).also { heard += it } }
        assertEquals(FinishSound.entries.toSet(), heard)
    }

    @Test
    fun theFirstTimeAnyCanComeUp() {
        val random = Random(1)
        val heard = (1..200).map { FinishSound.pick(random, null) }.toSet()
        assertEquals(FinishSound.entries.toSet(), heard)
    }
}
