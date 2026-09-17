package fr.rsgnl.perimetre.util

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Utilitaires géodésiques.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6371000.0

    /** Distance en mètres entre deux points (formule de Haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    fun distanceMeters(location: Location, lat: Double, lon: Double): Double =
        distanceMeters(location.latitude, location.longitude, lat, lon)
}
