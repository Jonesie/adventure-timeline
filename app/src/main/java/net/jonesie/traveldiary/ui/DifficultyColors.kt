package net.jonesie.traveldiary.ui

import androidx.compose.ui.graphics.Color

// 1 = easiest ... 5 = hardest. Level 0 (not rated) has no colour.
private val difficultyColors = listOf(
    Color(0xFF2E9E4F), // 1 green
    Color(0xFFA6CE39), // 2 lime
    Color(0xFFF5A623), // 3 amber
    Color(0xFFE8431C), // 4 red-orange
    Color(0xFF5B1A8B)  // 5 purple
)

fun difficultyColor(level: Int): Color? = difficultyColors.getOrNull(level - 1)
