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
import android.location.Location
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    var currentLoc by remember { mutableStateOf<Location?>(null) }
    observeCurrentLocation(enabled = true, intervalMs = 10_000) { loc ->
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
    val inPeriod = TimeUtils.isWithinPeriod(
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
    return if (alarm.alwaysOn) {
        context.getString(R.string.always_on)
    } else {
        val days = TimeUtils.formatDays(alarm.daysOfWeek, dayNames(context))
        val times = "${TimeUtils.formatHourMinute(alarm.startHour, alarm.startMinute)}–${TimeUtils.formatHourMinute(alarm.endHour, alarm.endMinute)}"
        "$days · $times"
    }
}
