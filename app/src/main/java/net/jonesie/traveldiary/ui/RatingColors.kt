package net.jonesie.traveldiary.ui

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// 1 star = dark red ... 5 stars = yellow. 0 (unrated) has no colour and uses the default card.
private val ratingColors = listOf(
    Color(0xFF8E1B1B),
    Color(0xFFC8461C),
    Color(0xFFE8832A),
    Color(0xFFF2B91F),
    Color(0xFFFFE03A)
)

fun ratingColor(rating: Int): Color? = ratingColors.getOrNull(rating - 1)

/** White text on the dark end of the scale, black on the bright end, whichever is readable. */
fun ratingContentColor(rating: Int): Color? =
    ratingColor(rating)?.let { if (it.luminance() < 0.25f) Color.White else Color.Black }

@Composable
fun ratingCardColors(rating: Int): CardColors {
    val container = ratingColor(rating) ?: return CardDefaults.cardColors()
    return CardDefaults.cardColors(containerColor = container, contentColor = ratingContentColor(rating)!!)
}

fun starString(rating: Int): String = "★".repeat(rating) + "☆".repeat(5 - rating)
