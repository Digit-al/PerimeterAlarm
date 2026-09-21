package fr.rsgnl.perimetre.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.rsgnl.perimetre.MainActivity
import fr.rsgnl.perimetre.R
import fr.rsgnl.perimetre.data.Alarm
import fr.rsgnl.perimetre.data.AlarmDebugStatus
import fr.rsgnl.perimetre.data.AlarmRepository
import fr.rsgnl.perimetre.data.MonitorStatus
import fr.rsgnl.perimetre.util.AlarmSoundPlayer
import fr.rsgnl.perimetre.util.Geo
import fr.rsgnl.perimetre.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Service en premier plan qui surveille la position et déclenche les alarmes de périmètre.
 *
 * Stratégie GPS économe : **un fix unique par vérification**.
 * Le GPS ne s'allume que le temps d'obtenir un fix (~5-15 s), puis s'éteint
 * jusqu'à la prochaine vérification. L'intervalle entre vérifications est dynamique :
 *  - si l'utilisateur est loin ou immobile → intervalle max (config, ex: 5 min) ;
 *  - s'il se rapproche → intervalle = ETA / 2, borné entre min et max.
 *
 * Résultat : batterie préservée quand on est loin, détection rapide quand on approche.
 */
class LocationMonitorService : Service() {

    private val repository by lazy { AlarmRepository(this) }
    private val latestLocation = AtomicReference<Location?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var locationManager: LocationManager
    private val trackers = HashMap<String, Tracker>()
    private val soundPlayer = AlarmSoundPlayer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeChannel = Channel<Unit>(Channel.BUFFERED)

    /** PendingIntent réutilisable pour l'alarme de réveil longue durée (setAlarmClock). */
    private val wakeAlarmPending by lazy {
        PendingIntent.getBroadcast(
            this,
            WAKE_REQUEST_CODE,
            Intent(this, WakeReceiver::class.java).setAction(ACTION_WAKE),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureChannels()
        // Annule une éventuelle alarme de réveil laissée par une exécution précédente
        // (un PendingIntent survit à la mort du processus).
        cancelWakeAlarm()
        val notification = buildMonitorNotification(getString(R.string.notif_monitor_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_MONITOR,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID_MONITOR, notification)
        }

        // Amorce avec la dernière position connue (sans allumer le GPS).
        for (provider in availableProviders()) {
            try {
                locationManager.getLastKnownLocation(provider)?.let { latestLocation.set(it) }
            } catch (ignored: SecurityException) {
            }
        }

        publishStatuses(repository.loadAlarms())
        scope.launch { monitorLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            val key = intent.getIntExtra(EXTRA_ALARM_KEY, 0)
            NotificationManagerCompat.from(this).cancel(key)
            intent.getStringExtra(EXTRA_ALARM_ID)?.let { stopAlarmSound(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        soundPlayer.release()
        cancelWakeAlarm()
        MonitorStatus.clear()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- Acquisition de position (single fix) ----------------

    private fun availableProviders(): List<String> {
        val list = mutableListOf<String>()
        if (isProviderEnabled(LocationManager.GPS_PROVIDER)) list.add(LocationManager.GPS_PROVIDER)
        if (isProviderEnabled(LocationManager.NETWORK_PROVIDER)) list.add(LocationManager.NETWORK_PROVIDER)
        if (list.isEmpty()) list.add(LocationManager.GPS_PROVIDER)
        return list
    }

    private fun isProviderEnabled(provider: String): Boolean =
        try {
            locationManager.isProviderEnabled(provider)
        } catch (e: Exception) {
            false
        }

    /**
     * Demande un fix GPS unique (ou réseau en fallback).
     * Le GPS ne reste allumé que le temps de l'acquisition.
     * Retourne null si aucun fix obtenu dans le délai [FIX_TIMEOUT_MS].
     */
    private suspend fun requestSingleFix(): Location? {
        // Tente d'abord le GPS, puis le réseau.
        for (provider in availableProviders()) {
            val fix = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Location?> { cont ->
                    lateinit var removeFn: () -> Unit
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            latestLocation.set(location)
                            removeFn()
                            if (!cont.isCompleted) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onProviderEnabled(provider: String) {}

                        @Deprecated("Deprecated in Java")
                        override fun onProviderDisabled(provider: String) {}
                    }
                    removeFn = {
                        try { locationManager.removeUpdates(listener) }
                        catch (ignored: Exception) { }
                    }
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            0L,   // pas de minTime (on veut un fix rapide)
                            0f,    // pas de minDistance
                            listener,
                            Looper.getMainLooper()
                        )
                    } catch (e: Exception) {
                        if (!cont.isCompleted) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    // Si le contexte est annulé (timeout), on retire le listener.
                    cont.invokeOnCancellation {
                        mainHandler.post { removeFn() }
                    }
                }
            } ?: continue
            return fix
        }
        return null
    }

    // ---------------- Boucle de surveillance ----------------

    private suspend fun monitorLoop() {
        while (true) {
            val alarms = repository.loadAlarms()
            val settings = repository.loadSettings()
            val minSec = settings.minIntervalSeconds.coerceAtLeast(5)
            val maxSec = settings.maxIntervalSeconds.coerceAtLeast(minSec)
            val minMs = minSec.toLong() * 1000L
            val maxMs = maxSec.toLong() * 1000L

            // Alarmes activées ET dans leur période de validité.
            // Une alarme one-shot activée est toujours « in period ».
            val activeInPeriod = alarms.filter { a ->
                a.enabled && isAlarmInPeriod(a)
            }

            publishStatuses(alarms)

            if (activeInPeriod.isEmpty()) {
                trackers.clear()
                // On dort jusqu'au prochain début de période d'une alarme activée
                // (ou jusqu'à un changement de liste, signalé via requestWake).
                val nowMs = System.currentTimeMillis()
                val nextStart = alarms.filter { it.enabled }
                    .mapNotNull {
                        TimeUtils.nextPeriodStart(
                            it.alwaysOn, it.daysOfWeek, it.startHour, it.startMinute
                        )
                    }
                    .minOrNull()
                val targetMs = if (nextStart != null && nextStart > nowMs) nextStart
                               else nowMs + NO_ACTIVE_ALARM_FALLBACK_MS
                val sleepMs = (targetMs - nowMs).coerceIn(MIN_SLEEP_MS, MAX_SLEEP_CAP_MS)
                // Long sommeil (heures/jours) : on planifie une alarme résistante au
                // mode Doze pour que le réveil ne soit pas décalé si l'appareil s'endort.
                if (sleepMs > LONG_SLEEP_THRESHOLD_MS) {
                    scheduleWakeAlarm(nowMs + sleepMs)
                }
                withTimeoutOrNull(sleepMs) { wakeChannel.receive() }
                continue
            }

            // Surveillance active de nouveau : plus besoin de l'alarme de long sommeil.
            cancelWakeAlarm()

            val now = System.currentTimeMillis()
            var nextDue = Long.MAX_VALUE
            val stillActive = HashSet<String>()
            val dueAlarms = mutableListOf<Alarm>()

            for (alarm in activeInPeriod) {
                stillActive.add(alarm.id)
                val tracker = trackers.getOrPut(alarm.id) { Tracker() }
                if (tracker.lastCheckMs + tracker.nextIntervalMs <= now) {
                    dueAlarms.add(alarm)
                }
                nextDue = minOf(nextDue, tracker.lastCheckMs + tracker.nextIntervalMs)
            }

            // Un seul fix GPS pour toutes les vérifications dues ce cycle.
            if (dueAlarms.isNotEmpty()) {
                val location = requestSingleFix() ?: latestLocation.get()
                for (alarm in dueAlarms) {
                    val tracker = trackers[alarm.id]!!
                    performCheck(alarm, tracker, location, minMs, maxMs)
                    nextDue = minOf(nextDue, System.currentTimeMillis() + tracker.nextIntervalMs)
                }
                // Refresh du statut après les checks.
                publishStatuses(alarms)
            }

            for (key in trackers.keys.toList()) {
                if (key !in stillActive) trackers.remove(key)
            }

            val sleepMs = if (nextDue == Long.MAX_VALUE) {
                minMs
            } else {
                (nextDue - System.currentTimeMillis()).coerceAtLeast(MIN_SLEEP_MS)
            }
            delay(sleepMs)
        }
    }

    private fun performCheck(
        alarm: Alarm,
        tracker: Tracker,
        location: Location?,
        minMs: Long,
        maxMs: Long
    ) {
        val now = System.currentTimeMillis()
        val first = tracker.lastCheckMs == 0L

        if (location != null) {
            val distance = Geo.distanceMeters(location, alarm.latitude, alarm.longitude)

            if (!first) {
                val dt = (now - tracker.lastCheckMs) / 1000.0
                if (dt > 0.5) {
                    // Positif si l'utilisateur se rapproche.
                    tracker.lastSpeedMps = (tracker.lastDistM - distance) / dt
                }
            }
            tracker.lastDistM = distance
            tracker.lastCheckMs = now
            tracker.nextIntervalMs = if (first) minMs else computeInterval(tracker, alarm, minMs, maxMs)

            // Déclenchement avec hystérésis (marge de 15 % pour éviter les re-déclenchements).
            if (distance <= alarm.radiusMeters) {
                if (!tracker.triggered) {
                    tracker.triggered = true
                    triggerAlarm(alarm)
                    // Alarme ponctuelle : se désactive après le premier déclenchement.
                    if (alarm.oneShot) {
                        deactivateOneShot(alarm.id)
                    }
                }
            } else if (tracker.triggered && distance > alarm.radiusMeters * 1.15) {
                tracker.triggered = false
                stopAlarmSound(alarm.id)
            }
        } else {
            tracker.lastCheckMs = now
            tracker.nextIntervalMs = if (first) minMs else maxMs
        }
    }

    /**
     * Désactive une alarme ponctuelle après son déclenchement : sauvegarde + notification UI.
     */
    private fun deactivateOneShot(alarmId: String) {
        val alarms = repository.loadAlarms()
        val updated = alarms.map { if (it.id == alarmId) it.copy(enabled = false) else it }
        repository.saveAlarms(updated)
        MonitorStatus.notifyOneShotFired(alarmId)
        // Si plus aucune alarme activée, on peut arrêter le service.
        if (updated.none { it.enabled }) {
            mainHandler.postDelayed({ stop(this) }, 30_000L) // délai pour laisser l'alarme sonner
        }
    }

    /** Une alarme est « in period » si : one-shot activée OU période de validité normale. */
    private fun isAlarmInPeriod(a: Alarm): Boolean {
        if (a.oneShot) return true
        return TimeUtils.isWithinPeriod(
            a.alwaysOn, a.daysOfWeek,
            a.startHour, a.startMinute, a.endHour, a.endMinute
        )
    }

    private fun computeInterval(tracker: Tracker, alarm: Alarm, minMs: Long, maxMs: Long): Long {
        // Distance à l'ENTRÉE du périmètre (et non au centre) : avec un rayon de
        // 200 m, l'ETA au centre est surestimée de rayon/vitesse (666 s à 0,3 m/s !)
        // et l'intervalle reste bloqué au maximum même très près du périmètre.
        val distToEntry = (tracker.lastDistM - alarm.radiusMeters).coerceAtLeast(0.0)
        // À l'intérieur du périmètre ou très proche de l'entrée : vérification la
        // plus rapide — c'est là que l'utilisateur attend un déclenchement immédiat
        // (et une détection rapide de la sortie, via l'hystérésis).
        if (distToEntry <= PROXIMITY_FAST_ZONE_M) return minMs
        if (tracker.lastSpeedMps <= SPEED_EPS) return maxMs
        val etaSeconds = distToEntry / tracker.lastSpeedMps
        val intervalMs = (etaSeconds / 2.0) * 1000.0
        return intervalMs.toLong().coerceIn(minMs, maxMs)
    }

    private fun triggerAlarm(alarm: Alarm) {
        val key = alarm.id.hashCode()
        NotificationManagerCompat.from(this).notify(key, buildAlarmNotification(alarm))

        // Sonnerie/vibreur : réglages de l'alarme, ou défaut applicatif.
        val defaults = repository.loadSettings()
        val sound = if (alarm.sound.useDefault) defaults.defaultSound else alarm.sound
        soundPlayer.play(alarm.id, this, sound.ringtoneUri, sound.volume, loop = true)
        if (sound.useVibration) {
            soundPlayer.vibrate(alarm.id, this)
        }
    }

    private fun stopAlarmSound(alarmId: String) {
        soundPlayer.stopSound(alarmId)
        soundPlayer.stopVibration(alarmId)
    }

    /** Publie l'état de chaque alarme pour la page de debug. */
    private fun publishStatuses(alarms: List<Alarm>) {
        val loc = latestLocation.get()
        val map = HashMap<String, AlarmDebugStatus>()
        for (a in alarms) {
            val t = trackers[a.id]
            val inPeriod = isAlarmInPeriod(a)
            val distCenter = loc?.let { Geo.distanceMeters(it, a.latitude, a.longitude) }
            val distEntry = distCenter?.let { it - a.radiusMeters }
            map[a.id] = AlarmDebugStatus(
                name = a.displayName(this),
                enabled = a.enabled,
                inPeriod = inPeriod,
                distanceCenterM = distCenter,
                distanceEntryM = distEntry,
                nextCheckAtMs = t?.let { it.lastCheckMs + it.nextIntervalMs },
                lastCheckAtMs = t?.lastCheckMs,
                speedMps = t?.lastSpeedMps,
                triggered = t?.triggered ?: false
            )
        }
        MonitorStatus.publishAll(map)
    }

    // ---------------- Réveil longue durée (résistance au Doze) ----------------

    /**
     * Planifie une alarme de réveil qui se déclenche à l'heure pile, y compris
     * si l'appareil est en mode Doze.
     *
     * `setAlarmClock` (API 23+) = exact + whileIdle + icône horloge dans la
     * barre d'état. Depuis Android 12 (S), une app ciblant le SDK 31+ doit
     * détenir `SCHEDULE_EXACT_ALARM` (déclarée dans le manifest, accordée à
     * l'installation, révocable par l'utilisateur dans les paramètres « Alarmes
     * et rappels ») : sans elle, le système lève une `SecurityException`.
     * On vérifie donc `canScheduleExactAlarms()` avant d'appeler, et on
     * enrobe le tout en try/catch : le réveil exact est un bonus (résistance
     * au Doze), il ne doit jamais tuer le processus.
     *
     * Quand l'alarme sonne, `WakeReceiver` réveille la boucle (requestWake)
     * ou redémarre le service si le processus a été tué. La boucle recalcule
     * ensuite : si elle doit encore dormir, elle re-planifie sur la cible
     * recalculée (auto-correction si le réveil a été décalé).
     */
    private fun scheduleWakeAlarm(fireAtMs: Long) {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                Log.i(TAG, "SCHEDULE_EXACT_ALARM absente → réveil inexact (sommeil coroutine)")
                return
            }
            am.setAlarmClock(AlarmManager.AlarmClockInfo(fireAtMs, wakeAlarmPending), wakeAlarmPending)
            Log.i(TAG, "Réveil Doze planifié à " + java.time.Instant.ofEpochMilli(fireAtMs))
        } catch (e: Exception) {
            Log.w(TAG, "setAlarmClock a échoué (${e.javaClass.simpleName}: ${e.message}) → réveil inexact", e)
        }
    }

    /** Annule l'alarme de réveil en attente (no-op si aucune n'est planifiée). */
    private fun cancelWakeAlarm() {
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(wakeAlarmPending)
    }

    // ---------------- Notifications ----------------

    private fun ensureChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val monitor = NotificationChannel(
            CHANNEL_MONITOR, getString(R.string.notif_channel_monitor_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_monitor_desc)
        }
        val alarm = NotificationChannel(
            CHANNEL_ALARM, getString(R.string.notif_channel_alarm_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alarm_desc)
        }
        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(alarm)
    }

    private fun buildMonitorNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    private fun buildAlarmNotification(alarm: Alarm): Notification {
        val key = alarm.id.hashCode()
        val dismiss = Intent(this, LocationMonitorService::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_ALARM_KEY, key)
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val pendingDismiss = PendingIntent.getService(
            this, key, dismiss,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, key + 1, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_alarm_title, alarm.displayName(this)))
            .setContentText(getString(R.string.notif_alarm_text, alarm.radiusMeters))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(NotificationCompat.Action.Builder(null, getString(R.string.notif_alarm_stop), pendingDismiss).build())
            .setContentIntent(pendingOpen)
            .build()
    }

    companion object {
        private const val TAG = "LocationMonitorService"
        private const val CHANNEL_MONITOR = "perimetre_monitor"
        private const val CHANNEL_ALARM = "perimetre_alarm"
        private const val NOTIF_ID_MONITOR = 100
        private const val ACTION_DISMISS = "fr.rsgnl.perimetre.ACTION_DISMISS"
        private const val EXTRA_ALARM_KEY = "alarm_key"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val FIX_TIMEOUT_MS = 15_000L   // délai max pour obtenir un fix
        private const val MIN_SLEEP_MS = 1_000L
        private const val SPEED_EPS = 0.05 // m/s
        private const val NO_ACTIVE_ALARM_FALLBACK_MS = 15 * 60_000L    // filet de sécurité 15 min
        private const val MAX_SLEEP_CAP_MS = 8L * 24 * 60 * 60 * 1000L  // plafond 8 jours

        /**
         * En-deçà de ce seuil, le simple délai coroutine suffit : le Doze
         * « normal » ne démarre qu'après ~30 min d'inactivité (écran éteint,
         * pas de charge, immobile), le « moderate Doze » peut intervenir un peu
         * plus tôt — 10 min est une marge de sécurité conservative.
         */
        private const val LONG_SLEEP_THRESHOLD_MS = 10 * 60_000L

        /**
         * En-deçà de cette distance à l'entrée du périmètre, les vérifications
         * tournent à l'intervalle minimum : c'est près de la frontière que
         // l'utilisateur attend le déclenchement immédiat (la batterie ne pose
         // problème que quand on est loin).
         */
        private const val PROXIMITY_FAST_ZONE_M = 100.0

        private const val WAKE_REQUEST_CODE = 200
        const val ACTION_WAKE = "fr.rsgnl.perimetre.ACTION_WAKE"

        private var instance: LocationMonitorService? = null
        /** Le service est-il toujours en cours d'exécution dans ce processus ? */
        fun isRunning(): Boolean = instance != null
        /** Réveille la boucle de surveillance (appelé quand la liste d'alarmes change). */
        fun requestWake() {
            instance?.wakeChannel?.trySend(Unit)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationMonitorService::class.java))
        }
    }
}

/**
 * État de vérification dynamique par alarme.
 */
private class Tracker {
    var lastCheckMs: Long = 0L
    var lastDistM: Double = Double.MAX_VALUE
    var lastSpeedMps: Double = 0.0
    var nextIntervalMs: Long = 0L
    var triggered: Boolean = false
}
