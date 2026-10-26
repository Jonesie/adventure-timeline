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
import net.jonesie.traveldiary.util.MediaFiles
import net.jonesie.traveldiary.util.PathPoint
import net.jonesie.traveldiary.util.PlaceInfo
import net.jonesie.traveldiary.util.ReverseGeocoder
import net.jonesie.traveldiary.util.TripTimeWindow

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

    fun deleteAllTrips() {
        viewModelScope.launch { dao.deleteAllTrips() }
    }

    suspend fun findNearby(lat: Double, lon: Double): NearbyResult {
        val impressions = dao.findImpressionsNear(lat, lon, NEARBY_RADIUS_DEGREES_SQUARED)
        val media = dao.findMediaNear(lat, lon, NEARBY_RADIUS_DEGREES_SQUARED)
        return NearbyResult(impressions, media)
    }
}

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 6371.0 * 2 * Math.asin(Math.sqrt(a))
}

data class MediaImportProgress(val done: Int, val total: Int)

data class NearbyResult(
    val impressions: List<Impression>,
    val media: List<MediaItem>
) {
    val isEmpty: Boolean get() = impressions.isEmpty() && media.isEmpty()
}
