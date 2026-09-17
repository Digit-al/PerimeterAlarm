package fr.rsgnl.perimetre.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import fr.rsgnl.perimetre.PerimetreApp
import fr.rsgnl.perimetre.data.Alarm
import fr.rsgnl.perimetre.data.AlarmRepository
import fr.rsgnl.perimetre.data.AppSettings
import fr.rsgnl.perimetre.data.AppLanguage
import fr.rsgnl.perimetre.service.LocationMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _alarms = MutableStateFlow(repository.loadAlarms())
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
        val list = _alarms.value.toMutableList()
        list.firstOrNull { it.id == id }?.enabled = enabled
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
