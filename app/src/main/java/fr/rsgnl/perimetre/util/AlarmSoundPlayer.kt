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
 * **Volume pendant la lecture sur écouteurs** (flux média) : le volume du lecteur
 * est le slider de l'alarme (0..1), appliqué par-dessus le **volume média
 * système** — le niveau perçu serait donc « slider × volume média ». Pour que
 * le slider corresponde au niveau perçu, le volume média système est **temporairement
 * mis au maximum** tant qu'une alarme joue sur la sortie externe, puis restauré
 * à sa valeur d'origine à l'arrêt.
 *
 * **Ordre des opérations** (pour éviter tout effet audible parasite) :
 *
 * - le boost n'est posé **qu'après** que le focus audio est réellement accordé
 *   (c'est à cet instant que la musique en cours reçoit son événement de pause) :
 *   immédiatement si `requestAudioFocus` renvoie GRANTED, sinon au callback
 *   `onAudioFocusChange(GAIN)` si elle renvoie DELAYED ;
 * - le volume média est **restauré avant** de libérer le focus : la musique
 *   repart donc à sa volume d'origine, sans « ploc » de baisse audible.
 *
 * **Focus audio** (demandé sur le flux actif, partagé tant qu'au moins une
 * alarme sonne, libéré quand la dernière s'arrête) :
 *
 * - demandé en `AUDIOFOCUS_GAIN_TRANSIENT` : les autres lecteurs reçoivent
 *   `LOSS_TRANSIENT` et **reprennent d'eux-mêmes** quand le focus est rendu
 *   (à l'arrêt de l'alarme) ;
 * - si le flux change en cours de route (écouteurs branchés/débranchés), il est
 *   re-demandé sur le nouveau flux.
 *
 * [recheckOutput] ré-évalue la sortie connectée pour une alarme qui sonne : si
 * l'utilisateur branche (ou débranche) une sortie externe depuis [play], le
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
    private var appContext: Context? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusUsage: Int = 0
    private var focusHeld = false          // focus réellement accordé
    private var focusPending = false       // DELAYED : en attente du callback GAIN

    // Volume média système d'origine, sauvegardé avant le boost (null = pas de boost).
    private var originalMediaVolume: Int? = null

    private val focusListener = object : AudioManager.OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Focus accordé (immédiatement après la demande, ou après un
                    // DELAYED) : la musique en cours vient de recevoir son
                    // événement de pause → on peut poser le boost du volume média.
                    focusHeld = true
                    focusPending = false
                    syncMediaVolume()
                }
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Un autre app a pris l'audio (appel, …). L'alarme continue
                    // de sonner quoi qu'il arrive, mais on rend au volume média
                    // sa valeur d'origine.
                    focusHeld = false
                    focusPending = false
                    restoreMediaVolume()
                }
            }
        }
    }

    /**
     * Démarre la sonnerie (boucle) à un volume donné. [uri] null = son d'alarme système.
     * Le flux (média vs alarme) est choisi selon la présence d'une sortie externe
     * connectée — voir la documentation de classe.
     */
    fun play(id: String, context: Context, uri: String?, volume: Float, loop: Boolean = true) {
        appContext = context.applicationContext
        stopSound(id)
        // Uri de lecture (son donné, ou son d'alarme système par défaut).
        val u = uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        // Reprisable en chaîne (pour un éventuel restart via recheckOutput).
        val uriToStore = uri ?: u.toString()
        val v = volume.coerceIn(0f, 1f)
        val external = hasExternalOutput()
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
            // Focus audio sur le flux correspondant (le boost du volume média
            // ne sera posé qu'une fois le focus réellement accordé).
            ensureFocus(usage)
            mp.start()
            players[id] = SoundEntry(mp, uriToStore, v, loop, external)
            mp = null
        } catch (ignored: Exception) {
            mp?.release()
        }
    }

    /**
     * Ré-évalue la sortie connectée pour une alarme en cours de sonnerie : si
     * l'utilisateur a branché (ou débranché) une sortie externe depuis [play],
     * le lecteur est relancé sur le bon flux. Appelée périodiquement par le
     * service tant que l'alarme sonne.
     */
    fun recheckOutput(id: String, context: Context) {
        appContext = context.applicationContext
        val entry = players[id] ?: return
        val external = hasExternalOutput()
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
        if (players.isEmpty()) {
            // 1) Restaurer le volume média TANT QU'ON A ENCORE LE FOCUS
            //    (rien d'autre ne joue sur le flux média),
            // 2) puis libérer le focus → la musique reprend à son volume
            //    d'origine, sans baisse audible.
            restoreMediaVolume()
            releaseFocus()
        } else {
            // D'autres alarmes sonnent encore : mettre à jour l'état du
            // boost (la liste des sorties externes a pu changer).
            syncMediaVolume()
        }
    }

    fun stopAllSounds() {
        players.keys.toList().forEach { stopSound(it) }
    }

    /**
     * Demande le focus audio (transitoire) sur le flux [usage] (alarme ou média).
     * Une seule demande est partagée entre toutes les alarmes sonnant en même
     * temps ; si le flux change en cours de route, l'ancien focus est d'abord
     * libéré (et le volume média restauré).
     *
     * `AUDIOFOCUS_GAIN_TRANSIENT` : les autres lecteurs reçoivent
     * `LOSS_TRANSIENT` et reprennent d'eux-mêmes quand le focus est rendu.
     */
    private fun ensureFocus(usage: Int) {
        if (focusHeld || focusPending) {
            if (focusUsage == usage) return
            releaseFocus()
        }
        val ctx = appContext ?: return
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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
        focusUsage = usage
        focusHeld = false
        focusPending = true
        try {
            when (am.requestAudioFocus(request)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    // Focus accordé : la musique en cours vient de recevoir son
                    // événement de pause → on peut poser le boost. (Le callback
                    // GAIN du listener le confirmera ; syncMediaVolume est
                    // idempotent.)
                    focusHeld = true
                    focusPending = false
                    syncMediaVolume()
                }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    // La musique continue de jouer : le boost attendra le
                    // callback GAIN (focusListener).
                    focusPending = true
                }
                else -> {
                    // FAILED : l'alarme joue sans focus (repli : niveau
                    // slider × volume média courant, pas de boost).
                    focusPending = false
                }
            }
        } catch (ignored: Exception) {
            focusPending = false
        }
    }

    /** Libère le focus audio (l'appelant gère l'ordre du volume). */
    private fun releaseFocus() {
        val req = focusRequest
        focusRequest = null
        focusUsage = 0
        focusHeld = false
        focusPending = false
        try {
            req?.let {
                val ctx = appContext ?: return
                (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                    ?.abandonAudioFocusRequest(it)
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * Synchronise le volume média système avec l'état de lecture :
     *
     * - tant qu'au moins une alarme joue sur une sortie externe **et** que le
     *   focus est accordé → volume média au **maximum** (le niveau perçu de la
     *   sonnerie devient alors exactement son slider) ;
     * - sinon → restauration du volume média d'origine (une seule fois).
     */
    private fun syncMediaVolume() {
        val ctx = appContext ?: return
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val externalPlaying = players.values.any { it.externalOutput }
        if (externalPlaying && focusHeld) {
            val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
            if (originalMediaVolume == null) {
                originalMediaVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            }
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
        } else {
            restoreMediaVolume()
        }
    }

    /** Restaure le volume média d'origine (no-op si aucun boost en cours). */
    private fun restoreMediaVolume() {
        val original = originalMediaVolume ?: return
        originalMediaVolume = null
        val ctx = appContext ?: return
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
    }

    /**
     * Une sortie externe est-elle connectée (autre que le haut-parleur du
     * téléphone) ? Si oui, la sonnerie passe sur le flux média, qui est
     * garanti d'atteindre cette sortie.
     */
    private fun hasExternalOutput(): Boolean {
        val ctx = appContext ?: return false
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        appContext = context.applicationContext
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
        stopAllSounds()   // inclut restauration du volume + libération du focus
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
