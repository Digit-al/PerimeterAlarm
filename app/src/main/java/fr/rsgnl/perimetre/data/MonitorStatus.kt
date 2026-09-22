package fr.rsgnl.perimetre.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Statut de surveillance d'une alarme, publié par le service pour la page de debug.
 *
 * @param distanceCenterM Distance (m) entre la position actuelle et le centre de l'alarme (null si pas de position).
 * @param distanceEntryM Distance (m) à l'entrée du périmètre : `distanceCenterM - rayon`.
 *                       Négative (ou 0) si on est déjà dans la zone.
 * @param nextCheckAtMs Instant (epoch ms) de la prochaine vérification planifiée par le service (null si none).
 * @param lastCheckAtMs Instant (epoch ms) de la dernière vérification effectuée (null si jamais).
 * @param speedMps Vitesse de rapprochement estimée (m/s). Positive si on se rapproche.
 */
data class AlarmDebugStatus(
    val name: String,
    val enabled: Boolean,
    val inPeriod: Boolean,
    val distanceCenterM: Double?,
    val distanceEntryM: Double?,
    val nextCheckAtMs: Long?,
    val lastCheckAtMs: Long?,
    val speedMps: Double?,
    val triggered: Boolean
)

/**
 * Point d'accès unique au statut de surveillance (même processus service/UI).
 * Le service publie ici l'état de chaque alarme ; la page de debug et d'autres
 * composants l'observent.
 */
object MonitorStatus {
    private val _statuses = MutableStateFlow<Map<String, AlarmDebugStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, AlarmDebugStatus>> = _statuses.asStateFlow()

    /** Instant (epoch ms) de la dernière publication d'état par le service.
     *  Utile pour la page de debug : quand le service dort, ces valeurs gèlent à
     *  cet instant — l'afficher évite de les prendre pour un état "en direct". */
    private val _lastPublishedAtMs = MutableStateFlow(0L)
    val lastPublishedAtMs: StateFlow<Long> = _lastPublishedAtMs.asStateFlow()

    /** Émet l'id d'une alarme ponctuelle qui vient d'être désactivée après déclenchement. */
    private val _oneShotFired = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val oneShotFired: SharedFlow<String> = _oneShotFired

    fun publishAll(map: Map<String, AlarmDebugStatus>) {
        _statuses.value = map
        _lastPublishedAtMs.value = System.currentTimeMillis()
    }

    fun clear() {
        _statuses.value = emptyMap()
    }

    fun notifyOneShotFired(alarmId: String) {
        _oneShotFired.tryEmit(alarmId)
    }
}
