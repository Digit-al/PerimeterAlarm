package fr.rsgnl.perimetre.util

import android.content.Context
import android.location.Location
import android.location.LocationManager

/**
 * Récupération de la dernière position connue (sans FusedLocationProvider pour rester léger).
 */
object LocationUtils {

    fun lastKnownLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        var best: Location? = null
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )) {
            val loc = try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) {
                best = loc
            }
        }
        return best
    }
}
