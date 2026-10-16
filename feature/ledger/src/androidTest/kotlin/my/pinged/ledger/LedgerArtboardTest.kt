package my.pinged.ledger

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import my.pinged.data.LocalDate
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.Category
import my.pinged.ledger.entry.ADD_PAYMENT
import my.pinged.ledger.home.ADD_PAYMENT_BUTTON
import my.pinged.ledger.home.CASH_OFFER
import my.pinged.ledger.home.EMPTY_HERO
import my.pinged.ledger.home.ENTERED_BY_HAND
import my.pinged.ledger.home.HEARD_CHIP
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.MonthSummary
import my.pinged.ledger.home.dayLabel
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.HeroFill
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import kotlin.math.abs

/**
 * Spending and day one against `design/Main.dc.html` and `design/Empty.dc.html`
 * (#170), each value read off what was laid out or drawn rather than off the
 * source, and each with the board's figure in its message.
 *
 * Where a board value is overruled -- 12sp rather than 11 or 8 under
 * `MonoLabel`'s floor, rows split 3dp above and 8dp below so the category
 * line keeps WCAG 2.5.8's 24dp (spec 9.1) -- the assertion is the overruled
 * figure, and the board's is in the message.
 */
@RunWith(AndroidJUnit4::class)
class LedgerArtboardTest {
    @get:Rule val compose = createComposeRule()

    private var added = 0

    // ---- Populated ----

    @Test fun theHeroIsTheBoardsSerifAt54() {
        populated()
        val style = styleOf(HERO)
        assertEquals("The hero's family", Display, style.fontFamily)
        assertEquals("The board's hero is 54px", 54.sp, style.fontSize)
        assertEquals("The board's hero has line-height 1.02", 1.02.em, style.lineHeight)
    }

    /** The board's `.30`: a 30px span in Muted after the ringgit. */
    @Test fun theHeroSetsItsSenSmallerAndMuted() {
        populated()
        val text = layoutOf(HERO).layoutInput.text
        val sen = text.spanStyles.singleOrNull { text.substring(it.start, it.end) == ".30" }
        assertTrue("No span covers the hero's \".30\": ${text.spanStyles}", sen != null)
        assertEquals("The board's sen are 30px", 30.sp, sen!!.item.fontSize)
        assertEquals("The board's sen are in Muted", Muted, sen.item.color)
    }

    /**
     * The board's top three are `align-items: baseline`: a 13px Karla name and
     * a 13px Plex amount, whose ascents differ, so centring them puts their
     * baselines apart.
     */
    @Test fun theTopThreeShareABaseline() {
        populated()
        val name = baselineOf(FOOD.name)
        val amount = baselineOf(TOP_AMOUNT)
        // Half a pixel: centred, the two measured 3px apart at a density of
        // 2.625 and under 1px at CI's 1; on one baseline they are 0 apart.
        assertTrue("name's baseline at $name px, amount's at $amount px", abs(name - amount) <= 0.5f)
    }

    /**
     * The leader's dots: 8dp clear of the text either side (`margin: 0 8px`)
     * and 4.5dp above the shared baseline (`margin-bottom: 4px` under a 1px
     * border, centre to centre).
     *
     * Any pixel off Paper between the two texts is the leader, since nothing
     * else is drawn there. Not a match on the `Rule` colour: at CI's density of 1 the
     * 1dp line straddles a pixel boundary and is antialiased to `#e5ddcc`.
     */
    @Test fun theLeaderStandsEightClearAndOnTheBaseline() {
        populated()
        val name = boundsOf(FOOD.name)
        val amount = boundsOf(TOP_AMOUNT)
        val baseline = baselineOf(FOOD.name)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val dots = mutableListOf<Pair<Int, Int>>()
        for (y in name.top.toInt() until name.bottom.toInt()) {
            for (x in name.right.toInt() until amount.left.toInt()) {
                if (distance(pixels[x, y], Paper) > 0.05f) dots += x to y
            }
        }
        assertTrue("No leader dots between \"${FOOD.name}\" and its amount", dots.isNotEmpty())
        val density = compose.density.density
        val left = (dots.minOf { it.first } - name.right) / density
        val right = (amount.left - dots.maxOf { it.first } - 1) / density
        val rows = dots.map { it.second }.distinct()
        val centre = (rows.min() + rows.max() + 1) / 2f
        val above = (baseline - centre) / density
        assertTrue("The leader starts ${left}dp after the name; the board's margin is 8", abs(left - 8f) <= 1.5f)
        assertTrue("The leader ends ${right}dp before the amount; the board's margin is 8", abs(right - 8f) <= 3f)
        assertTrue("The leader's centre is ${above}dp above the baseline; the board's is 4.5", abs(above - 4.5f) <= 1f)
    }

    /** The board's 11px, raised to the 12sp floor of the day label beside it. */
    @Test fun theDaySubtotalIsAtTheMonoFloor() {
        populated()
        assertEquals("The board's subtotal is 11px, under the 12sp floor", 12.sp, styleOf(SUBTOTAL).fontSize)
    }

    /** The board's `gap: 11px` between one row and the next. */
    @Test fun rowsAreElevenApart() {
        populated()
        val gap = (boundsOf(SECOND).top - boundsOf(FIRST_LINE).bottom) / compose.density.density
        assertTrue("Row to row is ${gap}dp; the board's is 11", abs(gap - 11f) <= 0.6f)
    }

    /** The board's `gap: 11px` again, between a day's heading and its first row. */
    @Test fun aDayHeadingSitsElevenAboveItsFirstRow() {
        populated()
        val heading = boundsOf(dayLabel(SEP_30)).bottom.coerceAtLeast(boundsOf(SUBTOTAL).bottom)
        val gap = (boundsOf(FIRST).top - heading) / compose.density.density
        assertTrue("Heading to row is ${gap}dp; the board's is 11", abs(gap - 11f) <= 0.6f)
    }

    /** The board's `gap: 14px` between one day and the next. */
    @Test fun daysAreFourteenApart() {
        populated()
        val gap = (boundsOf(dayLabel(SEP_29)).top - boundsOf(SECOND_LINE).bottom) / compose.density.density
        assertTrue("Day to day is ${gap}dp; the board's is 14", abs(gap - 14f) <= 0.6f)
    }

    /** 54dp, in the accent: the board's CASH button. */
    @Test fun theAddButtonIsA54dpStamp() {
        populated()
        val node = compose.onNodeWithTag(ADD_PAYMENT_BUTTON)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals("The board's button is 54px wide", 54f, bounds.width / density, 0.6f)
        assertEquals("The board's button is 54px tall", 54f, bounds.height / density, 0.6f)
        assertEquals("The button is painted mostly in", Stamp, mostOf(node.captureToImage().toPixelMap()))
    }

    @Test fun theAddButtonIsLabelledCash() {
        populated()
        assertInside(boundsOf(CASH), compose.onNodeWithTag(ADD_PAYMENT_BUTTON).fetchSemanticsNode().boundsInRoot)
    }

    /** The + and its label in 54dp at the largest font scale a test here takes. */
    @Test fun cashFitsAtOnePointThreeScale() {
        populated(fontScale = 1.3f)
        assertInside(boundsOf(CASH), compose.onNodeWithTag(ADD_PAYMENT_BUTTON).fetchSemanticsNode().boundsInRoot)
        // Not `hasVisualOverflow`, which measured true for a 41x20 label in
        // the 54x32 it was given: what is drawn against the box it is drawn in.
        val cash = layoutOf(CASH)
        assertEquals("\"CASH\" wraps at 1.3x", 1, cash.lineCount)
        assertTrue("\"CASH\" is cut across at 1.3x", cash.getLineRight(0) <= cash.size.width + 0.5f)
        assertTrue("\"CASH\" is cut short at 1.3x", cash.multiParagraph.height <= cash.size.height + 0.5f)
    }

    @Test fun theAddButtonOpensTheEntry() {
        populated()
        compose.onNodeWithTag(ADD_PAYMENT_BUTTON).performClick()
        assertEquals(1, added)
    }

    /** The top right is the board's chip: a 1px Border frame, a Stamp dot, Ink type. */
    @Test fun theHeardLineIsAChipWithAStampDot() {
        populated(heard = HEARD)
        val chip = compose.onNodeWithTag(HEARD_CHIP).captureToImage().toPixelMap()
        assertTrue("No Stamp dot in the heard chip", count(chip, Stamp) > 0)
        assertTrue("No Border frame round the heard chip", distance(chip[chip.width / 2, 0], Border) < 0.08f)
        assertEquals("The board sets the chip's words in Ink", Ink, styleOf(HEARD).color)
    }

    // ---- Empty ----

    @Test fun dayOneDrawsNoFloatingButton() {
        empty()
        assertEquals(
            "The floating button is drawn over the empty ledger, whose board has none",
            0,
            compose.onAllNodes(hasTestTag(ADD_PAYMENT_BUTTON)).fetchSemanticsNodes().size,
        )
    }

    /** The board's frame: 1px Border round a HeroFill ground, torn in Paper at its foot. */
    @Test fun dayOnesHeroIsFramedAndTorn() {
        empty()
        val frame = compose.onNodeWithTag(EMPTY_HERO).captureToImage().toPixelMap()
        assertEquals("The hero frame is painted mostly in", HeroFill, mostOf(frame))
        assertTrue("No Border at the frame's top edge", distance(frame[frame.width / 2, 0], Border) < 0.08f)
        val density = compose.density.density
        val foot = (frame.height - (9 * density).toInt() until frame.height - 1)
        val torn = foot.sumOf { y -> (1 until frame.width - 1).count { x -> distance(frame[x, y], Paper) < 0.02f } }
        assertTrue("No Paper teeth in the frame's last 9dp", torn > 0)
    }

    @Test fun dayOnesHeadlineIsSerif31() {
        empty()
        val style = styleOf(HEADLINE)
        assertEquals("The headline's family", Display, style.fontFamily)
        assertEquals("The board's headline is 31px", 31.sp, style.fontSize)
        assertEquals("The board's headline has line-height 1.2", 1.2.em, style.lineHeight)
    }

    @Test fun dayOnesBodyIsMutedAt22Leading() {
        empty()
        val style = styleOf(BODY_START, substring = true)
        assertEquals("The body's family", Body, style.fontFamily)
        assertEquals("The board's body is 14px", 14.sp, style.fontSize)
        assertEquals("The board's body has line-height 1.6", 1.6.em, style.lineHeight)
        assertEquals("The board's body is Muted", Muted, style.color)
    }

    /** Full width at the foot, dashed, in Karla 14.5 at 500: the board's offer. */
    @Test fun dayOnesOfferIsAFullWidthDashedFoot() {
        empty()
        val offer = compose.onNodeWithTag(CASH_OFFER)
        val bounds = offer.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals("The offer's left margin; the board's is 20", 20f, (bounds.left - root.left) / density, 0.6f)
        assertEquals("The offer's right margin; the board's is 20", 20f, (root.right - bounds.right) / density, 0.6f)
        assertEquals("The offer above the foot; the board's is 22", 22f, (root.bottom - bounds.bottom) / density, 0.6f)

        val pixels = offer.captureToImage().toPixelMap()
        val inset = (4 * density).toInt()
        val run = (inset until pixels.width - inset).map { pixels[it, (density / 2).toInt()] }
        val gaps = run.count { distance(it, Paper) < distance(it, Stamp) }
        assertTrue("The offer's top edge has $gaps gaps in ${run.size}: not dashed", gaps > run.size / 10 && gaps < run.size * 9 / 10)

        val style = styleOf(CASH_OFFER_LABEL)
        assertEquals("The offer's family", Body, style.fontFamily)
        assertEquals("The offer's weight", FontWeight.Medium, style.fontWeight)
        assertEquals("The board's offer is 14.5px", 14.5.sp, style.fontSize)
    }

    /** The floating button's name rides on the offer that replaces it, after the words drawn. */
    @Test fun dayOnesOfferIsAddPaymentToTalkBack() {
        empty()
        val node = compose.onNode(hasContentDescription(ADD_PAYMENT, substring = true))
        node.performClick()
        assertEquals(1, added)
        val description = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertTrue("The offer's name does not start with what it says: $description", description.single().startsWith(CASH_OFFER_LABEL))
    }

    // ---- Harness ----

    private fun populated(heard: String? = null, fontScale: Float? = null) {
        val first = ledgerTxn(1_840L, localDate = SEP_30, categoryId = FOOD.id, id = 1L, rawCaptureId = null)
        val second = ledgerTxn(3_200L, localDate = SEP_30, categoryId = GROCERIES.id, id = 2L, rawCaptureId = null)
        val third = ledgerTxn(7_000L, localDate = SEP_29, categoryId = PETROL.id, id = 3L, rawCaptureId = null)
        show(
            items = listOf(
                LedgerItem.DayHeader(SEP_30, first.id, listOf(CurrencyTotal("MYR", 6_840L))),
                LedgerItem.Row(first, displayName = FIRST),
                LedgerItem.Row(second, displayName = SECOND),
                LedgerItem.DayHeader(SEP_29, third.id),
                LedgerItem.Row(third, displayName = THIRD),
            ),
            summary = MonthSummary(
                month = YearMonth.of(2026, 9),
                totals = listOf(CurrencyTotal("MYR", 284_730L)),
                top = listOf(CategoryTotal(FOOD.id, "MYR", 84_250L)),
                trustworthy = true,
            ),
            heard = heard,
            fontScale = fontScale,
        )
    }

    private fun empty() = show(items = emptyList(), summary = null, heard = null, fontScale = null)

    private fun show(items: List<LedgerItem>, summary: MonthSummary?, heard: String?, fontScale: Float?) {
        val flow = flowOf(
            PagingData.from(
                items,
                LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true)),
            ),
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                PingedTheme {
                    LedgerScreenContent(
                        items = flow.collectAsLazyPagingItems(),
                        read = LedgerRead(summary = summary, categories = listOf(FOOD, GROCERIES, PETROL)),
                        storageUnavailable = false,
                        onAssign = { _, _, _ -> },
                        onOpenSettings = {},
                        onAddPayment = { added++ },
                        heard = heard,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(text: String, substring: Boolean = false): SemanticsNodeInteraction =
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true)

    private fun boundsOf(text: String): Rect = text(text).fetchSemanticsNode().boundsInRoot

    private fun layoutOf(text: String, substring: Boolean = false): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(text(text, substring).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action) {
            "\"$text\" exposes no text-layout action"
        }(layouts)
        return layouts.single()
    }

    private fun styleOf(text: String, substring: Boolean = false): TextStyle = layoutOf(text, substring).layoutInput.style

    /** [text]'s first baseline, in root pixels. */
    private fun baselineOf(text: String): Float = boundsOf(text).top + layoutOf(text).firstBaseline

    private fun assertInside(inner: Rect, outer: Rect) = assertTrue(
        "$inner is not inside $outer",
        inner.left >= outer.left - 1 && inner.right <= outer.right + 1 &&
            inner.top >= outer.top - 1 && inner.bottom <= outer.bottom + 1,
    )

    private fun mostOf(pixels: PixelMap): Color {
        val counts = HashMap<Color, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) counts.merge(pixels[x, y], 1, Int::plus)
        return counts.maxBy { it.value }.key
    }

    private fun count(pixels: PixelMap, colour: Color): Int {
        var n = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (distance(pixels[x, y], colour) < 0.02f) n++
        return n
    }

    private fun distance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    private companion object {
        val FOOD = Category(id = 1L, name = "Food & Drinks", iconKey = "food", sortOrder = 1)
        val SEP_30 = LocalDate(20_260_930)
        val SEP_29 = LocalDate(20_260_929)

        const val FIRST = "Grab"
        const val SECOND = "99 Speedmart"
        const val THIRD = "Shell Jalan Ampang"
        val GROCERIES = Category(id = 2L, name = "Groceries", iconKey = "groceries", sortOrder = 2)
        val PETROL = Category(id = 3L, name = "Petrol", iconKey = "petrol", sortOrder = 3)
        val FIRST_LINE = FOOD.name + Separator + ENTERED_BY_HAND
        val SECOND_LINE = GROCERIES.name + Separator + ENTERED_BY_HAND

        val HERO = money(284_730L, "MYR", symbol = true)
        val TOP_AMOUNT = money(84_250L, "MYR", symbol = false)
        val SUBTOTAL = money(6_840L, "MYR", symbol = false)
        const val CASH = "CASH"
        const val HEARD = "HEARD IN THE LAST 5 MIN"

        const val HEADLINE = "Nothing is missing. There is just nothing yet."
        const val BODY_START = "Android will not hand over notifications"
        const val CASH_OFFER_LABEL = "Or start with a cash expense"
    }
}
