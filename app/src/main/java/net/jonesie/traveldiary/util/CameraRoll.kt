package net.jonesie.traveldiary.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import net.jonesie.traveldiary.data.MediaType

/** Finds photos and videos taken within a time window by this phone's own camera (DCIM/Camera only). */
object CameraRoll {

    data class Item(val uri: Uri, val type: MediaType, val takenAt: Long)

    fun find(context: Context, fromMs: Long, toMs: Long): List<Item> =
        (query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.PHOTO, fromMs, toMs) +
            query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, fromMs, toMs))
            .sortedBy { it.takenAt }

    private fun query(context: Context, base: Uri, type: MediaType, fromMs: Long, toMs: Long): List<Item> {
        val (pathClause, pathArg) = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to "DCIM/Camera%"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?" to "%/DCIM/Camera/%"
        }
        val selection = "${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ? AND $pathClause"
        val args = arrayOf(fromMs.toString(), toMs.toString(), pathArg)

        val items = mutableListOf<Item>()
        context.contentResolver.query(
            base,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN),
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_TAKEN} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            while (cursor.moveToNext()) {
                items.add(Item(ContentUris.withAppendedId(base, cursor.getLong(idCol)), type, cursor.getLong(takenCol)))
            }
        }
        return items
    }
}
