package net.jonesie.traveldiary.util

/** Where to leave out of anything shared: everything within [radiusKm] of this point (the user's home). */
data class HiddenArea(val lat: Double, val lon: Double, val radiusKm: Double) {
    fun contains(pointLat: Double, pointLon: Double) = RouteShape.distanceKm(lat, lon, pointLat, pointLon) <= radiusKm
    fun contains(pointLat: Double?, pointLon: Double?) = pointLat != null && pointLon != null && contains(pointLat, pointLon)
}
