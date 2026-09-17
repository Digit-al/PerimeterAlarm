package fr.rsgnl.perimetre.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF1565C0)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFFBBDEFB)
private val OnPrimaryContainer = Color(0xFF001D36)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC9FF),
    onPrimary = Color(0xFF002F5A),
    primaryContainer = Color(0xFF00437A),
    onPrimaryContainer = Color(0xFFDCE4FF)
)

@Composable
fun PerimetreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}
