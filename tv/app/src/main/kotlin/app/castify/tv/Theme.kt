package app.castify.tv

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Netflix-like tokens, same values as the phone (see `.kiro/steering/design.md`). */
object TvColors {
    val Background = Color(0xFF141414)
    val Video = Color(0xFF000000)
    val Surface = Color(0xFF1F1F1F)
    val Red = Color(0xFFE50914)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFB3B3B3)
    val Disabled = Color(0xFF6D6D6D)
    /** Left panel of the welcome screen. */
    val Panel = Color(0xFF0B0B0B)
    /** Status dot "Ready" (Netflix's green). The only non red/gray color. */
    val Ready = Color(0xFF46D369)
    /** Dark scrim behind text drawn over the video (diagnostics, hints). */
    val Scrim = Color(0xB3000000)
}

/** Default text color is white even outside a Material `Surface` (Compose falls back to black). */
@Composable
fun CastifyTvTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = TvColors.Red,
        background = TvColors.Background,
        surface = TvColors.Surface,
        onBackground = TvColors.TextPrimary,
        onSurface = TvColors.TextPrimary,
    ),
) {
    CompositionLocalProvider(LocalContentColor provides TvColors.TextPrimary, content = content)
}
