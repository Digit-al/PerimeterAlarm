package fr.rsgnl.perimetre

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import fr.rsgnl.perimetre.ui.AppViewModel
import fr.rsgnl.perimetre.ui.DebugScreen
import fr.rsgnl.perimetre.ui.EditorScreen
import fr.rsgnl.perimetre.ui.HomeScreen
import fr.rsgnl.perimetre.ui.Screen
import fr.rsgnl.perimetre.ui.SettingsScreen
import fr.rsgnl.perimetre.ui.theme.PerimetreTheme
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PerimetreTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val viewModel: AppViewModel = viewModel()
    val context = LocalContext.current
    val screen by viewModel.screen.collectAsState()

    // Demande des permissions au premier lancement.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ignoré */ }
    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }

    when (screen) {
        is Screen.Home -> HomeScreen(viewModel)
        is Screen.Editor -> EditorScreen(viewModel)
        is Screen.Settings -> SettingsScreen(viewModel)
        is Screen.Debug -> DebugScreen(viewModel)
    }
}
