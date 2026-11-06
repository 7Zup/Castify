package app.castify.phone.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Netflix-like tokens, see `.kiro/steering/design.md`. The only place colors are defined. */
object CastifyColors {
    val Background = Color(0xFF141414)
    val Surface = Color(0xFF1F1F1F)
    val SurfaceRaised = Color(0xFF2A2A2A)
    val Red = Color(0xFFE50914)
    val RedPressed = Color(0xFFB20710)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val Disabled = Color(0xFF6D6D6D)
    /** "Ready / OK" status dots only, never text or backgrounds. */
    val ReadyDot = Color(0xFF46D369)
}

private val scheme = darkColorScheme(
    primary = CastifyColors.Red,
    onPrimary = CastifyColors.TextPrimary,
    primaryContainer = CastifyColors.Red,
    onPrimaryContainer = CastifyColors.TextPrimary,
    secondary = CastifyColors.TextSecondary,
    onSecondary = CastifyColors.Background,
    background = CastifyColors.Background,
    onBackground = CastifyColors.TextPrimary,
    surface = CastifyColors.Surface,
    onSurface = CastifyColors.TextPrimary,
    surfaceVariant = CastifyColors.SurfaceRaised,
    onSurfaceVariant = CastifyColors.TextSecondary,
    surfaceContainer = CastifyColors.Surface,
    surfaceContainerLow = CastifyColors.Surface,
    surfaceContainerHigh = CastifyColors.SurfaceRaised,
    outline = CastifyColors.Disabled,
    error = CastifyColors.Red,
    onError = CastifyColors.TextPrimary,
)

/**
 * Dark only: the app ignores the system light setting.
 * Default text/icon color is white everywhere, even outside a Material `Surface`
 * (Compose's fallback is black, unreadable on this background).
 */
@Composable
fun CastifyTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme) {
    CompositionLocalProvider(LocalContentColor provides CastifyColors.TextPrimary, content = content)
}
