package my.pinged.ledger

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import my.pinged.ledger.NOT_READ_YET_ICON
import my.pinged.ledger.entry.ADD_PAYMENT
import my.pinged.ledger.home.ADD_PAYMENT_BUTTON
import my.pinged.ledger.home.CASH_OFFER
import my.pinged.ledger.home.EMPTY_HERO
import my.pinged.ledger.home.ENTERED_BY_HAND
import my.pinged.ledger.home.HEARD_CHIP
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.MONTH_BUTTON
import my.pinged.ledger.home.MONTH_SUMMARY
import my.pinged.ledger.home.MonthSummary
import my.pinged.ledger.home.TOP_BAR_TOTAL
import my.pinged.ledger.home.dayLabel
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.HeroFill
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.Mono
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import kotlin.math.abs

/**
 * Spending and day one against `design/Main.dc.html` and `design/Empty.dc.html`
 * (#170), and Spending scrolled against `design/MainScrolled.dc.html` and
 * `MainScrolled320.dc.html` (#253), each value read off what was laid out or drawn rather than off the
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

    /**
     * The button floats over the list as `Main.dc.html` draws it (#287):
     * 20dp in from the right and 16dp above the tab bar, which is this
     * screen's foot. The list's viewport runs to that foot, with no band.
     */
    @Test fun at320UnderTheBannerTheListRunsToTheTabBar() = assertTheListRunsToTheTabBar(UNDER_THE_BANNER)

    @Test fun at411TheListRunsToTheTabBar() = assertTheListRunsToTheTabBar(AT_411)

    private fun assertTheListRunsToTheTabBar(size: DpSize) {
        long(size = size)
        val screen = screenBounds()
        assertEquals("The list ends ${(screen.bottom - listBounds().bottom) / compose.density.density}dp above the tab bar", screen.bottom, listBounds().bottom, 0.5f)
        assertTheButtonRests()
    }

    /**
     * At the end of the list its last row rests above the button, on the
     * list's 86dp of end padding: the button and 16dp either side
     * (`MainEnd.dc.html`).
     */
    @Test fun at320UnderTheBannerTheLastRowEndsAboveTheButton() = assertTheLastRowEndsAboveTheButton(UNDER_THE_BANNER)

    @Test fun at411TheLastRowEndsAboveTheButton() = assertTheLastRowEndsAboveTheButton(AT_411)

    private fun assertTheLastRowEndsAboveTheButton(size: DpSize) {
        long(size = size)
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("txn-24")
        compose.waitForIdle()
        val row = boundsOf(LAST_ROW)
        val button = buttonBounds()
        assertTrue("The last row is cut off at ${row.bottom}, the list's foot at ${listBounds().bottom}", row.bottom <= listBounds().bottom + 0.5f)
        assertTrue("The last row ends at ${row.bottom}, under the button's top at ${button.top}", row.bottom <= button.top)
    }

    /**
     * A drag down the list, toward older days, slides the button off the
     * bottom; a drag back up brings it back while the finger is still down.
     * The clock is held so the finger stays down: the list never comes to
     * rest between the two. Away, no Stamp is drawn where it rests.
     */
    @Test fun aDragDownTheListHidesTheButtonAndADragUpShowsIt() {
        long(size = UNDER_THE_BANNER)
        val list = compose.onNode(hasScrollToIndexAction())
        compose.mainClock.autoAdvance = false
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, -height / 3f))
            moveBy(Offset(0f, -height / 3f))
        }
        compose.mainClock.advanceTimeBy(SLIDE_SETTLED)
        val screen = screenBounds()
        val density = compose.density.density
        val column = (screen.right - 74 * density)..(screen.right - 20 * density)
        assertEquals("Stamp where the button rests, away", 0, stampIn(column, (screen.bottom - 70 * density)..(screen.bottom - 16 * density)))
        list.performTouchInput { moveBy(Offset(0f, height / 4f)) }
        compose.mainClock.advanceTimeBy(SLIDE_SETTLED)
        assertTheButtonRests()
        list.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
    }

    /** After a fling the button is back once the list has come to rest, and opens the entry. */
    @Test fun afterAFlingTheButtonComesBackAndOpensTheEntry() {
        long(size = UNDER_THE_BANNER)
        val before = compose.onNodeWithTag(MONTH_SUMMARY).fetchSemanticsNode().boundsInRoot.top
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val after = compose.onAllNodesWithTag(MONTH_SUMMARY).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.top
        assertTrue("The fling did not move the list", after == null || after < before)
        assertTheButtonRests()
        compose.onNodeWithTag(ADD_PAYMENT_BUTTON).performClick()
        assertEquals(1, added)
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

    /**
     * The board's chip is one line (#221). The widest lines `heardLine` writes,
     * beside the widest month and with the chevron and the magnifier drawn;
     * nothing caps the day count, so three digits. At 320dp none fits on the
     * month's row, so each moves whole to a line of its own rather than wrap.
     */
    private fun assertHeardDropsWhole(heard: String) {
        populated(heard = heard, chrome = true, width = 320.dp)
        assertEquals("\"$heard\" wraps", 1, layoutOf(heard).lineCount)
        val chip = compose.onNodeWithTag(HEARD_CHIP).fetchSemanticsNode().boundsInRoot
        val month = compose.onNodeWithTag(MONTH_BUTTON).fetchSemanticsNode().boundsInRoot
        assertTrue("\"$heard\" at ${chip.top} is not under the month's row at ${month.bottom}", chip.top >= month.bottom)
    }

    @Test fun heardUnderFiveMinutesDropsWholeAt320() = assertHeardDropsWhole(HEARD)

    @Test fun heardFiftyNineMinutesDropsWholeAt320() = assertHeardDropsWhole("HEARD 59 MIN")

    @Test fun heardTwentyThreeHoursDropsWholeAt320() = assertHeardDropsWhole("HEARD 23 HR")

    @Test fun heardThreeDigitDaysDropsWholeAt320() = assertHeardDropsWhole("HEARD 999 DAYS")

    /** Where it fits, the chip keeps the board's place on the month's row. */
    @Test fun heardSitsOnTheMonthsRowAt411() {
        populated(heard = "HEARD 59 MIN", chrome = true, width = 411.dp)
        assertEquals(1, layoutOf("HEARD 59 MIN").lineCount)
        val chip = compose.onNodeWithTag(HEARD_CHIP).fetchSemanticsNode().boundsInRoot
        val month = compose.onNodeWithTag(MONTH_BUTTON).fetchSemanticsNode().boundsInRoot
        assertTrue("The chip at ${chip.top}..${chip.bottom} is off the month's row ${month.top}..${month.bottom}", chip.top >= month.top && chip.bottom <= month.bottom)
    }

    // ---- Scrolled, `design/MainScrolled.dc.html` ----

    /** A row's amount type, the board's 15px Plex, in Ink. */
    private fun assertTheTotalIsARowsAmount(width: Dp) {
        scrolled(width)
        val style = styleOf(BAR_TOTAL, inTotal = true)
        assertEquals("The total's family", Mono, style.fontFamily)
        assertEquals("The board's total is 15px", 15.sp, style.fontSize)
        assertEquals("The board's total is Ink", Ink, style.color)
    }

    /** `align-items: baseline`: the abbreviated month and the total on one line. */
    private fun assertTheTotalSharesTheMonthsBaseline(width: Dp) {
        scrolled(width)
        val month = boundsOf(SHORT_MONTH).top + layoutOf(SHORT_MONTH).firstBaseline
        val total = boundsOf(BAR_TOTAL, inTotal = true).top + layoutOf(BAR_TOTAL, inTotal = true).firstBaseline
        assertTrue("The month's baseline at $month px, the total's at $total px", abs(month - total) <= 0.5f)
        assertTrue(
            "The total does not follow the month on its row",
            boundsOf(BAR_TOTAL, inTotal = true).left > compose.onNodeWithTag(MONTH_BUTTON).fetchSemanticsNode().boundsInRoot.right,
        )
    }

    @Test fun theTotalIsARowsAmountAt411() = assertTheTotalIsARowsAmount(411.dp)

    @Test fun theTotalIsARowsAmountAt320() = assertTheTotalIsARowsAmount(320.dp)

    @Test fun theTotalSharesTheMonthsBaselineAt411() = assertTheTotalSharesTheMonthsBaseline(411.dp)

    @Test fun theTotalSharesTheMonthsBaselineAt320() = assertTheTotalSharesTheMonthsBaseline(320.dp)

    /** At 320 the chip keeps its own line, under the month's, scrolled as at the top. */
    @Test fun at320TheChipKeepsItsOwnLineUnderTheTotal() {
        scrolled(320.dp)
        val chip = compose.onNodeWithTag(HEARD_CHIP).fetchSemanticsNode().boundsInRoot
        val total = compose.onNodeWithTag(TOP_BAR_TOTAL).fetchSemanticsNode().boundsInRoot
        assertTrue("The chip at ${chip.top} is not under the total's row at ${total.bottom}", chip.top >= total.bottom)
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

    // ---- Not read yet (#280) ----

    /** `LoadingSpending.dc.html`'s `RM&thinsp;&mdash;`: the hero's serif at 54, in `#cfc4ae`. */
    @Test fun theHeroNotReadYetIsTheBoardsSerifInRule() {
        notReadYet()
        val style = styleOf(HERO_NOT_READ_YET)
        assertEquals("The hero dash's family", Display, style.fontFamily)
        assertEquals("The board's hero dash is 54px", 54.sp, style.fontSize)
        assertEquals("The board's hero dash is #cfc4ae", Rule, style.color)
    }

    /** `LoadingSpending.dc.html`'s status line: READING in `#6b6459`, beside a square of the same. */
    @Test fun readingIsMutedWithAMutedSquare() {
        notReadYet()
        assertEquals("The board's READING is #6b6459", Muted, styleOf(READING).color)
        assertTrue("No Muted square beside READING", squareBeside(READING, Muted) > 0)
    }

    /** `LoadingWaiting.dc.html`'s status line: the wait named in Stamp, beside a Stamp square. */
    @Test fun anExportsWaitIsStampWithAStampSquare() {
        notReadYet(HeldBy.Export)
        assertEquals("The board's waiting line is #a03826", Stamp, styleOf(WAITING_FOR_AN_EXPORT).color)
        assertTrue("No Stamp square beside the waiting line", squareBeside(WAITING_FOR_AN_EXPORT, Stamp) > 0)
    }

    /**
     * The board's rows: a dotted 18dp circle where the icon goes, never a
     * filled or hatched one. Dotted: along the circle's top, Rule's dots
     * have Card between them.
     */
    @Test fun aRowNotReadYetHasADottedCircle() {
        notReadYet()
        val circle = compose.onAllNodesWithTag(NOT_READ_YET_ICON).onFirst().fetchSemanticsNode()
        val density = compose.density.density
        assertEquals("The board's circle is 18px", 18f, circle.size.width / density, 0.6f)
        val pixels = compose.onAllNodesWithTag(NOT_READ_YET_ICON).onFirst().captureToImage().toPixelMap()
        val inside = count(pixels, Card)
        val dots = count(pixels, Rule)
        assertTrue("The circle drew no Rule dots", dots > 0)
        assertTrue("The circle is filled rather than outlined: $inside Card pixels", inside > pixels.width * pixels.height / 2)
    }

    // ---- Harness ----

    private fun notReadYet(heldBy: HeldBy? = null) {
        show(items = emptyList(), summary = null, heard = null, fontScale = null, refresh = LoadState.Loading, heldBy = heldBy)
        compose.mainClock.advanceTimeBy(READING_DELAY_MILLIS + 40)
        compose.waitForIdle()
    }

    /** Pixels of [colour] in the 10dp left of [text], where the status line's square is. */
    private fun squareBeside(text: String, colour: Color): Int {
        val density = compose.density.density
        val bounds = boundsOf(text)
        return countIn(bounds.left - 14 * density..bounds.left - 4 * density, bounds.top..bounds.bottom, colour)
    }

    /** Pixels within a hair of [colour] in the root's [x] by [y]. */
    private fun countIn(x: ClosedFloatingPointRange<Float>, y: ClosedFloatingPointRange<Float>, colour: Color): Int {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val xs = x.start.toInt().coerceAtLeast(0) until x.endInclusive.toInt().coerceAtMost(pixels.width)
        val ys = y.start.toInt().coerceAtLeast(0) until y.endInclusive.toInt().coerceAtMost(pixels.height)
        return ys.sumOf { py -> xs.count { px -> distance(pixels[px, py], colour) < 0.02f } }
    }

    private fun populated(heard: String? = null, fontScale: Float? = null, chrome: Boolean = false, width: Dp? = null) {
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
            chrome = chrome,
            width = width,
        )
    }

    /** [long] at [width], with the TopBar's chevron, magnifier and chip, scrolled past its summary. */
    private fun scrolled(width: Dp) {
        long(size = DpSize(width, 640.dp), heard = HEARD, chrome = true)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(PAST_THE_SUMMARY)
        compose.waitForIdle()
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertExists()
    }

    /** More rows than a screen holds, so the list scrolls under wherever the button is. */
    private fun long(size: DpSize? = null, heard: String? = null, chrome: Boolean = false) {
        show(
            items = longFeed(),
            summary = MonthSummary(
                month = YearMonth.of(2026, 9),
                totals = listOf(CurrencyTotal("MYR", 30_000L)),
                top = listOf(CategoryTotal(FOOD.id, "MYR", 30_000L)),
                trustworthy = true,
            ),
            heard = heard,
            fontScale = null,
            chrome = chrome,
            size = size,
        )
    }

    private fun empty() = show(items = emptyList(), summary = null, heard = null, fontScale = null)

    /**
     * [chrome] draws the month's chevron and the magnifier, which the top bar
     * shares with the chip; [width], or [size], lays the screen out that big
     * whatever the device's.
     */
    private fun show(
        items: List<LedgerItem>,
        summary: MonthSummary?,
        heard: String?,
        fontScale: Float?,
        chrome: Boolean = false,
        width: Dp? = null,
        size: DpSize? = null,
        refresh: LoadState = LoadState.NotLoading(true),
        heldBy: HeldBy? = null,
    ) {
        val flow = flowOf(
            PagingData.from(
                items,
                LoadStates(refresh, LoadState.NotLoading(true), LoadState.NotLoading(true)),
            ),
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalHeldBy provides heldBy,
            ) {
                PingedTheme {
                    LedgerScreenContent(
                        modifier = Modifier.testTag(SCREEN).then(
                            when {
                                size != null -> Modifier.requiredSize(size)
                                width != null -> Modifier.requiredWidth(width)
                                else -> Modifier
                            },
                        ),
                        items = flow.collectAsLazyPagingItems(),
                        read = LedgerRead(summary = summary, categories = listOf(FOOD, GROCERIES, PETROL)),
                        storageUnavailable = false,
                        onAssign = { _, _, _ -> },
                        onOpenSettings = {},
                        onAddPayment = { added++ },
                        heard = heard,
                        onOpenSearch = if (chrome) ({}) else null,
                        onOpenMonths = if (chrome) ({}) else null,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * [inTotal] finds [text] inside the TopBar's total as it shows: the
     * total is laid out while faded out too, and the unmerged tree lists it.
     */
    private fun text(text: String, substring: Boolean = false, inTotal: Boolean = false): SemanticsNodeInteraction =
        if (inTotal) {
            compose.onNode(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag(TOP_BAR_TOTAL)), useUnmergedTree = true)
        } else {
            compose.onNodeWithText(text, substring = substring, useUnmergedTree = true)
        }

    private fun boundsOf(text: String, inTotal: Boolean = false): Rect = text(text, inTotal = inTotal).fetchSemanticsNode().boundsInRoot

    private fun listBounds(): Rect = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot

    /** The screen as laid out, whose foot is the tab bar's top in the app. */
    private fun screenBounds(): Rect = compose.onNodeWithTag(SCREEN).fetchSemanticsNode().boundsInRoot

    private fun buttonBounds(): Rect = compose.onNodeWithTag(ADD_PAYMENT_BUTTON).fetchSemanticsNode().boundsInRoot

    /** Pixels within a hair of Stamp in the root's [x] by [y], clamped to the root. */
    private fun stampIn(x: ClosedFloatingPointRange<Float>, y: ClosedFloatingPointRange<Float>): Int {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val xs = x.start.toInt().coerceAtLeast(0) until x.endInclusive.toInt().coerceAtMost(pixels.width)
        val ys = y.start.toInt().coerceAtLeast(0) until y.endInclusive.toInt().coerceAtMost(pixels.height)
        assertTrue("The strip $x by $y is off the root, ${pixels.width}x${pixels.height}", !xs.isEmpty() && !ys.isEmpty())
        return ys.sumOf { py -> xs.count { px -> distance(pixels[px, py], Stamp) < 0.02f } }
    }

    /** The whole button where the board rests it: 20dp in, 16dp above the tab bar. */
    private fun assertTheButtonRests() {
        val button = buttonBounds()
        val screen = screenBounds()
        val density = compose.density.density
        assertEquals("The button shows ${button.height / density}dp of its 54", 54f, button.height / density, 0.6f)
        assertEquals("The button above the tab bar; the board's is 16", 16f, (screen.bottom - button.bottom) / density, 0.6f)
        assertEquals("The button's right margin; the board's is 20", 20f, (screen.right - button.right) / density, 0.6f)
    }

    private fun layoutOf(text: String, substring: Boolean = false, inTotal: Boolean = false): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(text(text, substring, inTotal).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action) {
            "\"$text\" exposes no text-layout action"
        }(layouts)
        return layouts.single()
    }

    private fun styleOf(text: String, substring: Boolean = false, inTotal: Boolean = false): TextStyle =
        layoutOf(text, substring, inTotal).layoutInput.style

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
        val BAR_TOTAL = money(30_000L, "MYR", symbol = true)
        const val SHORT_MONTH = "SEP"

        /** An index of [long]'s list well past its summary, the first item. */
        const val PAST_THE_SUMMARY = 20
        const val HEARD = "HEARD <5 MIN"

        /** [long]'s last row, keyed `txn-24`. */
        const val LAST_ROW = "Row 24"

        /**
         * In ms of the test clock, longer than the button's slide: the drag
         * test asserts the button at rest, whole, after it.
         */
        const val SLIDE_SETTLED = 1_000L

        /** The test tag on the screen, whose foot is the tab bar's top in the app. */
        const val SCREEN = "screen"

        /**
         * The Spending tab at 320x640 under the capture-off banner, which
         * every fresh install draws: 640 less the 52dp status bar, the 62dp
         * tab bar and the banner's 226dp (measured in `:app`).
         */
        val UNDER_THE_BANNER = DpSize(320.dp, 276.dp)

        val AT_411 = DpSize(411.dp, 800.dp)

        const val HEADLINE = "Nothing is missing. There is just nothing yet."
        const val BODY_START = "Android will not hand over notifications"
        const val CASH_OFFER_LABEL = "Or start with a cash expense"
    }
}
