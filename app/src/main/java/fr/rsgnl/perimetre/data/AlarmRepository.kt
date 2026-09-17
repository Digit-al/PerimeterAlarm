package fr.rsgnl.perimetre.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fr.rsgnl.perimetre.service.LocationMonitorService

/**
 * Persistance simple des alarmes et des paramètres via SharedPreferences + Gson.
 */
class AlarmRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadAlarms(): MutableList<Alarm> {
        val json = prefs.getString(KEY_ALARMS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Alarm>>() {}.type
            val list = gson.fromJson<MutableList<Alarm>>(json, type) ?: mutableListOf()
            // Normalise les champs absents dans les sauvegardes anciennes (Gson -> null).
            list.forEach { a ->
                val snd: SoundSettings? = a.sound
                a.sound = snd?.normalized() ?: SoundSettings()
                a.radiusMeters = a.radiusMeters.coerceIn(1, Int.MAX_VALUE)
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAlarms(alarms: List<Alarm>) {
        prefs.edit().putString(KEY_ALARMS, gson.toJson(alarms)).apply()
        // Réveille la surveillance : la situation (périodes, alarmes actives) peut avoir changé.
        LocationMonitorService.requestWake()
    }

    fun loadSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            val s = gson.fromJson<AppSettings>(json, AppSettings::class.java) ?: AppSettings()
            // Normalise les champs absents dans les sauvegardes anciennes (Gson -> null).
            val snd: SoundSettings? = s.defaultSound
            s.defaultSound = snd?.normalized() ?: SoundSettings(useDefault = false)
            s.minIntervalSeconds = s.minIntervalSeconds.coerceAtLeast(5)
            s.maxIntervalSeconds = s.maxIntervalSeconds.coerceAtLeast(s.minIntervalSeconds)
            s
        } catch (e: Exception) {
            AppSettings()
        }
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, gson.toJson(settings)).apply()
    }

    companion object {
        private const val PREFS_NAME = "perimetre_prefs"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_SETTINGS = "settings"
    }
}
