package my.pinged.ledger

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The window a screen gets on the Pixel_10a AVD under MainActivity's
 * `safeDrawing` padding: 411x923dp, less a 54dp status bar and a 24dp
 * gesture bar, measured off its root insets (#272).
 */
internal val PIXEL_WINDOW = DpSize(411.dp, (923 - 54 - 24).dp)
