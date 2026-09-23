package fr.rsgnl.perimetre.data

import android.content.Context
import fr.rsgnl.perimetre.R
import java.util.UUID

/**
 * Réglages sonores d'une alarme (sonnerie, volume, vibreur).
 *
 * @param useDefault Si vrai, les réglages "par défaut" de l'application sont utilisés
 *                   (uniquement pertinent au niveau d'une alarme, pas au niveau global).
 * @param useVibration Active le vibreur en plus de la sonnerie.
 * @param volume Volume de la sonnerie, de 0f (muet) à 1f (max).
 * @param ringtoneUri Uri du son de sonnerie, ou null pour le son d'alarme système par défaut.
 */
data class SoundSettings(
    var useDefault: Boolean = true,
    var useVibration: Boolean = true,
    var volume: Float = 1.0f,
    var ringtoneUri: String? = null
) {
    /** Copie normalisée : volume borné entre 0 et 1. */
    fun normalized(): SoundSettings = copy(volume = volume.coerceIn(0f, 1f))
}

/**
 * Une alarme de périmètre.
 *
 * @param oneShot Alarme ponctuelle : activée manuellement, se désactive automatiquement
 *                après le premier déclenchement. Les jours/heures sont ignorés.
 * @param retriggerable Si l'alarme peut se déclencher plusieurs fois dans sa période
 *                de validité : à `true`, elle se réarme quand l'utilisateur quitte
 *                le périmètre (hystérésis) puis ré-entre ; à `false`, elle ne sonne
 *                qu'une fois par période (réarmée au début de la période suivante).
 *                Nullable pour la compatibilité avec les sauvegardes anciennes
 *                (Gson → null) : `AlarmRepository.loadAlarms()` normalise en `true`.
 * @param daysOfWeek Jours de la semaine où l'alarme est valide, encodés en ISO (1 = lundi … 7 = dimanche).
 *                   Ignoré si [alwaysOn] est vrai ou si [oneShot] est vrai.
 * @param sound Réglages sonores spécifiques à cette alarme (ou usage du défaut applicatif).
 */
data class Alarm(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var latitude: Double = 48.8566,
    var longitude: Double = 2.3522,
    var radiusMeters: Int = 200,
    var oneShot: Boolean = false,
    var alwaysOn: Boolean = false,
    var daysOfWeek: Set<Int> = (1..7).toSet(),
    var startHour: Int = 8,
    var startMinute: Int = 0,
    var endHour: Int = 20,
    var endMinute: Int = 0,
    var enabled: Boolean = true,
    var retriggerable: Boolean? = true,
    var sound: SoundSettings = SoundSettings()
) {
    /** Nom affiché : le nom saisi, ou un nom par défaut basé sur les coordonnées. */
    fun displayName(context: Context): String =
        name.trim().ifEmpty {
            context.getString(R.string.alarm_default_name, latitude, longitude)
        }
}

/**
 * Langues supportées par l'application.
 *
 * @see AppSettings.language
 */
object AppLanguage {
    const val AUTO = "auto" // suit la langue du téléphone (défaut)
    const val EN = "en"
    const val FR = "fr"
    val SUPPORTED = listOf(AUTO, EN, FR)
}

/**
 * Paramètres globaux de l'application.
 *
 * @param minIntervalSeconds Intervalle minimum (secondes) entre deux vérifications.
 * @param maxIntervalSeconds Intervalle maximum (secondes) entre deux vérifications.
 * @param defaultSound Réglages sonores par défaut, appliqués aux alarmes qui les utilisent.
 * @param language Langue de l'interface (AppLanguage), « auto » suit le téléphone.
 */
data class AppSettings(
    var minIntervalSeconds: Int = 30,
    var maxIntervalSeconds: Int = 300,
    var defaultSound: SoundSettings = SoundSettings(useDefault = false),
    var language: String = AppLanguage.AUTO
)
