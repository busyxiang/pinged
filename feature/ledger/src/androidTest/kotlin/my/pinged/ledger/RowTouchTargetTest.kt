package my.pinged.ledger

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.LedgerRow
import my.pinged.ledger.home.awaitingReviewBadge
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Separator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #186: a ledger row's three controls -- the category line,
 * "+ CATEGORY" and the review badge -- are laid out at their drawn size, and
 * take the free space around them as their touch target (`rowGestures`'s
 * KDoc has the rule).
 *
 * Three parts, and each passes without the others. The heights say the
 * layout is the drawing's, which #178's 44dp layout fails. The near misses
 * say the target is bigger than the drawing, which a bare clickable on its
 * own does not give: [nearMissNeedsComposesMinimum] reaches the row once the
 * minimum is gone. And [theNameKeepsTheRowsTap] says the target stops at the
 * name, which the minimum alone does not.
 *
 * A near miss is 5dp into the row's 10dp padding below a control. Not more:
 * the minimum makes a target 48dp in all, so the review badge, two lines and
 * 35dp drawn at CI's 320dp, reaches 6.5dp past its frame. Not above the line
 * or the chip either: the 2dp gap there is the name's as much as theirs, and
 * a tap in it goes to whichever is nearer. On a captured row the row's own
 * tap is #132's remove sheet, which is what a near miss must not open.
 */
@RunWith(AndroidJUnit4::class)
class RowTouchTargetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val food = Category(id = 1L, name = "Food", iconKey = "food", sortOrder = 1)
    private val uncategorized = Category(id = 2L, name = "Uncategorized", iconKey = "uncategorized", sortOrder = 2)
    private val line = "Food" + Separator + SOURCE_LABEL
    private val badge = awaitingReviewBadge(PendingReason.RULE_REVIEW.name)

    private var categorized = 0
    private var reviewed = 0
    private var removed = 0

    /**
     * The row under test, tagged [ROW], and under it the same row with nothing
     * to categorise, tagged [BARE], at the screen's width so that every tap
     * lands on screen at CI's 320dp. [minimumTarget] false takes
     * away Compose's minimum touch target, for the one test that shows the
     * near misses depend on it.
     */
    private fun rows(categoryId: Long, state: TxnState = TxnState.COMMITTED, minimumTarget: Boolean = true) = compose.setContent {
        val platform = LocalViewConfiguration.current
        val configuration = if (minimumTarget) {
            platform
        } else {
            object : ViewConfiguration by platform {
                override val minimumTouchTargetSize = DpSize.Zero
            }
        }
        CompositionLocalProvider(LocalViewConfiguration provides configuration) {
            PingedTheme {
                Column {
                    listOf(ROW to true, BARE to false).forEach { (tag, canCategorize) ->
                        Box(Modifier.fillMaxWidth().testTag(tag)) {
                            LedgerRow(
                                row = FeedRow(
                                    ledgerTxn(
                                        1_250L,
                                        categoryId = categoryId,
                                        state = state,
                                        pendingReason = PendingReason.RULE_REVIEW.takeIf { state == TxnState.PENDING },
                                        id = 7L,
                                    ),
                                    MERCHANT,
                                    MERCHANT,
                                ),
                                categoriesById = mapOf(food.id to food, uncategorized.id to uncategorized),
                                uncategorizedId = uncategorized.id,
                                canCategorize = canCategorize,
                                onCategorize = { categorized++ },
                                onOpenMerchant = {},
                                onOpenReview = { reviewed++ },
                                onRemove = { removed++ },
                            )
                        }
                    }
                }
            }
        }
    }

    /** [text] on the row under test; [BARE] draws the same words. */
    private fun inRow(text: String, unmerged: Boolean = false): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(ROW)), useUnmergedTree = unmerged)

    private fun height(node: SemanticsNodeInteraction): Dp = node.getBoundsInRoot().let { it.bottom - it.top }

    /** [text]'s own laid-out height, which is what is drawn of it whatever box it sits in. */
    private fun textHeight(text: String): Dp {
        val layouts = mutableListOf<TextLayoutResult>()
        inRow(text, unmerged = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        return with(compose.density) { layouts.single().size.height.toDp() }
    }

    /** Within a pixel: a padding of a fractional pixel count rounds either way. */
    private fun assertDrawnHeight(drawn: Dp, laidOut: Dp) {
        val px = with(compose.density) { abs(drawn.toPx() - laidOut.toPx()) }
        assertTrue("laid out at $laidOut, drawn at $drawn", px <= 1f)
    }

    /** A tap [outside] beyond the drawn edge of [text]'s control, whose drawing is [drawnHalf] either side of its centre. */
    private fun nearMiss(text: String, drawnHalf: Dp, above: Boolean, outside: Dp = if (above) 1.dp else 5.dp): String {
        val row = compose.onNodeWithTag(ROW).getBoundsInRoot()
        val control = inRow(text, unmerged = true).getBoundsInRoot()
        val x = (control.left + control.right) / 2 - row.left
        val y = (control.top + control.bottom) / 2 - row.top
        val at = if (above) y - drawnHalf - outside else y + drawnHalf + outside
        compose.onNodeWithTag(ROW).performTouchInput { click(Offset(x.toPx(), at.toPx())) }
        compose.waitForIdle()
        return "tapped ($x, $at) in a row ${row.right - row.left} by ${row.bottom - row.top}; " +
            "the control is ${control.top - row.top} to ${control.bottom - row.top}, drawn ${y - drawnHalf} to ${y + drawnHalf}"
    }

    @Test fun categoryLineAddsNoHeight() {
        rows(categoryId = food.id)
        assertEquals(height(compose.onNodeWithTag(BARE)), height(compose.onNodeWithTag(ROW)))
    }

    @Test fun categoryLineTakesANearMiss() {
        rows(categoryId = food.id)
        val below = nearMiss(line, textHeight(line) / 2, above = false)
        assertEquals("the line, $below", 1, categorized)
        assertEquals("the row, $below", 0, removed)
    }

    @Test fun nearMissNeedsComposesMinimum() {
        rows(categoryId = food.id, minimumTarget = false)
        nearMiss(line, textHeight(line) / 2, above = false)
        assertEquals("taps the line took", 0, categorized)
        assertEquals("taps the row took", 1, removed)
    }

    /**
     * The middle of the name, and above the far end of the category line,
     * past the end of [MERCHANT], which is the shorter: both the row's, within
     * the line's near miss though they are.
     */
    @Test fun theNameKeepsTheRowsTap() {
        rows(categoryId = food.id)
        val row = compose.onNodeWithTag(ROW).getBoundsInRoot()
        val name = inRow(MERCHANT, unmerged = true).getBoundsInRoot()
        val farEnd = inRow(line, unmerged = true).getBoundsInRoot().right - 4.dp
        val y = (name.top + name.bottom) / 2 - row.top
        listOf((name.left + name.right) / 2, farEnd).forEach { x ->
            compose.onNodeWithTag(ROW).performTouchInput { click(Offset((x - row.left).toPx(), y.toPx())) }
            compose.waitForIdle()
        }
        assertEquals("taps the line took", 0, categorized)
        assertEquals("taps the row took", 2, removed)
    }

    /** The chip's drawing is its label and 3dp above and below, inside the outline. */
    @Test fun categoryChipIsLaidOutAtItsDrawnHeight() {
        rows(categoryId = uncategorized.id)
        assertDrawnHeight(textHeight(CATEGORY_CHIP) + 6.dp, height(inRow(CATEGORY_CHIP)))
    }

    @Test fun categoryChipTakesANearMiss() {
        rows(categoryId = uncategorized.id)
        val below = nearMiss(CATEGORY_CHIP, textHeight(CATEGORY_CHIP) / 2 + 3.dp, above = false)
        assertEquals("the chip, $below", 1, categorized)
        assertEquals("the row, $below", 0, removed)
    }

    /** The badge's drawing is its text and 2dp above and below, inside the frame. */
    @Test fun reviewBadgeIsLaidOutAtItsDrawnHeight() {
        rows(categoryId = food.id, state = TxnState.PENDING)
        assertDrawnHeight(textHeight(badge) + 4.dp, height(inRow(badge)))
    }

    /**
     * Below the badge is the row's padding. Above it is its 4dp of gap and
     * then the category line: 1dp into the gap is nearer the badge.
     */
    @Test fun reviewBadgeTakesANearMiss() {
        rows(categoryId = food.id, state = TxnState.PENDING)
        val below = nearMiss(badge, textHeight(badge) / 2 + 2.dp, above = false)
        assertEquals("below, $below", 1, reviewed)
        val above = nearMiss(badge, textHeight(badge) / 2 + 2.dp, above = true)
        assertEquals("above, $above", 2, reviewed)
        assertEquals("taps the line took", 0, categorized)
    }

    private companion object {
        const val ROW = "row"
        const val BARE = "bare"
    }
}
