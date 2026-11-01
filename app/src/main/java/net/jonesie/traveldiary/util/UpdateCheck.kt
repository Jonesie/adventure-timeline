package net.jonesie.traveldiary.util

import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** What the latest published release is, and whether this install is behind it. */
sealed interface UpdateResult {
    data class UpToDate(val version: String) : UpdateResult
    data class Available(val version: String, val releaseUrl: String) : UpdateResult
    /** No network, or GitHub didn't answer sensibly. */
    data object Failed : UpdateResult
}

/**
 * Asks GitHub for the latest release tag. Only ever run when the user taps "Check for updates";
 * nothing is checked in the background.
 */
object UpdateCheck {
    private const val LATEST_URL = "https://api.github.com/repos/Jonesie/adventure-timeline/releases/latest"

    /** Dotted numbers compared part by part, ignoring a leading "v": "0.0.10" is newer than "0.0.9". */
    internal fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (conn.responseCode != 200) return@runCatching UpdateResult.Failed
                var tag: String? = null
                var page: String? = null
                JsonReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "tag_name" -> tag = r.nextString()
                            "html_url" -> page = r.nextString()
                            else -> r.skipValue()
                        }
                    }
                }
                val latest = tag?.removePrefix("v") ?: return@runCatching UpdateResult.Failed
                if (isNewer(latest, currentVersion)) UpdateResult.Available(latest, page ?: "https://github.com/Jonesie/adventure-timeline/releases")
                else UpdateResult.UpToDate(currentVersion)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(UpdateResult.Failed)
    }
}
