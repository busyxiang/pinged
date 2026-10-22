package my.pinged.ledger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ledger.NOT_READ_YET_ICON
import my.pinged.ledger.day.DayScreenContent
import my.pinged.ledger.detail.DetailScreenContent
import my.pinged.ledger.detail.VERBATIM_NOT_READ_YET
import my.pinged.ledger.entry.ManualEntryScreenContent
import my.pinged.ledger.entry.ManualEntryState
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ledger.home.HeroSerif
import my.pinged.ledger.review.CARD_NOT_READ_YET
import my.pinged.ledger.review.ReviewInboxScreenContent
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.math.abs

/**
 * What #282's four screens draw while their read is in flight, read off the
 * device's type and pixels, which Robolectric's drawing is not
 * (`DayNotReadYetTest` and its siblings hold the timing and the content):
 * the hero dashes in the boards' `#cfc4ae`, and the empty cells dotted in it,
 * never filled or hatched, since hatching means Not captured.
 */
@RunWith(AndroidJUnit4::class)
class NotReadYetBoardsTest {
    @get:JUnitRule val compose = createComposeRule()

    // ---- Day: design/LoadingDay ----

    /** `RM&thinsp;&mdash;` in the board's 46px serif, in `#cfc4ae`. */
    @Test fun daysTotalNotReadYetIsTheBoardsSerifInRule() {
        day()
        val style = styleOf(HERO_NOT_READ_YET)
        assertEquals("The hero dash's family", Display, style.fontFamily)
        assertEquals("The board's hero dash is 46px", 46.sp, style.fontSize)
        assertEquals("The board's hero dash is #cfc4ae", Rule, style.color)
    }

    /** The board's rows: an 18dp circle dotted in Rule on Card, never filled. */
    @Test fun aDaysRowNotReadYetHasADottedCircleOnCard() {
        day()
        assertDotted(cell(NOT_READ_YET_ICON), ground = Card, edge = false)
    }

    // ---- Detail and Edit payment: design/LoadingDetail ----

    @Test fun detailsAmountNotReadYetIsTheHeroSerifInRule() {
        detail()
        val style = styleOf(HERO_NOT_READ_YET)
        assertEquals("The hero dash's family", Display, style.fontFamily)
        assertEquals("The hero dash is not the hero's size", HeroSerif.fontSize, style.fontSize)
        assertEquals("The board's hero dash is #cfc4ae", Rule, style.color)
    }

    /** WHAT PINGED READ's box: dotted in Rule on Card, empty. */
    @Test fun detailsNotificationNotReadYetIsADottedBoxOnCard() {
        detail()
        assertDotted(cell(VERBATIM_NOT_READ_YET), ground = Card)
    }

    @Test fun editPaymentsAmountNotReadYetIsInRule() {
        editPayment()
        val style = styleOf(HERO_NOT_READ_YET)
        assertEquals("The amount's dash is not the serif", Display, style.fontFamily)
        assertEquals("The board's hero dash is #cfc4ae", Rule, style.color)
    }

    // ---- Review inbox: design/LoadingInbox ----

    /** The count's dash in Muted, not the board's Faint, which fails 4.5:1 for text. */
    @Test fun theInboxCountNotReadYetIsMuted() {
        inbox()
        assertEquals("The count's dash is not Muted", Muted, styleOf("$NOT_READ_YET AWAITING REVIEW").color)
    }

    /** A card's notification box: dotted in Rule on the card's Paper, empty. */
    @Test fun anInboxCardNotReadYetHasADottedBoxOnPaper() {
        inbox()
        assertDotted(cell(CARD_NOT_READ_YET), ground = Paper)
    }

    // ---- Harness ----

    private fun show(heldBy: HeldBy? = null, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalHeldBy provides heldBy) { PingedTheme(content = content) }
        }
        compose.mainClock.advanceTimeBy(READING_DELAY_MILLIS + 40)
        compose.waitForIdle()
    }

    private fun day() = show {
        DayScreenContent(date = LocalDate.of(2026, 9, 25), read = null, storageUnavailable = false, onBack = {}, onAssign = { _, _, _ -> })
    }

    private fun detail() = show { DetailScreenContent(read = null, storageUnavailable = false, onBack = {}) }

    private fun editPayment() = show {
        ManualEntryScreenContent(
            state = ManualEntryState(editing = 7, reading = true),
            onAmount = {},
            onMerchant = {},
            onNote = {},
            onSave = {},
            onBack = {},
            onSaved = {},
        )
    }

    private fun inbox() = show {
        ReviewInboxScreenContent(cards = null, storageUnavailable = false, busy = null, onBack = {}, onAnswer = { _, _ -> })
    }

    private fun styleOf(text: String): TextStyle =
        ArtboardProbe.layoutOf(compose.onAllNodesWithText(text, useUnmergedTree = true).onFirst()).layoutInput.style

    private fun cell(tag: String): PixelMap = compose.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().captureToImage().toPixelMap()

    /**
     * [pixels] is an empty cell outlined in Rule's dots on [ground]: mostly
     * [ground] inside, so not filled or hatched, and some Rule ink, so
     * outlined. A box ([edge]) also has [ground] between the dots along its
     * top, so it is dotted rather than a solid rule; a circle's top is a
     * point, so it is not asked.
     *
     * Ink is Rule over [ground] at 20% coverage or more: the 1dp stroke is
     * inset half a stroke, so at CI's density 1 it straddles two rows of
     * pixels and neither is Rule itself (the first run, reading for Rule
     * within 0.05, found 0 and 1 such pixels along the edge).
     */
    private fun assertDotted(pixels: PixelMap, ground: Color, edge: Boolean = true) {
        val total = pixels.width * pixels.height
        val inside = count(pixels, 0 until pixels.height) { distance(it, ground) < 0.05f }
        assertTrue("The cell is filled or hatched: $inside of $total pixels are its ground", inside > total / 2)
        val ink = count(pixels, 0 until pixels.height) { inked(it, ground) }
        assertTrue("The cell drew no Rule ink", ink > 0)
        if (!edge) return
        val top = (0 until minOf(3, pixels.height)).maxBy { y -> count(pixels, y..y) { inked(it, ground) } }
        val dots = count(pixels, top..top) { inked(it, ground) }
        val gaps = count(pixels, top..top) { distance(it, ground) < 0.05f }
        assertTrue("The cell has no Rule outline along its top: $dots of ${pixels.width}", dots >= pixels.width / 6)
        assertTrue("The cell's top is a solid rule, not dots: $gaps gaps in ${pixels.width}", gaps >= pixels.width / 6)
    }

    /** [c] is Rule drawn over [ground] at 20% coverage or more. */
    private fun inked(c: Color, ground: Color): Boolean {
        val alpha = (ground.green - c.green) / (ground.green - Rule.green)
        if (alpha < 0.2f || alpha > 1.1f) return false
        val blend = Color(
            red = ground.red + (Rule.red - ground.red) * alpha,
            green = ground.green + (Rule.green - ground.green) * alpha,
            blue = ground.blue + (Rule.blue - ground.blue) * alpha,
        )
        return distance(c, blend) < 0.05f
    }

    private fun count(pixels: PixelMap, rows: IntRange, matches: (Color) -> Boolean): Int =
        rows.sumOf { y -> (0 until pixels.width).count { x -> matches(pixels[x, y]) } }

    private fun distance(a: Color, b: Color): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
}
