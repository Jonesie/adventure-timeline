package net.jonesie.traveldiary.util

import kotlin.random.Random

/**
 * Picks which of [count] takes of a sound to play: a random one, but never the same as [previous] (when there is more
 * than one), so hearing a scream twice in a row means two different screams.
 */
fun pickVariant(random: Random, count: Int, previous: Int?): Int {
    require(count > 0) { "no takes to pick from" }
    if (count == 1) return 0
    val options = (0 until count).filter { it != previous }
    return options[random.nextInt(options.size)]
}
