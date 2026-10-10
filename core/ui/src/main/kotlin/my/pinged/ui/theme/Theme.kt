package my.pinged.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * One palette, committed to.
 *
 * No dark scheme and no dynamic colour. `Dark.dc.html` is filed under rejected
 * directions in the design set, and dynamic colour would repaint a palette
 * whose whole argument is that it looks like a paper receipt. A theme that
 * offered either would be offering a direction the design did not choose.
 */
private val ReceiptScheme = lightColorScheme(
    primary = Stamp,
    onPrimary = Paper,
    secondary = Muted,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Card,
    onSurfaceVariant = Muted,
    outline = Rule,
    outlineVariant = Border,
    error = Stamp,
    onError = Paper,
)

@Composable
fun PingedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ReceiptScheme,
        typography = PingedTypography,
        content = content,
    )
}
