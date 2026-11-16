package net.jonesie.traveldiary.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class VariantsTest {

    @Test
    fun aSingleTakeIsAlwaysThatTake() {
        assertEquals(0, pickVariant(Random(1), 1, null))
        assertEquals(0, pickVariant(Random(1), 1, 0))
    }

    @Test
    fun twoTakesAlternateBecauseNeverTheSameTwice() {
        var previous = pickVariant(Random(3), 2, null)
        repeat(50) {
            val next = pickVariant(Random(it), 2, previous)
            assertNotEquals(previous, next)
            previous = next
        }
    }

    @Test
    fun theFirstTimeEitherTakeCanComeUp() {
        val heard = (0 until 100).map { pickVariant(Random(it), 2, null) }.toSet()
        assertEquals(setOf(0, 1), heard)
    }

    @Test
    fun withSeveralTakesAllGetUsed() {
        val random = Random(9)
        val heard = mutableSetOf<Int>()
        var previous: Int? = null
        repeat(200) { previous = pickVariant(random, 4, previous).also { heard += it } }
        assertEquals(setOf(0, 1, 2, 3), heard)
    }

    @Test
    fun noTakesIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { pickVariant(Random(1), 0, null) }
    }
}
