package my.pinged.charts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import my.pinged.charts.model.ChartsState
import my.pinged.ui.theme.ChartRamp
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Charts' first read in flight against `design/LoadingCharts.dc.html` (#281):
 * what needs the device's pixels and type. Timing and content are
 * `ChartsNotReadYetTest`'s, on the JVM.
 */
class ChartsNotReadYetArtboardTest {
    @get:JUnitRule val compose = createComposeRule()

    private fun notReadYet() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(YearMonth.of(2026, 9), ChartsState.Reading, unavailable = false, today = LocalDate.of(2026, 10, 22))
            }
        }
        compose.mainClock.advanceTimeBy(READING_DELAY_MILLIS + 40)
        compose.waitForIdle()
    }

    /**
     * The board's grid: 18px cells outlined in `#cfc4ae` dots, and empty.
     * Never hatched, since hatching means Not captured: no pixel of the
     * hatch's `#b6ad99` anywhere in the cell.
     */
    @Test fun aDayNotReadYetIsADottedEmptyCellNeverHatched() {
        notReadYet()
        val cell = compose.onAllNodesWithTag(NOT_READ_YET_DAY).onFirst()
        assertEquals("The board's cell is 18px", 18f, cell.fetchSemanticsNode().size.width / compose.density.density, 0.6f)
        val pixels = cell.captureToImage().toPixelMap()
        val ground = pixels[pixels.width / 2, pixels.height / 2]
        // At CI's mdpi a 1dp dot is one pixel blended with the ground, never
        // Rule itself, so the test reads where the cell drew, not what colour.
        // The 1dp stroke's own pixels, and a pixel more of antialiasing
        // before the inside starts.
        val stroke = ceil(compose.density.density).toInt()
        fun within(x: Int, y: Int, band: Int) = x < band || y < band || x >= pixels.width - band || y >= pixels.height - band
        var edge = 0
        var edgeDrawn = 0
        var insideDrawn = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val drawn = distance(pixels[x, y], ground) > 0.02f
            if (within(x, y, stroke)) {
                edge++
                if (drawn) edgeDrawn++
            } else if (!within(x, y, stroke + 1) && drawn) {
                insideDrawn++
            }
        }
        assertTrue("The cell drew no outline: ${colours(pixels)}", edgeDrawn > 0)
        assertEquals("The cell drew inside its outline, as a hatched day Not captured does", 0, insideDrawn)
        assertEquals("The cell's outline is in the hatch's colour", 0, count(pixels, ChartRamp.last()))
        assertTrue("The outline is solid, not dotted: $edgeDrawn of $edge edge pixels drawn", edgeDrawn < edge * 3 / 4)
    }

    /** The hero's dash in the serif at the hero's size, in `#cfc4ae`, as the board's `RM&thinsp;&mdash;`. */
    @Test fun theHeroNotReadYetIsTheSerifInRule() {
        notReadYet()
        val style = styleOf(HERO_NOT_READ_YET)
        assertEquals("The hero dash's family", Display, style.fontFamily)
        assertEquals("The hero dash's colour", Rule, style.color)
    }

    /** The board's status line, `#6b6459`. */
    @Test fun readingIsMuted() {
        notReadYet()
        assertEquals("The board's READING is #6b6459", Muted, styleOf(READING).color)
    }

    private fun styleOf(text: String): TextStyle {
        val node = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(text))), useUnmergedTree = true)
            .onFirst().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style
    }

    /** The cell's colours by how many pixels each has, for a failure's message. */
    private fun colours(pixels: PixelMap): Map<Color, Int> {
        val counts = HashMap<Color, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) counts.merge(pixels[x, y], 1, Int::plus)
        return counts.entries.sortedByDescending { it.value }.take(6).associate { it.key to it.value }
    }

    private fun count(pixels: PixelMap, colour: Color): Int {
        var n = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (distance(pixels[x, y], colour) < 0.02f) n++
        return n
    }

    private fun distance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
}
