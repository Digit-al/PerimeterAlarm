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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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
    val alarms by viewModel.alarms.collectAsState()
    val statuses by MonitorStatus.statuses.collectAsState()

    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var nextRefreshAt by remember { mutableLongStateOf(System.currentTimeMillis() + 30_000) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Tic à la seconde (pour le compte à rebours du rafraîchissement).
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick = System.currentTimeMillis()
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
                    if (d > 0) "$at (dans ${d / 1000} s)" else at
                } else "—"
                val speedStr = if (speed != null && speed > 0) {
                    "%.1f m/s".format(speed)
                } else "—"
                "$ts  ${a.displayName} | active=${if (a.enabled) "oui" else "non"} | " +
                        "période=${if (inPeriod) "oui" else "non"} | " +
                        "dist.entrée=${Format.distanceToEntry(distEntry)} | " +
                        "vitesse=$speedStr | " +
                        "prochain=$nextStr"
            }
            // Plus récent en tête, plafonné à 300 lignes.
            log = (lines + log).take(300)
            nextRefreshAt = now + 30_000
        }

        appendSnapshot()
        while (true) {
            delay(30_000)
            appendSnapshot()
        }
    }

    val remaining = ((nextRefreshAt - tick) / 1000).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { log = emptyList() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Effacer le journal")
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
                    "Mise à jour toutes les 30 s · prochain rafraîchissement dans ${remaining} s",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "« dist.entrée » : distance à l'entrée du périmètre (négatif/« dans la zone » si à l'intérieur). " +
                            "« prochain » : instant de la prochaine vérification du service (ou — si non planifiée).",
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
