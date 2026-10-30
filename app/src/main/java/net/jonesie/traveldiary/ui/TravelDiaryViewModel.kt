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
import net.jonesie.traveldiary.data.Incident
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
import net.jonesie.traveldiary.util.ContactsHelper
import net.jonesie.traveldiary.util.DiaryBackup
import net.jonesie.traveldiary.util.DuplicateCopies
import net.jonesie.traveldiary.data.GalleryIndexItem
import net.jonesie.traveldiary.util.HomeFinder
import net.jonesie.traveldiary.util.MediaLocation
import net.jonesie.traveldiary.util.RouteProximity
import kotlinx.coroutines.sync.withLock
import net.jonesie.traveldiary.util.MediaFiles
import net.jonesie.traveldiary.util.PathPoint
import net.jonesie.traveldiary.util.PlaceInfo
import net.jonesie.traveldiary.util.ReverseGeocoder
import net.jonesie.traveldiary.util.RouteShape
import net.jonesie.traveldiary.util.TripStats
import net.jonesie.traveldiary.util.TripStatsCalculator
import net.jonesie.traveldiary.util.TripTimeWindow
import org.osmdroid.util.GeoPoint

class TravelDiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = TravelDiaryDatabase.get(application).dao()

    // Declared first: later properties read their saved values from it while the class is being built.
    private val prefs = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    val trips: StateFlow<List<Trip>> = dao.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaKindsByTrip: StateFlow<Map<Long, Set<MediaType>>> = dao.observeTripMediaKinds()
        .map { rows -> rows.groupBy({ it.tripId }, { it.type }).mapValues { it.value.toSet() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun tripDetail(tripId: Long): Flow<TripDetail?> = dao.observeTripDetail(tripId)

    /** The companion with this name, created if new, and linked to a matching contact when one exists. */
    private suspend fun companionIdFor(name: String): Long {
        val context = getApplication<Application>()
        val existing = dao.findCompanionByName(name)
        val id = existing?.id ?: dao.insertCompanion(Companion(name = name))
        if (existing?.contactId == null) {
            withContext(Dispatchers.IO) { ContactsHelper.match(context, name) }?.let { dao.setCompanionContact(id, it.lookupKey) }
        }
        return id
    }

    /** Links existing free-text companions to contacts by name. Returns (linked, companions that were unlinked). */
    suspend fun linkCompanionsToContacts(): Pair<Int, Int> {
        val context = getApplication<Application>()
        val unlinked = dao.getCompanionsWithoutContact()
        var linked = 0
        unlinked.forEach { companion ->
            val contact = withContext(Dispatchers.IO) { ContactsHelper.match(context, companion.name, allowFirstName = true) }
            if (contact != null) {
                dao.setCompanionContact(companion.id, contact.lookupKey)
                linked++
            }
        }
        return linked to unlinked.size
    }

    fun addTrip(title: String, startDate: Long, endDate: Long?, notes: String, companionNames: List<String>, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val tripId = dao.insertTrip(Trip(title = title, startDate = startDate, endDate = endDate, notes = notes))
            companionNames.map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
                dao.insertTripCompanionCrossRef(TripCompanionCrossRef(tripId, companionIdFor(name)))
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
                dao.insertTripCompanionCrossRef(TripCompanionCrossRef(tripId, companionIdFor(name)))
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

    fun addMedia(
        tripId: Long?, legId: Long?, type: MediaType, uri: String, caption: String, lat: Double?, lon: Double?,
        incidentId: Long? = null
    ) {
        viewModelScope.launch {
            dao.insertMediaItem(
                MediaItem(
                    tripId = tripId,
                    legId = legId,
                    incidentId = incidentId,
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

    // ---- Incidents

    /** Every incident, newest first, with or without a trip. */
    val incidents: StateFlow<List<Incident>> = dao.observeIncidents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Photos and videos linked to incidents, whether or not their incident has a trip yet. */
    val incidentMedia: StateFlow<List<MediaItem>> = dao.observeIncidentMedia()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addIncident(incident: Incident, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = dao.insertIncident(incident)
            assignIncidentsToTrips()
            onDone(id)
        }
    }

    fun updateIncident(incident: Incident) {
        viewModelScope.launch {
            dao.updateIncident(incident)
            assignIncidentsToTrips()
        }
    }

    suspend fun incident(id: Long): Incident? = dao.getIncident(id)

    /** Photos and videos that belong to a trip stay in it; ones that never had a trip go with the incident. */
    fun deleteIncident(id: Long) {
        viewModelScope.launch {
            val loose = dao.getTripLessIncidentMedia(id)
            dao.deleteTripLessIncidentMedia(id)
            withContext(Dispatchers.IO) { loose.forEach { MediaFiles.deleteIfOwned(getApplication(), it.uri) } }
            dao.unlinkIncidentMedia(id)
            dao.deleteIncidentById(id)
        }
    }

    /**
     * Incidents are logged on the road, before the trip exists. Once a trip covers the moment an
     * incident happened it joins that trip (with its photos and videos), and takes its place from
     * the trip's GPS trail if it was logged without a location.
     */
    suspend fun assignIncidentsToTrips() {
        val waiting = dao.getUnassignedIncidents()
        if (waiting.isEmpty()) return
        val trips = dao.getAllTrips()
        waiting.forEach { incident ->
            val trip = trips.firstOrNull { incident.timestamp in TripTimeWindow.of(it) } ?: return@forEach
            val place = if (incident.lat == null) routePositionAt(trip.id, incident.timestamp) else null
            dao.assignIncident(incident.id, trip.id, place?.first, place?.second)
            dao.assignIncidentMedia(incident.id, trip.id)
        }
    }

    /**
     * Where the trip's GPS trail was around [timestamp], for incidents logged after the fact. Null when
     * the trail doesn't come within [maxGapMs] of that moment.
     */
    suspend fun routePositionAt(tripId: Long, timestamp: Long, maxGapMs: Long = 30 * 60_000L): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            dao.getRoutePointsForTrip(tripId)
                .minByOrNull { Math.abs(it.timestamp - timestamp) }
                ?.takeIf { Math.abs(it.timestamp - timestamp) <= maxGapMs }
                ?.let { it.lat to it.lon }
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
        assignIncidentsToTrips()
    }

    suspend fun routePointsFor(tripId: Long) = dao.getRoutePointsForTrip(tripId)

    suspend fun tripStats(): TripStats = withContext(Dispatchers.IO) {
        TripStatsCalculator.compute(
            trips = dao.getAllTrips(),
            legs = dao.getAllLegs(),
            trails = dao.getAllRoutePoints().groupBy { it.tripId },
            media = dao.getAllMedia(),
            impressions = dao.getAllImpressions(),
            incidents = dao.getAllIncidents()
        )
    }

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
    fun importFiles(
        tripId: Long?, uris: List<Uri>, forcedType: MediaType?, incidentId: Long? = null,
        onDone: (added: Int, failed: Int) -> Unit
    ) {
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
                            timestamp = copied.takenAt ?: System.currentTimeMillis(),
                            incidentId = incidentId
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

    // ---- Finding gallery photos near a trip's route

    // ---- Home area (left out of "near this route" photo suggestions)

    private fun loadHome() = HomeSettings(
        mode = runCatching { HomeMode.valueOf(prefs.getString("homeMode", null) ?: "") }.getOrDefault(HomeMode.AUTO),
        lat = prefs.getString("homeLat", null)?.toDoubleOrNull(),
        lon = prefs.getString("homeLon", null)?.toDoubleOrNull(),
        radiusKm = prefs.getInt("homeRadiusKm", 10)
    )
    private val _homeSettings = MutableStateFlow(loadHome())
    val homeSettings: StateFlow<HomeSettings> = _homeSettings.asStateFlow()

    fun setHomeMode(mode: HomeMode) {
        prefs.edit().putString("homeMode", mode.name).apply()
        _homeSettings.value = loadHome()
    }

    fun setHomeHere(lat: Double, lon: Double) {
        prefs.edit().putString("homeMode", HomeMode.CUSTOM.name).putString("homeLat", lat.toString()).putString("homeLon", lon.toString()).apply()
        _homeSettings.value = loadHome()
    }

    fun setHomeRadius(km: Int) {
        prefs.edit().putInt("homeRadiusKm", km).apply()
        _homeSettings.value = loadHome()
    }

    /** The home centre as the settings define it: set by hand, found from where trips start and end, or off. */
    suspend fun homeCentre(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val home = _homeSettings.value
        when (home.mode) {
            HomeMode.OFF -> null
            HomeMode.CUSTOM -> if (home.lat != null && home.lon != null) home.lat to home.lon else null
            HomeMode.AUTO -> {
                val ends = dao.getAllRoutePoints().groupBy { it.tripId }.values.flatMap { trail ->
                    val ordered = trail.sortedBy { it.timestamp }
                    listOf(ordered.first(), ordered.last()).map { it.lat to it.lon }
                }
                HomeFinder.detect(ends)
            }
        }
    }

    private val _galleryScan = MutableStateFlow<GalleryScan?>(null)
    /** Non-null while the gallery's photo locations are being read. */
    val galleryScan: StateFlow<GalleryScan?> = _galleryScan.asStateFlow()
    private val scanMutex = kotlinx.coroutines.sync.Mutex()

    private fun canReadGallery(context: Application): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Brings the location index up to date with the gallery: only photos and videos not seen before
     * are read, so the first run takes a while and later ones are quick. Needs library access and
     * "location info in photos"; without both it does nothing and says so.
     */
    suspend fun scanGallery(): ScanOutcome = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (!canReadGallery(context) || !MediaLocation.hasOriginalAccess(context)) return@withContext ScanOutcome.NEEDS_PERMISSION
            val all = CameraRoll.find(context, null, cameraOnly = false)
            val known = dao.getIndexedUris().toHashSet()
            val present = all.map { it.uri.toString() }.toHashSet()
            // Forget photos that have since been deleted.
            known.filter { it !in present }.chunked(500).forEach { dao.deleteGalleryItems(it) }

            val fresh = all.filter { it.uri.toString() !in known }
            if (fresh.isEmpty()) return@withContext ScanOutcome.DONE
            _galleryScan.value = GalleryScan(0, fresh.size)
            var done = 0
            try {
                fresh.chunked(24).forEach { chunk ->
                    val rows = coroutineScope {
                        chunk.map { item ->
                            async {
                                val location = runCatching { MediaLocation.readUri(context, item.uri, item.type) }.getOrNull()
                                GalleryIndexItem(item.uri.toString(), item.type, item.takenAt, location?.lat, location?.lon)
                            }
                        }.awaitAll()
                    }
                    dao.insertGalleryItems(rows)
                    done += chunk.size
                    _galleryScan.value = GalleryScan(done, fresh.size)
                }
            } finally {
                _galleryScan.value = null
            }
            ScanOutcome.DONE
        }
    }

    /** Photos/videos from the gallery taken within [radiusKm] of the trip's route, newest first. */
    suspend fun nearRoutePhotos(
        tripId: Long,
        radiusKm: Double,
        notInAnyTrip: Boolean,
        skipHomeArea: Boolean = true
    ): List<CameraRoll.Item> =
        withContext(Dispatchers.IO) {
            val route = RouteProximity.of(dao.getRoutePointsForTrip(tripId), dao.getAllLegs().filter { it.tripId == tripId })
            if (route.isEmpty) return@withContext emptyList()
            val used = if (notInAnyTrip) dao.getAllMedia().map { it.uri }.toHashSet() else emptySet()
            // Trips start and end at home, so photos taken around home would match every route.
            val home = if (skipHomeArea) homeCentre() else null
            val homeRadius = _homeSettings.value.radiusKm.toDouble()
            dao.getLocatedGallery()
                .filter { it.uri !in used && route.isNear(it.lat!!, it.lon!!, radiusKm) }
                .filter { home == null || RouteShape.distanceKm(home.first, home.second, it.lat!!, it.lon!!) > homeRadius }
                .map { CameraRoll.Item(Uri.parse(it.uri), it.type, it.takenAt) }
                .sortedByDescending { it.takenAt }
        }

    /** How many unassigned gallery photos sit along the route, or null until the gallery has been indexed. */
    suspend fun unassignedNearRouteCount(tripId: Long): Int? {
        if (withContext(Dispatchers.IO) { dao.galleryIndexSize() } == 0) return null
        return nearRoutePhotos(tripId, 2.0, notInAnyTrip = true).size
    }

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
        viewModelScope.launch {
            // Incidents survive their trip, and so do the photos and videos that document them.
            dao.detachIncidentMediaFromTrip(tripId)
            dao.deleteTripById(tripId)
        }
    }


    private fun loadBackupSettings() = BackupSettings(
        lastBackup = prefs.getLong("lastBackup", 0L).takeIf { it > 0 },
        reminderDays = prefs.getInt("reminderDays", 14),
        snoozedUntil = prefs.getLong("snoozedUntil", 0L)
    )

    val mapViewState = MapViewState(prefs)

    private val _listSort = MutableStateFlow(
        ListSort(
            by = runCatching { SortBy.valueOf(prefs.getString("sortBy", null) ?: "") }.getOrDefault(SortBy.DATE),
            ascending = prefs.getBoolean("sortAscending", false)
        )
    )
    val listSort: StateFlow<ListSort> = _listSort.asStateFlow()

    fun setListSort(sort: ListSort) {
        prefs.edit().putString("sortBy", sort.by.name).putBoolean("sortAscending", sort.ascending).apply()
        _listSort.value = sort
    }

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("themeMode", null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("themeMode", mode.name).apply()
        _themeMode.value = mode
    }

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
        viewModelScope.launch {
            dao.deleteAllTrips()
            dao.deleteAllIncidents()
            dao.deleteTripLessMedia()
        }
    }

    suspend fun findNearby(lat: Double, lon: Double): NearbyResult {
        val impressions = dao.findImpressionsNear(lat, lon, NEARBY_RADIUS_DEGREES_SQUARED)
        val media = dao.findMediaNear(lat, lon, NEARBY_RADIUS_DEGREES_SQUARED)
        val incidents = dao.findIncidentsNear(lat, lon, NEARBY_RADIUS_DEGREES_SQUARED)
        return NearbyResult(impressions, media, incidents)
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
/**
 * What the all-trips map was showing. It lives in the ViewModel so switching tabs or opening a trip
 * and coming back leaves the map as it was, and it is saved to preferences so a full restart does too.
 */
class MapViewState(private val prefs: android.content.SharedPreferences) {
    var latitude: Double? = prefs.getString("mapLat", null)?.toDoubleOrNull()
        private set
    var longitude: Double? = prefs.getString("mapLon", null)?.toDoubleOrNull()
        private set
    var zoom: Double? = prefs.getString("mapZoom", null)?.toDoubleOrNull()
        private set

    var types: Set<TransportType> = (prefs.getStringSet("mapTypes", null) ?: emptySet())
        .mapNotNull { runCatching { TransportType.valueOf(it) }.getOrNull() }.toSet()
        set(value) {
            if (field == value) return
            field = value
            prefs.edit().putStringSet("mapTypes", value.map { it.name }.toSet()).apply()
        }
    var showWaypoints: Boolean = prefs.getBoolean("mapWaypoints", false)
        set(value) {
            if (field == value) return
            field = value
            prefs.edit().putBoolean("mapWaypoints", value).apply()
        }
    var showPhotos: Boolean = prefs.getBoolean("mapPhotos", false)
        set(value) {
            if (field == value) return
            field = value
            prefs.edit().putBoolean("mapPhotos", value).apply()
        }

    /** Not kept across restarts: a selected trip is only a momentary highlight. */
    var selectedTripId: Long? = null

    val hasCamera get() = latitude != null && longitude != null && zoom != null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val save = Runnable {
        val lat = latitude
        val lon = longitude
        val z = zoom
        if (lat != null && lon != null && z != null) {
            prefs.edit().putString("mapLat", lat.toString()).putString("mapLon", lon.toString()).putString("mapZoom", z.toString()).apply()
        }
    }

    /** Called on every pan/zoom, so the write waits until the map has been still for a moment. */
    fun updateCamera(lat: Double, lon: Double, zoomLevel: Double) {
        latitude = lat
        longitude = lon
        zoom = zoomLevel
        handler.removeCallbacks(save)
        handler.postDelayed(save, 500)
    }
}

enum class SortBy(val label: String) { DATE("Date"), RATING("Rating") }

data class ListSort(val by: SortBy, val ascending: Boolean) {
    /** Unrated trips always go last when sorting by rating, whichever way round; ties are newest first. */
    fun apply(trips: List<Trip>): List<Trip> = when (by) {
        SortBy.DATE -> if (ascending) trips.sortedBy { it.startDate } else trips.sortedByDescending { it.startDate }
        SortBy.RATING -> {
            val (rated, unrated) = trips.partition { it.rating > 0 }
            val byRating = if (ascending) rated.sortedWith(compareBy<Trip> { it.rating }.thenByDescending { it.startDate })
            else rated.sortedWith(compareByDescending<Trip> { it.rating }.thenByDescending { it.startDate })
            byRating + unrated.sortedByDescending { it.startDate }
        }
    }
}

enum class HomeMode { AUTO, CUSTOM, OFF }

data class HomeSettings(val mode: HomeMode, val lat: Double?, val lon: Double?, val radiusKm: Int)

data class GalleryScan(val done: Int, val total: Int)

enum class ScanOutcome { DONE, NEEDS_PERMISSION }

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
    val media: List<MediaItem>,
    val incidents: List<Incident> = emptyList()
) {
    val isEmpty: Boolean get() = impressions.isEmpty() && media.isEmpty() && incidents.isEmpty()
}
