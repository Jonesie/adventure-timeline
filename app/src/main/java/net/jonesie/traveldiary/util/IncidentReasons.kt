package net.jonesie.traveldiary.util

import android.content.Context

/**
 * The choices offered in an incident's Reason dropdown. The user can edit the list in Settings; an
 * incident stores the reason's text, so changing or removing a choice never loses what was recorded.
 */
object IncidentReasons {
    val DEFAULTS = listOf(
        "Not my fault",
        "Situation beyond my capability",
        "Lack of focus",
        "Stupidity",
        "Rather not say",
        "Other"
    )

    private const val PREFS = "incident_reasons"
    private const val KEY = "list"

    fun load(context: Context): List<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return if (saved == null) DEFAULTS else saved.split('\n').filter { it.isNotBlank() }
    }

    fun save(context: Context, reasons: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, reasons.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))
            .apply()
    }

    /** What the earlier fixed list stored, by name, so old rows and old backups read as text. */
    fun fromLegacyName(value: String): String = when (value) {
        "NOT_MY_FAULT" -> DEFAULTS[0]
        "BEYOND_CAPABILITY" -> DEFAULTS[1]
        "LACK_OF_FOCUS" -> DEFAULTS[2]
        "STUPIDITY" -> DEFAULTS[3]
        "RATHER_NOT_SAY" -> DEFAULTS[4]
        "OTHER" -> DEFAULTS[5]
        else -> value
    }
}
