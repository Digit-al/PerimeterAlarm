package fr.rsgnl.perimetre.ui.components

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.rsgnl.perimetre.R
import fr.rsgnl.perimetre.data.SoundSettings
import fr.rsgnl.perimetre.ui.dayNames
import fr.rsgnl.perimetre.util.RingtoneUtils
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ */
/* Observation de la position actuelle                                  */
/* ------------------------------------------------------------------ */

/**
 * S'abonne aux mises à jour de position et rappelle [onLocation] à chaque changement
 * (et immédiatement avec la dernière position connue). S'arrête au départ du composable.
 */
@Composable
fun observeCurrentLocation(
    enabled: Boolean = true,
    intervalMs: Long = 5000,
    onLocation: (Location) -> Unit
) {
    val context = LocalContext.current
    val onLocationRef = rememberUpdatedState(onLocation)
    DisposableEffect(enabled) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocationRef.value(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onProviderEnabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onProviderDisabled(provider: String) {}
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { p ->
                try {
                    lm?.isProviderEnabled(p) == true
                } catch (e: Exception) {
                    false
                }
            }.ifEmpty { listOf(LocationManager.NETWORK_PROVIDER) }
        if (enabled) {
            for (p in providers) {
                try {
                    lm?.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper())
                    lm?.getLastKnownLocation(p)?.let { onLocationRef.value(it) }
                } catch (e: SecurityException) {
                }
            }
        }
        onDispose {
            try {
                lm?.removeUpdates(listener)
            } catch (e: Exception) {
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Sélecteur de jours (cases à cocher)                                  */
/* ------------------------------------------------------------------ */

/**
 * 7 cases à cocher (lundi → dimanche). Les jours sont encodés en ISO (1..7).
 */
@Composable
fun DaySelector(
    days: Set<Int>,
    enabled: Boolean,
    onToggle: (dayIso: Int, checked: Boolean) -> Unit
) {
    val context = LocalContext.current
    val names = dayNames(context).toList()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        for (i in 0..6) {
            val dayIso = i + 1
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Checkbox(
                    checked = dayIso in days,
                    enabled = enabled,
                    onCheckedChange = { onToggle(dayIso, it) }
                )
                Text(
                    text = names[i],
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Choix de la sonnerie                                                 */
/* ------------------------------------------------------------------ */

/**
 * Dialogue listant les sonneries "alarme/sonnerie" de l'appareil + le défaut système.
 * [onPick] reçoit l'uri choisie, ou null pour le défaut système.
 *
 * Utilise l'Intent système officiel ACTION_RINGTONE_PICKER qui affiche
 * toutes les sonneries (collections système, personnalisées) sans permission.
 */
@Composable
fun RingtonePickerDialog(
    currentUri: String?,
    onPick: (uri: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.let { data ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
            }
            onPick(uri?.toString())
        }
        onDismiss()
    }

    // Lance le picker système dès que le composant est composé.
    LaunchedEffect(Unit) {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            currentUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
        }
        launcher.launch(intent)
    }
}

@Composable
private fun RingtoneRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* ------------------------------------------------------------------ */
/* Éditeur des réglages sonores (réutilisé alarme + paramètres)         */
/* ------------------------------------------------------------------ */

/**
 * Éditeur des réglages sonores : vibreur, volume, sonnerie.
 *
 * @param showUseDefault Affiche (alarme uniquement) le toggle "utiliser les réglages par défaut".
 * @param appDefaultRingtoneUri La sonnerie par défaut de l'application (pour affichage
 *   quand useDefault = true : montre quelle sonnerie sera réellement utilisée).
 */
@Composable
fun SoundSettingsEditor(
    settings: SoundSettings,
    onChange: (SoundSettings) -> Unit,
    showUseDefault: Boolean = false,
    appDefaultRingtoneUri: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val isEnabled = if (showUseDefault) !settings.useDefault else true

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showUseDefault) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sound_use_default), modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.useDefault,
                    onCheckedChange = { onChange(settings.copy(useDefault = it)) }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sound_vibration), modifier = Modifier.weight(1f))
            Switch(
                checked = settings.useVibration,
                enabled = isEnabled,
                onCheckedChange = { onChange(settings.copy(useVibration = it)) }
            )
        }

        Column {
            Text(stringResource(R.string.sound_volume, (settings.volume * 100).roundToInt()))
            Slider(
                value = settings.volume,
                onValueChange = { onChange(settings.copy(volume = it)) },
                valueRange = 0f..1f,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sound_ringtone), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showPicker = true }, enabled = isEnabled) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (showUseDefault && settings.useDefault) {
                        val title = RingtoneUtils.title(context, appDefaultRingtoneUri)
                        "${stringResource(R.string.sound_default_app_prefix)} $title"
                    } else {
                        RingtoneUtils.title(context, settings.ringtoneUri)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
            }
            // Bouton réinitialiser : visible uniquement si une sonnerie a été choisie.
            if (settings.ringtoneUri != null && isEnabled) {
                IconButton(
                    onClick = { onChange(settings.copy(ringtoneUri = null)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.sound_reset_ringtone),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showPicker) {
        RingtonePickerDialog(
            currentUri = settings.ringtoneUri,
            onPick = { uri -> onChange(settings.copy(ringtoneUri = uri)) },
            onDismiss = { showPicker = false }
        )
    }
}
