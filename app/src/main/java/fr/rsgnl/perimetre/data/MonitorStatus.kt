package fr.rsgnl.perimetre.data

import kotlinx.coroutines.flow.MutableStateFlow
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

    fun publishAll(map: Map<String, AlarmDebugStatus>) {
        _statuses.value = map
    }

    fun clear() {
        _statuses.value = emptyMap()
    }
}
