package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Impression
import net.jonesie.traveldiary.data.Incident
import net.jonesie.traveldiary.data.IncidentCategory
import net.jonesie.traveldiary.data.Leg
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.RoutePoint
import net.jonesie.traveldiary.data.TransportType
import net.jonesie.traveldiary.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatsCalculatorTest {

    // Mid-June, so the calendar year is the same in every time zone.
    private val june2025 = 1_749_945_600_000L
    private val june2026 = 1_781_481_600_000L

    private fun trip(id: Long, start: Long, rating: Int = 0) =
        Trip(id = id, title = "Trip $id", startDate = start, endDate = null, rating = rating)

    private fun point(tripId: Long, lat: Double, t: Long) = RoutePoint(tripId = tripId, lat = lat, lon = 172.0, timestamp = t)

    private fun leg(tripId: Long, type: TransportType, start: Long, end: Long, difficulty: Int = 0, enjoyment: Int = 0) = Leg(
        tripId = tripId, transportType = type, originName = "a", destinationName = "b", startTime = start, endTime = end,
        difficulty = difficulty, enjoyment = enjoyment
    )

    @Test
    fun countsTripsAndGroupsThemByYear() {
        val stats = TripStatsCalculator.compute(
            trips = listOf(trip(1, june2025), trip(2, june2025 + 86_400_000), trip(3, june2026)),
            legs = emptyList(), trails = emptyMap(), media = emptyList(), impressions = emptyList()
        )
        assertEquals(3, stats.trips)
        assertEquals(listOf(2025, 2026), stats.byYear.map { it.year })
        assertEquals(listOf(2, 1), stats.byYear.map { it.trips })
        assertEquals(3, stats.daysOnTheRoad)
    }

    @Test
    fun addsUpDistanceFromTheTrailAndAttributesItToTheLegTransport() {
        val trail = listOf(point(1, -43.0, 1000), point(1, -43.1, 2000), point(1, -43.2, 3000))
        val expected = RouteShape.distanceKm(-43.0, 172.0, -43.1, 172.0) + RouteShape.distanceKm(-43.1, 172.0, -43.2, 172.0)
        val stats = TripStatsCalculator.compute(
            trips = listOf(trip(1, june2025)),
            legs = listOf(leg(1, TransportType.MOTORBIKE, 0, 4000)),
            trails = mapOf(1L to trail), media = emptyList(), impressions = emptyList()
        )
        assertEquals(expected, stats.totalKm, 0.01)
        assertEquals(TransportType.MOTORBIKE, stats.byTransport.first().type)
        assertEquals(expected, stats.byTransport.first().km, 0.01)
    }

    @Test
    fun aFlightWithNoTrailCountsAsAStraightLine() {
        val flight = Leg(
            tripId = 1, transportType = TransportType.PLANE, originName = "a", destinationName = "b",
            startTime = 0, endTime = 10_000, originLat = -43.49, originLon = 172.53,
            destinationLat = -33.93, destinationLon = 151.18
        )
        val stats = TripStatsCalculator.compute(
            trips = listOf(trip(1, june2025)), legs = listOf(flight),
            trails = emptyMap(), media = emptyList(), impressions = emptyList()
        )
        assertTrue("was ${stats.totalKm}", stats.totalKm in 2000.0..2300.0)
        assertEquals(TransportType.PLANE, stats.byTransport.first().type)
    }

    @Test
    fun countsMediaAndNotes() {
        val media = listOf(
            MediaItem(tripId = 1, type = MediaType.PHOTO, uri = "a", timestamp = 0),
            MediaItem(tripId = 1, type = MediaType.PHOTO, uri = "b", timestamp = 0),
            MediaItem(tripId = 1, type = MediaType.VIDEO, uri = "c", timestamp = 0),
            MediaItem(tripId = 1, type = MediaType.AUDIO, uri = "d", timestamp = 0)
        )
        val stats = TripStatsCalculator.compute(
            trips = listOf(trip(1, june2025)), legs = emptyList(), trails = emptyMap(), media = media,
            impressions = listOf(Impression(tripId = 1, text = "wow", timestamp = 0))
        )
        assertEquals(2, stats.photos)
        assertEquals(1, stats.videos)
        assertEquals(1, stats.voiceNotes)
        assertEquals(1, stats.notes)
    }

    @Test
    fun ratingsAreSummarisedAndTopRatedAreListed() {
        val stats = TripStatsCalculator.compute(
            trips = listOf(trip(1, june2025, rating = 5), trip(2, june2025, rating = 3), trip(3, june2025), trip(4, june2025, rating = 4)),
            legs = listOf(leg(1, TransportType.CAR, 0, 1, difficulty = 2, enjoyment = 4), leg(1, TransportType.CAR, 0, 1, difficulty = 4)),
            trails = emptyMap(), media = emptyList(), impressions = emptyList()
        )
        assertEquals(listOf(1, 0, 0, 1, 1, 1), stats.ratingCounts)
        assertEquals(4.0, stats.averageRating!!, 0.001)
        assertEquals(3.0, stats.averageDifficulty!!, 0.001)
        assertEquals(4.0, stats.averageEnjoyment!!, 0.001)
        assertEquals(listOf(1L, 4L), stats.topRated.map { it.id })
    }

    @Test
    fun noRatingsMeansNoAverages() {
        val stats = TripStatsCalculator.compute(
            trips = listOf(trip(1, june2025)), legs = emptyList(), trails = emptyMap(), media = emptyList(), impressions = emptyList()
        )
        assertNull(stats.averageRating)
        assertNull(stats.longestTrip)
    }

    @Test
    fun countsIncidentsByCategoryMostCommonFirst() {
        fun incident(category: IncidentCategory, severity: Int = 0) =
            Incident(tripId = null, category = category, severity = severity, timestamp = june2025)
        val stats = TripStatsCalculator.compute(
            trips = listOf(trip(1, june2025)), legs = emptyList(), trails = emptyMap(), media = emptyList(), impressions = emptyList(),
            incidents = listOf(
                incident(IncidentCategory.LOST), incident(IncidentCategory.FELL_OFF, severity = 3),
                incident(IncidentCategory.FELL_OFF), incident(IncidentCategory.WATER, severity = 2)
            )
        )
        assertEquals(4, stats.incidents)
        assertEquals(listOf(IncidentCategory.FELL_OFF, IncidentCategory.WATER, IncidentCategory.LOST), stats.byIncident.map { it.category })
        assertEquals(2, stats.byIncident.first().count)
        assertEquals(1, stats.seriousIncidents)
    }
}
