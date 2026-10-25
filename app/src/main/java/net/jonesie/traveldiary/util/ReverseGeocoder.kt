package net.jonesie.traveldiary.util

import android.content.Context
import android.location.Geocoder
import java.util.Locale

data class PlaceInfo(
    val landmark: String?,
    val locality: String?,
    val region: String?,
    val country: String?,
    val countryCode: String?
) {
    /** Local places show just the town/suburb; the country is only added for overseas places. */
    fun format(overseas: Boolean): String? {
        val place = locality ?: region
        val parts = if (overseas) listOfNotNull(landmark, place, country) else listOfNotNull(landmark, place)
        return parts.distinct().joinToString(", ").ifBlank { null }
    }
}

/**
 * Best-effort place lookup. Needs network; callers should fall back to raw
 * coordinates when this returns null (offline, no result, or Geocoder unavailable).
 */
object ReverseGeocoder {

    @Suppress("DEPRECATION")
    fun lookup(context: Context, lat: Double, lon: Double): PlaceInfo? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lon, 1)
                ?.firstOrNull()
                ?.let { address ->
                    PlaceInfo(
                        // featureName is just the bare house/street number (e.g. "42") when
                        // there's no named landmark — not a useful "place name", so drop it.
                        landmark = address.featureName?.takeIf {
                            it != address.thoroughfare && it != address.locality && !it.matches(Regex("^\\d+[A-Za-z]?$"))
                        },
                        locality = address.locality,
                        region = address.adminArea,
                        country = address.countryName,
                        countryCode = address.countryCode
                    )
                }
        }.getOrNull()
    }
}
