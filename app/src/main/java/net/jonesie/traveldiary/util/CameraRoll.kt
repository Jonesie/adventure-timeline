package net.jonesie.traveldiary.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import net.jonesie.traveldiary.data.MediaType

/** Finds photos and videos on the phone, optionally limited to a time window and/or the camera folder. */
object CameraRoll {

    data class Item(val uri: Uri, val type: MediaType, val takenAt: Long, val size: Long = 0)

    /** Photos and videos this phone's own camera (DCIM/Camera) took within the window. */
    fun find(context: Context, fromMs: Long, toMs: Long): List<Item> =
        find(context, fromMs..toMs, cameraOnly = true)

    /** [range] null means any date; [cameraOnly] false means any folder. */
    fun find(context: Context, range: LongRange?, cameraOnly: Boolean, newestFirst: Boolean = false): List<Item> {
        val items = query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.PHOTO, range, cameraOnly, newestFirst) +
            query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, range, cameraOnly, newestFirst)
        return if (newestFirst) items.sortedByDescending { it.takenAt } else items.sortedBy { it.takenAt }
    }

    private fun query(
        context: Context,
        base: Uri,
        type: MediaType,
        range: LongRange?,
        cameraOnly: Boolean,
        newestFirst: Boolean
    ): List<Item> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (range != null) {
            clauses += "${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ?"
            args += range.first.toString()
            args += range.last.toString()
        }
        if (cameraOnly) {
            if (Build.VERSION.SDK_INT >= 29) {
                clauses += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                args += "DCIM/Camera%"
            } else {
                clauses += "${MediaStore.MediaColumns.DATA} LIKE ?"
                args += "%/DCIM/Camera/%"
            }
        }

        val items = mutableListOf<Item>()
        context.contentResolver.query(
            base,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.SIZE),
            clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_TAKEN} ${if (newestFirst) "DESC" else "ASC"}"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                items.add(Item(ContentUris.withAppendedId(base, cursor.getLong(idCol)), type, cursor.getLong(takenCol), cursor.getLong(sizeCol)))
            }
        }
        return items
    }
}
