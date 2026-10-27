package net.jonesie.traveldiary.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.Companion
import net.jonesie.traveldiary.data.Impression
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.Trip
import net.jonesie.traveldiary.data.TripCompanionCrossRef
import net.jonesie.traveldiary.data.TripDetail
import net.jonesie.traveldiary.data.TravelDiaryDatabase
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.util.ActivityLeg
import net.jonesie.traveldiary.util.NEARBY_RADIUS_DEGREES_SQUARED
import net.jonesie.traveldiary.util.CameraRoll
import net.jonesie.traveldiary.util.DiaryBackup
import net.jonesie.traveldiary.util.DuplicateCopies
import net.jonesie.traveldiary.util.MediaLocation
import kotlinx.coroutines.sync.withLock
import net.jonesie.traveldiary.util.MediaFiles
import net.jonesie.traveldiary.util.PathPoint
import net.jonesie.traveldiary.util.PlaceInfo
import net.jonesie.traveldiary.util.ReverseGeocoder
import net.jonesie.traveldiary.util.RouteShape
import net.jonesie.traveldiary.util.TripTimeWindow
import org.osmdroid.util.GeoPoint

class TravelDiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = TravelDiaryDatabase.get(application).dao()

    val trips: StateFlow<List<Trip>> = dao.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaKindsByTrip: StateFlow<Map<Long, Set<MediaType>>> = dao.observeTripMediaKinds()
        .map { rows -> rows.groupBy({ it.tripId }, { it.type }).mapValues { it.value.toSet() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun tripDetail(tripId: Long): Flow<TripDetail?> = dao.observeTripDetail(tripId)

    fun addTrip(title: String, startDate: Long, endDate: Long?, notes: String, companionNames: List<String>, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val tripId = dao.insertTrip(Trip(title = title, startDate = startDate, endDate = endDate, notes = notes))
            companionNames.map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
                val existing = dao.findCompanionByName(name)
                val companionId = existing?.id ?: dao.insertCompanion(Companion(name = name))
                dao.insertTripCompanionCrossRef(TripCompanionCrossRef(tripId, companionId))
            }
            onDone(tripId)
        }
    }

    fun updateTrip(
        tripId: Long,
        title: String,
        notes: String,
        companionNames: List<String>,
        startDate: Long,
        endDate: Long?,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            dao.updateTripDetails(tripId, title, notes, startDate, endDate)
            dao.clearTripCompanions(tripId)
            companionNames.map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
                val existing = dao.findCompanionByName(name)
                val companionId = existing?.id ?: dao.insertCompanion(Companion(name = name))
                dao.insertTripCompanionCrossRef(TripCompanionCrossRef(tripId, companionId))
            }
            onDone()
        }
    }

    fun addLeg(
        tripId: Long,
        transportType: TransportType,
        originName: String,
        destinationName: String,
        startTime: Long,
        endTime: Long?
    ) {
        viewModelScope.launch {
            dao.insertLeg(
                Leg(
                    tripId = tripId,
                    transportType = transportType,
                    originName = originName,
                    destinationName = destinationName,
                    startTime = startTime,
                    endTime = endTime
                )
            )
        }
    }

    fun addMedia(tripId: Long, legId: Long?, type: MediaType, uri: String, caption: String, lat: Double?, lon: Double?) {
        viewModelScope.launch {
            dao.insertMediaItem(
                MediaItem(
                    tripId = tripId,
                    legId = legId,
                    type = type,
                    uri = uri,
                    timestamp = System.currentTimeMillis(),
                    caption = caption,
                    lat = lat,
                    lon = lon
                )
            )
        }
    }

    fun addImpression(tripId: Long, legId: Long?, text: String, lat: Double?, lon: Double?) {
        viewModelScope.launch {
            dao.insertImpression(
                Impression(
                    tripId = tripId,
                    legId = legId,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    lat = lat,
                    lon = lon
                )
            )
        }
    }

    /**
     * Called sequentially (one trip at a time) from the import screen's own coroutine so
     * progress can be reported trip-by-trip. Re-importing a trip with the same date bounds
     * (deterministic from Google's export) updates it in place instead of duplicating it —
     * its legs are replaced, but companions/media/impressions added since are left alone.
     */
    suspend fun importTimelineTrip(
        startDate: Long,
        endDate: Long,
        legs: List<ActivityLeg>,
        pathPoints: List<PathPoint>,
        fallbackTitle: String,
        onProgress: (String) -> Unit
    ) {
        val context = getApplication<Application>()
        val sortedLegs = legs.sortedBy { it.startTime }

        val coords = linkedSetOf<Pair<Double, Double>>()
        sortedLegs.forEach { leg ->
            if (leg.originLat != null && leg.originLon != null) coords.add(leg.originLat to leg.originLon)
            if (leg.destinationLat != null && leg.destinationLon != null) coords.add(leg.destinationLat to leg.destinationLon)
        }

        val places = HashMap<Pair<Double, Double>, PlaceInfo?>()
        coords.toList().chunked(5).forEach { batch ->
            onProgress("Resolving places (${places.size}/${coords.size})")
            val resolved = coroutineScope {
                batch.map { coord ->
                    async(Dispatchers.IO) { coord to ReverseGeocoder.lookup(context, coord.first, coord.second) }
                }.awaitAll()
            }
            resolved.forEach { (coord, info) -> places[coord] = info }
        }

        // "Home" is where the trip starts; anywhere in a different country counts as overseas.
        val homeCountryCode = sortedLegs
            .firstOrNull { it.originLat != null && it.originLon != null }
            ?.let { places[it.originLat!! to it.originLon!!]?.countryCode }

        fun nameFor(lat: Double?, lon: Double?): String {
            if (lat == null || lon == null) return ""
            val info = places[lat to lon]
            val overseas = homeCountryCode != null && info?.countryCode != null && info.countryCode != homeCountryCode
            return info?.format(overseas) ?: "%.4f, %.4f".format(lat, lon)
        }

        // Trips start and end at home, so "first leg → last leg" gave every trip the same title.
        // Name it by the furthest place reached from where it started instead.
        val title = run {
            val homeLeg = sortedLegs.firstOrNull { it.originLat != null && it.originLon != null }
            val home = homeLeg?.let { nameFor(it.originLat, it.originLon) }
            val farthest = if (homeLeg == null) null else sortedLegs
                .flatMap { leg ->
                    listOf(leg.originLat to leg.originLon, leg.destinationLat to leg.destinationLon)
                }
                .filter { (lat, lon) -> lat != null && lon != null }
                .maxByOrNull { (lat, lon) -> distanceKm(homeLeg.originLat!!, homeLeg.originLon!!, lat!!, lon!!) }
                ?.let { (lat, lon) -> nameFor(lat, lon) }
            when {
                home.isNullOrBlank() || farthest.isNullOrBlank() -> fallbackTitle
                home == farthest -> "Around $home"
                else -> "$home → $farthest"
            }
        }

        onProgress("Saving trip")
        val existing = dao.findTripByDateRange(startDate, endDate)
        // Manual edits survive a re-import: legs whose transport was changed by hand are matched
        // back by their (start, end) time, and a hand-edited title isn't regenerated.
        val editedLegs = HashMap<Pair<Long, Long?>, Leg>()
        val tripId = if (existing != null) {
            if (!existing.titleEdited) dao.updateTripTitle(existing.id, title)
            dao.getEditedLegs(existing.id).forEach { editedLegs[it.startTime to it.endTime] = it }
            dao.deleteLegsForTrip(existing.id)
            dao.deleteRoutePointsForTrip(existing.id)
            existing.id
        } else {
            dao.insertTrip(Trip(title = title, startDate = startDate, endDate = endDate, notes = "Imported from Google Timeline"))
        }

        sortedLegs.forEachIndexed { index, leg ->
            onProgress("Saving legs (${index + 1}/${sortedLegs.size})")
            val keptLeg = editedLegs[leg.startTime to leg.endTime]
            val keptTransport = keptLeg?.takeIf { it.transportEdited }?.transportType
            dao.insertLeg(
                Leg(
                    tripId = tripId,
                    transportType = keptTransport ?: leg.transportType,
                    originName = nameFor(leg.originLat, leg.originLon),
                    destinationName = nameFor(leg.destinationLat, leg.destinationLon),
                    startTime = leg.startTime,
                    endTime = leg.endTime,
                    originLat = leg.originLat,
                    originLon = leg.originLon,
                    destinationLat = leg.destinationLat,
                    destinationLon = leg.destinationLon,
                    transportEdited = keptTransport != null,
                    difficulty = keptLeg?.difficulty ?: 0,
                    enjoyment = keptLeg?.enjoyment ?: 0
                )
            )
        }

        if (pathPoints.isNotEmpty()) {
            onProgress("Saving route (${pathPoints.size} points)")
            dao.insertRoutePoints(
                pathPoints.sortedBy { it.timestamp }.map { RoutePoint(tripId = tripId, lat = it.lat, lon = it.lon, timestamp = it.timestamp) }
            )
        }
    }

    suspend fun routePointsFor(tripId: Long) = dao.getRoutePointsForTrip(tripId)

    /**
     * Every trip's route for the all-trips map. Trips with no GPS trail fall back to a line through
     * their legs' stops, and long trails are thinned so dozens of trips stay quick to draw.
     */
    suspend fun allTripRoutes(): List<TripRoute> = withContext(Dispatchers.IO) {
        val trails = dao.getAllRoutePoints().groupBy { it.tripId }
        val legs = dao.getAllLegs().groupBy { it.tripId }
        val media = dao.getAllMedia().groupBy { it.tripId }
        dao.getAllTrips().sortedBy { it.startDate }.map { trip ->
            val tripLegs = legs[trip.id].orEmpty()
            val trail = trails[trip.id].orEmpty()
            val lines = mutableListOf<RouteLine>()
            if (trail.isNotEmpty()) {
                // Thin very long trails (per trip) so many trips stay quick to draw.
                val step = (trail.size + 1499) / 1500
                RouteShape.splitByTransport(trail, tripLegs).forEach { piece ->
                    val pts = piece.points.filterIndexed { i, _ -> step <= 1 || i % step == 0 || i == piece.points.lastIndex }
                    lines += RouteLine(pts.map { GeoPoint(it.lat, it.lon) }, piece.transport)
                }
                RouteShape.sparseLegs(trail, tripLegs, TransportType.PLANE).forEach { leg ->
                    lines += RouteLine(
                        listOf(GeoPoint(leg.originLat!!, leg.originLon!!), GeoPoint(leg.destinationLat!!, leg.destinationLon!!)),
                        leg.transportType, dashed = true
                    )
                }
            } else {
                // No GPS trail: a dashed straight line per leg, since that's all that's known.
                tripLegs.sortedBy { it.startTime }.forEach { leg ->
                    val pts = listOfNotNull(
                        if (leg.originLat != null && leg.originLon != null) GeoPoint(leg.originLat, leg.originLon) else null,
                        if (leg.destinationLat != null && leg.destinationLon != null) GeoPoint(leg.destinationLat, leg.destinationLon) else null
                    )
                    if (pts.size == 2) lines += RouteLine(pts, leg.transportType, dashed = true)
                }
            }
            TripRoute(trip, lines, tripLegs, media[trip.id].orEmpty().sortedBy { it.timestamp })
        }
    }

    fun setLegDetails(legIds: List<Long>, type: TransportType, difficulty: Int, enjoyment: Int) {
        viewModelScope.launch {
            dao.updateLegDetails(legIds, type, difficulty.coerceIn(0, 5), enjoyment.coerceIn(0, 5))
        }
    }

    /** [from] null means every leg of the trip. */
    fun changeTripTransport(tripId: Long, from: TransportType?, to: TransportType) {
        viewModelScope.launch {
            if (from == null) dao.updateAllTripLegsTransport(tripId, to)
            else dao.updateTripLegsTransport(tripId, from, to)
        }
    }

    private val _mediaImportProgress = MutableStateFlow<MediaImportProgress?>(null)
    val mediaImportProgress: StateFlow<MediaImportProgress?> = _mediaImportProgress.asStateFlow()

    /**
     * Copies picked files into the app and adds them to the trip. [forcedType] overrides the
     * photo/video guess from the file's mime type (used for audio files).
     */
    fun importFiles(tripId: Long, uris: List<Uri>, forcedType: MediaType?, onDone: (added: Int, failed: Int) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val context = getApplication<Application>()
            var added = 0
            var failed = 0
            _mediaImportProgress.value = MediaImportProgress(0, uris.size)
            uris.forEachIndexed { index, uri ->
                val copied = withContext(Dispatchers.IO) { runCatching { MediaFiles.copyIntoApp(context, uri) }.getOrNull() }
                if (copied == null) {
                    failed++
                } else {
                    val type = forcedType ?: when {
                        copied.mimeType?.startsWith("video/") == true -> MediaType.VIDEO
                        copied.mimeType?.startsWith("audio/") == true -> MediaType.AUDIO
                        else -> MediaType.PHOTO
                    }
                    dao.insertMediaItem(
                        MediaItem(
                            tripId = tripId,
                            type = type,
                            uri = copied.uri.toString(),
                            timestamp = copied.takenAt ?: System.currentTimeMillis()
                        )
                    )
                    added++
                }
                _mediaImportProgress.value = MediaImportProgress(index + 1, uris.size)
            }
            _mediaImportProgress.value = null
            onDone(added, failed)
        }
    }

    /**
     * Adds photos and videos this phone's camera took during the trip's dates. They're referenced
     * in place (not copied), and anything already in the trip is skipped, so it's safe to re-run.
     * Returns (photos added, videos added).
     */
    suspend fun autoAddCameraMedia(tripId: Long): Pair<Int, Int> {
        val trip = dao.getTripById(tripId) ?: return 0 to 0
        val window = TripTimeWindow.of(trip)
        val context = getApplication<Application>()
        val existing = dao.getMediaUris(tripId).toHashSet()
        val found = withContext(Dispatchers.IO) { CameraRoll.find(context, window.first, window.last) }
        val fresh = found.filter { it.uri.toString() !in existing }
        dao.insertMediaItems(
            fresh.map { MediaItem(tripId = tripId, type = it.type, uri = it.uri.toString(), timestamp = it.takenAt) }
        )
        return fresh.count { it.type == MediaType.PHOTO } to fresh.count { it.type == MediaType.VIDEO }
    }

    private val locationMutex = kotlinx.coroutines.sync.Mutex()

    /** True when gallery photos still lack a location and Android needs us to ask for access to it. */
    suspend fun galleryLocationPermissionNeeded(): Boolean = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        dao.getMediaNeedingLocation().any { MediaLocation.needsPermission(context, it) }
    }

    /** Whether we've already asked for access to photo locations; the system prompt is only worth showing once. */
    var askedForMediaLocation: Boolean
        get() = prefs.getBoolean("askedMediaLocation", false)
        set(value) { prefs.edit().putBoolean("askedMediaLocation", value).apply() }

    /**
     * Reads each photo/video's own location into the diary so it can be pinned on maps. Items
     * whose location is hidden by missing permission are left to try again later.
     * Returns how many items now have a location.
     */
    suspend fun backfillMediaLocations(): Int = locationMutex.withLock {
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            var found = 0
            dao.getMediaNeedingLocation().forEach { item ->
                val location = MediaLocation.read(context, item)
                if (location == null && MediaLocation.needsPermission(context, item)) return@forEach
                dao.setMediaLocation(item.id, location?.lat, location?.lon)
                if (location != null) found++
            }
            found
        }
    }

    /** Adds photos/videos already on the phone by reference (nothing is copied). */
    fun addPhoneMedia(tripId: Long, items: List<CameraRoll.Item>) {
        viewModelScope.launch {
            val existing = dao.getMediaUris(tripId).toHashSet()
            dao.insertMediaItems(
                items.filter { it.uri.toString() !in existing }
                    .map { MediaItem(tripId = tripId, type = it.type, uri = it.uri.toString(), timestamp = it.takenAt) }
            )
        }
    }

    /** Imported copies that are byte-for-byte duplicates of something in the phone's gallery. */
    suspend fun findDuplicateCopies(): List<DuplicateCopies.Match> {
        _backupProgress.value = "Looking for duplicate copies…"
        return try {
            withContext(Dispatchers.IO) { DuplicateCopies.find(getApplication(), dao.getAllMedia()) }
        } finally {
            _backupProgress.value = null
        }
    }

    fun replaceCopiesWithLinks(matches: List<DuplicateCopies.Match>, onDone: (freedBytes: Long) -> Unit) {
        viewModelScope.launch {
            var freed = 0L
            matches.forEachIndexed { index, m ->
                _backupProgress.value = "Replacing copies… ${index + 1} of ${matches.size}"
                dao.updateMediaUri(m.mediaId, m.galleryUri.toString())
                if (withContext(Dispatchers.IO) { m.file.delete() }) freed += m.bytes
            }
            _backupProgress.value = null
            onDone(freed)
        }
    }

    fun deleteMedia(item: MediaItem) {
        viewModelScope.launch {
            dao.deleteMediaItem(item.id)
            withContext(Dispatchers.IO) { MediaFiles.deleteIfOwned(getApplication(), item.uri) }
        }
    }

    suspend fun tripIdsNear(lat: Double, lon: Double, radiusKm: Double): Set<Long> {
        val radiusDegrees = radiusKm / 111.32
        return dao.findTripIdsNear(lat, lon, Math.cos(Math.toRadians(lat)), radiusDegrees * radiusDegrees).toSet()
    }

    fun setTripRating(tripId: Long, rating: Int) {
        viewModelScope.launch { dao.updateTripRating(tripId, rating.coerceIn(0, 5)) }
    }

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { dao.deleteTripById(tripId) }
    }

    private val prefs = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private fun loadBackupSettings() = BackupSettings(
        lastBackup = prefs.getLong("lastBackup", 0L).takeIf { it > 0 },
        reminderDays = prefs.getInt("reminderDays", 14),
        snoozedUntil = prefs.getLong("snoozedUntil", 0L)
    )

    private val _backupSettings = MutableStateFlow(loadBackupSettings())
    val backupSettings: StateFlow<BackupSettings> = _backupSettings.asStateFlow()

    /** 0 turns the reminder off. */
    fun setReminderDays(days: Int) {
        prefs.edit().putInt("reminderDays", days).apply()
        _backupSettings.value = loadBackupSettings()
    }

    fun snoozeBackupReminder(days: Int = 7) {
        prefs.edit().putLong("snoozedUntil", System.currentTimeMillis() + days * BackupSettings.DAY_MS).apply()
        _backupSettings.value = loadBackupSettings()
    }

    private fun recordBackupDone() {
        prefs.edit().putLong("lastBackup", System.currentTimeMillis()).putLong("snoozedUntil", 0L).apply()
        _backupSettings.value = loadBackupSettings()
    }

    private val _backupProgress = MutableStateFlow<String?>(null)
    /** Non-null while a backup or restore is running; the text says where it's up to. */
    val backupProgress: StateFlow<String?> = _backupProgress.asStateFlow()

    suspend fun backupEstimate(): DiaryBackup.Estimate = DiaryBackup.estimate(getApplication())

    fun exportBackup(target: Uri, includeCameraRoll: Boolean, onDone: (String) -> Unit) {
        if (_backupProgress.value != null) return
        viewModelScope.launch {
            _backupProgress.value = "Starting…"
            val message = runCatching {
                DiaryBackup.export(getApplication(), target, includeCameraRoll) { _backupProgress.value = it }
            }.fold(
                onSuccess = {
                    recordBackupDone()
                    buildString {
                        append("Backed up ${it.trips} trips and ${it.files} photo/video/audio files (${formatBytes(it.bytes)}).")
                        if (it.savedBytes == null || it.savedBytes != it.bytes) {
                            append(" The destination reports ${it.savedBytes?.let(::formatBytes) ?: "no size"}. If that's Google Drive it may still be uploading — check the file there before relying on it.")
                        }
                        if (it.linkedOnly > 0) append(" ${it.linkedOnly} camera-roll items were not included; they stay in your gallery.")
                    }
                },
                onFailure = { "Backup failed: ${it.message ?: it.javaClass.simpleName}" }
            )
            _backupProgress.value = null
            onDone(message)
        }
    }

    fun restoreBackup(source: Uri, onDone: (String) -> Unit) {
        if (_backupProgress.value != null) return
        viewModelScope.launch {
            _backupProgress.value = "Starting…"
            val message = runCatching {
                DiaryBackup.restore(getApplication(), source) { _backupProgress.value = it }
            }.fold(
                onSuccess = {
                    buildString {
                        append("Restored ${it.trips} trips and ${it.files} media files.")
                        if (it.skipped > 0) append(" ${it.skipped} files were missing from the backup.")
                    }
                },
                onFailure = { "Restore failed: ${it.message ?: it.javaClass.simpleName}. Your diary was not changed." }
            )
            _backupProgress.value = null
            onDone(message)
        }
    }

    fun deleteAllTrips() {
        viewModelScope.launch { dao.deleteAllTrips() }
    }

    suspend fun findNearby(lat: Double, lon: Double): NearbyResult {
        val impressions = dao.findImpressionsNear(lat, lon, NEARBY_RADIUS_DEGREES_SQUARED)
        val media = dao.findMediaNear(lat, lon, NEARBY_RADIUS_DEGREES_SQUARED)
        return NearbyResult(impressions, media)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    else -> "%d KB".format(bytes / 1000)
}

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 6371.0 * 2 * Math.asin(Math.sqrt(a))
}

/** One continuous stretch of a route; [transport] is null where no leg covers it. */
data class RouteLine(val points: List<GeoPoint>, val transport: TransportType?, val dashed: Boolean = false)

/** [media] is in the same order the photo viewer uses, so a pin's index opens the right photo. */
data class TripRoute(val trip: Trip, val lines: List<RouteLine>, val legs: List<Leg>, val media: List<MediaItem>)

data class BackupSettings(val lastBackup: Long?, val reminderDays: Int, val snoozedUntil: Long) {
    /** True when the diary has data and the user is due (or has never had) a backup. */
    fun reminderDue(hasTrips: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        hasTrips && reminderDays > 0 && now >= snoozedUntil &&
            (lastBackup == null || now - lastBackup >= reminderDays * DAY_MS)

    companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}

data class MediaImportProgress(val done: Int, val total: Int)

data class NearbyResult(
    val impressions: List<Impression>,
    val media: List<MediaItem>
) {
    val isEmpty: Boolean get() = impressions.isEmpty() && media.isEmpty()
}
