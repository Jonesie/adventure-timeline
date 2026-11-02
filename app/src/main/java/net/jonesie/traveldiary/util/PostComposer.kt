package net.jonesie.traveldiary.util

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.TripDetail
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
    val includeCompanions: Boolean,
    val includeTripNotes: Boolean,
    val includeImpressions: Boolean,
    val includeFooter: Boolean = false
)

/**
 * The trip as a social-media post: a written caption plus the map and photos as separate pictures,
 * rather than one long image. The caption is plain text so it pastes into anything.
 */
object PostComposer {

    private const val MAX_PLACES = 8
    private const val PHOTO_MAX_SIDE = 1600

    /** Written post. [trailKm] is the recorded route length, or null when there is no GPS trail. */
    fun buildText(trip: TripDetail, options: PostOptions, trailKm: Double?, locale: Locale = Locale.getDefault()): String {
        val t = trip.trip
        val title = options.title.trim().ifEmpty { t.title }
        val out = StringBuilder(title)

        val facts = mutableListOf<String>()
        if (options.includeDates) facts += dateRange(t.startDate, t.endDate, locale)
        if (options.includeRoute) {
            dayCount(t.startDate, t.endDate).takeIf { it > 1 }?.let { facts += "$it days" }
            trailKm?.let { km -> distanceText(km)?.let { facts += it } }
            transportSummary(trip.legs).takeIf { it.isNotEmpty() }?.let { facts += it }
        }
        if (options.includeRating && t.rating > 0) facts += "★".repeat(t.rating) + "☆".repeat(5 - t.rating)
        if (facts.isNotEmpty()) out.append('\n').append(facts.joinToString(" · "))

        if (options.message.isNotBlank()) out.append("\n\n").append(options.message.trim())

        if (options.includeRoute) {
            val places = placeChain(trip.legs)
            if (places.size > 1) out.append("\n\nRoute: ").append(places.joinToString(" → "))
        }
        if (options.includeCompanions && trip.companions.isNotEmpty()) {
            out.append("\n\nWith ").append(joinNames(trip.companions.map { it.name }))
        }
        val notes = cleanNotes(t.notes)
        if (options.includeTripNotes && notes.isNotEmpty()) out.append("\n\n").append(notes)

        if (options.includeImpressions && trip.impressions.isNotEmpty()) {
            out.append("\n")
            trip.impressions.sortedBy { it.timestamp }.forEach { out.append("\n• ").append(it.text.trim()) }
        }
        if (options.includeFooter) out.append("\n\n").append(AppLinks.footerText())
        return out.toString()
    }

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
            .joinToString(", ") { (type, _) -> if (type == TransportType.FOUR_WD) "4WD" else type.name.lowercase() }

    /** Where the trip went in order, each stop once in a row; long trips keep the start and end. */
    internal fun placeChain(legs: List<Leg>): List<String> {
        val sorted = legs.sortedBy { it.startTime }
        val chain = mutableListOf<String>()
        fun add(name: String) {
            val n = name.trim()
            if (n.isNotEmpty() && chain.lastOrNull() != n) chain += n
        }
        sorted.firstOrNull()?.let { add(it.originName) }
        sorted.forEach { add(it.destinationName) }
        return if (chain.size <= MAX_PLACES) chain else chain.take(4) + "…" + chain.takeLast(3)
    }

    internal fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /**
     * Writes the map and each chosen photo as its own JPEG, map first. Photos are re-drawn from their pixels,
     * so the location and camera details stored inside the original files are not passed on.
     */
    suspend fun writeImages(context: Context, map: Bitmap?, photos: List<MediaItem>): List<File> =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val files = mutableListOf<File>()
            if (map != null) {
                val file = File(dir, "route-map.jpg")
                file.outputStream().use { map.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                files += file
            }
            photos.forEachIndexed { index, item ->
                val bitmap = ShareComposer.loadPhoto(context, item, PHOTO_MAX_SIDE) ?: return@forEachIndexed
                val file = File(dir, "photo-%02d.jpg".format(Locale.US, index + 1))
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bitmap.recycle()
                files += file
            }
            files
        }
}
