package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSoundTest {

    @Test
    fun everyTransportButOtherHasItsOwnSound() {
        val sounds = TransportType.entries.filter { it != TransportType.OTHER }.map { it.ambient() }
        assertTrue(sounds.none { it == null })
        assertEquals(sounds.size, sounds.toSet().size)
        assertNull(TransportType.OTHER.ambient())
    }

    @Test
    fun onlyBoatsGetAccents() {
        assertEquals(listOf(TransportAccent.FOG_HORN, TransportAccent.SHIP_BELL), TransportType.BOAT.accents())
        TransportType.entries.filter { it != TransportType.BOAT }.forEach { assertTrue(it.accents().isEmpty()) }
    }

    @Test
    fun accentsComeInTurnAndNotBeforeTheirTime() {
        val clock = AccentClock(intervalMs = 1000)
        val heard = mutableListOf<TransportAccent>()
        var elapsed = 0L
        while (heard.size < 3 && elapsed < 20_000) {
            clock.tick(100, TransportType.BOAT)?.let { heard += it }
            elapsed += 100
        }
        assertEquals(listOf(TransportAccent.FOG_HORN, TransportAccent.SHIP_BELL, TransportAccent.FOG_HORN), heard)
        assertTrue("first came too soon: $elapsed", elapsed >= 400 + 1000 + 1000 - 100)
    }

    @Test
    fun theFirstAccentComesSoonerThanTheRest() {
        val clock = AccentClock(intervalMs = 1000)
        var first = 0L
        while (clock.tick(50, TransportType.BOAT) == null) first += 50
        var second = 0L
        while (clock.tick(50, TransportType.BOAT) == null) second += 50
        assertTrue("first $first, second $second", first < second)
    }

    @Test
    fun nothingSoundsOnTransportWithoutAccentsAndTheClockRestartsAfterwards() {
        val clock = AccentClock(intervalMs = 1000)
        repeat(100) { assertNull(clock.tick(100, TransportType.CAR)) }
        var wait = 0L
        while (clock.tick(50, TransportType.BOAT) == null) wait += 50
        assertTrue("was $wait", wait in 250..450)
    }
}
