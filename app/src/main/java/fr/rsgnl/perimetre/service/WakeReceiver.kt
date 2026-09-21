package fr.rsgnl.perimetre.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.rsgnl.perimetre.data.AlarmRepository

/**
 * Reçoit le broadcast planifié par `AlarmManager.setAlarmClock` (voir
 * `LocationMonitorService.scheduleWakeAlarm`).
 *
 * Sert à rendre fiables les longs sommeils de la boucle de surveillance
 * (heures/jours, quand aucune alarme n'est dans sa période) : quand
 * l'appareil est en mode Doze, un simple délai coroutine peut être décalé,
 * alors qu'une alarme « clock » se déclenche à l'heure pile.
 *
 * Deux cas :
 *  - le service est encore vivant → on réveille la boucle (requestWake,
 *    qui coupe le sommeil via le channel) ;
 *  - le processus a été tué → on redémarre le service si au moins une
 *    alarme est activée (même logique que BootReceiver).
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocationMonitorService.ACTION_WAKE) return
        if (LocationMonitorService.isRunning()) {
            LocationMonitorService.requestWake()
        } else {
            val repository = AlarmRepository(context)
            if (repository.loadAlarms().any { it.enabled }) {
                LocationMonitorService.start(context)
            }
        }
    }
}
