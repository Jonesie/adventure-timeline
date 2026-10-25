package net.jonesie.traveldiary.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransportType {
    WALK, BIKE, MOTORBIKE, CAR, BUS, TRAIN, PLANE, BOAT, OTHER
}

enum class MediaType {
    PHOTO, VIDEO, AUDIO
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
    @ColumnInfo(defaultValue = "0") val transportEdited: Boolean = false
)

@Entity(
    tableName = "media_items",
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Leg::class, parentColumns = ["id"], childColumns = ["legId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("tripId"), Index("legId")]
)
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val legId: Long? = null,
    val type: MediaType,
    val uri: String,
    val timestamp: Long,
    val caption: String = "",
    val lat: Double? = null,
    val lon: Double? = null
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
