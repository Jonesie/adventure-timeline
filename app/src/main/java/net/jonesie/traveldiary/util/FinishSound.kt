package net.jonesie.traveldiary.util

import kotlin.random.Random

/**
 * The little celebration when the trip animation reaches the end. One is picked at random each time; clips live in
 * `res/raw` (see `tools/generate_sfx.py`).
 */
enum class FinishSound {
    TA_DA, DRUM_ROLL, FLOURISH, CROWD_CHEER;

    companion object {
        /** A random one, never the same as [previous] so replaying the trip doesn't repeat itself. */
        fun pick(random: Random, previous: FinishSound?): FinishSound {
            val options = entries.filter { it != previous }
            return options[random.nextInt(options.size)]
        }
    }
}
