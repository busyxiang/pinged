package my.pinged.ledger

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Seed
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ALWAYS_SWITCH_TAG
import my.pinged.ledger.home.CategoryPicker
import my.pinged.ledger.home.ChooserSubject
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.home.TeachOffer
import my.pinged.ledger.home.alwaysLabel
import my.pinged.ledger.home.fixLine
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.CLOSE
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Mono
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * #167: the category chooser against `design/Chooser.dc.html`, one test per
 * place it had drifted. Layout and type off the semantics tree; strokes and
 * fills off the screen, as [SheetChromeTest] reads them and for its reason.
 *
 * Where the board sets mono under 12 (the strip's bill at 10.5, the notes at
 * 9.5) the sheet keeps [my.pinged.ui.theme.MonoLabel]'s 12sp floor, and these
 * tests read the family, not the size.
 */
class ChooserArtboardTest {
    @get:JUnitRule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theHeadingIsTheSerifQuestionWithAnX() {
        var dismissed = false
        chooser(onDismiss = { dismissed = true })
        val style = styleOf(PICKER_HEADING)
        assertEquals("What was this?", PICKER_HEADING)
        assertEquals("The heading's family", Display, style.fontFamily)
        assertEquals("The heading's size", 23.sp, style.fontSize)
        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.waitForIdle()
        assertTrue("The chooser's X did not dismiss it", dismissed)
    }

    @Test fun aStripQuotesTheBillAndItsAmountInTheMono() {
        chooser()
        assertEquals("The bill's family", Mono, styleOf(BILL).fontFamily)
        assertEquals("The amount's family", Mono, styleOf(AMOUNT).fontFamily)
        val heading = bounds(compose.onNodeWithText(PICKER_HEADING).fetchSemanticsNode())
        val bill = bounds(compose.onNodeWithText(BILL).fetchSemanticsNode())
        val first = bounds(tile(FOOD.name).fetchSemanticsNode())
        assertTrue("The strip is not between the heading and the grid", heading.bottom <= bill.top && bill.bottom <= first.top)
    }

    @Test fun theCategoriesAreATwoColumnGridOf52dpTiles() {
        chooser()
        val (a, b, c) = listOf(FOOD, GROCERIES, TRANSPORT).map { bounds(tile(it.name).fetchSemanticsNode()) }
        assertEquals("The second tile's top against the first's", a.top, b.top)
        assertTrue("The second tile is not right of the first", b.left >= a.right)
        assertEquals("The third tile's left against the first's", a.left, c.left)
        assertTrue("The third tile is not under the first", c.top >= a.bottom)
        assertEquals("The gap between columns, dp", 8, ((b.left - a.right) / density).roundToInt())
        assertEquals("The gap between rows, dp", 8, ((c.top - a.bottom) / density).roundToInt())
        assertTrue("A tile is %.1fdp tall".format(a.height / density), a.height / density >= 51.5f)
        val name = styleOf(TRANSPORT.name)
        assertEquals("A tile's family", Body, name.fontFamily)
        assertEquals("A tile's size", 13.sp, name.fontSize)
    }

    @Test fun anUnchosenTileIsOutlinedInBorder() {
        val screen = chooser()
        val at = bounds(tile(TRANSPORT.name).fetchSemanticsNode())
        assertTrue("An unchosen tile's left edge is not Border", edgeIs(screen, at, Border))
        assertEquals("An unchosen tile's name", Ink, styleOf(TRANSPORT.name).color)
    }

    @Test fun theChosenTileIsOutlinedInStampAndNotFilled() {
        val screen = chooser()
        val at = bounds(tile(GROCERIES.name).fetchSemanticsNode())
        assertTrue("The chosen tile's left edge is not Stamp", edgeIs(screen, at, Stamp))
        val inside = screen[at.left + (4 * density).roundToInt(), at.top + (4 * density).roundToInt()]
        assertTrue("The chosen tile is filled $inside, not left Paper", close(inside, Paper))
        val name = styleOf(GROCERIES.name)
        assertEquals("The chosen tile's name", Stamp, name.color)
        assertNotEquals("The chosen tile's weight", FontWeight.Bold, name.fontWeight)
    }

    @Test fun theGridComesBeforeTheSwitchAndTheSwitchBeforeSave() {
        chooser()
        val grid = bounds(tile(TRANSPORT.name).fetchSemanticsNode())
        val switch = bounds(compose.onNodeWithTag(ALWAYS_SWITCH_TAG, useUnmergedTree = true).fetchSemanticsNode())
        val save = bounds(compose.onNodeWithText(SAVE_LABEL).fetchSemanticsNode())
        assertTrue("The switch (top ${switch.top}) is not under the grid (bottom ${grid.bottom})", switch.top >= grid.bottom)
        assertTrue("Save (top ${save.top}) is not under the switch (bottom ${switch.bottom})", save.top >= switch.bottom)
    }

    @Test fun theSwitchIsKarla14UnderADottedRule() {
        val screen = chooser()
        val label = styleOf(alwaysLabel(GROCERIES.name))
        assertEquals("The switch label's family", Body, label.fontFamily)
        assertEquals("The switch label's size", 14.sp, label.fontSize)
        assertNotEquals("The switch label's weight", FontWeight.Medium, label.fontWeight)
        // The rule is the switch row's top edge, margin to margin; a 1dp
        // stroke centred on it, so read a few pixels either side.
        val row = bounds(compose.onNodeWithText(alwaysLabel(GROCERIES.name)).fetchSemanticsNode())
        val reach = (2 * density).roundToInt()
        val ruled = (row.top - reach..row.top + reach).any { y ->
            (row.left until row.right).count { inked(screen[it, y], Rule) } > (row.right - row.left) / 6
        }
        assertTrue("No dotted Rule above the switch", ruled)
    }

    @Test fun theFixLineIsInStamp() {
        chooser(movable = 10)
        compose.boundedWaitUntil(5_000) { compose.onAllNodes(hasText(fixLine(10))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("The fix line's colour", Stamp, styleOf(fixLine(10)).color)
    }

    /** The issue's note: every seeded choice at CI's 320x640, and Save still on screen with the grid. */
    @Test fun saveStaysOnScreenBesideAFullGrid() {
        val seeded = Seed.categories().filterNot { it.isProtected }.mapIndexed { i, c -> c.copy(id = i + 1L) }
        chooser(categories = seeded, initial = 1)
        compose.onNodeWithText(SAVE_LABEL).assertIsDisplayed()
        tile(seeded.first().name).assertIsDisplayed()
    }

    // ---- what is drawn ------------------------------------------------------

    private fun chooser(
        categories: List<Category> = listOf(FOOD, GROCERIES, TRANSPORT),
        initial: Long = GROCERIES.id,
        movable: Int = 0,
        onDismiss: () -> Unit = {},
    ): PixelMap {
        compose.setContent {
            PingedTheme {
                Behind()
                CategoryPicker(
                    categories = categories,
                    subject = ChooserSubject(BILL, AMOUNT),
                    teach = TeachOffer(merchant = "99 Speedmart", startsOn = true),
                    initial = initial,
                    preview = { RetroPreview(movable = movable, conflicts = emptyList()) },
                    onSave = { _, _ -> },
                    onDismiss = onDismiss,
                )
            }
        }
        compose.waitForIdle()
        return settledScreen()
    }

    /**
     * The screen once two captures a beat apart agree. `waitForIdle` returns
     * when composition is done, not when the frame reaches the display: the
     * class's first test read pure Paper across a tile edge the semantics
     * tree placed correctly, the sheet still on its way up.
     */
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

    @Composable private fun Behind() = Box(Modifier.fillMaxSize().background(Paper))

    private fun tile(name: String): SemanticsNodeInteraction = compose.onNode(hasText(name) and isSelectable())

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
     * Whether a pixel of [colour] lies in the first 2dp of [at]'s left edge,
     * halfway down. A 1dp hairline is under 3px here, and the node's position
     * is a float rounded to the pixel, so no single column can be named.
     */
    private fun edgeIs(screen: PixelMap, at: Bounds, colour: Color): Boolean =
        (at.left until at.left + (2 * density).roundToInt()).any { close(screen[it, at.top + at.height / 2], colour) }

    /**
     * [c] is [ink] drawn over [Paper] at 30% coverage or more. At CI's
     * density a 1dp stroke is one pixel straddling two rows, and neither row
     * is [ink] itself.
     */
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
        val GROCERIES = Category(id = 2, name = "Groceries", iconKey = "shopping-basket", sortOrder = 1)
        val TRANSPORT = Category(id = 3, name = "Transport", iconKey = "car", sortOrder = 2)
        const val BILL = "TNG*99SPEEDMART"
        const val AMOUNT = "32.00"
        const val TOLERANCE = 10f / 255f
    }
}
