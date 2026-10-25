package net.jonesie.traveldiary.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
import net.jonesie.traveldiary.util.PathPoint
import net.jonesie.traveldiary.util.PlaceInfo
import net.jonesie.traveldiary.util.ReverseGeocoder

class TravelDiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = TravelDiaryDatabase.get(application).dao()

    val trips: StateFlow<List<Trip>> = dao.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        val editedTransport = HashMap<Pair<Long, Long?>, TransportType>()
        val tripId = if (existing != null) {
            if (!existing.titleEdited) dao.updateTripTitle(existing.id, title)
            dao.getEditedLegs(existing.id).forEach { editedTransport[it.startTime to it.endTime] = it.transportType }
            dao.deleteLegsForTrip(existing.id)
            dao.deleteRoutePointsForTrip(existing.id)
            existing.id
        } else {
            dao.insertTrip(Trip(title = title, startDate = startDate, endDate = endDate, notes = "Imported from Google Timeline"))
        }

        sortedLegs.forEachIndexed { index, leg ->
            onProgress("Saving legs (${index + 1}/${sortedLegs.size})")
            val keptTransport = editedTransport[leg.startTime to leg.endTime]
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
                    transportEdited = keptTransport != null
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

    fun setLegsTransport(legIds: List<Long>, type: TransportType) {
        viewModelScope.launch { dao.updateLegsTransport(legIds, type) }
    }

    /** [from] null means every leg of the trip. */
    fun changeTripTransport(tripId: Long, from: TransportType?, to: TransportType) {
        viewModelScope.launch {
            if (from == null) dao.updateAllTripLegsTransport(tripId, to)
            else dao.updateTripLegsTransport(tripId, from, to)
        }
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

data class NearbyResult(
    val impressions: List<Impression>,
    val media: List<MediaItem>
) {
    val isEmpty: Boolean get() = impressions.isEmpty() && media.isEmpty()
}
