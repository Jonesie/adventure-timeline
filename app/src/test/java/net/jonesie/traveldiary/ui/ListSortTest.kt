package net.jonesie.traveldiary.ui

import net.jonesie.traveldiary.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSortTest {

    private fun trip(id: Long, start: Long, rating: Int) = Trip(id = id, title = "t$id", startDate = start, endDate = null, rating = rating)

    private val trips = listOf(trip(1, 100, 3), trip(2, 200, 0), trip(3, 300, 5), trip(4, 400, 4), trip(5, 500, 0))

    @Test
    fun dateNewestFirst() {
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), ListSort(SortBy.DATE, ascending = false).apply(trips).map { it.id })
    }

    @Test
    fun dateOldestFirst() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ListSort(SortBy.DATE, ascending = true).apply(trips).map { it.id })
    }

    @Test
    fun ratingHighestFirstWithUnratedLast() {
        assertEquals(listOf(3L, 4L, 1L, 5L, 2L), ListSort(SortBy.RATING, ascending = false).apply(trips).map { it.id })
    }

    @Test
    fun ratingLowestFirstStillKeepsUnratedLast() {
        assertEquals(listOf(1L, 4L, 3L, 5L, 2L), ListSort(SortBy.RATING, ascending = true).apply(trips).map { it.id })
    }
}
