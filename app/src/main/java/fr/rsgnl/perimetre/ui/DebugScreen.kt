package fr.rsgnl.perimetre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.rsgnl.perimetre.R
import fr.rsgnl.perimetre.data.MonitorStatus
import fr.rsgnl.perimetre.util.Format
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Page de debug : journalise l'état de toutes les alarmes toutes les 30 secondes.
 * Chaque ligne commence par la date/heure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val alarms by viewModel.alarms.collectAsState()
    val statuses by MonitorStatus.statuses.collectAsState()

    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var nextRefreshAt by remember { mutableLongStateOf(System.currentTimeMillis() + 30_000) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Le polling et le tic ne tournent que tant que la page est visible (cycle de vie STARTED).
    val lifecycleOwner = LocalLifecycleOwner.current

    // Tic à la seconde (pour le compte à rebours du rafraîchissement).
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1_000)
                tick = System.currentTimeMillis()
            }
        }
    }

    // Ajoute un instantané (une ligne par alarme) toutes les 30 s, et immédiatement à l'ouverture.
    LaunchedEffect(Unit) {
        val fmtStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val fmtTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun appendSnapshot() {
            val now = System.currentTimeMillis()
            val ts = fmtStamp.format(Date(now))
            val lines = alarms.map { a ->
                val s = statuses[a.id]
                val inPeriod = s?.inPeriod ?: false
                val distEntry = s?.distanceEntryM
                val speed = s?.speedMps
                val nextAt = s?.nextCheckAtMs
                val nextStr = if (nextAt != null) {
                    val d = nextAt - now
                    val at = fmtTime.format(Date(nextAt))
                    if (d > 0) "$at (${context.getString(R.string.debug_in_seconds, d / 1000)})" else at
                } else context.getString(R.string.unknown)
                val speedStr = if (speed != null && speed > 0) {
                    "%.1f m/s".format(speed)
                } else context.getString(R.string.unknown)
                val yn = { b: Boolean -> context.getString(if (b) R.string.yes else R.string.no) }
                context.getString(
                    R.string.debug_line,
                    ts, a.displayName(context), yn(a.enabled), yn(inPeriod),
                    Format.distanceToEntry(context, distEntry), speedStr, nextStr
                )
            }
            // Plus récent en tête, plafonné à 300 lignes.
            log = (lines + log).take(300)
            nextRefreshAt = now + 30_000
        }

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            appendSnapshot()
            while (true) {
                delay(30_000)
                appendSnapshot()
            }
        }
    }

    val remaining = ((nextRefreshAt - tick) / 1000).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { log = emptyList() }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_clear_log))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.debug_refresh, remaining),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.debug_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
            items(log) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}
