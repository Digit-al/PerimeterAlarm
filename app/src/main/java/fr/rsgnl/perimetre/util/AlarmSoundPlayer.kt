package fr.rsgnl.perimetre.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
 * **Routage de la sortie** (pour que l'alarme sonne dans les écouteurs branchés
 * au lieu du haut-parleur du téléphone) :
 *
 * - quand une **sortie externe** (casque Bluetooth A2DP, écouteurs filaires ou
 *   USB/BLE, dock, …) est connectée, la sonnerie est jouée sur le **flux média**
 *   (`USAGE_MEDIA`) : ce flux est toujours mélangé vers la sortie active, le son
 *   passe donc dans les écouteurs ;
 * - sinon, la sonnerie est jouée sur le **flux alarme** (`USAGE_ALARM`) : volume
 *   indépendant du volume média, audible même en mode silencieux.
 *
 * Dans les deux cas, le **focus audio** est demandé sur le flux correspondant
 * avant la lecture : une seule demande est partagée (acquise quand la première
 * alarme démarre, libérée quand la dernière s'arrête, re-demandée si le flux
 * change en cours de route).
 *
 * [recheckOutput] ré-évalue la sortie connectée pour une alarme qui sonne : si
 * l'utilisateur branche (ou débranche) ses écouteurs en cours de sonnerie, le
 * lecteur est relancé sur le bon flux (le service l'appelle périodiquement).
 */
class AlarmSoundPlayer {

    private class SoundEntry(
        val player: MediaPlayer,
        val uri: String?,
        val volume: Float,
        val loop: Boolean,
        val externalOutput: Boolean
    )

    private val players = HashMap<String, SoundEntry>()
    private val vibrators = HashMap<String, Vibrator>()
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var focusUsage: Int = AudioAttributes.USAGE_ALARM

    private val focusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {
            // Une alarme doit sonner quoi qu'il arrive : on ne réagit pas aux
            // pertes de focus (on garde simplement le focus demandé).
        }
    }

    /**
     * Démarre la sonnerie (boucle) à un volume donné. [uri] null = son d'alarme système.
     * Le flux (média vs alarme) est choisi selon la présence d'une sortie externe
     * connectée — voir la documentation de classe.
     */
    fun play(id: String, context: Context, uri: String?, volume: Float, loop: Boolean = true) {
        stopSound(id)
        // Uri de lecture (son donné, ou son d'alarme système par défaut).
        val u = uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        // Reprisable en chaîne (pour un éventuel restart via recheckOutput).
        val uriToStore = uri ?: u.toString()
        val v = volume.coerceIn(0f, 1f)
        val external = hasExternalOutput(context)
        val usage = if (external) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM
        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, u)
            mp.isLooping = loop
            mp.setVolume(v, v)
            mp.prepare()
            // Focus audio sur le flux correspondant, avant le start.
            acquireFocus(context, usage)
            mp.start()
            players[id] = SoundEntry(mp, uriToStore, v, loop, external)
            mp = null
        } catch (ignored: Exception) {
            mp?.release()
        }
    }

    /**
     * Ré-évalue la sortie connectée pour une alarme en cours de sonnerie : si
     * l'utilisateur a branché (ou débranché) une sortie externe (écouteurs
     * Bluetooth, filaires…) depuis l'appel [play], le lecteur est relancé sur
     * le bon flux. Appelée périodiquement par le service tant que l'alarme sonne.
     */
    fun recheckOutput(id: String, context: Context) {
        val entry = players[id] ?: return
        val external = hasExternalOutput(context)
        if (external == entry.externalOutput) return
        stopSound(id)
        play(id, context, entry.uri, entry.volume, entry.loop)
    }

    fun stopSound(id: String) {
        val removed = players.remove(id)
        removed?.let {
            try {
                it.player.stop()
                it.player.release()
            } catch (ignored: Exception) {
            }
        }
        if (players.isEmpty()) releaseFocus()
    }

    fun stopAllSounds() {
        players.keys.toList().forEach { stopSound(it) }
    }

    /**
     * Demande le focus audio sur le flux [usage] (alarme ou média). Une seule
     * demande est partagée entre toutes les alarmes sonnant en même temps ;
     * si le flux change en cours de sonnerie (branchement de écouteurs),
     * l'ancien focus est d'abord libéré.
     */
    private fun acquireFocus(context: Context, usage: Int) {
        if (focusHeld) {
            if (focusUsage == usage) return
            releaseFocus()
        }
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setAcceptsDelayedFocusGain(true)
            .build()
        focusRequest = request
        try {
            val result = am.requestAudioFocus(request)
            focusHeld = true
            focusUsage = usage
            // DELAYED/FAILED : on joue quand même — le focus n'est qu'un bonus
            // de routage/ducking, l'alarme doit sonner quoi qu'il arrive.
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // (aucune action spécifique, on continue)
            }
        } catch (ignored: Exception) {
            // Échec du focus : ne doit pas empêcher la sonnerie de jouer.
        }
    }

    /** Libère le focus audio quand plus aucune sonnerie ne joue. */
    private fun releaseFocus() {
        if (!focusHeld) return
        focusHeld = false
        val req = focusRequest
        focusRequest = null
        try {
            req?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (ignored: Exception) {
        }
    }

    /**
     * Une sortie externe est-elle connectée (autre que le haut-parleur du
     * téléphone) ? Si oui, la sonnerie passe sur le flux média, qui est
     * garanti d'atteindre cette sortie.
     */
    private fun hasExternalOutput(context: Context): Boolean {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HDMI,
                    AudioDeviceInfo.TYPE_DOCK,
                    AudioDeviceInfo.TYPE_DOCK_ANALOG,
                    AudioDeviceInfo.TYPE_HEARING_AID -> true
                    else -> false
                }
            }
        } catch (e: Exception) {
            false
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
