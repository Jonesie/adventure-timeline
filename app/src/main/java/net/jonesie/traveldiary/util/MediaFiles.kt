package net.jonesie.traveldiary.util

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MediaFiles {

    /** Must match the provider declared in the manifest, which is `${applicationId}.fileprovider`. */
    private fun authority(context: Context) = context.packageName + ".fileprovider"
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun mediaDir(context: Context): File =
        File(context.getExternalFilesDir(null), "media").apply { mkdirs() }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The file behind a diary entry, if the app owns it (i.e. it isn't a camera-roll reference). */
    fun ownedFile(context: Context, uriString: String): File? {
        val uri = Uri.parse(uriString)
        if (uri.authority != authority(context)) return null
        val relative = uri.path?.removePrefix("/") ?: return null
        if (!relative.startsWith("media/")) return null
        return File(context.getExternalFilesDir(null), relative)
    }

    fun newPhotoUri(context: Context): Uri {
        val file = File(mediaDir(context), "photo_${stamp.format(Date())}.jpg")
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    fun newVideoUri(context: Context): Uri {
        val file = File(mediaDir(context), "video_${stamp.format(Date())}.mp4")
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    fun newAudioFile(context: Context): File =
        File(mediaDir(context), "audio_${stamp.format(Date())}.m4a")

    fun audioUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    class CopiedFile(val uri: Uri, val mimeType: String?, val takenAt: Long?)

    /**
     * Copies a picked file into the app's own storage. Pickers hand out URIs that only work
     * for a short while, and the diary shouldn't lose a memory if the original is moved or deleted.
     */
    fun copyIntoApp(context: Context, source: Uri): CopiedFile {
        val resolver = context.contentResolver
        val mime = resolver.getType(source)
        val displayName = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: "bin"

        val dest = File(mediaDir(context), "import_${System.currentTimeMillis()}_${(0..9999).random()}.$extension")
        val input = resolver.openInputStream(source) ?: error("Couldn't open $source")
        input.use { stream -> dest.outputStream().use { out -> stream.copyTo(out) } }

        return CopiedFile(
            uri = FileProvider.getUriForFile(context, authority(context), dest),
            mimeType = mime,
            takenAt = takenAt(dest, mime)
        )
    }

    /** When the photo/video was taken, from its own metadata; null if it can't be read. */
    private fun takenAt(file: File, mime: String?): Long? = runCatching {
        when {
            mime?.startsWith("image/") == true -> {
                val raw = ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                raw?.let { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }
            }
            mime?.startsWith("video/") == true -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    raw?.substringBefore('.')?.removeSuffix("Z")?.let {
                        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                            .apply { timeZone = TimeZone.getTimeZone("UTC") }
                            .parse(it)?.time
                    }
                } finally {
                    retriever.release()
                }
            }
            else -> null
        }
    }.getOrNull()

    /** Deletes the file behind a diary entry, but only if the app created it (never a camera-roll file). */
    fun deleteIfOwned(context: Context, uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.authority != authority(context)) return
        val relative = uri.path?.removePrefix("/") ?: return
        if (!relative.startsWith("media/")) return
        File(context.getExternalFilesDir(null), relative).delete()
    }
}
