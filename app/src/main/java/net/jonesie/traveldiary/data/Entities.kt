package net.jonesie.traveldiary.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransportType {
    WALK, BIKE, MOTORBIKE, CAR, FOUR_WD, BUS, TRAIN, PLANE, BOAT, OTHER
}

enum class MediaType {
    PHOTO, VIDEO, AUDIO
}

/** Why it happened, in the rider's own estimation. Optional, and only ever for their own eyes. Stored by name. */
enum class IncidentReason {
    NOT_MY_FAULT, BEYOND_CAPABILITY, LACK_OF_FOCUS, STUPIDITY, RATHER_NOT_SAY, OTHER
}

/**
 * What went wrong. Stored by name, so only ever add to the end of the list or rename with care.
 * Labels and icons live in the UI layer ([net.jonesie.traveldiary.ui.label]).
 */
enum class IncidentCategory {
    FELL_OFF, OFF_ROAD, CRASH, NEAR_MISS, WATER, STUCK, LOST, BREAKDOWN, FLAT_TYRE, OUT_OF_FUEL,
    INJURY, WEATHER, ROAD_CLOSED, GEAR, WILDLIFE, DELAY, OTHER
}

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startDate: Long,
    val endDate: Long?,
    val notes: String = "",
    @ColumnInfo(defaultValue = "0") val titleEdited: Boolean = false,
    // 0 = not rated, otherwise 1..5 stars
    @ColumnInfo(defaultValue = "0") val rating: Int = 0
)

@Entity(tableName = "companions")
data class Companion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // Filled in later once matched against Google Contacts.
    val contactId: String? = null
)

@Entity(
    tableName = "trip_companions",
    primaryKeys = ["tripId", "companionId"],
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Companion::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("companionId")]
)
data class TripCompanionCrossRef(
    val tripId: Long,
    val companionId: Long
)

@Entity(
    tableName = "legs",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")]
)
data class Leg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val transportType: TransportType,
    val originName: String,
    val destinationName: String,
    val startTime: Long,
    val endTime: Long?,
    val originLat: Double? = null,
    val originLon: Double? = null,
    val destinationLat: Double? = null,
    val destinationLon: Double? = null,
    @ColumnInfo(defaultValue = "0") val transportEdited: Boolean = false,
    // 0 = not rated, otherwise 1..5 (difficulty: 1 easiest, 5 hardest)
    @ColumnInfo(defaultValue = "0") val difficulty: Int = 0,
    @ColumnInfo(defaultValue = "0") val enjoyment: Int = 0
)

@Entity(
    tableName = "media_items",
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Leg::class, parentColumns = ["id"], childColumns = ["legId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("tripId"), Index("legId"), Index("incidentId")]
)
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Null only for an unassigned incident's photos/videos; they join the trip once it exists.
    val tripId: Long?,
    val legId: Long? = null,
    val type: MediaType,
    val uri: String,
    val timestamp: Long,
    val caption: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    // True once the file's own metadata has been checked for a location (found or not).
    @ColumnInfo(defaultValue = "0") val locationChecked: Boolean = false,
    // The incident this photo/video documents, if any. Deliberately not a foreign key: deleting an
    // incident just un-links its media, which stays in the trip (or is removed if it has no trip).
    val incidentId: Long? = null
)

@Entity(
    tableName = "route_points",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")]
)
data class RoutePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val lat: Double,
    val lon: Double,
    val timestamp: Long
)

@Entity(
    tableName = "impressions",
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Leg::class, parentColumns = ["id"], childColumns = ["legId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("tripId"), Index("legId")]
)
data class Impression(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val legId: Long? = null,
    val text: String,
    val timestamp: Long,
    val lat: Double? = null,
    val lon: Double? = null
)

/**
 * Something that went wrong (or nearly did): a spill, a wrong turn, a river crossing gone sideways.
 * It stands on its own (time, place, category, notes, media) and attaches to whichever trip covers
 * that time once the trip exists, e.g. after a Google Timeline import.
 */
@Entity(
    tableName = "incidents",
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Leg::class, parentColumns = ["id"], childColumns = ["legId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("tripId"), Index("legId")]
)
data class Incident(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Null until the incident is matched to a trip: it's logged on the road, the trip arrives later.
    val tripId: Long? = null,
    val legId: Long? = null,
    val category: IncidentCategory,
    // 0 = not set, 1 = minor, 2 = moderate, 3 = serious
    @ColumnInfo(defaultValue = "0") val severity: Int = 0,
    val notes: String = "",
    val timestamp: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val reason: IncidentReason? = null
)

/**
 * A cache of where each photo/video in the phone's gallery was taken, so the app can find ones near a
 * trip's route without re-reading every file. [lat]/[lon] are null for items with no location.
 */
@Entity(tableName = "gallery_index")
data class GalleryIndexItem(
    @PrimaryKey val uri: String,
    val type: MediaType,
    val takenAt: Long,
    val lat: Double?,
    val lon: Double?
)
