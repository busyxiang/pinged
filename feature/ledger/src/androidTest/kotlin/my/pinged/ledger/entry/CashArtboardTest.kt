package my.pinged.ledger.entry

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import my.pinged.data.entity.Category
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * #169: manual entry against `design/Cash.dc.html`, one test per place it had
 * drifted. The board's keypad (spec 9.4 keeps the system keyboard) and its
 * wrapped chips (the screen's chips scroll sideways) are documented
 * departures and are not read. Type off the semantics tree; strokes and fills
 * off the screen, as `ChooserArtboardTest` reads them and for its reason.
 */
class CashArtboardTest {
    @get:JUnitRule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theAmountIsSerif58InInk() {
        cash()
        val style = amountStyle()
        assertEquals("The figure's family", Display, style.fontFamily)
        assertEquals("The figure's size", 58.sp, style.fontSize)
        assertEquals("The figure's colour", Ink, style.color)
    }

    @Test fun theRmIsSerif30InFaint() {
        cash()
        val rm = amountLayout().layoutInput.text.spanStyles.singleOrNull { it.start == 0 && it.end == 2 }?.item
            ?: SpanStyle()
        assertEquals("RM's family", Display, rm.fontFamily ?: amountStyle().fontFamily)
        assertEquals("RM's size", 30.sp, rm.fontSize)
        assertEquals("RM's colour", Faint, rm.color)
    }

    /** The board's `2px` by `44px` Stamp bar after the figure. */
    @Test fun theCaretIsA2dpStampBar44dpTall() {
        val screen = cash()
        val field = bounds(compose.onNodeWithTag(AMOUNT_FIELD).fetchSemanticsNode())
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (x in field.left until field.right) for (y in field.top until field.bottom) {
            if (close(screen[x, y], Stamp)) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("No Stamp caret in the amount field", left <= right)
        assertEquals("The caret's width, dp", 2f, (right - left + 1) / density, 1f / density + 0.01f)
        assertEquals("The caret's height, dp", 44f, (bottom - top + 1) / density, 1f / density + 0.01f)
    }

    @Test fun theChosenChipIsFilledInStamp() {
        val screen = cash()
        val at = bounds(chip(FOOD.name).fetchSemanticsNode())
        val inside = screen[at.left + (4 * density).roundToInt(), at.top + (4 * density).roundToInt()]
        assertTrue("The chosen chip is filled $inside, not Stamp", close(inside, Stamp))
        val name = styleOf(FOOD.name)
        assertEquals("The chosen chip's name", Paper, name.color)
        assertEquals("The chosen chip's weight", FontWeight.Medium, name.fontWeight)
    }

    @Test fun anUnchosenChipIsKarla13OutlinedInBorder() {
        val screen = cash()
        val at = bounds(chip(TRANSPORT.name).fetchSemanticsNode())
        assertEdge(screen, at, Border, "An unchosen chip's outline")
        val name = styleOf(TRANSPORT.name)
        assertEquals("An unchosen chip's family", Body, name.fontFamily)
        assertEquals("An unchosen chip's size", 13.sp, name.fontSize)
        assertEquals("An unchosen chip's name", Ink, name.color)
    }

    /** The board draws no date chip; outlined as its neighbours are, since [Rule] is for dotted rules. */
    @Test fun theDateChipIsOutlinedInBorderNotRule() {
        val screen = cash()
        assertEdge(screen, bounds(compose.onNodeWithTag(DATE_CHIP).fetchSemanticsNode()), Border, "The date chip's outline")
    }

    @Test fun saveIs52dpInKarlaUnderADottedRule() {
        compose.setContent { Cash() }
        // The amount opens the keyboard on arrival. MainActivity pads every
        // screen by it, so Save is above it in the app; this bare activity
        // does not, and the keyboard covers the band.
        compose.waitForIdle()
        compose.runOnUiThread {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.onNodeWithText(SAVE).performScrollTo()
        compose.waitForIdle()
        val screen = settledScreen()
        val save = bounds(compose.onNodeWithText(SAVE).fetchSemanticsNode())
        assertTrue("Save is %.1fdp tall".format(save.height / density), save.height / density >= 51.5f)
        val label = styleOf(SAVE)
        assertEquals("Save's family", Body, label.fontFamily)
        assertEquals("Save's weight", FontWeight.Medium, label.fontWeight)
        assertEquals("Save's size", 15.5.sp, label.fontSize)
        // The band's top edge is 14dp above the button: read a few dp either
        // side for a dotted Rule running most of the screen's width.
        val width = screen.width
        val band = save.top - (14 * density).roundToInt()
        val reach = (3 * density).roundToInt()
        val ruled = (band - reach..band + reach).any { y ->
            (0 until width).count { inked(screen[it, y], Rule) } > width / 6
        }
        assertTrue("No dotted Rule above Save's band", ruled)
    }

    // ---- what is drawn ------------------------------------------------------

    @androidx.compose.runtime.Composable
    private fun Cash() = PingedTheme {
        ManualEntryScreenContent(
            state = ManualEntryState(
                draft = ManualDraft(amountSen = 1250, category = CategoryChoice(FOOD.id, ChosenBy.TAPPED)),
                categories = listOf(FOOD, TRANSPORT, GROCERIES),
                today = TODAY,
            ),
            onAmount = {},
            onMerchant = {},
            onNote = {},
            onSave = {},
            onBack = {},
            onSaved = {},
        )
    }

    private fun cash(): PixelMap {
        compose.setContent { Cash() }
        compose.waitForIdle()
        return settledScreen()
    }

    /** The screen once two captures a beat apart agree; `ChooserArtboardTest.settledScreen`'s reason. */
    private fun settledScreen(): PixelMap {
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

    private fun chip(name: String): SemanticsNodeInteraction = compose.onNode(hasText(name) and isSelectable())

    private fun amountLayout(): TextLayoutResult {
        val node = compose.onNodeWithTag(AMOUNT_FIELD).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single()
    }

    private fun amountStyle(): TextStyle = amountLayout().layoutInput.style

    private fun styleOf(text: String): TextStyle {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single().layoutInput.style
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height get() = bottom - top
    }

    private fun bounds(node: SemanticsNode): Bounds {
        val at = node.positionOnScreen
        val l = at.x.roundToInt()
        val t = at.y.roundToInt()
        return Bounds(l, t, l + node.size.width, t + node.size.height)
    }

    /**
     * The darkest pixel in the first 3dp of [at]'s left edge, halfway down,
     * is [colour]. The darkest, not any: a [Rule] stroke's antialiased
     * fringe over Paper passes for [Border] within the tolerance.
     */
    private fun assertEdge(screen: PixelMap, at: Bounds, colour: Color, what: String) {
        val y = at.top + at.height / 2
        val darkest = (at.left until at.left + (3 * density).roundToInt()).map { screen[it, y] }.minBy { it.green }
        assertTrue("$what is $darkest, not $colour", close(darkest, colour))
    }

    /** [c] is [ink] drawn over [Paper] at 30% coverage or more; `ChooserArtboardTest.inked`'s reason. */
    private fun inked(c: Color, ink: Color): Boolean {
        val alpha = (Paper.green - c.green) / (Paper.green - ink.green)
        if (alpha < 0.3f || alpha > 1.1f) return false
        val blend = Color(
            red = Paper.red + (ink.red - Paper.red) * alpha,
            green = Paper.green + (ink.green - Paper.green) * alpha,
            blue = Paper.blue + (ink.blue - Paper.blue) * alpha,
        )
        return close(c, blend)
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun close(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < TOLERANCE && abs(a.green - b.green) < TOLERANCE && abs(a.blue - b.blue) < TOLERANCE

    private companion object {
        val FOOD = Category(id = 1, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
        val TRANSPORT = Category(id = 2, name = "Transport", iconKey = "car", sortOrder = 1)
        val GROCERIES = Category(id = 3, name = "Groceries", iconKey = "shopping-basket", sortOrder = 2)
        val TODAY: LocalDate = LocalDate.of(2026, 8, 14)
        const val TOLERANCE = 10f / 255f
    }
}
