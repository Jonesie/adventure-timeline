package net.jonesie.traveldiary.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaFiles {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun mediaDir(context: Context): File =
        File(context.getExternalFilesDir(null), "media").apply { mkdirs() }

    fun newPhotoUri(context: Context): Uri {
        val file = File(mediaDir(context), "photo_${stamp.format(Date())}.jpg")
        return FileProvider.getUriForFile(context, "net.jonesie.traveldiary.fileprovider", file)
    }

    fun newAudioFile(context: Context): File =
        File(mediaDir(context), "audio_${stamp.format(Date())}.m4a")

    fun audioUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "net.jonesie.traveldiary.fileprovider", file)
}
