package net.jonesie.traveldiary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM companions WHERE name = :name LIMIT 1")
    suspend fun findCompanionByName(name: String): Companion?

    @Insert
    suspend fun insertTripCompanionCrossRef(crossRef: TripCompanionCrossRef)

    @Insert
    suspend fun insertLeg(leg: Leg): Long

    @Insert
    suspend fun insertMediaItem(mediaItem: MediaItem): Long

    @Query("SELECT tripId, type FROM media_items GROUP BY tripId, type")
    fun observeTripMediaKinds(): Flow<List<TripMediaKind>>

    @Insert
    suspend fun insertMediaItems(items: List<MediaItem>)

    @Query("SELECT uri FROM media_items WHERE tripId = :tripId")
    suspend fun getMediaUris(tripId: Long): List<String>

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

    // Whole-diary backup / restore.
    @Query("SELECT * FROM trips") suspend fun getAllTrips(): List<Trip>
    @Query("SELECT * FROM route_points ORDER BY tripId, timestamp") suspend fun getAllRoutePoints(): List<RoutePoint>
    @Query("SELECT * FROM companions") suspend fun getAllCompanions(): List<Companion>
    @Query("SELECT * FROM trip_companions") suspend fun getAllCrossRefs(): List<TripCompanionCrossRef>
    @Query("SELECT * FROM legs") suspend fun getAllLegs(): List<Leg>
    @Query("SELECT * FROM media_items") suspend fun getAllMedia(): List<MediaItem>
    @Query("SELECT * FROM impressions") suspend fun getAllImpressions(): List<Impression>

    @Insert suspend fun insertTrips(items: List<Trip>)
    @Insert suspend fun insertCompanions(items: List<Companion>)
    @Insert suspend fun insertCrossRefs(items: List<TripCompanionCrossRef>)
    @Insert suspend fun insertLegs(items: List<Leg>)
    @Insert suspend fun insertImpressions(items: List<Impression>)

    @Query("DELETE FROM companions") suspend fun deleteAllCompanions()

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
