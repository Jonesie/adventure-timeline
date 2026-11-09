package net.jonesie.traveldiary.data

import java.text.DateFormat
import java.util.Date

/**
 * Impressions used to be separate timestamped notes. They now live in the trip's single note, so older
 * data and older backups are folded in: one "date: text" line each, oldest first, after whatever is already there.
 */
internal fun foldLegacyImpressions(notes: String, entries: List<Pair<Long, String>>): String {
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val lines = entries.sortedBy { it.first }
        .filter { it.second.isNotBlank() }
        .map { (time, text) -> format.format(Date(time)) + ": " + text.trim() }
    if (lines.isEmpty()) return notes
    return if (notes.isBlank()) lines.joinToString("\n") else notes.trimEnd() + "\n\n" + lines.joinToString("\n")
}
