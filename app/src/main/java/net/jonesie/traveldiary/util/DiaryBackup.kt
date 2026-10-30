package net.jonesie.traveldiary.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.Companion
import net.jonesie.traveldiary.data.Impression
import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
import net.jonesie.traveldiary.data.IncidentPersonCrossRef
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.TravelDiaryDatabase
import net.jonesie.traveldiary.data.Trip
import net.jonesie.traveldiary.data.TripCompanionCrossRef
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-diary backup as one zip: `diary.json` (every table) plus `media/` (photos, videos and
 * audio). Restoring replaces the current diary with the backup's contents.
 */
object DiaryBackup {

    private const val FORMAT = 1
    private const val JSON_ENTRY = "diary.json"
    private const val MEDIA_PREFIX = "media/"

    class ExportSummary(val trips: Int, val files: Int, val linkedOnly: Int, val bytes: Long, val savedBytes: Long?)
    class RestoreSummary(val trips: Int, val files: Int, val skipped: Int)

    // ---------------------------------------------------------------- export

    suspend fun export(
        context: Context,
        target: Uri,
        includeCameraRoll: Boolean,
        onProgress: (String) -> Unit
    ): ExportSummary = withContext(Dispatchers.IO) {
        val dao = TravelDiaryDatabase.get(context).dao()
        val resolver = context.contentResolver

        var stage = "starting"
        val progress: (String) -> Unit = { stage = it; onProgress(it) }
        try {
        progress("Reading diary…")
        val trips = dao.getAllTrips()
        val media = dao.getAllMedia()

        // Decide up front how each media item is stored, so diary.json can go first in the zip.
        class Plan(val item: MediaItem, val owned: File?, val entryName: String?)
        val plans = media.map { item ->
            val owned = MediaFiles.ownedFile(context, item.uri)?.takeIf { it.exists() }
            when {
                owned != null -> Plan(item, owned, owned.name)
                includeCameraRoll && MediaFiles.ownedFile(context, item.uri) == null ->
                    Plan(item, null, "cr_${item.id}.${extensionFor(resolver.getType(Uri.parse(item.uri)), item.type)}")
                else -> Plan(item, null, null)
            }
        }

        if (includeCameraRoll) {
            val needed = estimate(context).let { it.appFilesBytes + it.cameraRollBytes }
            if (needed > freeBytes(context)) {
                error("This backup would be about ${gb(needed)} but the phone has only ${gb(freeBytes(context))} free. " +
                    "Untick the camera-roll option, or free up space.")
            }
        }

        // Build the zip locally first: cloud destinations like Drive can't be trusted to report a
        // failed write, so the finished file is checked and then copied across as a separate step.
        val temp = File(context.cacheDir, "backup-in-progress.zip").apply { delete() }
        var files = 0
        var linkedOnly = 0
        try {
        ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            val writer = JsonWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            writer.beginObject()
            writer.name("format").value(FORMAT.toLong())

            writer.name("trips").beginArray()
            trips.forEach { t ->
                writer.beginObject()
                writer.put("id", t.id); writer.put("title", t.title)
                writer.put("startDate", t.startDate); writer.put("endDate", t.endDate)
                writer.put("notes", t.notes); writer.put("titleEdited", t.titleEdited)
                writer.put("rating", t.rating.toLong())
                writer.endObject()
            }
            writer.endArray()

            writer.name("companions").beginArray()
            dao.getAllCompanions().forEach { c ->
                writer.beginObject()
                writer.put("id", c.id); writer.put("name", c.name); writer.put("contactId", c.contactId)
                writer.endObject()
            }
            writer.endArray()

            writer.name("tripCompanions").beginArray()
            dao.getAllCrossRefs().forEach { r ->
                writer.beginObject()
                writer.put("tripId", r.tripId); writer.put("companionId", r.companionId)
                writer.endObject()
            }
            writer.endArray()

            writer.name("legs").beginArray()
            dao.getAllLegs().forEach { l ->
                writer.beginObject()
                writer.put("id", l.id); writer.put("tripId", l.tripId)
                writer.put("transportType", l.transportType.name)
                writer.put("originName", l.originName); writer.put("destinationName", l.destinationName)
                writer.put("startTime", l.startTime); writer.put("endTime", l.endTime)
                writer.put("originLat", l.originLat); writer.put("originLon", l.originLon)
                writer.put("destinationLat", l.destinationLat); writer.put("destinationLon", l.destinationLon)
                writer.put("transportEdited", l.transportEdited)
                writer.put("difficulty", l.difficulty.toLong()); writer.put("enjoyment", l.enjoyment.toLong())
                writer.endObject()
            }
            writer.endArray()

            writer.name("media").beginArray()
            plans.forEach { p ->
                val m = p.item
                writer.beginObject()
                writer.put("id", m.id); writer.put("tripId", m.tripId); writer.put("legId", m.legId)
                writer.put("incidentId", m.incidentId)
                writer.put("type", m.type.name)
                if (p.entryName != null) writer.put("file", p.entryName) else writer.put("uri", m.uri)
                writer.put("timestamp", m.timestamp); writer.put("caption", m.caption)
                writer.put("lat", m.lat); writer.put("lon", m.lon)
                writer.endObject()
            }
            writer.endArray()

            writer.name("impressions").beginArray()
            dao.getAllImpressions().forEach { i ->
                writer.beginObject()
                writer.put("id", i.id); writer.put("tripId", i.tripId); writer.put("legId", i.legId)
                writer.put("text", i.text); writer.put("timestamp", i.timestamp)
                writer.put("lat", i.lat); writer.put("lon", i.lon)
                writer.endObject()
            }
            writer.endArray()

            writer.name("incidents").beginArray()
            dao.getAllIncidents().forEach { i ->
                writer.beginObject()
                writer.put("id", i.id); writer.put("tripId", i.tripId); writer.put("legId", i.legId)
                writer.put("category", i.category.name); writer.put("severity", i.severity.toLong())
                writer.put("reason", i.reason)
                writer.name("involvesMe").value(i.involvesMe)
                writer.put("notes", i.notes); writer.put("timestamp", i.timestamp)
                writer.put("lat", i.lat); writer.put("lon", i.lon)
                writer.endObject()
            }
            writer.endArray()

            writer.name("incidentPeople").beginArray()
            dao.getAllIncidentPeople().forEach { r ->
                writer.beginObject()
                writer.put("incidentId", r.incidentId); writer.put("companionId", r.companionId)
                writer.endObject()
            }
            writer.endArray()

            // Route points are by far the biggest table, so each is a compact [lat, lon, time] triple.
            writer.name("routes").beginArray()
            trips.forEachIndexed { index, t ->
                val points = dao.getRoutePointsForTrip(t.id)
                if (points.isNotEmpty()) {
                    progress("Saving routes… ${index + 1} of ${trips.size}")
                    writer.beginObject()
                    writer.put("tripId", t.id)
                    writer.name("points").beginArray()
                    points.forEach { p ->
                        writer.beginArray()
                        writer.value(p.lat); writer.value(p.lon); writer.value(p.timestamp)
                        writer.endArray()
                    }
                    writer.endArray()
                    writer.endObject()
                }
            }
            writer.endArray()

            writer.endObject()
            writer.flush() // not close(): that would close the zip stream
            zip.closeEntry()

            // Photos/videos/audio are already compressed, so don't waste time deflating them.
            zip.setLevel(Deflater.NO_COMPRESSION)
            val toCopy = plans.filter { it.entryName != null }
            linkedOnly = plans.count { it.entryName == null }
            toCopy.forEachIndexed { index, p ->
                progress("Saving media… ${index + 1} of ${toCopy.size}")
                val input = if (p.owned != null) p.owned.inputStream()
                else resolver.openInputStream(Uri.parse(p.item.uri))
                if (input == null) {
                    linkedOnly++
                    return@forEachIndexed
                }
                zip.putNextEntry(ZipEntry(MEDIA_PREFIX + p.entryName))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
                files++
            }
        }
        val bytes = temp.length()
        check(bytes > 0) { "The backup file came out empty" }
        java.util.zip.ZipFile(temp).use { zf -> check(zf.getEntry(JSON_ENTRY) != null) { "The backup file is incomplete" } }

        progress("Saving to the chosen location…")
        try {
            val output = resolver.openOutputStream(target, "wt") ?: error("Couldn't open the chosen file")
            temp.inputStream().use { input -> output.use { out -> input.copyTo(out); out.flush() } }
        } catch (e: Throwable) {
            // Don't leave a half-written file behind that looks like a real backup.
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw e
        }
        ExportSummary(trips = trips.size, files = files, linkedOnly = linkedOnly, bytes = bytes, savedBytes = sizeOf(context, target))
        } finally {
            temp.delete()
        }
        } catch (e: java.io.IOException) {
            throw java.io.IOException("while \"$stage\": ${e.message}", e)
        }
    }

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1e9)

    // --------------------------------------------------------------- restore

    private class Parsed(
        val trips: List<Trip>,
        val companions: List<Companion>,
        val crossRefs: List<TripCompanionCrossRef>,
        val legs: List<Leg>,
        val media: List<ParsedMedia>,
        val impressions: List<Impression>,
        val incidents: List<Incident>,
        val incidentPeople: List<IncidentPersonCrossRef>,
        val routes: List<RoutePoint>
    )

    private class ParsedMedia(val item: MediaItem, val file: String?)

    suspend fun restore(context: Context, source: Uri, onProgress: (String) -> Unit): RestoreSummary =
        withContext(Dispatchers.IO) {
            var stage = "starting"
            val progress: (String) -> Unit = { stage = it; onProgress(it) }
            try {
            val db = TravelDiaryDatabase.get(context)
            val dao = db.dao()
            if (sizeOf(context, source) == 0L) error("That file is empty (0 bytes), so there is nothing to restore")
            val mediaDir = MediaFiles.mediaDir(context)
            val staging = File(mediaDir.parentFile, "media_restore").apply { deleteRecursively(); mkdirs() }

            try {
                var parsed: Parsed? = null
                var extracted = 0
                val input = context.contentResolver.openInputStream(source) ?: error("Couldn't open the chosen file")
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when {
                            entry.isDirectory -> Unit
                            entry.name == JSON_ENTRY -> {
                                progress("Reading diary…")
                                parsed = parse(zip)
                            }
                            entry.name.startsWith(MEDIA_PREFIX) -> {
                                // Only flat file names: never let an entry write outside the staging folder.
                                val name = entry.name.removePrefix(MEDIA_PREFIX)
                                if (name.isNotEmpty() && '/' !in name && '\\' !in name && name != "..") {
                                    extracted++
                                    progress("Restoring media… $extracted")
                                    File(staging, name).outputStream().use { zip.copyTo(it) }
                                }
                            }
                        }
                    }
                }
                val data = parsed ?: error("This isn't an Adventure Timeline backup")

                progress("Restoring trips…")
                var skipped = 0
                val mediaItems = data.media.mapNotNull { m ->
                    if (m.file != null) {
                        if (File(staging, m.file).exists()) {
                            m.item.copy(uri = MediaFiles.uriFor(context, File(mediaDir, m.file)).toString())
                        } else {
                            skipped++
                            null
                        }
                    } else m.item
                }

                db.withTransaction {
                    dao.deleteAllTrips()
                    dao.deleteAllIncidents()
                    dao.deleteTripLessMedia()
                    dao.deleteAllCompanions()
                    dao.insertTrips(data.trips)
                    dao.insertCompanions(data.companions)
                    dao.insertCrossRefs(data.crossRefs)
                    dao.insertLegs(data.legs)
                    dao.insertMediaItems(mediaItems)
                    dao.insertImpressions(data.impressions)
                    dao.insertIncidents(data.incidents)
                    dao.insertIncidentPeople(data.incidentPeople)
                    data.routes.chunked(5000).forEach { dao.insertRoutePoints(it) }
                }

                // The database now points at the backup's files, so swap them in for the old ones.
                mediaDir.listFiles()?.forEach { it.delete() }
                staging.listFiles()?.forEach { it.renameTo(File(mediaDir, it.name)) }
                RestoreSummary(trips = data.trips.size, files = data.media.count { it.file != null } - skipped, skipped = skipped)
            } finally {
                staging.deleteRecursively()
            }
            } catch (e: java.io.IOException) {
                throw java.io.IOException("while \"$stage\": ${e.message}", e)
            }
        }

    private fun parse(stream: java.io.InputStream): Parsed {
        // No close(): it would close the zip stream and stop later entries being read.
        val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        var format = 0L
        val trips = mutableListOf<Trip>()
        val companions = mutableListOf<Companion>()
        val crossRefs = mutableListOf<TripCompanionCrossRef>()
        val legs = mutableListOf<Leg>()
        val media = mutableListOf<ParsedMedia>()
        val impressions = mutableListOf<Impression>()
        val incidents = mutableListOf<Incident>()
        val incidentPeople = mutableListOf<IncidentPersonCrossRef>()
        val routes = mutableListOf<RoutePoint>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "format" -> format = reader.nextLong()
                "trips" -> readObjects(reader) { m ->
                    trips += Trip(
                        id = m.long("id"), title = m.string("title"), startDate = m.long("startDate"),
                        endDate = m.longOrNull("endDate"), notes = m.stringOrNull("notes") ?: "",
                        titleEdited = m.bool("titleEdited"), rating = m.long("rating").toInt()
                    )
                }
                "companions" -> readObjects(reader) { m ->
                    companions += Companion(id = m.long("id"), name = m.string("name"), contactId = m.stringOrNull("contactId"))
                }
                "tripCompanions" -> readObjects(reader) { m ->
                    crossRefs += TripCompanionCrossRef(m.long("tripId"), m.long("companionId"))
                }
                "legs" -> readObjects(reader) { m ->
                    legs += Leg(
                        id = m.long("id"), tripId = m.long("tripId"),
                        transportType = runCatching { TransportType.valueOf(m.string("transportType")) }
                            .getOrDefault(TransportType.OTHER),
                        originName = m.stringOrNull("originName") ?: "", destinationName = m.stringOrNull("destinationName") ?: "",
                        startTime = m.long("startTime"), endTime = m.longOrNull("endTime"),
                        originLat = m.doubleOrNull("originLat"), originLon = m.doubleOrNull("originLon"),
                        destinationLat = m.doubleOrNull("destinationLat"), destinationLon = m.doubleOrNull("destinationLon"),
                        transportEdited = m.bool("transportEdited"),
                        difficulty = m.long("difficulty").toInt(), enjoyment = m.long("enjoyment").toInt()
                    )
                }
                "media" -> readObjects(reader) { m ->
                    val type = runCatching { MediaType.valueOf(m.string("type")) }.getOrNull() ?: return@readObjects
                    media += ParsedMedia(
                        MediaItem(
                            id = m.long("id"), tripId = m.longOrNull("tripId"), legId = m.longOrNull("legId"), type = type,
                            uri = m.stringOrNull("uri") ?: "", timestamp = m.long("timestamp"),
                            caption = m.stringOrNull("caption") ?: "", lat = m.doubleOrNull("lat"), lon = m.doubleOrNull("lon"),
                            incidentId = m.longOrNull("incidentId")
                        ),
                        file = m.stringOrNull("file")
                    )
                }
                "impressions" -> readObjects(reader) { m ->
                    impressions += Impression(
                        id = m.long("id"), tripId = m.long("tripId"), legId = m.longOrNull("legId"),
                        text = m.string("text"), timestamp = m.long("timestamp"),
                        lat = m.doubleOrNull("lat"), lon = m.doubleOrNull("lon")
                    )
                }
                "incidents" -> readObjects(reader) { m ->
                    incidents += Incident(
                        id = m.long("id"), tripId = m.longOrNull("tripId"), legId = m.longOrNull("legId"),
                        category = runCatching { IncidentCategory.valueOf(m.string("category")) }.getOrDefault(IncidentCategory.OTHER),
                        severity = m.long("severity").toInt(), notes = m.stringOrNull("notes") ?: "",
                        timestamp = m.long("timestamp"), lat = m.doubleOrNull("lat"), lon = m.doubleOrNull("lon"),
                        reason = m.stringOrNull("reason")?.let { IncidentReasons.fromLegacyName(it) },
                        // Backups from before this field existed were all the owner's own.
                        involvesMe = m["involvesMe"] as? Boolean ?: true
                    )
                }
                "incidentPeople" -> readObjects(reader) { m ->
                    incidentPeople += IncidentPersonCrossRef(m.long("incidentId"), m.long("companionId"))
                }
                "routes" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var tripId = 0L
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "tripId" -> tripId = reader.nextLong()
                                "points" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        reader.beginArray()
                                        val lat = reader.nextDouble()
                                        val lon = reader.nextDouble()
                                        val time = reader.nextLong()
                                        reader.endArray()
                                        routes += RoutePoint(tripId = tripId, lat = lat, lon = lon, timestamp = time)
                                    }
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (format < 1) error("This isn't an Adventure Timeline backup")
        if (format > FORMAT) error("This backup was made by a newer version of the app")
        return Parsed(trips, companions, crossRefs, legs, media, impressions, incidents, incidentPeople, routes)
    }

    class Estimate(val appFilesBytes: Long, val cameraRollBytes: Long)

    /** How big a backup will be: files the app owns, plus (optionally) camera-roll items it links to. */
    suspend fun estimate(context: Context): Estimate = withContext(Dispatchers.IO) {
        var owned = 0L
        var linked = 0L
        TravelDiaryDatabase.get(context).dao().getAllMedia().forEach { item ->
            val file = MediaFiles.ownedFile(context, item.uri)
            if (file != null) owned += file.length()
            else linked += sizeOf(context, Uri.parse(item.uri)) ?: 0L
        }
        Estimate(owned, linked)
    }

    fun freeBytes(context: Context): Long = context.cacheDir.usableSpace

    /** Size the destination reports for [uri], or null if it doesn't say. */
    fun sizeOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }.getOrNull()

    // ----------------------------------------------------------- json helpers

    private fun readObjects(reader: JsonReader, onObject: (Map<String, Any?>) -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
            val fields = HashMap<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                fields[name] = when (reader.peek()) {
                    JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
                    JsonToken.STRING -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> { reader.nextNull(); null }
                    else -> { reader.skipValue(); null }
                }
            }
            reader.endObject()
            onObject(fields)
        }
        reader.endArray()
    }

    private fun Map<String, Any?>.long(key: String): Long = longOrNull(key) ?: 0L
    private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<String, Any?>.doubleOrNull(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false
    private fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: ""
    private fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as? String

    private fun JsonWriter.put(name: String, value: String?) { if (value != null) name(name).value(value) }
    private fun JsonWriter.put(name: String, value: Long?) { if (value != null) name(name).value(value) }
    private fun JsonWriter.put(name: String, value: Double?) { if (value != null) name(name).value(value) }
    private fun JsonWriter.put(name: String, value: Boolean) { name(name).value(value) }

    private fun extensionFor(mime: String?, type: MediaType): String =
        mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: when (type) {
                MediaType.PHOTO -> "jpg"
                MediaType.VIDEO -> "mp4"
                MediaType.AUDIO -> "m4a"
            }
}
