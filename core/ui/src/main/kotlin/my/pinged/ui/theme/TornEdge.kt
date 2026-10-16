package my.pinged.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * The artboards' perforation: a 9dp band of [band], with a [notch] wedge cut
 * out of the bottom of each 12dp tile, its apex at the middle.
 *
 * That is what the boards' pair of 45-degree gradients draws over a ground:
 * the gradients' colour is [band], and the ground showing through the
 * transparent half of both is [notch]. Sources tears Card over Paper, between
 * "stored" and "not stored"; day one's hero frame tears Paper over its own
 * [HeroFill] at its foot.
 *
 * One declaration, so the tile geometry exists once: a second copy is a
 * second thing to keep in step.
 */
@Composable
fun TornEdge(modifier: Modifier = Modifier, band: Color = Card, notch: Color = Paper) {
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        drawRect(color = band, topLeft = Offset.Zero, size = size)
        val tile = 12.dp.toPx()
        val h = size.height
        // One Path, reset per tile. Built inside the loop this allocated a
        // native-backed Path per 12dp of width -- about 33 on a 400dp screen --
        // on every frame this strip is invalidated, i.e. throughout a scroll.
        val wedge = Path()
        var x = 0f
        while (x < size.width) {
            wedge.reset()
            wedge.moveTo(x + tile * 0.125f, h)
            wedge.lineTo(x + tile * 0.5f, h * 0.5f)
            wedge.lineTo(x + tile * 0.875f, h)
            wedge.close()
            drawPath(path = wedge, color = notch)
            x += tile
        }
    }
}
