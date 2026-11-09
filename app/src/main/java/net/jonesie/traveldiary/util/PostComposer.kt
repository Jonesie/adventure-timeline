package net.jonesie.traveldiary.util

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.TripDetail
import net.jonesie.traveldiary.ui.label
import net.jonesie.traveldiary.ui.severityLabel
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** What goes into the written part of a post. The title and message are whatever the Share tab shows. */
data class PostOptions(
    val title: String,
    val message: String,
    val includeDates: Boolean,
    val includeRating: Boolean,
    val includeRoute: Boolean,
    val includeLegs: Boolean,
    val includeIncidents: Boolean,
    val includeCompanions: Boolean,
    val includeNotes: Boolean,
    /** Places inside this area are written as "home" rather than by name. */
    val hidden: HiddenArea? = null
)

/** One block of a post. [accent] marks the incident highlights. */
class PostParagraph(val text: String, val accent: Boolean = false)

/** The written post in pieces, so it can be flattened to text or drawn as a picture. */
class PostContent(val title: String, val facts: String?, val paragraphs: List<PostParagraph>) {
    fun toText(): String {
        val out = StringBuilder(title)
        if (facts != null) out.append('\n').append(facts)
        paragraphs.forEach { out.append("\n\n").append(it.text) }
        return out.toString()
    }
}

/**
 * The trip as a social-media post: a picture of the written post with the map, then each photo as its own
 * picture, then a closing card. The same text is also offered as a plain-text caption.
 */
object PostComposer {

    private const val MAX_LEG_LINES = 15
    private const val PHOTO_MAX_SIDE = 1600

    /** Written post as plain text. [trailKm] is the recorded route length, or null when there is no GPS trail. */
    fun buildText(trip: TripDetail, options: PostOptions, trailKm: Double?, locale: Locale = Locale.getDefault()): String =
        build(trip, options, trailKm, locale).toText()

    fun build(trip: TripDetail, options: PostOptions, trailKm: Double?, locale: Locale = Locale.getDefault()): PostContent {
        val t = trip.trip
        val title = options.title.trim().ifEmpty { t.title }

        val facts = mutableListOf<String>()
        if (options.includeDates) facts += dateRange(t.startDate, t.endDate, locale)
        if (options.includeRoute) {
            dayCount(t.startDate, t.endDate).takeIf { it > 1 }?.let { facts += "$it days" }
            trailKm?.let { km -> distanceText(km)?.let { facts += it } }
            transportSummary(trip.legs).takeIf { it.isNotEmpty() }?.let { facts += it }
        }
        if (options.includeRating && t.rating > 0) facts += "★".repeat(t.rating) + "☆".repeat(5 - t.rating)

        val paragraphs = mutableListOf<PostParagraph>()
        if (options.message.isNotBlank()) paragraphs += PostParagraph(options.message.trim())

        val legLines = if (options.includeLegs) legLines(trip.legs, options.hidden) else emptyList()
        if (legLines.isNotEmpty()) paragraphs += PostParagraph("Legs:" + legLines.joinToString("") { "\n• $it" })
        if (options.includeIncidents && trip.incidents.isNotEmpty()) {
            val lines = trip.incidents.sortedBy { it.timestamp }.joinToString("") { "\n⚠ " + incidentLine(it) }
            paragraphs += PostParagraph("⚠ Incidents:$lines", accent = true)
        }
        if (options.includeCompanions && trip.companions.isNotEmpty()) {
            paragraphs += PostParagraph("With " + joinNames(trip.companions.map { it.name }))
        }
        val notes = cleanNotes(t.notes)
        if (options.includeNotes && notes.isNotEmpty()) paragraphs += PostParagraph(notes)
        return PostContent(title, facts.joinToString(" · ").ifEmpty { null }, paragraphs)
    }

    /** The text that goes out with a post; the Share tab's preview leaves this off. */
    fun withFooter(text: String): String = text + "\n\n" + AppLinks.footerText()

    /** "Got stuck (serious): wheel deep in mud". The reason and who else was involved stay private. */
    internal fun incidentLine(incident: Incident): String {
        val severity = severityLabel(incident.severity)?.lowercase()?.let { " ($it)" } ?: ""
        val notes = incident.notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return incident.category.label() + severity + if (notes.isNotEmpty()) ": $notes" else ""
    }

    /** One line per leg in time order: "Christchurch → Hanmer Springs · motorbike". Long trips keep the first [MAX_LEG_LINES]. */
    internal fun legLines(legs: List<Leg>, hidden: HiddenArea? = null): List<String> {
        val lines = legs.sortedBy { it.startTime }.mapNotNull { leg ->
            val from = if (hidden?.contains(leg.originLat, leg.originLon) == true) "home" else leg.originName.trim()
            val to = if (hidden?.contains(leg.destinationLat, leg.destinationLon) == true) "home" else leg.destinationName.trim()
            if (from.isEmpty() && to.isEmpty()) return@mapNotNull null
            val places = if (from.isEmpty() || from == to) to else if (to.isEmpty()) from else "$from → $to"
            val transport = if (leg.transportType == TransportType.OTHER) "" else " · " + transportName(leg.transportType)
            places + transport
        }
        return if (lines.size <= MAX_LEG_LINES) lines else lines.take(MAX_LEG_LINES) + "…and ${lines.size - MAX_LEG_LINES} more"
    }

    private fun transportName(type: TransportType) = if (type == TransportType.FOUR_WD) "4WD" else type.name.lowercase()

    /** The importer stamps trips with "Imported from Google Timeline"; that isn't something to post. */
    internal fun cleanNotes(notes: String): String =
        notes.lines().filterNot { it.contains("Imported from Google Timeline", ignoreCase = true) }.joinToString("\n").trim()

    internal fun dateRange(start: Long, end: Long?, locale: Locale): String {
        val format = DateFormat.getDateInstance(DateFormat.LONG, locale)
        val first = format.format(Date(start))
        val last = end?.let { format.format(Date(it)) }
        return if (last == null || last == first) first else "$first – $last"
    }

    /** Calendar days the trip touches, so a day trip is 1 and an overnight is 2. */
    internal fun dayCount(start: Long, end: Long?): Int {
        if (end == null) return 1
        val zone = java.util.TimeZone.getDefault()
        fun day(ms: Long) = Math.floorDiv(ms + zone.getOffset(ms), 86_400_000L)
        return (day(end) - day(start)).toInt().coerceAtLeast(0) + 1
    }

    internal fun distanceText(km: Double): String? = when {
        km < 0.5 -> null
        km < 10 -> "%.1f km".format(Locale.US, km)
        else -> "${Math.round(km)} km"
    }

    /** The kinds of transport used, most time first: "motorbike, car". */
    internal fun transportSummary(legs: List<Leg>): String =
        legs.filter { it.transportType != TransportType.OTHER }
            .groupBy { it.transportType }
            .entries.sortedByDescending { (_, group) -> group.sumOf { (it.endTime ?: it.startTime) - it.startTime } }
            .joinToString(", ") { (type, _) -> transportName(type) }

    internal fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /**
     * Writes the post as pictures, because Messenger, Facebook and Instagram drop text that comes with a share: first the
     * written post with the map, then each chosen photo as its own JPEG, then a closing card with the app icon, link and
     * QR code. Photos are re-drawn from their pixels, so the location and camera details stored inside the originals are
     * not passed on.
     */
    suspend fun writeImages(context: Context, content: PostContent, map: Bitmap?, photos: List<MediaItem>): List<File> =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val files = mutableListOf<File>()
            val post = File(dir, "post.jpg")
            ShareComposer.writePostCard(post, content, map)
            files += post
            photos.forEachIndexed { index, item ->
                val bitmap = ShareComposer.loadPhoto(context, item, PHOTO_MAX_SIDE) ?: return@forEachIndexed
                val file = File(dir, "photo-%02d.jpg".format(Locale.US, index + 1))
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bitmap.recycle()
                files += file
            }
            val card = File(dir, "adventure-timeline.jpg")
            ShareComposer.writeFooterCard(context, card)
            files += card
            files
        }
}
