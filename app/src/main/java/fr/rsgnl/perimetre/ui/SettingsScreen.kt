package fr.rsgnl.perimetre.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import fr.rsgnl.perimetre.data.AppSettings
import fr.rsgnl.perimetre.data.AppLanguage
import fr.rsgnl.perimetre.ui.components.SoundSettingsEditor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var minText by remember(settings.minIntervalSeconds) { mutableStateOf(settings.minIntervalSeconds.toString()) }
    var maxText by remember(settings.maxIntervalSeconds) { mutableStateOf(settings.maxIntervalSeconds.toString()) }
    var showLangPicker by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var snackbarMsg by remember { mutableStateOf<String?>(null) }

    // Afficher le snackbar quand un message est prêt.
    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            snackbarMsg = null
        }
    }

    // Launcher export : crée un fichier JSON via SAF.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            val json = viewModel.exportConfigJson()
            if (json != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                } catch (e: Exception) { /* ignoré */ }
            }
        }
    }

    // Launcher import : ouvre un fichier JSON via SAF.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val success = try {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: throw Exception("null")
                    viewModel.importConfigJson(text)
                } catch (e: Exception) {
                    false
                }
                snackbarMsg = context.getString(if (success) R.string.import_success else R.string.import_failed)
            }
        }
    }

    fun apply(minSec: Int, maxSec: Int) {
        val m = minSec.coerceIn(5, 3600)
        val M = maxSec.coerceIn(m, 3600)
        viewModel.updateSettings(AppSettings(m, M))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.settings_check_frequency), style = MaterialTheme.typography.titleMedium)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.settings_min_interval), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_min_interval_hint),
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
                    Text(stringResource(R.string.settings_max_interval), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_max_interval_hint),
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

            // Langue de l'interface
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_language_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLangPicker = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (settings.language) {
                                AppLanguage.EN -> stringResource(R.string.language_en)
                                AppLanguage.FR -> stringResource(R.string.language_fr)
                                else -> stringResource(R.string.language_auto)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Alarme par défaut (sonnerie, vibreur, volume)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.settings_default_alarm), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_default_alarm_hint),
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

            // Export / Import de la configuration
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.settings_backup), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_backup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("perimetre_config.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.export_button))
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.import_button))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.settings_interval_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showLangPicker) {
        LanguagePickerDialog(
            current = settings.language,
            onSelect = { lang ->
                viewModel.setLanguage(lang)
                showLangPicker = false
            },
            onDismiss = { showLangPicker = false }
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                LanguageOptionRow(AppLanguage.AUTO, stringResource(R.string.language_auto), current) {
                    onSelect(AppLanguage.AUTO)
                }
                LanguageOptionRow(AppLanguage.EN, stringResource(R.string.language_en), current) {
                    onSelect(AppLanguage.EN)
                }
                LanguageOptionRow(AppLanguage.FR, stringResource(R.string.language_fr), current) {
                    onSelect(AppLanguage.FR)
                }
            }
        }
    }
}

@Composable
private fun LanguageOptionRow(
    value: String,
    label: String,
    current: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (value == current) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}
