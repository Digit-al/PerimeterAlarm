package fr.rsgnl.perimetre.util

import java.util.Locale

/**
 * Formatage divers (distances, libellés).
 */
object Format {

    /** Distance lisible : mètres en dessous de 1 km, kilomètres au-delà. */
    fun distance(meters: Double?): String {
        if (meters == null) return "—"
        return if (meters < 1000) {
            String.format(Locale.FRENCH, "%.0f m", meters)
        } else {
            String.format(Locale.FRENCH, "%.2f km", meters / 1000.0)
        }
    }

    /** Libellé de distance à l'entrée d'un périmètre (négatif = déjà dedans). */
    fun distanceToEntry(entryMeters: Double?): String {
        if (entryMeters == null) return "position inconnue"
        return if (entryMeters <= 0) {
            "dans la zone"
        } else {
            distance(entryMeters) + " à l'entrée"
        }
    }
}
