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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
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
    val dots = perforation()
    onDrawBehind { drawLine(color = Rule, start = start, end = end, strokeWidth = weight, pathEffect = dots) }
}

/**
 * [dottedRule] around all four sides: the artboard's `1px dotted` box, which
 * the review inbox draws around a notification quoted word for word.
 *
 * Stroked by hand and inset by half the stroke, as [dashedOutline] is and
 * for its reason; the same 1dp-on-2dp-off pattern as [dottedRule], from the
 * same declaration.
 */
fun Modifier.dottedOutline(): Modifier = drawWithCache {
    val weight = 1.dp.toPx()
    val topLeft = Offset(weight / 2f, weight / 2f)
    val box = Size(size.width - weight, size.height - weight)
    val corner = CornerRadius(2.dp.toPx())
    val dots = Stroke(width = weight, pathEffect = perforation())
    onDrawBehind { drawRoundRect(color = Rule, topLeft = topLeft, size = box, cornerRadius = corner, style = dots) }
}

/**
 * [dottedOutline] around any [shape]: a circle for a row's icon not read
 * yet, a square for a grid cell (`DottedCell`). Inset by half the stroke
 * for [dashedOutline]'s reason.
 */
fun Modifier.dottedOutline(shape: Shape): Modifier = drawWithCache {
    val weight = 1.dp.toPx()
    val outline = shape.createOutline(Size(size.width - weight, size.height - weight), layoutDirection, this)
    val dots = Stroke(width = weight, pathEffect = perforation())
    onDrawBehind { translate(weight / 2f, weight / 2f) { drawOutline(outline, color = Rule, style = dots) } }
}

/** The perforation's pattern, 1dp on and 2dp off; every dotted mark draws with it. */
private fun Density.perforation(): PathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 2.dp.toPx()))

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
 * The artboards' sheet edge, `border-top: 1.5px dashed`: [dashedOutline]'s
 * dash along the top only, at the boards' heavier 1.5dp. The same 3dp on and
 * 2dp off, so a sheet's edge and the offers inside it read as one mark.
 *
 * Drawn over the content rather than behind it, so a row that paints its own
 * background at the top of a sheet cannot cover it.
 */
fun Modifier.dashedEdge(colour: Color): Modifier = drawWithCache {
    val weight = 1.5.dp.toPx()
    val dashes = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
    onDrawWithContent {
        drawContent()
        drawLine(colour, Offset(0f, weight / 2f), Offset(size.width, weight / 2f), weight, pathEffect = dashes)
    }
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

/**
 * The boards' arrow out of the app, `M8 16L16 8M9 8h7v7` in a 24-unit [size]
 * box: where a row's tap leaves Pinged for Android's own settings, a
 * [Chevron] would promise a screen of Pinged's that is not there.
 *
 * Here beside [Chevron] for its reason.
 */
@Composable
fun OutwardArrow(size: Dp, strokeWidth: Dp, tint: Color) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 24f
        val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(tint, Offset(8 * unit, 16 * unit), Offset(16 * unit, 8 * unit), stroke.width, StrokeCap.Round)
        val head = Path().apply {
            moveTo(9 * unit, 8 * unit)
            lineTo(16 * unit, 8 * unit)
            lineTo(16 * unit, 15 * unit)
        }
        drawPath(head, tint, style = stroke)
    }
}

/**
 * `Search.dc.html`'s magnifier: a circle of radius 6.5 about (11, 11) and a
 * round-capped handle from (16, 16) to (20, 20), in a 24-unit [size] box at
 * a 1.7 stroke. Spending's header draws it at 20dp, the search field at 17dp.
 *
 * Here beside [Chevron] for its reason.
 */
@Composable
fun Magnifier(size: Dp, tint: Color) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 24f
        val stroke = 1.7f * unit
        drawCircle(tint, radius = 6.5f * unit, center = Offset(11 * unit, 11 * unit), style = Stroke(stroke))
        drawLine(tint, Offset(16 * unit, 16 * unit), Offset(20 * unit, 20 * unit), stroke, StrokeCap.Round)
    }
}

/**
 * The artboards' +: two round-capped strokes from 5/24 to 19/24 of a [size]
 * box, crossing at its middle -- the boards' `M12 5v14M5 12h14`. The add
 * button draws it at 20dp, day one's cash offer at 17dp.
 *
 * Here beside [Chevron] for its reason: a glyph drawn twice is geometry kept
 * once. [strokeWidth] is the board's stroke scaled from its 24-unit box.
 */
@Composable
fun PlusMark(size: Dp, strokeWidth: Dp, tint: Color) {
    Canvas(Modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val near = this.size.width * 5f / 24f
        val far = this.size.width * 19f / 24f
        val middle = this.size.width / 2f
        drawLine(tint, Offset(middle, near), Offset(middle, far), stroke, StrokeCap.Round)
        drawLine(tint, Offset(near, middle), Offset(far, middle), stroke, StrokeCap.Round)
    }
}
