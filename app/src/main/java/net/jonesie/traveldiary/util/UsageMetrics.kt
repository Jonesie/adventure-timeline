package net.jonesie.traveldiary.util

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which features get used. Each one is only ever a count: never a trip title, place, name, photo or id of any kind.
 * [label] is what the person sees in Settings and in the feedback email, so it says what happened in plain words.
 */
enum class Metric(val key: String, val label: String) {
    SHARE_TAB_OPENED("share_tab", "Opened the Share tab"),
    SHARED_POST("share_post", "Shared a post"),
    SHARED_PICTURE("share_picture", "Shared a picture"),
    SHARED_PDF("share_pdf", "Shared a PDF"),
    SHARED_GPX("share_gpx", "Shared a GPX track"),
    SHARED_KML("share_kml", "Shared a KML track"),
    EXPORTED_TRIP_DATA("export_data", "Exported trip data"),
    PLAYED_TRIP("play_start", "Played a trip"),
    FINISHED_TRIP_PLAY("play_finish", "Watched a trip play to the end"),
    MUTED_PLAY_SOUND("play_mute", "Turned off sound effects while playing"),
    IMPORTED_TIMELINE("import_timeline", "Imported from Google Timeline"),
    IMPORTED_GPX("import_gpx", "Imported a GPX file"),
    ADDED_CAPTURED_MEDIA("media_captured", "Added a photo, video or recording made in the app"),
    ADDED_GALLERY_MEDIA("media_gallery", "Added photos or videos from the phone"),
    LOGGED_INCIDENT("incident", "Logged an incident"),
    ADDED_MAP_NOTE("map_note", "Added a map note"),
    BACKED_UP("backup", "Backed up the diary"),
    RESTORED("restore", "Restored from a backup")
}

/** Where the counts are kept. On the phone this is SharedPreferences; tests use a map. */
interface MetricsStore {
    fun getInt(key: String): Int
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun put(values: Map<String, Any?>)
    fun removeAll(keys: Collection<String>)
}

/**
 * Local-only counters for how often features are used. Nothing here touches the network: the counts stay on the
 * phone, can be looked at or cleared in Settings, and leave it only inside a feedback email the person chooses to
 * send with them included.
 */
class UsageMetrics(private val store: MetricsStore, private val today: () -> Long = System::currentTimeMillis) {

    /** Counting can be switched off, which also clears what was counted. */
    var enabled: Boolean
        get() = store.getBoolean(ENABLED, true)
        set(value) {
            if (!value) reset()
            store.put(mapOf(ENABLED to value))
        }

    /** Whether the feedback email gets the counts added to it. Off unless the person turns it on. */
    var includeInFeedback: Boolean
        get() = store.getBoolean(INCLUDE, false)
        set(value) = store.put(mapOf(INCLUDE to value))

    @Synchronized
    fun record(metric: Metric) {
        if (!enabled) return
        val updates = mutableMapOf<String, Any?>(PREFIX + metric.key to store.getInt(PREFIX + metric.key) + 1)
        if (store.getString(SINCE) == null) updates[SINCE] = today().toString()
        store.put(updates)
    }

    fun counts(): Map<Metric, Int> = Metric.values().associateWith { store.getInt(PREFIX + it.key) }

    /** When counting started, or null if nothing has been counted yet. */
    fun since(): Long? = store.getString(SINCE)?.toLongOrNull()

    @Synchronized
    fun reset() {
        store.removeAll(Metric.values().map { PREFIX + it.key } + SINCE)
    }

    /** What to show or send: only the things that have happened, with the date counting began. */
    fun report(): String = format(counts(), since())

    companion object {
        private const val PREFIX = "count_"
        private const val SINCE = "since"
        private const val ENABLED = "enabled"
        private const val INCLUDE = "include_in_feedback"

        @Volatile private var shared: UsageMetrics? = null

        /** Call once at start-up; until then [record] quietly does nothing. */
        fun init(context: Context) {
            if (shared == null) shared = UsageMetrics(PrefsStore(context.applicationContext.getSharedPreferences("usage_metrics", Context.MODE_PRIVATE)))
        }

        val instance: UsageMetrics? get() = shared

        /** Counts one use of a feature. Safe to call from anywhere. */
        fun record(metric: Metric) {
            shared?.record(metric)
        }

        fun format(counts: Map<Metric, Int>, since: Long?): String {
            val used = Metric.values().filter { (counts[it] ?: 0) > 0 }
            if (used.isEmpty()) return "Feature use: nothing counted yet."
            return buildString {
                append("Feature use (counts only")
                if (since != null) append(", since ").append(SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(since)))
                append("):")
                used.forEach { append("\n- ").append(it.label).append(": ").append(counts[it]) }
            }
        }
    }
}

private class PrefsStore(private val prefs: android.content.SharedPreferences) : MetricsStore {
    override fun getInt(key: String) = prefs.getInt(key, 0)
    override fun getString(key: String) = prefs.getString(key, null)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun put(values: Map<String, Any?>) {
        prefs.edit().apply {
            values.forEach { (key, value) ->
                when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    null -> remove(key)
                }
            }
        }.apply()
    }
    override fun removeAll(keys: Collection<String>) {
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }
}
