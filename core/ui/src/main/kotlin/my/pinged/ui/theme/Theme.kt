package my.pinged.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

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

/**
 * The artboards' "2px radii throughout" (#164), so a stock component that
 * reads its shape from the theme draws a 2dp corner rather than Material's
 * 4 to 28. It does not reach a component whose default is `CircleShape`
 * outright -- `Button` is one -- which is why none is used.
 */
private val ReceiptShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

@Composable
fun PingedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ReceiptScheme,
        typography = PingedTypography,
        shapes = ReceiptShapes,
        content = content,
    )
}
