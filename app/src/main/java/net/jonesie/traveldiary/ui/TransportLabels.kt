package net.jonesie.traveldiary.ui

import net.jonesie.traveldiary.data.TransportType

fun TransportType.displayName(): String =
    if (this == TransportType.FOUR_WD) "4WD" else name.lowercase().replaceFirstChar { it.uppercase() }
