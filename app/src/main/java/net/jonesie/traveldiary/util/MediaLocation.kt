package net.jonesie.traveldiary.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType

/** Where a photo or video was taken, read from the file's own metadata. */
object MediaLocation {

    data class LatLon(val lat: Double, val lon: Double)

    /**
     * Gallery items (MediaStore) have their location stripped unless the app holds
     * ACCESS_MEDIA_LOCATION, so this is worth asking for once.
     */
    fun needsPermission(context: Context, item: MediaItem): Boolean =
        Build.VERSION.SDK_INT >= 29 && item.type != MediaType.AUDIO && item.lat == null &&
            Uri.parse(item.uri).authority == MediaStore.AUTHORITY &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED

    fun read(context: Context, item: MediaItem): LatLon? {
        if (item.lat != null && item.lon != null) return LatLon(item.lat, item.lon)
        if (item.type == MediaType.AUDIO) return null
        val uri = Uri.parse(item.uri)
        val original = originalUri(context, uri)
        return runCatching { fromFile(context, original, item.type) }.getOrNull()
            ?: if (original != uri) runCatching { fromFile(context, uri, item.type) }.getOrNull() else null
    }

    private fun originalUri(context: Context, uri: Uri): Uri =
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri

    private fun fromFile(context: Context, uri: Uri, type: MediaType): LatLon? =
        when (type) {
            MediaType.PHOTO -> context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = FloatArray(2)
                if (ExifInterface(stream).getLatLong(out)) LatLon(out[0].toDouble(), out[1].toDouble()) else null
            }
            MediaType.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    // ISO 6709, e.g. "-41.2865+174.7762/" (some phones add altitude).
                    val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    raw?.let { Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(it) }
                        ?.let { LatLon(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
                } finally {
                    retriever.release()
                }
            }
            MediaType.AUDIO -> null
        }
}
