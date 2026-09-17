package fr.rsgnl.perimetre.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.rsgnl.perimetre.data.AlarmRepository

/**
 * Redémarre la surveillance après un redémarrage de l'appareil s'il existe des alarmes activées.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val repository = AlarmRepository(context)
            if (repository.loadAlarms().any { it.enabled }) {
                LocationMonitorService.start(context)
            }
        }
    }
}
