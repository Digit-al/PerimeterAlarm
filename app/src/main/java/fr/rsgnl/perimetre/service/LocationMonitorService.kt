package fr.rsgnl.perimetre.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Service en premier plan qui surveille la position et déclenche les alarmes de périmètre.
 *
 * Logique d'intervalle dynamique par alarme :
 *  - vérification de départ toutes les [minIntervalSeconds] secondes ;
 *  - ensuite l'intervalle = durée d'arrivée estimée (distance / vitesse de rapprochement) / 2,
 *    bornée entre l'intervalle minimum et l'intervalle maximum (configurables).
 *  - si l'utilisateur ne se rapproche pas, on repasse à l'intervalle maximum.
 */
class LocationMonitorService : Service() {

    private val repository by lazy { AlarmRepository(this) }
    private val latestLocation = AtomicReference<Location?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var locationManager: LocationManager
    @Volatile
    private var tracking = false
    private val trackedProviders = mutableListOf<String>()
    private val trackers = HashMap<String, Tracker>()
    private val soundPlayer = AlarmSoundPlayer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeChannel = Channel<Unit>(Channel.BUFFERED)

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocation.set(location)
            publishStatuses(repository.loadAlarms())
        }

        @Deprecated("Deprecated in Java")
        override fun onProviderEnabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureChannels()
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

        // Amorce la position connue.
        for (provider in availableProviders()) {
            try {
                locationManager.getLastKnownLocation(provider)?.let { latestLocation.set(it) }
            } catch (ignored: SecurityException) {
            }
        }

        startTracking()
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
        stopTracking()
        soundPlayer.release()
        MonitorStatus.clear()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- Suivi de position ----------------
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

    private fun startTracking() {
        if (tracking) return
        for (provider in availableProviders()) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    MIN_UPDATE_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
                trackedProviders.add(provider)
                tracking = true
            } catch (ignored: SecurityException) {
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }

    private fun stopTracking() {
        if (!tracking) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (ignored: Exception) {
        }
        trackedProviders.clear()
        tracking = false
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
            val activeInPeriod = alarms.filter { a ->
                a.enabled && TimeUtils.isWithinPeriod(
                    a.alwaysOn, a.daysOfWeek,
                    a.startHour, a.startMinute, a.endHour, a.endMinute
                )
            }

            // Suivi GPS uniquement si au moins une alarme est active et dans sa période.
            val shouldTrack = activeInPeriod.isNotEmpty()
            if (shouldTrack != tracking) {
                mainHandler.post {
                    if (shouldTrack) startTracking() else stopTracking()
                }
            }

            publishStatuses(alarms)

            if (activeInPeriod.isEmpty()) {
                trackers.clear()
                // On dort jusqu'au prochain début de période d'une alarme activée (ou jusqu'à
                // un changement de liste, signalé via requestWake). Plus de polling à 30 s.
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
                withTimeoutOrNull(sleepMs) { wakeChannel.receive() }
                continue
            }

            val now = System.currentTimeMillis()
            var nextDue = Long.MAX_VALUE
            val stillActive = HashSet<String>()

            for (alarm in activeInPeriod) {
                stillActive.add(alarm.id)
                val tracker = trackers.getOrPut(alarm.id) { Tracker() }
                if (tracker.lastCheckMs + tracker.nextIntervalMs <= now) {
                    performCheck(alarm, tracker, latestLocation.get(), minMs, maxMs)
                    nextDue = minOf(nextDue, now + tracker.nextIntervalMs)
                } else {
                    nextDue = minOf(nextDue, tracker.lastCheckMs + tracker.nextIntervalMs)
                }
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
            tracker.nextIntervalMs = if (first) minMs else computeInterval(tracker, minMs, maxMs)

            // Déclenchement avec hystérésis (marge de 15 % pour éviter les re-déclenchements).
            if (distance <= alarm.radiusMeters) {
                if (!tracker.triggered) {
                    tracker.triggered = true
                    triggerAlarm(alarm)
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

    private fun computeInterval(tracker: Tracker, minMs: Long, maxMs: Long): Long {
        if (tracker.lastSpeedMps <= SPEED_EPS) return maxMs
        val etaSeconds = tracker.lastDistM / tracker.lastSpeedMps
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
            val inPeriod = TimeUtils.isWithinPeriod(
                a.alwaysOn, a.daysOfWeek,
                a.startHour, a.startMinute, a.endHour, a.endMinute
            )
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
            // Son et vibration gérés par AlarmSoundPlayer (réglages personnalisables).
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
            // Son/vibration émis par AlarmSoundPlayer (pour respecter les réglages).
            .addAction(NotificationCompat.Action.Builder(null, getString(R.string.notif_alarm_stop), pendingDismiss).build())
            .setContentIntent(pendingOpen)
            .build()
    }

    companion object {
        private const val CHANNEL_MONITOR = "perimetre_monitor"
        private const val CHANNEL_ALARM = "perimetre_alarm"
        private const val NOTIF_ID_MONITOR = 100
        private const val ACTION_DISMISS = "fr.rsgnl.perimetre.ACTION_DISMISS"
        private const val EXTRA_ALARM_KEY = "alarm_key"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val MIN_UPDATE_MS = 5000L
        private const val CHECK_NO_ALARM_MS = 30_000L
        private const val MIN_SLEEP_MS = 1_000L
        private const val SPEED_EPS = 0.05 // m/s
        private const val NO_ACTIVE_ALARM_FALLBACK_MS = 15 * 60_000L    // filet de sécurité 15 min
        private const val MAX_SLEEP_CAP_MS = 8L * 24 * 60 * 60 * 1000L  // plafond 8 jours

        private var instance: LocationMonitorService? = null
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
