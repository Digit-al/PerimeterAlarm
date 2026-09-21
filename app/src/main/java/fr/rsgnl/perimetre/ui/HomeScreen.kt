package fr.rsgnl.perimetre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fr.rsgnl.perimetre.R
import fr.rsgnl.perimetre.data.Alarm
import fr.rsgnl.perimetre.ui.components.observeCurrentLocation
import fr.rsgnl.perimetre.util.Format
import fr.rsgnl.perimetre.util.Geo
import fr.rsgnl.perimetre.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val alarms by viewModel.alarms.collectAsState()

    // Position actuelle pour afficher la distance à l'entrée de chaque alarme.
    // GPS actif uniquement si : (1) l'écran est visible (lifecycle STARTED) ET
    // (2) au moins une alarme est activée et dans sa période.
    // En arrière-plan, c'est le service de monitoring qui gère le GPS (single fix).
    var currentLoc by remember { mutableStateOf<Location?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isScreenStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    // Permission SCHEDULE_EXACT_ALARM (réveil Doze) : sous Android 12+, elle
    // est refusée par défaut pour les apps ciblant le SDK 33+ — l'app affiche
    // donc automatiquement un avertissement tant qu'elle n'est pas accordée.
    // Ré-évaluée à chaque retour au premier plan (l'utilisateur vient de
    // l'accorder dans l'écran système dédié).
    fun exactAlarmGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .canScheduleExactAlarms()
    }
    var exactAlarmGranted by remember { mutableStateOf(exactAlarmGranted()) }
    var exactAlarmDismissed by remember { mutableStateOf(false) }
    val exactAlarmNeedsPrompt = !exactAlarmGranted && !exactAlarmDismissed

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isScreenStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            exactAlarmGranted = exactAlarmGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val anyActiveInPeriod = alarms.any { a ->
        a.enabled && (a.oneShot || TimeUtils.isWithinPeriod(
            a.alwaysOn, a.daysOfWeek,
            a.startHour, a.startMinute, a.endHour, a.endMinute
        ))
    }
    observeCurrentLocation(enabled = isScreenStarted && anyActiveInPeriod, intervalMs = 10_000) { loc ->
        currentLoc = loc
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = { viewModel.openDebug() }) {
                        Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.cd_debug))
                    }
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openNewAlarm() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_alarm))
            }
        }
    ) { padding ->
        if (alarms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsNone,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.home_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (exactAlarmNeedsPrompt) {
                    item(key = "exact_alarm_prompt") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Alarm,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        stringResource(R.string.home_exact_alarm_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { exactAlarmDismissed = true }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.cd_dismiss),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Text(
                                    stringResource(R.string.home_exact_alarm_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Button(
                                    onClick = {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                Uri.parse("package:${context.packageName}")
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    Text(stringResource(R.string.home_exact_alarm_action))
                                }
                            }
                        }
                    }
                }
                items(alarms, key = { it.id }) { alarm ->
                    AlarmRow(alarm = alarm, viewModel = viewModel, currentLoc = currentLoc)
                }
            }
        }
    }
}

@Composable
fun AlarmRow(alarm: Alarm, viewModel: AppViewModel, currentLoc: Location?) {
    val context = LocalContext.current
    val inPeriod = if (alarm.oneShot) true else TimeUtils.isWithinPeriod(
        alarm.alwaysOn, alarm.daysOfWeek,
        alarm.startHour, alarm.startMinute, alarm.endHour, alarm.endMinute
    )
    val activeNow = alarm.enabled && inPeriod

    val distEntry = currentLoc?.let { loc ->
        Geo.distanceMeters(loc, alarm.latitude, alarm.longitude) - alarm.radiusMeters
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (activeNow) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = alarm.displayName(context),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.home_coord_radius,
                        alarm.latitude, alarm.longitude, alarm.radiusMeters
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = periodLabel(context, alarm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (activeNow) {
                    val inside = distEntry != null && distEntry <= 0
                    Text(
                        text = stringResource(
                            R.string.home_distance_entry,
                            Format.distanceToEntry(context, distEntry)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (inside) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { viewModel.openEditAlarm(alarm.id) }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_edit))
            }
            Switch(checked = alarm.enabled, onCheckedChange = { viewModel.toggleAlarm(alarm.id, it) })
        }
    }
}

fun periodLabel(context: android.content.Context, alarm: Alarm): String {
    return if (alarm.oneShot) {
        context.getString(R.string.home_one_shot_label)
    } else if (alarm.alwaysOn) {
        context.getString(R.string.always_on)
    } else {
        val days = TimeUtils.formatDays(alarm.daysOfWeek, dayNames(context))
        val times = "${TimeUtils.formatHourMinute(alarm.startHour, alarm.startMinute)}–${TimeUtils.formatHourMinute(alarm.endHour, alarm.endMinute)}"
        "$days · $times"
    }
}
