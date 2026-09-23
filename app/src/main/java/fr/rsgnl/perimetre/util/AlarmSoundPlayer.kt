package fr.rsgnl.perimetre.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Lecture des alarmes : sonnerie (MediaPlayer, volume réglable, en boucle) + vibreur.
 * Une instance est partagée par le service ; les lecteurs sont indexés par identifiant
 * d'alarme pour pouvoir les arrêter individuellement.
 *
 * La sonnerie est routée sur le flux « alarme » (USAGE_ALARM) : le volume réglé dans
 * l'application est appliqué en plus du volume alarme système, indépendamment du
 * volume média.
 *
 * Pour que la sonnerie suive les écouteurs branchés (filaire ou Bluetooth) au lieu
 * de rester sur le haut-parleur du téléphone, le lecteur demande aussi le
 * **focus audio** sur le flux alarme avant de jouer : c'est ce focus qui fait
 * que la politique audio du système mélange le flux alarme dans la sortie par
 * défaut courante (écouteurs, casque Bluetooth, …). Le focus est demandé quand
 * la première alarme démarre et libéré quand la dernière s'arrête.
 */
class AlarmSoundPlayer {

    private val players = HashMap<String, MediaPlayer>()
    private val vibrators = HashMap<String, Vibrator>()
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false

    private val focusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {
            // Une alarme doit sonner quoi qu'il arrive : on ne réagit pas aux
            // pertes de focus (on garde simplement le focus demandé).
        }
    }

    /** Démarre la sonnerie (boucle) à un volume donné. [uri] null = son d'alarme système. */
    fun play(id: String, context: Context, uri: String?, volume: Float, loop: Boolean = true) {
        stopSound(id)
        val u = uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer()
            // Sans cela MediaPlayer.create() routait sur le flux média : le volume
            // réglé ici était un pourcentage du volume média courant.
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, u)
            mp.isLooping = loop
            val v = volume.coerceIn(0f, 1f)
            mp.setVolume(v, v)
            mp.prepare()
            // Focus audio (flux alarme) AVANT le start : c'est lui qui route la
            // sonnerie vers les écouteurs branchés au lieu du haut-parleur.
            acquireFocus(context)
            mp.start()
            players[id] = mp
            mp = null
        } catch (ignored: Exception) {
            mp?.release()
        }
    }

    fun stopSound(id: String) {
        val removed = players.remove(id)
        removed?.let {
            try {
                it.stop()
                it.release()
            } catch (ignored: Exception) {
            }
        }
        if (players.isEmpty()) releaseFocus()
    }

    fun stopAllSounds() {
        players.keys.toList().forEach { stopSound(it) }
    }

    /**
     * Demande le focus audio sur le flux « alarme » (USAGE_ALARM) : sans lui, la
     * politique audio peut laisser le flux alarme sur le haut-parleur même quand
     * des écouteurs (filaire ou Bluetooth) sont branchés. Une seule demande est
     * partagée entre toutes les alarmes sonnant en même temps (les joueurs sont
     * comptés via [players]).
     */
    private fun acquireFocus(context: Context) {
        if (focusHeld) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setAcceptsDelayedFocusGain(true)
            .build()
        focusRequest = request
        try {
            am.requestAudioFocus(request)
            focusHeld = true
        } catch (ignored: Exception) {
            // Le focus est un bonus de routage : une échec ne doit pas empêcher
            // la sonnerie de jouer.
        }
    }

    /** Libère le focus audio quand plus aucune sonnerie ne joue. */
    private fun releaseFocus() {
        if (!focusHeld) return
        focusHeld = false
        val req = focusRequest ?: return
        focusRequest = null
        try {
            audioManager?.abandonAudioFocusRequest(req)
        } catch (ignored: Exception) {
        }
    }

    /** Démarre la vibration en boucle (motif répété). */
    fun vibrate(id: String, context: Context) {
        stopVibration(id)
        val vib = getVibrator(context) ?: return
        try {
            val pattern = longArrayOf(0, 600, 400)
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            vibrators[id] = vib
        } catch (ignored: Exception) {
        }
    }

    fun stopVibration(id: String) {
        vibrators.remove(id)?.let {
            try {
                it.cancel()
            } catch (ignored: Exception) {
            }
        }
    }

    fun stopVibrationAll() {
        vibrators.keys.toList().forEach { stopVibration(it) }
    }

    fun release() {
        stopAllSounds()
        releaseFocus()
        stopVibrationAll()
    }

    private fun getVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
