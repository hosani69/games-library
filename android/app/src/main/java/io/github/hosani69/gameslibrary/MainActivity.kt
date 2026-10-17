package io.github.hosani69.gameslibrary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MinimalTheme { App() }
        }
    }
}

private val Light = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color.White,
    secondary = Color(0xFF6B6B6B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E9E9), onSecondaryContainer = Color(0xFF111111),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF111111),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEFEFEF), onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFEDEDED),
    outline = Color(0xFFD6D6D6), outlineVariant = Color(0xFFE4E4E4), error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF111111),
    secondary = Color(0xFF9A9A9A), onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF0E0E0E), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0E0E0E), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainer = Color(0xFF181818), surfaceContainerHigh = Color(0xFF202020),
    outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF2A2A2A), error = Color(0xFFF2B8B5),
)

@Composable
fun MinimalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
