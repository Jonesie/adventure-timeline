package net.jonesie.traveldiary.util

import android.annotation.SuppressLint
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

    /**
     * A recent fix if there is one, otherwise asks for a fresh one; falls back to the last known
     * location if that fails. [onResult] runs on the main thread, with null if nothing is available.
     */
    @SuppressLint("MissingPermission") // hasPermission() is checked first
    fun locate(context: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) {
            onResult(null)
            return
        }
        val last = lastKnownLocation(context)
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000) {
            onResult(last)
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider: String? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            onResult(last)
            return
        }
        val consumer = androidx.core.util.Consumer<Location?> { fresh -> onResult(fresh ?: last) }
        try {
            androidx.core.location.LocationManagerCompat.getCurrentLocation(
                manager, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(context), consumer
            )
        } catch (e: Exception) {
            onResult(last)
        }
    }

    @SuppressLint("MissingPermission") // hasPermission() is checked first
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
