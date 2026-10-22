package my.pinged.ledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ledger.categories.CategoriesContent
import my.pinged.ledger.categories.CategoriesState
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.hypot

/**
 * `design/LoadingList.dc.html`'s pixels (#283), which Robolectric's drawing
 * cannot vouch for: each row's icon is a dotted 18dp circle, never a filled
 * or hatched one, since hatching means Not captured.
 */
@RunWith(AndroidJUnit4::class)
class ListNotReadYetArtboardTest {
    @get:Rule val compose = createComposeRule()

    private fun categoriesInFlight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                CategoriesContent(state = CategoriesState(), onBack = {}, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onDelete = {})
            }
        }
        compose.mainClock.advanceTimeBy(READING_DELAY_MILLIS + 40)
        compose.waitForIdle()
    }

    @Test fun aListRowNotReadYetHasADottedCircleOnPaper() {
        categoriesInFlight()
        val circle = compose.onAllNodesWithTag(NOT_READ_YET_ICON, useUnmergedTree = true).onFirst()
        val density = compose.density.density
        assertEquals("The board's circle is 18px", 18f, circle.fetchSemanticsNode().size.width / density, 0.6f)
        val pixels = circle.captureToImage().toPixelMap()
        // At CI's mdpi a 1dp dot is one pixel blended with the ground, never
        // Rule itself (#281 measured 0 within 0.02 of Rule), so this reads
        // where the circle drew rather than in what colour.
        val ground = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("The circle's middle is not Paper but $ground", distance(ground, Paper) < 0.02f)
        val radius = pixels.width / 2f
        var inside = 0
        var band = 0
        var drawn = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val r = hypot(x + 0.5f - radius, y + 0.5f - radius)
            val ink = distance(pixels[x, y], ground) > 0.02f
            if (r < radius - 2.5f * density && ink) inside++
            if (r >= radius - 1.5f * density && r <= radius) {
                band++
                if (ink) drawn++
            }
        }
        assertEquals("The circle is filled rather than outlined: $inside pixels drawn inside", 0, inside)
        assertTrue("The circle drew no outline", drawn > 0)
        assertTrue("The outline is solid, not dotted: $drawn of $band drawn", drawn < band * 3 / 4)
    }

    @Test fun aListRowNotReadYetIsTheBoardsFortyFourHigh() {
        categoriesInFlight()
        val row = compose.onAllNodesWithTag(ROW_NOT_READ_YET, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        assertTrue("A row is ${row.size.height / compose.density.density}dp; the board's is at least 44", row.size.height / compose.density.density >= 43.5f)
    }

    private fun distance(a: Color, b: Color): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
}
