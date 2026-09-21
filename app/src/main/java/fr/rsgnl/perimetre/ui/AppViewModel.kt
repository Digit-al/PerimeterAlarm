package fr.rsgnl.perimetre.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import fr.rsgnl.perimetre.PerimetreApp
import fr.rsgnl.perimetre.data.Alarm
import fr.rsgnl.perimetre.data.AlarmRepository
import fr.rsgnl.perimetre.data.AppSettings
import fr.rsgnl.perimetre.data.AppLanguage
import fr.rsgnl.perimetre.data.ConfigExport
import fr.rsgnl.perimetre.data.MonitorStatus
import fr.rsgnl.perimetre.service.LocationMonitorService
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Écrans de l'application. */
sealed class Screen {
    object Home : Screen()
    object Editor : Screen()
    object Settings : Screen()
    object Debug : Screen()
}

/**
 * ViewModel principal : liste des alarmes, paramètres et navigation.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = AlarmRepository(app)

    private val _alarms: MutableStateFlow<List<Alarm>> = MutableStateFlow(repository.loadAlarms())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _settings = MutableStateFlow(repository.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** Id de l'alarme en cours d'édition, ou null si création. */
    private val _editingId = MutableStateFlow<String?>(null)
    val editingId: StateFlow<String?> = _editingId.asStateFlow()

    init {
        refreshService()
        // Réagit quand une alarme ponctuelle est désactivée par le service après déclenchement.
        viewModelScope.launch {
            MonitorStatus.oneShotFired.collect { alarmId ->
                val list = _alarms.value.map { if (it.id == alarmId) it.copy(enabled = false) else it }
                _alarms.value = list
            }
        }
    }

    // ---- Navigation ----
    fun openNewAlarm() {
        _editingId.value = null
        _screen.value = Screen.Editor
    }

    fun openEditAlarm(id: String) {
        _editingId.value = id
        _screen.value = Screen.Editor
    }

    fun openSettings() {
        _screen.value = Screen.Settings
    }

    fun openDebug() {
        _screen.value = Screen.Debug
    }

    fun goHome() {
        _screen.value = Screen.Home
    }

    fun goBack() {
        _screen.value = Screen.Home
    }

    // ---- Alarmes ----
    fun getAlarm(id: String?): Alarm? =
        id?.let { id2 -> _alarms.value.firstOrNull { a -> a.id == id2 } }

    fun saveAlarm(alarm: Alarm) {
        val list = _alarms.value.toMutableList()
        val index = list.indexOfFirst { it.id == alarm.id }
        if (index >= 0) list[index] = alarm else list.add(alarm)
        repository.saveAlarms(list)
        _alarms.value = list
        refreshService()
        goHome()
    }

    fun deleteAlarm(id: String) {
        val list = _alarms.value.toMutableList()
        list.removeAll { it.id == id }
        repository.saveAlarms(list)
        _alarms.value = list
        refreshService()
    }

    fun toggleAlarm(id: String, enabled: Boolean) {
        val list = _alarms.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        repository.saveAlarms(list)
        _alarms.value = list
        refreshService()
    }

    // ---- Paramètres ----
    fun updateSettings(settings: AppSettings) {
        repository.saveSettings(settings)
        _settings.value = settings
    }

    /**
     * Change la langue de l'interface et l'applique immédiatement
     * (l'activité est recréée par AppCompat avec la nouvelle configuration).
     *
     * @param language Valeur [AppLanguage] (« auto », « en », « fr »).
     */
    fun setLanguage(language: String) {
        if (language !in AppLanguage.SUPPORTED) return
        updateSettings(_settings.value.copy(language = language))
        AppCompatDelegate.setApplicationLocales(PerimetreApp.localesFor(language))
    }

    // ---- Export / Import ----

    private val gson = Gson()

    /**
     * Sérialise la configuration actuelle (paramètres + alarmes) en JSON.
     *
     * @return Chaîne JSON, ou null si une erreur survient.
     */
    fun exportConfigJson(): String? = try {
        val export = ConfigExport(
            version = 1,
            settings = _settings.value,
            alarms = _alarms.value
        )
        gson.toJson(export)
    } catch (e: Exception) {
        null
    }

    /**
     * Importe une configuration depuis une chaîne JSON.
     * Remplace les alarmes existantes et met à jour les paramètres.
     *
     * @return true si l'import a réussi, false sinon.
     */
    fun importConfigJson(json: String): Boolean {
        val export = try {
            gson.fromJson(json, ConfigExport::class.java) ?: return false
        } catch (e: Exception) {
            return false
        }
        // Normalise (même logique que AlarmRepository.loadAlarms/loadSettings).
        export.alarms.forEach { a ->
            val snd = a.sound
            a.sound = snd?.normalized() ?: fr.rsgnl.perimetre.data.SoundSettings()
            a.radiusMeters = a.radiusMeters.coerceIn(1, Int.MAX_VALUE)
        }
        val s = export.settings
        val snd = s.defaultSound
        s.defaultSound = snd?.normalized() ?: fr.rsgnl.perimetre.data.SoundSettings(useDefault = false)
        s.minIntervalSeconds = s.minIntervalSeconds.coerceAtLeast(5)
        s.maxIntervalSeconds = s.maxIntervalSeconds.coerceAtLeast(s.minIntervalSeconds)
        s.language = if (s.language in AppLanguage.SUPPORTED) s.language else AppLanguage.AUTO

        repository.saveAlarms(export.alarms)
        repository.saveSettings(s)
        _alarms.value = export.alarms
        _settings.value = s
        refreshService()
        return true
    }

    /** Démarre/arrête le service selon la présence d'alarmes activées. */
    private fun refreshService() {
        val context = getApplication<Application>()
        if (_alarms.value.any { it.enabled }) {
            LocationMonitorService.start(context)
        } else {
            LocationMonitorService.stop(context)
        }
    }

}
