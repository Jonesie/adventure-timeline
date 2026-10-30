package net.jonesie.traveldiary.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class TripWithCompanions(
    @Embedded val trip: Trip,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TripCompanionCrossRef::class,
            parentColumn = "tripId",
            entityColumn = "companionId"
        )
    )
    val companions: List<Companion>
)

data class TripDetail(
    @Embedded val trip: Trip,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TripCompanionCrossRef::class,
            parentColumn = "tripId",
            entityColumn = "companionId"
        )
    )
    val companions: List<Companion>,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val legs: List<Leg>,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val media: List<MediaItem>,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val impressions: List<Impression>,
    @Relation(parentColumn = "id", entityColumn = "tripId")
    val incidents: List<Incident>
)

/** One row per (trip, kind of media) pair, so the diary list can show photo/video and audio icons. */
data class TripMediaKind(
    val tripId: Long,
    val type: MediaType
)
