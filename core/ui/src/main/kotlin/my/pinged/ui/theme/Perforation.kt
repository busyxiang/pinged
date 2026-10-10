package my.pinged.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
fun Modifier.dottedRule(atTop: Boolean = false, inset: Dp = 0.dp): Modifier = drawWithCache {
    val y = if (atTop) 0f else size.height
    val start = Offset(inset.toPx(), y)
    val end = Offset(size.width - inset.toPx(), y)
    val weight = 1.dp.toPx()
    val dots = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 2.dp.toPx()))
    onDrawBehind { drawLine(color = Rule, start = start, end = end, strokeWidth = weight, pathEffect = dots) }
}

/**
 * The artboards' `1px dashed` box, for a control drawn as an offer rather than
 * as a decision: the ledger's "+ CATEGORY" chip and the delete sheet's "Export
 * it first".
 *
 * Here for the same reason [dottedRule] is: two screens draw it, and two
 * dash lengths for one mark is exactly the drift this file exists to stop.
 *
 * **Deliberately not parameterised on the dash length**, only on the colour:
 * a parameter would put the divergence back, one default away. CSS does not
 * specify what `1px dashed` measures, so 3dp on and 2dp off is chosen rather
 * than read off the boards; what it has to stay is clearly *dashed*, since
 * [dottedRule]'s 1dp-on-2dp-off is this app's perforation and a different mark.
 *
 * `Modifier.border` draws a solid stroke and takes no dash pattern, so the
 * rectangle is stroked by hand, inset by half the stroke -- centred on the
 * bounds, its outer half would be clipped and read thinner than the hairlines
 * beside it. It paints in the node's own bounds, so padding applied *before*
 * this modifier enlarges the tap target without enlarging the drawn box.
 */
fun Modifier.dashedOutline(colour: Color): Modifier = drawWithCache {
    val weight = 1.dp.toPx()
    val topLeft = Offset(weight / 2f, weight / 2f)
    val box = Size(size.width - weight, size.height - weight)
    // The 2dp both artboards give every bordered box in this app.
    val corner = CornerRadius(2.dp.toPx())
    val dashes = Stroke(width = weight, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
    onDrawBehind { drawRoundRect(color = colour, topLeft = topLeft, size = box, cornerRadius = corner, style = dashes) }
}

/**
 * The artboards' chevron: two strokes between 38% and 62% of a [size] box,
 * meeting at its middle, pointing right where a row leads somewhere and left
 * for back.
 *
 * Here so the geometry exists once. [size] and [strokeWidth] stay the
 * caller's because the two marks are drawn at different weights: a row's
 * trailing chevron at 17dp and 1.7dp, the back chevron at 20dp and 1.5dp.
 */
@Composable
fun Chevron(pointsRight: Boolean, size: Dp, strokeWidth: Dp, tint: Color) {
    Canvas(Modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val near = this.size.width * 0.38f
        val far = this.size.width * 0.62f
        val tail = if (pointsRight) near else far
        val tip = if (pointsRight) far else near
        drawLine(tint, Offset(tail, this.size.height * 0.22f), Offset(tip, this.size.height * 0.5f), stroke)
        drawLine(tint, Offset(tip, this.size.height * 0.5f), Offset(tail, this.size.height * 0.78f), stroke)
    }
}
