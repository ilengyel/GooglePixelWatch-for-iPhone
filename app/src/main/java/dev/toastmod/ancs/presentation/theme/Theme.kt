package dev.toastmod.ancs.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

private val GreenColorScheme = ColorScheme(
    primary = Color(0xFF65D98C),
    primaryDim = Color(0xFF3E9F65),
    primaryContainer = Color(0xFF1E4D30),
    onPrimary = Color(0xFF00391C),
    onPrimaryContainer = Color(0xFFBDF2C9),
    secondary = Color(0xFFB5DDBB),
    secondaryDim = Color(0xFF83B58D),
    secondaryContainer = Color(0xFF344B39),
    onSecondary = Color(0xFF1C3823),
    onSecondaryContainer = Color(0xFFD1EBD3),
    tertiary = Color(0xFFACD9C8),
    tertiaryDim = Color(0xFF7FAF9E),
    tertiaryContainer = Color(0xFF294A40),
    onTertiary = Color(0xFF12382D),
    onTertiaryContainer = Color(0xFFC5ECDE),
    surfaceContainerLow = Color(0xFF141714),
    surfaceContainer = Color(0xFF1C201C),
    surfaceContainerHigh = Color(0xFF252A25),
    onSurface = Color(0xFFE2E3DF),
    onSurfaceVariant = Color(0xFFC1C9BF),
    outline = Color(0xFF899187),
    outlineVariant = Color(0xFF424A42),
    background = Color(0xFF101310),
    onBackground = Color(0xFFE2E3DF),
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFFDB6B64),
    errorContainer = Color(0xFF8C1D18),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun ANCSTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = dynamicColorScheme(LocalContext.current) ?: GreenColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}