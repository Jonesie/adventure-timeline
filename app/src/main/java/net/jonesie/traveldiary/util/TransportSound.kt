package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.TransportType

/**
 * The sound that loops quietly while the trip animation travels a leg. Clips live in `res/raw` (see
 * `tools/generate_sfx.py`); which transport gets which is decided here, away from Android, so it can be tested.
 */
enum class TransportSound { WALK, BIKE, MOTORBIKE, CAR, FOUR_WD, BUS, TRAIN, PLANE, BOAT }

/** One-off sounds that punctuate a leg now and then, on top of its loop. */
enum class TransportAccent { FOG_HORN, SHIP_BELL }

/** Null for [TransportType.OTHER]: nothing is known about it, so it travels in silence. */
fun TransportType.ambient(): TransportSound? = when (this) {
    TransportType.WALK -> TransportSound.WALK
    TransportType.BIKE -> TransportSound.BIKE
    TransportType.MOTORBIKE -> TransportSound.MOTORBIKE
    TransportType.CAR -> TransportSound.CAR
    TransportType.FOUR_WD -> TransportSound.FOUR_WD
    TransportType.BUS -> TransportSound.BUS
    TransportType.TRAIN -> TransportSound.TRAIN
    TransportType.PLANE -> TransportSound.PLANE
    TransportType.BOAT -> TransportSound.BOAT
    TransportType.OTHER -> null
}

fun TransportType.accents(): List<TransportAccent> = when (this) {
    TransportType.BOAT -> listOf(TransportAccent.FOG_HORN, TransportAccent.SHIP_BELL)
    else -> emptyList()
}

/**
 * Decides when to sound an accent: every [intervalMs] of travelling on a transport that has them, taking them in
 * turn. The first comes a little sooner so a short leg still gets one.
 */
class AccentClock(private val intervalMs: Long = 7000) {
    // Starts part-way through the interval, so the first accent comes after 40% of it.
    private val headStartMs = intervalMs * 0.6
    private var elapsed = headStartMs
    private var next = 0

    fun tick(dtMs: Long, transport: TransportType?): TransportAccent? {
        val options = transport?.accents().orEmpty()
        if (options.isEmpty()) {
            elapsed = headStartMs
            return null
        }
        elapsed += dtMs
        if (elapsed < intervalMs) return null
        elapsed = 0.0
        return options[next++ % options.size]
    }
}
