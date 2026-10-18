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
            App()
        }
    }
}

/** Builds a light and dark scheme around one accent colour; Mono keeps the original black and white look. */
private fun scheme(dark: Boolean, accent: Color, accentDark: Color, tint: Color, tintDark: Color) = if (dark) darkColorScheme(
    primary = accentDark, onPrimary = Color(0xFF111111),
    secondary = Color(0xFF9A9A9A), onSecondary = Color(0xFF111111),
    secondaryContainer = tintDark, onSecondaryContainer = Color(0xFFF2F2F2),
    primaryContainer = tintDark, onPrimaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF0E0E0E), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0E0E0E), onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainer = Color(0xFF181818), surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerLow = Color(0xFF141414), surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF3A3A3A), outlineVariant = Color(0xFF2A2A2A), error = Color(0xFFF2B8B5),
) else lightColorScheme(
    primary = accent, onPrimary = Color.White,
    secondary = Color(0xFF6B6B6B), onSecondary = Color.White,
    secondaryContainer = tint, onSecondaryContainer = Color(0xFF111111),
    primaryContainer = tint, onPrimaryContainer = Color(0xFF111111),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF111111),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEFEFEF), onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerLow = Color(0xFFF5F5F5), surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFFD6D6D6), outlineVariant = Color(0xFFE4E4E4), error = Color(0xFFB3261E),
)

fun colorsFor(theme: AppTheme, dark: Boolean) = when (theme) {
    AppTheme.MONO -> scheme(dark, Color(0xFF111111), Color(0xFFF2F2F2), Color(0xFFE9E9E9), Color(0xFF2A2A2A))
    AppTheme.OCEAN -> scheme(dark, Color(0xFF1565C0), Color(0xFF90CAF9), Color(0xFFDCE9F8), Color(0xFF1A2E44))
    AppTheme.FOREST -> scheme(dark, Color(0xFF2E7D32), Color(0xFFA5D6A7), Color(0xFFDDEEDD), Color(0xFF1C3320))
    AppTheme.SUNSET -> scheme(dark, Color(0xFFE65100), Color(0xFFFFB74D), Color(0xFFFCE3D2), Color(0xFF42260F))
}

@Composable
fun MinimalTheme(theme: AppTheme = AppTheme.MONO, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorsFor(theme, isSystemInDarkTheme()), content = content)
}
