package net.jonesie.traveldiary.util

/**
 * Works out where "home" is from where trips start and end: the busiest spot among them. Trips that
 * leave from and return to the same place are how the app can tell it isn't part of any one trip.
 */
object HomeFinder {

    private const val CLUSTER_KM = 2.0

    /**
     * [points] are the first and last recorded point of each trip as (lat, lon). Returns the centre of
     * the busiest cluster, or null if there are too few trips for anything to stand out.
     */
    fun detect(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.size < 6) return null
        var best: List<Pair<Double, Double>> = emptyList()
        for (p in points) {
            val cluster = points.filter { RouteShape.distanceKm(p.first, p.second, it.first, it.second) < CLUSTER_KM }
            if (cluster.size > best.size) best = cluster
        }
        // It only counts as home if a good share of all the starts and ends are there.
        if (best.size < 3 || best.size * 4 < points.size) return null
        return best.sumOf { it.first } / best.size to best.sumOf { it.second } / best.size
    }
}
