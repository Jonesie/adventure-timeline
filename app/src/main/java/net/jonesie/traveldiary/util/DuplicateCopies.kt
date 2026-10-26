package net.jonesie.traveldiary.util

import android.content.Context
import android.net.Uri
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * Finds photos/videos the diary holds its own copy of although the very same file is still in the
 * phone's gallery. Matching is by type and exact size, then confirmed by comparing the start,
 * middle and end of both files, so a copy is only ever replaced by an identical original.
 */
object DuplicateCopies {

    class Match(val mediaId: Long, val file: File, val galleryUri: Uri, val bytes: Long)

    private const val SAMPLE = 64 * 1024

    fun find(context: Context, media: List<MediaItem>): List<Match> {
        val gallery = CameraRoll.find(context, null, cameraOnly = false)
            .filter { it.size > 0 }
            .groupBy { it.type to it.size }
        val resolver = context.contentResolver
        val matches = mutableListOf<Match>()
        val claimed = HashSet<Uri>()

        for (item in media) {
            if (item.type == MediaType.AUDIO) continue
            val file = MediaFiles.ownedFile(context, item.uri)?.takeIf { it.isFile } ?: continue
            val candidates = gallery[item.type to file.length()] ?: continue
            val original = candidates.firstOrNull { it.uri !in claimed && sameContent(context, file, it.uri) } ?: continue
            claimed += original.uri
            matches += Match(item.id, file, original.uri, file.length())
        }
        return matches
    }

    private fun sameContent(context: Context, file: File, other: Uri): Boolean = runCatching {
        val size = file.length()
        context.contentResolver.openFileDescriptor(other, "r")?.use { pfd ->
            if (pfd.statSize in 0 until size) return@use false
            val otherChannel = FileInputStream(pfd.fileDescriptor).channel
            RandomAccessFile(file, "r").use { mine ->
                val offsets = listOf(0L, size / 2, size - minOf(size, SAMPLE.toLong())).distinct()
                offsets.all { offset ->
                    val n = minOf(SAMPLE.toLong(), size - offset).toInt()
                    val a = ByteArray(n)
                    mine.seek(offset)
                    mine.readFully(a)
                    val b = java.nio.ByteBuffer.allocate(n)
                    otherChannel.position(offset)
                    while (b.hasRemaining() && otherChannel.read(b) > 0) { /* fill */ }
                    b.position() == n && a.contentEquals(b.array())
                }
            }
        } ?: false
    }.getOrDefault(false)
}
