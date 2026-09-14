package my.pinged.ledger.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The receipt's dotted rule, drawn along one edge of whatever it modifies.
 *
 * One declaration for every dotted line in this feature, so the dash pattern
 * and the stroke weight exist once: a dash length changed at one call site and
 * not the others reads as two different rules on one screen, and nothing fails.
 *
 * 1dp on, 2dp off in [Rule]: the artboard's perforation, and decorative, so no
 * contrast rule applies to it (see `Color.kt`).
 *
 * @param atTop draws along the top edge instead of the bottom.
 * @param inset held back from each end, for a leader that must not touch the
 *   text on either side of it.
 */
fun Modifier.dottedRule(atTop: Boolean = false, inset: Dp = 0.dp): Modifier = drawBehind {
    val y = if (atTop) 0f else size.height
    val edge = inset.toPx()
    drawLine(
        color = Rule,
        start = Offset(edge, y),
        end = Offset(size.width - edge, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(1.dp.toPx(), 2.dp.toPx()),
        ),
    )
}
