package fr.rsgnl.perimetre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fr.rsgnl.perimetre.R
import fr.rsgnl.perimetre.data.Alarm
import fr.rsgnl.perimetre.ui.components.DaySelector
import fr.rsgnl.perimetre.ui.components.OsmMap
import fr.rsgnl.perimetre.ui.components.SoundSettingsEditor
import fr.rsgnl.perimetre.ui.components.observeCurrentLocation
import fr.rsgnl.perimetre.util.LocationUtils
import fr.rsgnl.perimetre.util.TimeUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val RADIUS_MIN = 10
private const val RADIUS_MAX = 5000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val editingId by viewModel.editingId.collectAsState()
    val appSettings by viewModel.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Alarma existante, ou nouvelle centrée sur la position actuelle si disponible.
    val initial: Alarm = remember(editingId) {
        viewModel.getAlarm(editingId) ?: Alarm().apply {
            LocationUtils.lastKnownLocation(context)?.let { loc ->
                latitude = loc.latitude
                longitude = loc.longitude
            }
        }
    }

    // Brouillon local (non persisté tant que l'utilisateur n'enregistre pas).
    var name by remember { mutableStateOf(initial.name) }
    var lat by remember { mutableStateOf(initial.latitude) }
    var lng by remember { mutableStateOf(initial.longitude) }
    var radius by remember { mutableStateOf(initial.radiusMeters) }
    var radiusText by remember { mutableStateOf(initial.radiusMeters.toString()) }
    var oneShot by remember { mutableStateOf(initial.oneShot) }
    var alwaysOn by remember { mutableStateOf(initial.alwaysOn) }
    var days by remember { mutableStateOf(initial.daysOfWeek) }
    var startH by remember { mutableStateOf(initial.startHour) }
    var startM by remember { mutableStateOf(initial.startMinute) }
    var endH by remember { mutableStateOf(initial.endHour) }
    var endM by remember { mutableStateOf(initial.endMinute) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var retriggerable by remember { mutableStateOf(initial.retriggerable ?: true) }
    var draftSound by remember { mutableStateOf(initial.sound.copy()) }
    var fitTrigger by remember { mutableIntStateOf(0) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    // Position actuelle affichée sur la carte (point bleu, rafraîchie).
    var currentLat by remember { mutableStateOf<Double?>(null) }
    var currentLng by remember { mutableStateOf<Double?>(null) }
    observeCurrentLocation(enabled = true, intervalMs = 5000) { loc ->
        currentLat = loc.latitude
        currentLng = loc.longitude
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (editingId == null) stringResource(R.string.editor_new_title)
                        else stringResource(R.string.editor_edit_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.deleteAlarm(initial.id)
                        viewModel.goBack()
                    },
                    enabled = editingId != null
                ) { Text(stringResource(R.string.editor_delete)) }
                Button(onClick = {
                    viewModel.saveAlarm(
                        Alarm(
                            id = initial.id,
                            name = name.trim(),
                            latitude = lat,
                            longitude = lng,
                            radiusMeters = radius.coerceIn(RADIUS_MIN, RADIUS_MAX),
                            oneShot = oneShot,
                            alwaysOn = if (oneShot) true else alwaysOn,
                            daysOfWeek = if (oneShot || alwaysOn) (1..7).toSet() else days,
                            startHour = startH,
                            startMinute = startM,
                            endHour = endH,
                            endMinute = endM,
                            enabled = enabled,
                            retriggerable = retriggerable,
                            sound = draftSound.normalized()
                        )
                    )
                }) {
                    Text(
                        if (editingId == null) stringResource(R.string.editor_add)
                        else stringResource(R.string.editor_save)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Nom
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.editor_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Carte (avec la position actuelle en point bleu)
            OsmMap(
                centerLat = lat,
                centerLng = lng,
                radiusMeters = radius,
                fitTrigger = fitTrigger,
                currentLat = currentLat,
                currentLng = currentLng,
                onMapClick = { cLat, cLng ->
                    lat = cLat
                    lng = cLng
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val notFound = context.getString(R.string.editor_location_not_found)
                    val loc = LocationUtils.lastKnownLocation(context)
                    if (loc != null) {
                        lat = loc.latitude
                        lng = loc.longitude
                        fitTrigger++
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(notFound) }
                    }
                }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_my_location))
                }
                OutlinedButton(onClick = { fitTrigger++ }) {
                    Icon(Icons.Filled.CenterFocusStrong, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.editor_recenter))
                }
            }

            Text(
                text = stringResource(R.string.editor_location, lat, lng),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Périmètre
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.editor_perimeter), style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = radius.toFloat(),
                        onValueChange = { value ->
                            val r = value.roundToInt()
                            radius = r
                            radiusText = r.toString()
                        },
                        valueRange = RADIUS_MIN.toFloat()..RADIUS_MAX.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.editor_radius_label), modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = radiusText,
                            onValueChange = { text ->
                                radiusText = text
                                text.toIntOrNull()?.let { radius = it.coerceIn(RADIUS_MIN, RADIUS_MAX) }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(120.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.editor_radius_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Période
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.editor_period), style = MaterialTheme.typography.titleMedium)

                    // Toggle "Ponctuelle" (one-shot)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.editor_one_shot), modifier = Modifier.weight(1f))
                        Switch(
                            checked = oneShot,
                            onCheckedChange = { value ->
                                oneShot = value
                                if (value) {
                                    alwaysOn = true
                                    days = (1..7).toSet()
                                }
                            }
                        )
                    }
                    if (oneShot) {
                        Text(
                            stringResource(R.string.editor_one_shot_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!oneShot) {
                        DaySelector(
                            days = days,
                            enabled = !alwaysOn,
                            onToggle = { dayIso, checked ->
                                days = if (checked) days + dayIso else days - dayIso
                            }
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.editor_always_on), modifier = Modifier.weight(1f))
                            Switch(
                                checked = alwaysOn,
                                onCheckedChange = { value ->
                                    alwaysOn = value
                                    if (value) days = (1..7).toSet()
                                }
                            )
                        }

                        // Réarmement au sein de la période
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.editor_retriggerable), modifier = Modifier.weight(1f))
                            Switch(
                                checked = retriggerable,
                                onCheckedChange = { value -> retriggerable = value }
                            )
                        }
                        if (!retriggerable) {
                            Text(
                                stringResource(R.string.editor_retriggerable_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!alwaysOn) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(onClick = { showStartPicker = true }) {
                                    Icon(Icons.Filled.Schedule, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(TimeUtils.formatHourMinute(startH, startM))
                                }
                                Text(stringResource(R.string.editor_time_to))
                                OutlinedButton(onClick = { showEndPicker = true }) {
                                    Icon(Icons.Filled.Schedule, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(TimeUtils.formatHourMinute(endH, endM))
                                }
                            }
                        }
                    }
                }
            }

            // Sonnerie & vibration (spécifique à cette alarme)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.editor_sound), style = MaterialTheme.typography.titleMedium)
                    SoundSettingsEditor(
                        settings = draftSound,
                        onChange = { draftSound = it },
                        showUseDefault = true,
                        appDefaultRingtoneUri = appSettings.defaultSound.ringtoneUri
                    )
                }
            }

            // Activation
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_enabled), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // Sélecteurs d'heure
    if (showStartPicker) {
        TimePickerDialog(
            title = stringResource(R.string.editor_start_time),
            initialHour = startH,
            initialMinute = startM,
            onConfirm = { h, m ->
                startH = h
                startM = m
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            title = stringResource(R.string.editor_end_time),
            initialHour = endH,
            initialMinute = endM,
            onConfirm = { h, m ->
                endH = h
                endM = m
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TimePicker(state = state)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        onConfirm(state.hour, state.minute)
                        onDismiss()
                    }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}
