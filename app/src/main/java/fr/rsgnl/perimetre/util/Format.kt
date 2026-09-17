package fr.rsgnl.perimetre.util

import android.content.Context
import fr.rsgnl.perimetre.R
import java.util.Locale

/**
 * Formatage divers (distances, libellés).
 */
object Format {

    /** Distance lisible : mètres en dessous de 1 km, kilomètres au-delà. */
    fun distance(context: Context, meters: Double?): String {
        if (meters == null) return context.getString(R.string.unknown)
        return if (meters < 1000) {
            String.format(Locale.getDefault(), context.getString(R.string.fmt_distance_m), meters)
        } else {
            String.format(Locale.getDefault(), context.getString(R.string.fmt_distance_km), meters / 1000.0)
        }
    }

    /** Valeur lisible de la distance à l'entrée d'un périmètre (négative = déjà dedans). */
    fun distanceToEntry(context: Context, entryMeters: Double?): String {
        if (entryMeters == null) return context.getString(R.string.fmt_unknown_location)
        return if (entryMeters <= 0) context.getString(R.string.fmt_in_zone)
        else distance(context, entryMeters)
    }
}
