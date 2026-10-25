package net.jonesie.traveldiary.util

import net.jonesie.traveldiary.data.Trip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The span of real time a trip covers, used to match camera photos to it.
 *
 * Trips imported from Google Timeline carry exact instants. Trips entered by hand carry the
 * date picker's UTC-midnight value, which stands for a whole local day, so those are widened
 * to local midnight-to-midnight. A trip with no end date covers just its start day.
 */
object TripTimeWindow {
    private const val DAY_MS = 86_400_000L
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun isDateOnly(ms: Long) = ms % DAY_MS == 0L

    private fun localDate(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(if (isDateOnly(ms)) ZoneOffset.UTC else zone).toLocalDate()

    private fun startOfDay(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun of(trip: Trip): LongRange {
        val end = trip.endDate
        if (end == null) {
            val day = localDate(trip.startDate)
            return startOfDay(day)..(startOfDay(day.plusDays(1)) - 1)
        }
        val from = if (isDateOnly(trip.startDate)) startOfDay(localDate(trip.startDate)) else trip.startDate
        val to = if (isDateOnly(end)) startOfDay(localDate(end).plusDays(1)) - 1 else end
        return from..maxOf(from, to)
    }
}
