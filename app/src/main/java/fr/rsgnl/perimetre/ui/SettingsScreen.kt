package fr.rsgnl.perimetre.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.rsgnl.perimetre.data.AppSettings
import fr.rsgnl.perimetre.ui.components.SoundSettingsEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()

    var minText by remember(settings.minIntervalSeconds) { mutableStateOf(settings.minIntervalSeconds.toString()) }
    var maxText by remember(settings.maxIntervalSeconds) { mutableStateOf(settings.maxIntervalSeconds.toString()) }

    fun apply(minSec: Int, maxSec: Int) {
        val m = minSec.coerceIn(5, 3600)
        val M = maxSec.coerceIn(m, 3600)
        viewModel.updateSettings(AppSettings(m, M))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Fréquence de vérification de position", style = MaterialTheme.typography.titleMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Intervalle minimum (secondes)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Valeur de départ et plancher de la vérification dynamique.\nPar défaut : 30 s.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { text ->
                            minText = text
                            text.toIntOrNull()?.let { apply(it, settings.maxIntervalSeconds) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Intervalle maximum (secondes)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Plafond de la vérification dynamique (quand on ne se rapproche pas).\nPar défaut : 300 s (5 minutes).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { text ->
                            maxText = text
                            text.toIntOrNull()?.let { apply(settings.minIntervalSeconds, it) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Alarme par défaut (sonnerie, vibreur, volume)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Alarme par défaut", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Appliquée aux alarmes qui n'ont pas de réglage personnalisé.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SoundSettingsEditor(
                        settings = settings.defaultSound,
                        onChange = { viewModel.updateSettings(settings.copy(defaultSound = it)) },
                        showUseDefault = false
                    )
                }
            }

            Text(
                "Astuce : 1 minute = 60 s. L'intervalle réel est estimé en temps réel à partir de la " +
                        "distance qui vous sépare de l'alarme et de votre vitesse de rapprochement " +
                        "(durée d'arrivée estimée / 2), et est borné entre le minimum et le maximum ci-dessus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}
