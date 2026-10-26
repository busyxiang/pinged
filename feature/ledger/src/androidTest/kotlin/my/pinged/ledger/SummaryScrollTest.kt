package my.pinged.ledger

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ledger.home.BackupReminder
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.MONTH_BUTTON
import my.pinged.ledger.home.MONTH_SUMMARY
import my.pinged.ledger.home.SHOW_SUMMARY
import my.pinged.ledger.home.TOP_BAR_TOTAL
import my.pinged.ledger.review.awaitingReviewLabel
import my.pinged.ui.money
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spending's summary scrolls away with the list and the TopBar carries the
 * total once it has gone (#253). On a device because each step is a
 * position: where the hero is against the list's top edge, and where the
 * pinned review row stops. Run at CI's 320x640 too (`emu --ci-size`).
 */
@RunWith(AndroidJUnit4::class)
class SummaryScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSummaryScrollsOff() {
        show()
        compose.onNodeWithText(HERO, useUnmergedTree = true).assertIsDisplayed()
        list().performScrollToIndex(ROW_20)
        compose.waitForIdle()
        compose.onNodeWithTag(MONTH_SUMMARY).assertDoesNotExist()
    }

    /**
     * Two pixels short of the hero's foot under the list's top, no total;
     * two past it, the total. The hero's foot rather than the summary's: the
     * top three are still on screen when the total takes over.
     */
    @Test fun theTotalAppearsOnlyOnceTheHeroHasGone() = assertTheTotalAppearsOnlyOnceTheHeroHasGone(reminder = null)

    /**
     * The same under the backup reminder, the list's first item there
     * (#252): the summary is the second, and the reminder scrolling away is
     * not the hero going.
     */
    @Test fun underTheBackupReminderTheTotalAppearsOnlyOnceTheHeroHasGone() =
        assertTheTotalAppearsOnlyOnceTheHeroHasGone(reminder = BackupReminder(damaged = false, onAction = {}, onDismiss = {}))

    private fun assertTheTotalAppearsOnlyOnceTheHeroHasGone(reminder: BackupReminder?) {
        show(reminder)
        val toFoot = boundsOf(HERO).bottom - list().fetchSemanticsNode().boundsInRoot.top
        scrollBy(toFoot - 2f)
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertDoesNotExist()
        compose.onNodeWithText(HERO, useUnmergedTree = true).assertExists()

        scrollBy(4f)
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertIsDisplayed()
        compose.onNodeWithText(FOOD_NAME, useUnmergedTree = true).assertExists()
    }

    @Test fun tappingTheTotalBringsTheSummaryBack() {
        show()
        list().performScrollToIndex(ROW_20)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(SHOW_SUMMARY).performClick()
        compose.waitForIdle()
        val hero = boundsOf(HERO)
        assertTrue("The hero is not back on screen: $hero", hero.top >= list().fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag(MONTH_SUMMARY).assertIsDisplayed()
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertDoesNotExist()
    }

    /** Under the summary at the top; stopped at the list's edge, under the TopBar, past it. */
    @Test fun theReviewRowPinsUnderTheTopBar() {
        show()
        val top = list().fetchSemanticsNode().boundsInRoot.top
        assertTrue("The review row starts at the list's top, above the summary", boundsOf(REVIEW).top > boundsOf(HERO).bottom)

        list().performScrollToIndex(ROW_20)
        compose.waitForIdle()
        val review = compose.onNodeWithText(REVIEW, useUnmergedTree = true)
        review.assertIsDisplayed()
        val bar = compose.onNodeWithTag(MONTH_BUTTON).fetchSemanticsNode().boundsInRoot
        assertTrue("The review row went under the TopBar: row at ${boundsOf(REVIEW).top}, bar to ${bar.bottom}", boundsOf(REVIEW).top >= bar.bottom)
        // The row is 44dp and its words centred in it, so the row's top is
        // the words' less half of what the row adds over them.
        val row = review.fetchSemanticsNode().boundsInRoot
        val rowTop = row.top - (44f * compose.density.density - row.height) / 2
        assertEquals("The pinned review row is not at the list's top", top, rowTop, 2f)
    }

    private fun show(reminder: BackupReminder? = null) {
        val feed = loadedFeed(longFeed())
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = feed.collectAsLazyPagingItems(),
                    read = LedgerRead(summary = septemberSummary(), categories = listOf(FEED_FOOD), awaitingReview = 4, awaitingReviewInMonth = 4),
                    storageUnavailable = false,
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    onOpenSettings = {},
                    onOpenMonths = {},
                    backupReminder = reminder,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun list(): SemanticsNodeInteraction = compose.onNode(hasScrollToIndexAction())

    private fun scrollBy(px: Float) {
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        compose.waitForIdle()
    }

    private fun boundsOf(text: String): Rect =
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private companion object {
        val HERO = money(284_730L, "MYR", symbol = true)
        val FOOD_NAME = FEED_FOOD.name
        val REVIEW = awaitingReviewLabel(4)

        /** The summary, the review row, the day's heading, then Row 1: "Row 20" is the 23rd item. */
        const val ROW_20 = 22
    }
}
