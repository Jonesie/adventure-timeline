package net.jonesie.traveldiary.util

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

/**
 * Plain LocationManager, not Play Services FusedLocationProvider — the "old wifi-only
 * phone" target device may not have Play Services installed at all.
 */
object LocationHelper {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun lastKnownLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manager.allProviders
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }
}

/** ~1km at mid latitudes, used as the "have I been here before" search radius. */
const val NEARBY_RADIUS_DEGREES_SQUARED = 0.01 * 0.01
