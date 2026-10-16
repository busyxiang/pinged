package my.pinged.ledger

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.ui.theme.Paper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the artboard tests read off a drawn screen: a node's bounds and text
 * layout from the semantics tree, and colours from a screenshot, as
 * `ChooserArtboardTest` reads them and for its reasons.
 */
internal object ArtboardProbe {
    /** 10 of 255 per channel: a solid fill reads back within this. */
    const val TOLERANCE = 10f / 255f

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun bounds(node: SemanticsNode): Bounds {
        val at = node.positionOnScreen
        val l = at.x.roundToInt()
        val t = at.y.roundToInt()
        return Bounds(l, t, l + node.size.width, t + node.size.height)
    }

    fun bounds(node: SemanticsNodeInteraction): Bounds = bounds(node.fetchSemanticsNode())

    fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single()
    }

    /**
     * The screen once two captures a beat apart agree: `waitForIdle` returns
     * when composition is done, not when a sheet has finished coming up.
     */
    fun settledScreen(): PixelMap {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var last = automation.takeScreenshot()
        repeat(20) {
            Thread.sleep(100)
            val next = automation.takeScreenshot()
            if (next.sameAs(last)) return next.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap().toPixelMap()
            last = next
        }
        error("The screen did not settle in 2s")
    }

    fun close(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < TOLERANCE && abs(a.green - b.green) < TOLERANCE && abs(a.blue - b.blue) < TOLERANCE

    /**
     * [c] is [ink] drawn over [Paper] at 30% coverage or more. At CI's
     * density a 1dp stroke is one pixel straddling two, and neither is [ink].
     */
    fun inked(c: Color, ink: Color): Boolean {
        val alpha = (Paper.green - c.green) / (Paper.green - ink.green)
        if (alpha < 0.3f || alpha > 1.1f) return false
        val blend = Color(
            red = Paper.red + (ink.red - Paper.red) * alpha,
            green = Paper.green + (ink.green - Paper.green) * alpha,
            blue = Paper.blue + (ink.blue - Paper.blue) * alpha,
        )
        return close(c, blend)
    }

    /** The darkest pixel in [xs] by [ys]: a stroke's fully covered core, whatever its anti-aliasing. */
    fun darkest(screen: PixelMap, xs: IntRange, ys: IntRange): Color {
        var best = Color.White
        for (y in ys) for (x in xs) {
            val c = screen[x, y]
            if (c.red + c.green + c.blue < best.red + best.green + best.blue) best = c
        }
        return best
    }

    /**
     * The x on screen at which [node]'s first line of ink is centred. Read
     * off the line, not the node: a wrap-content `Text` is as wide as its
     * line, so its own alignment says nothing about where it sits.
     */
    fun lineCentre(node: SemanticsNodeInteraction): Float {
        val layout = layoutOf(node)
        return bounds(node).left + (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
    }

    /** [dp] in whole pixels at [density], for comparing a gap to the board's. */
    fun px(dp: Float, density: Float): Int = (dp * density).roundToInt()

    /** Whether [actual] pixels is [dp] within a pixel's rounding either way. */
    fun isDp(actual: Int, dp: Float, density: Float): Boolean = abs(actual - dp * density) <= 1.5f
}
