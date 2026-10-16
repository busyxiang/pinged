package my.pinged.ledger.review

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The review inbox as `design/Inbox.dc.html` draws it (#168), read off the
 * laid-out bounds and the painted pixels: which ground each surface is on,
 * where the header puts its chevron, the card's padding and spacing, and the
 * answer buttons' shape and colours.
 *
 * Stamp is "this needs you" (spec 8): the board gives it to the reason line
 * and the primary answer only, so a secondary answer outlined in Stamp is a
 * failure here and not a matter of taste.
 */
@RunWith(AndroidJUnit4::class)
class InboxArtboardTest {
    @get:JUnitRule val compose = createComposeRule()

    @Test fun theListGroundIsCardAndTheCardsArePaper() {
        inbox()
        val surface = bounds(compose.onNodeWithTag(reviewSurfaceTag(FIRST)))
        val screen = compose.onRoot().captureToImage().toPixelMap()
        // Beside the card, in the list's 16dp margin.
        val ground = screen[px(8f), surface.center.y.toInt()]
        assertNear("The list's ground beside a card is $ground, not Card", Card, ground)
        val most = mostPainted(compose.onNodeWithTag(reviewSurfaceTag(FIRST)).captureToImage().toPixelMap())
        assertNear("A card is mostly painted in $most, not Paper", Paper, most)
    }

    @Test fun aCardIsOutlinedInBorder() {
        inbox()
        val pixels = compose.onNodeWithTag(reviewSurfaceTag(FIRST)).captureToImage().toPixelMap()
        val edge = pixels[px(0.5f), pixels.height / 2]
        assertNear("A card's left edge is painted $edge, not a Border outline", Border, edge)
    }

    @Test fun theHeaderIsOnPaperAboveADottedRule() {
        inbox()
        val pixels = compose.onNodeWithTag(REVIEW_HEADER).captureToImage().toPixelMap()
        assertNear("The header is mostly painted in ${mostPainted(pixels)}, not Paper", Paper, mostPainted(pixels))
        // The rule is centred on the header's bottom edge, so at density 1 its
        // pixel row is the one below the node: read both off the whole screen.
        val edge = bounds(compose.onNodeWithTag(REVIEW_HEADER)).bottom.toInt()
        val screen = compose.onRoot().captureToImage().toPixelMap()
        val run = listOf(edge - 1, edge).map { y -> (0 until screen.width).map { screen[it, y] } }
            .maxBy { row -> row.count { distance(it, Rule) < distance(it, Paper) } }
        val dots = run.count { distance(it, Rule) < distance(it, Paper) }
        val gaps = run.size - dots
        assertTrue(
            "The header's bottom edge has $dots rule pixels and $gaps gaps; a dotted rule has both",
            dots > run.size / 5 && gaps > run.size / 5,
        )
    }

    /**
     * The 20dp chevron sits inline with the title, 11dp before it, as the
     * board's flex row puts it; its 44dp touch area stays, centred on it.
     */
    @Test fun theChevronSitsInlineWithTheTitle() {
        inbox()
        val back = bounds(compose.onNodeWithContentDescription(REVIEW_BACK))
        val title = bounds(compose.onNodeWithText(INBOX_TITLE, useUnmergedTree = true))
        assertTrue("The back target is ${dp(back.height)}dp tall, under 44", dp(back.height) >= 43.5f)
        assertTrue(
            "The chevron's middle (${dp(back.center.y)}dp) is outside the title's line (${dp(title.top)}-${dp(title.bottom)}dp)",
            back.center.y in title.top..title.bottom,
        )
        val gap = dp(title.left - (back.center.x + px(10f)))
        assertEquals("The chevron is drawn ${gap}dp before the title, not 11", 11f, gap, 1f)
        assertEquals("The title is not the serif at 25", 25.sp, styleOf(INBOX_TITLE).fontSize)
        assertEquals("The title breaks across lines beside Accept all", 1, layoutOf(INBOX_TITLE).lineCount)
    }

    /** 13 by 14 inside the board's 1px border, so 14 by 15 from the card's edge. */
    @Test fun aCardIsPaddedAsTheBoardPadsIt() {
        inbox()
        val surface = bounds(compose.onNodeWithTag(reviewSurfaceTag(FIRST)))
        val reason = bounds(textIn(FIRST, reasonSentence(PendingReason.OVER_THRESHOLD)))
        assertEquals("The reason line starts ${dp(reason.left - surface.left)}dp in, not 15", 15f, dp(reason.left - surface.left), 0.5f)
        assertEquals("The reason line starts ${dp(reason.top - surface.top)}dp down, not 14", 14f, dp(reason.top - surface.top), 0.5f)
    }

    @Test fun cardsAreTwelveApart() {
        inbox()
        // Both read after the one scroll, so they are in the same frame.
        compose.onNodeWithTag(reviewSurfaceTag(SECOND)).performScrollTo()
        val first = bounds(compose.onNodeWithTag(reviewSurfaceTag(FIRST)))
        val second = bounds(compose.onNodeWithTag(reviewSurfaceTag(SECOND)))
        assertEquals("Cards are ${dp(second.top - first.bottom)}dp apart, not 12", 12f, dp(second.top - first.bottom), 0.5f)
    }

    /** Equal halves, 8 apart, across the card's whole inner width. */
    @Test fun aPlainCardsAnswersAreEqualHalves() {
        inbox()
        val accept = bounds(answer(FIRST, ReviewAnswer.ACCEPT))
        val reject = bounds(answer(FIRST, ReviewAnswer.NOT_A_PAYMENT))
        assertEquals("The answers are ${dp(accept.width)}dp and ${dp(reject.width)}dp wide", accept.width, reject.width, px(0.5f).toFloat() + 1)
        assertEquals("The answers are ${dp(reject.left - accept.right)}dp apart, not 8", 8f, dp(reject.left - accept.right), 0.5f)
    }

    /** A Border outline and Ink lettering: Stamp is the primary answer's alone. */
    @Test fun aSecondaryAnswerIsOutlinedInBorderAndLetteredInInk() {
        inbox()
        assertSecondary(answer(FIRST, ReviewAnswer.NOT_A_PAYMENT))
    }

    private fun assertMonoLabel(what: String, text: String) {
        val style = styleOf(text)
        assertEquals("$what is not in the mono", MonoLabel.fontFamily, style.fontFamily)
        assertEquals("$what is not at MonoLabel's size", MonoLabel.fontSize, style.fontSize)
        assertEquals("$what is not at MonoLabel's tracking", MonoLabel.letterSpacing, style.letterSpacing)
        assertEquals("$what is not in capitals", text.uppercase(), text)
    }

    private fun assertSecondary(button: SemanticsNodeInteraction) {
        val pixels = button.captureToImage().toPixelMap()
        val top = pixels[pixels.width / 2, px(0.5f)]
        assertNear("The secondary answer's outline is painted $top, not Border", Border, top)
        val ink = darkest(pixels)
        assertTrue(
            "The secondary answer is lettered in $ink, nearer Stamp than Ink",
            distance(ink, Ink) < distance(ink, Stamp),
        )
    }

    /**
     * The transfer card's third answer goes below the first two rather than
     * squeezing three into thirds: at CI's 320dp a third is about 80dp, and
     * "Keep as expense" would break across two lines.
     */
    @Test fun aTransferCardsThirdAnswerIsAFullWidthRowBelow() {
        inbox(card(FIRST, PendingReason.TRANSFER_SUSPECT))
        val leave = bounds(answer(FIRST, ReviewAnswer.LEAVE_OUT))
        val keep = bounds(answer(FIRST, ReviewAnswer.KEEP_AS_EXPENSE))
        val reject = bounds(answer(FIRST, ReviewAnswer.NOT_A_PAYMENT))
        assertEquals("Leave out and Keep as expense are ${dp(leave.width)}dp and ${dp(keep.width)}dp wide", leave.width, keep.width, px(0.5f).toFloat() + 1)
        assertEquals("Leave out and Keep as expense are not on one row", leave.top, keep.top, 1f)
        assertTrue("Not a payment is not below the first two", reject.top >= keep.bottom)
        assertEquals("Not a payment is ${dp(reject.width)}dp wide, not the row's ${dp(keep.right - leave.left)}", keep.right - leave.left, reject.width, 2f)
        assertEquals("\"Keep as expense\" breaks across lines", 1, layoutOf(ReviewAnswer.KEEP_AS_EXPENSE.label).lineCount)
        assertSecondary(answer(FIRST, ReviewAnswer.NOT_A_PAYMENT))
    }

    /** The board's mono caps label, at [MonoLabel]'s 12sp floor rather than its 9.5. */
    @Test fun theReasonLineIsTheMonoLabelInStamp() {
        inbox(card(FIRST))
        val text = reasonSentence(PendingReason.OVER_THRESHOLD)
        assertMonoLabel("The reason line", text)
        val ink = darkest(textIn(FIRST, text).captureToImage().toPixelMap())
        assertTrue("The reason line is painted $ink, not Stamp", distance(ink, Stamp) < distance(ink, Ink))
    }

    /** Muted, not the board's Faint: Faint is 3.12:1 on Card and not for text (`Color.kt`). */
    @Test fun theCountLineIsTheMonoLabelInMuted() {
        inbox()
        val text = awaitingReviewLabel(2)
        assertMonoLabel("The count line", text)
        val ink = darkest(compose.onNodeWithText(text, useUnmergedTree = true).captureToImage().toPixelMap())
        assertTrue("The count line is painted $ink, not Muted", distance(ink, Muted) < distance(ink, Stamp))
    }

    /**
     * An offer at the header's right, as the board draws it: a dashed Muted
     * outline on Paper, not a filled Stamp button competing with the primary
     * answers. Lettered as a button (#180), with a 44dp target. At the right
     * whether it shares the title's line or, at 320dp, takes one below it.
     */
    @Test fun acceptAllIsAQuietOfferAtTheHeadersRight() {
        inbox()
        val node = compose.onNode(
            androidx.compose.ui.test.hasText(requireNotNull(AcceptAll.of(listOf(card(FIRST), card(SECOND)))).label) and
                androidx.compose.ui.test.hasClickAction(),
        )
        assertTrue(
            "Accept all is drawn outside the header",
            androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(REVIEW_HEADER)).matches(node.fetchSemanticsNode()),
        )
        val header = bounds(compose.onNodeWithTag(REVIEW_HEADER))
        val box = bounds(node)
        assertTrue("Accept all's target is ${dp(box.height)}dp tall, under 44", dp(box.height) >= 43.5f)
        assertEquals("Accept all ends ${dp(header.right - box.right)}dp from the header's right, not 20", 20f, dp(header.right - box.right), 4f)
        val pixels = node.captureToImage().toPixelMap()
        assertNear("Accept all is mostly painted ${mostPainted(pixels)}, not Paper", Paper, mostPainted(pixels))
        val ink = darkest(pixels)
        assertTrue("Accept all is drawn in $ink, nearer Stamp than Muted", distance(ink, Muted) < distance(ink, Stamp))
    }

    private fun inbox(vararg cards: ReviewCard = arrayOf(card(FIRST), card(SECOND))) = compose.setContent {
        PingedTheme {
            ReviewInboxScreenContent(cards = cards.toList(), storageUnavailable = false, busy = null, onBack = {}, onAnswer = { _, _ -> })
        }
    }

    private fun card(id: Long, reason: PendingReason = PendingReason.OVER_THRESHOLD) = ReviewCard(
        txn = ledgerTxn(amountSen = 65_000L, state = TxnState.PENDING, pendingReason = reason, id = id),
        title = "Maybank2u",
        body = "RM650.00 debited from a/c 0000xxxx",
    )

    /** [answer]'s button on card [id]: the clickable node holding its label. */
    private fun answer(id: Long, answer: ReviewAnswer): SemanticsNodeInteraction =
        compose.onNode(
            androidx.compose.ui.test.hasText(answer.label) and
                androidx.compose.ui.test.hasClickAction() and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(reviewSurfaceTag(id))),
        ).performScrollTo()

    /** The text node [text] on card [id] alone: every card in [inbox] gives the same reason. */
    private fun textIn(id: Long, text: String): SemanticsNodeInteraction =
        compose.onNode(
            androidx.compose.ui.test.hasText(text) and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(reviewSurfaceTag(id))),
            useUnmergedTree = true,
        ).performScrollTo()

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private fun styleOf(text: String): TextStyle = layoutOf(text).layoutInput.style

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single()
    }

    private fun px(dp: Float): Int = (dp * compose.density.density).toInt()

    private fun dp(px: Float): Float = px / compose.density.density

    private fun mostPainted(pixels: PixelMap): Color {
        val counts = HashMap<Color, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) counts.merge(pixels[x, y], 1, Int::plus)
        return counts.maxBy { it.value }.key
    }

    private fun darkest(pixels: PixelMap): Color {
        var best = Color.White
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.red + c.green + c.blue < best.red + best.green + best.blue) best = c
        }
        return best
    }

    private fun distance(a: Color, b: Color): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    /** Within a 255th per channel of [want]: the token itself, not a blend near it. */
    private fun assertNear(message: String, want: Color, got: Color) = assertTrue(message, distance(want, got) < 3f / 255)

    private companion object {
        const val FIRST = 51L
        const val SECOND = 52L
    }
}
