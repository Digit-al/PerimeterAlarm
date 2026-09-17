package fr.rsgnl.perimetre.util

import java.time.ZonedDateTime

/**
 * Utilitaires de période / heure.
 */
object TimeUtils {

    /**
     * Indique si le moment [now] est dans la période de validité.
     *
     * Les jours sont encodés en ISO (1 = lundi … 7 = dimanche).
     * Gère le cas où l'heure de fin est avant l'heure de début (nuit, par ex. 22h → 06h).
     */
    fun isWithinPeriod(
        alwaysOn: Boolean,
        daysOfWeek: Set<Int>,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Boolean {
        if (alwaysOn) return true
        if (now.dayOfWeek.value !in daysOfWeek) return false
        val current = now.hour * 60 + now.minute
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        return if (start <= end) {
            current in start..end
        } else {
            // période qui traverse minuit
            current >= start || current <= end
        }
    }

    /**
     * Prochain instant où l'alarme (re)entre dans sa période de validité.
     * Retourne null si l'alarme est toujours active ou si aucun jour n'est sélectionné.
     * Valeur en millisecondes epoch.
     */
    fun nextPeriodStart(
        alwaysOn: Boolean,
        daysOfWeek: Set<Int>,
        startHour: Int,
        startMinute: Int,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Long? {
        if (alwaysOn) return null
        if (daysOfWeek.isEmpty()) return null
        var best: ZonedDateTime? = null
        // On explore les 8 prochains jours : garantit de trouver le prochain début de période
        // (au plus 7 jours plus loin).
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (day.dayOfWeek.value !in daysOfWeek) continue
            val candidate = ZonedDateTime.of(day.atTime(startHour, startMinute), now.zone)
            if (candidate.isAfter(now) && (best == null || candidate.isBefore(best))) {
                best = candidate
            }
        }
        return best?.toInstant()?.toEpochMilli()
    }

    fun formatHourMinute(hour: Int, minute: Int): String =
        String.format("%02d:%02d", hour, minute)

    /** Libellé lisible des jours sélectionnés (ISO 1..7). [dayNames] ordonné lundi → dimanche. */
    fun formatDays(days: Set<Int>, dayNames: Array<String>): String {
        return days.sorted().joinToString(" ") { dayNames[it - 1] }
    }
}
