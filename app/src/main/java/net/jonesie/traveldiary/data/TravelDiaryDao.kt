package net.jonesie.traveldiary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TravelDiaryDao {

    @Insert
    suspend fun insertTrip(trip: Trip): Long

    @Query("SELECT * FROM trips ORDER BY startDate DESC")
    fun observeTrips(): Flow<List<Trip>>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun observeTripDetail(tripId: Long): Flow<TripDetail?>

    /** Used to detect a re-import of the same Google Timeline trip (deterministic date bounds). */
    @Query("SELECT * FROM trips WHERE startDate = :startDate AND endDate = :endDate LIMIT 1")
    suspend fun findTripByDateRange(startDate: Long, endDate: Long): Trip?

    @Query("UPDATE trips SET title = :title WHERE id = :id")
    suspend fun updateTripTitle(id: Long, title: String)

    @Query("UPDATE trips SET titleEdited = CASE WHEN title != :title THEN 1 ELSE titleEdited END, title = :title, notes = :notes, startDate = :startDate, endDate = :endDate WHERE id = :id")
    suspend fun updateTripDetails(id: Long, title: String, notes: String, startDate: Long, endDate: Long?)

    @Query("UPDATE trips SET rating = :rating WHERE id = :id")
    suspend fun updateTripRating(id: Long, rating: Int)

    /**
     * Trips that came within a radius of a point: any GPS trail point, leg endpoint, note or media
     * location. Distances use a flat-earth approximation (longitude scaled by cos(latitude)),
     * with [radiusSquared] in squared degrees — plenty accurate at trip-search scales.
     */
    @Query("""
        SELECT DISTINCT tripId FROM route_points WHERE ((lat - :lat) * (lat - :lat) + ((lon - :lon) * :cosLat) * ((lon - :lon) * :cosLat)) < :radiusSquared UNION
        SELECT DISTINCT tripId FROM legs WHERE originLat IS NOT NULL AND ((originLat - :lat) * (originLat - :lat) + ((originLon - :lon) * :cosLat) * ((originLon - :lon) * :cosLat)) < :radiusSquared UNION
        SELECT DISTINCT tripId FROM legs WHERE destinationLat IS NOT NULL AND ((destinationLat - :lat) * (destinationLat - :lat) + ((destinationLon - :lon) * :cosLat) * ((destinationLon - :lon) * :cosLat)) < :radiusSquared UNION
        SELECT DISTINCT tripId FROM impressions WHERE lat IS NOT NULL AND ((lat - :lat) * (lat - :lat) + ((lon - :lon) * :cosLat) * ((lon - :lon) * :cosLat)) < :radiusSquared UNION
        SELECT DISTINCT tripId FROM incidents WHERE lat IS NOT NULL AND ((lat - :lat) * (lat - :lat) + ((lon - :lon) * :cosLat) * ((lon - :lon) * :cosLat)) < :radiusSquared UNION
        SELECT DISTINCT tripId FROM media_items WHERE lat IS NOT NULL AND ((lat - :lat) * (lat - :lat) + ((lon - :lon) * :cosLat) * ((lon - :lon) * :cosLat)) < :radiusSquared
    """)
    suspend fun findTripIdsNear(lat: Double, lon: Double, cosLat: Double, radiusSquared: Double): List<Long>

    @Query("DELETE FROM trip_companions WHERE tripId = :tripId")
    suspend fun clearTripCompanions(tripId: Long)

    @Query(
        "UPDATE legs SET transportEdited = CASE WHEN transportType != :type THEN 1 ELSE transportEdited END, " +
            "transportType = :type, difficulty = :difficulty, enjoyment = :enjoyment WHERE id IN (:legIds)"
    )
    suspend fun updateLegDetails(legIds: List<Long>, type: TransportType, difficulty: Int, enjoyment: Int)

    @Query("UPDATE legs SET transportType = :to, transportEdited = 1 WHERE tripId = :tripId")
    suspend fun updateAllTripLegsTransport(tripId: Long, to: TransportType)

    @Query("UPDATE legs SET transportType = :to, transportEdited = 1 WHERE tripId = :tripId AND transportType = :from")
    suspend fun updateTripLegsTransport(tripId: Long, from: TransportType, to: TransportType)

    @Query("SELECT * FROM legs WHERE tripId = :tripId AND (transportEdited = 1 OR difficulty > 0 OR enjoyment > 0)")
    suspend fun getEditedLegs(tripId: Long): List<Leg>

    @Query("DELETE FROM legs WHERE tripId = :tripId")
    suspend fun deleteLegsForTrip(tripId: Long)

    @Query("DELETE FROM route_points WHERE tripId = :tripId")
    suspend fun deleteRoutePointsForTrip(tripId: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTripById(id: Long)

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()

    @Insert
    suspend fun insertCompanion(companion: Companion): Long

    @Query("SELECT * FROM companions ORDER BY name")
    fun observeCompanions(): Flow<List<Companion>>

    @Query("UPDATE companions SET contactId = :contactId WHERE id = :id")
    suspend fun setCompanionContact(id: Long, contactId: String?)

    @Query("SELECT * FROM companions WHERE contactId IS NULL")
    suspend fun getCompanionsWithoutContact(): List<Companion>

    @Query("SELECT * FROM companions WHERE name = :name LIMIT 1")
    suspend fun findCompanionByName(name: String): Companion?

    @Insert
    suspend fun insertTripCompanionCrossRef(crossRef: TripCompanionCrossRef)

    @Insert
    suspend fun insertLeg(leg: Leg): Long

    @Insert
    suspend fun insertMediaItem(mediaItem: MediaItem): Long

    @Query("SELECT tripId, type FROM media_items WHERE tripId IS NOT NULL GROUP BY tripId, type")
    fun observeTripMediaKinds(): Flow<List<TripMediaKind>>

    @Insert
    suspend fun insertMediaItems(items: List<MediaItem>)

    @Query("SELECT uri FROM media_items WHERE tripId = :tripId")
    suspend fun getMediaUris(tripId: Long): List<String>

    @Query("SELECT * FROM media_items WHERE type != 'AUDIO' AND lat IS NULL AND locationChecked = 0")
    suspend fun getMediaNeedingLocation(): List<MediaItem>

    @Query("UPDATE media_items SET lat = :lat, lon = :lon, locationChecked = 1 WHERE id = :id")
    suspend fun setMediaLocation(id: Long, lat: Double?, lon: Double?)

    @Query("UPDATE media_items SET uri = :uri WHERE id = :id")
    suspend fun updateMediaUri(id: Long, uri: String)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteMediaItem(id: Long)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTripById(id: Long): Trip?

    @Insert
    suspend fun insertImpression(impression: Impression): Long

    @Insert
    suspend fun insertRoutePoints(points: List<RoutePoint>)

    @Insert
    suspend fun insertIncident(incident: Incident): Long

    @Update
    suspend fun updateIncident(incident: Incident)

    @Query("SELECT * FROM incidents WHERE id = :id")
    suspend fun getIncident(id: Long): Incident?

    @Query("DELETE FROM incidents WHERE id = :id")
    suspend fun deleteIncidentById(id: Long)

    /** Un-links an incident's photos and videos; any that were never in a trip are returned for cleanup. */
    @Query("UPDATE media_items SET incidentId = NULL WHERE incidentId = :incidentId AND tripId IS NOT NULL")
    suspend fun unlinkIncidentMedia(incidentId: Long)

    @Query("SELECT * FROM media_items WHERE incidentId = :incidentId AND tripId IS NULL")
    suspend fun getTripLessIncidentMedia(incidentId: Long): List<MediaItem>

    @Query("DELETE FROM media_items WHERE incidentId = :incidentId AND tripId IS NULL")
    suspend fun deleteTripLessIncidentMedia(incidentId: Long)

    @Query("SELECT * FROM incidents ORDER BY timestamp DESC")
    fun observeIncidents(): Flow<List<Incident>>

    @Query("SELECT * FROM media_items WHERE incidentId IS NOT NULL ORDER BY timestamp")
    fun observeIncidentMedia(): Flow<List<MediaItem>>

    @Query("SELECT * FROM incidents WHERE tripId IS NULL")
    suspend fun getUnassignedIncidents(): List<Incident>

    @Query("UPDATE incidents SET tripId = :tripId, lat = COALESCE(lat, :lat), lon = COALESCE(lon, :lon) WHERE id = :id")
    suspend fun assignIncident(id: Long, tripId: Long, lat: Double?, lon: Double?)

    @Query("UPDATE media_items SET tripId = :tripId WHERE incidentId = :incidentId AND tripId IS NULL")
    suspend fun assignIncidentMedia(incidentId: Long, tripId: Long)

    @Query("UPDATE media_items SET legId = CASE WHEN tripId IS :tripId THEN legId ELSE NULL END, tripId = :tripId WHERE incidentId = :incidentId")
    suspend fun setIncidentMediaTrip(incidentId: Long, tripId: Long?)

    /** A trip's incident photos must not vanish with the trip: they go back to being trip-less. */
    @Query("UPDATE media_items SET tripId = NULL WHERE incidentId IS NOT NULL AND tripId = :tripId")
    suspend fun detachIncidentMediaFromTrip(tripId: Long)

    @Query("DELETE FROM incidents")
    suspend fun deleteAllIncidents()

    @Query("UPDATE incidents SET reason = :to WHERE reason = :from")
    suspend fun renameIncidentReason(from: String, to: String)

    @Query("UPDATE media_items SET incidentId = NULL WHERE id = :id")
    suspend fun clearMediaIncident(id: Long)

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertIncidentPerson(ref: IncidentPersonCrossRef)

    @Query("DELETE FROM incident_people WHERE incidentId = :incidentId")
    suspend fun clearIncidentPeople(incidentId: Long)

    /** Adds every companion involved in a trip's incidents to that trip's companions. Never removes anyone. */
    @Query(
        "INSERT OR IGNORE INTO trip_companions (tripId, companionId) " +
            "SELECT DISTINCT i.tripId, p.companionId FROM incidents i JOIN incident_people p ON p.incidentId = i.id WHERE i.tripId IS NOT NULL"
    )
    suspend fun addIncidentPeopleToTrips()

    @Query("SELECT c.name FROM companions c JOIN incident_people p ON p.companionId = c.id WHERE p.incidentId = :incidentId ORDER BY c.name")
    suspend fun getIncidentPeopleNames(incidentId: Long): List<String>

    @Query("SELECT * FROM incident_people")
    fun observeIncidentPeople(): Flow<List<IncidentPersonCrossRef>>

    @Query("DELETE FROM media_items WHERE tripId IS NULL")
    suspend fun deleteTripLessMedia()

    // Whole-diary backup / restore.
    @Query("SELECT * FROM trips") suspend fun getAllTrips(): List<Trip>
    @Query("SELECT * FROM route_points ORDER BY tripId, timestamp") suspend fun getAllRoutePoints(): List<RoutePoint>
    @Query("SELECT * FROM companions") suspend fun getAllCompanions(): List<Companion>
    @Query("SELECT * FROM trip_companions") suspend fun getAllCrossRefs(): List<TripCompanionCrossRef>
    @Query("SELECT * FROM legs") suspend fun getAllLegs(): List<Leg>
    @Query("SELECT * FROM media_items") suspend fun getAllMedia(): List<MediaItem>
    @Query("SELECT * FROM impressions") suspend fun getAllImpressions(): List<Impression>
    @Query("SELECT * FROM incidents") suspend fun getAllIncidents(): List<Incident>
    @Query("SELECT * FROM incident_people") suspend fun getAllIncidentPeople(): List<IncidentPersonCrossRef>

    @Insert suspend fun insertTrips(items: List<Trip>)
    @Insert suspend fun insertCompanions(items: List<Companion>)
    @Insert suspend fun insertCrossRefs(items: List<TripCompanionCrossRef>)
    @Insert suspend fun insertLegs(items: List<Leg>)
    @Insert suspend fun insertImpressions(items: List<Impression>)
    @Insert suspend fun insertIncidents(items: List<Incident>)
    @Insert suspend fun insertIncidentPeople(items: List<IncidentPersonCrossRef>)

    @Query("DELETE FROM companions") suspend fun deleteAllCompanions()

    // Gallery location index (a cache, not part of backups).
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertGalleryItems(items: List<GalleryIndexItem>)

    @Query("SELECT uri FROM gallery_index") suspend fun getIndexedUris(): List<String>
    @Query("SELECT * FROM gallery_index WHERE lat IS NOT NULL AND lon IS NOT NULL") suspend fun getLocatedGallery(): List<GalleryIndexItem>
    @Query("SELECT COUNT(*) FROM gallery_index") suspend fun galleryIndexSize(): Int
    @Query("DELETE FROM gallery_index WHERE uri IN (:uris)") suspend fun deleteGalleryItems(uris: List<String>)

    @Query("SELECT * FROM route_points WHERE tripId = :tripId ORDER BY timestamp")
    suspend fun getRoutePointsForTrip(tripId: Long): List<RoutePoint>

    /** Nearby past visits, for "have I been here before?" (v1: manual entries only). */
    @Query(
        """
        SELECT * FROM impressions
        WHERE lat IS NOT NULL AND lon IS NOT NULL
        AND (lat - :lat) * (lat - :lat) + (lon - :lon) * (lon - :lon) < :radiusDegreesSquared
        ORDER BY timestamp DESC
        """
    )
    suspend fun findImpressionsNear(lat: Double, lon: Double, radiusDegreesSquared: Double): List<Impression>

    /** Past incidents near a point, so "have I been here before?" can warn about a bad spot. */
    @Query(
        """
        SELECT * FROM incidents
        WHERE lat IS NOT NULL AND lon IS NOT NULL
        AND (lat - :lat) * (lat - :lat) + (lon - :lon) * (lon - :lon) < :radiusDegreesSquared
        ORDER BY timestamp DESC
        """
    )
    suspend fun findIncidentsNear(lat: Double, lon: Double, radiusDegreesSquared: Double): List<Incident>

    @Query(
        """
        SELECT * FROM media_items
        WHERE lat IS NOT NULL AND lon IS NOT NULL
        AND (lat - :lat) * (lat - :lat) + (lon - :lon) * (lon - :lon) < :radiusDegreesSquared
        ORDER BY timestamp DESC
        """
    )
    suspend fun findMediaNear(lat: Double, lon: Double, radiusDegreesSquared: Double): List<MediaItem>
}
