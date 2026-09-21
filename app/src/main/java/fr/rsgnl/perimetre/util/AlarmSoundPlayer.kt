package fr.rsgnl.perimetre.util

import android.content.Context
import android.media.AudioAttributes
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
 */
class AlarmSoundPlayer {

    private val players = HashMap<String, MediaPlayer>()
    private val vibrators = HashMap<String, Vibrator>()

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
            mp.start()
            players[id] = mp
            mp = null
        } catch (ignored: Exception) {
            mp?.release()
        }
    }

    fun stopSound(id: String) {
        players.remove(id)?.let {
            try {
                it.stop()
                it.release()
            } catch (ignored: Exception) {
            }
        }
    }

    fun stopAllSounds() {
        players.keys.toList().forEach { stopSound(it) }
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
