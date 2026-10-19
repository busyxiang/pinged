package my.pinged.ledger.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ledger.FEED_FOOD
import my.pinged.ledger.loadedFeed
import my.pinged.ledger.longFeed
import my.pinged.ledger.septemberSummary
import my.pinged.ui.money
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TopBar's total in the cases nothing has to be measured to tell apart
 * (#253): what it says, and in which colour, once the summary has scrolled
 * away. Its place and type against the board are `LedgerArtboardTest`'s, and
 * when it appears `SummaryScrollTest`'s, both on a device.
 *
 * Also the scroll position across a tab switch, which `NavDisplay`'s
 * saveable-state decorator implements with the same holder used here.
 */
@RunWith(AndroidJUnit4::class)
class TopBarTotalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneCurrencyIsTheTotalInInk() {
        scrolledPast(septemberSummary())
        assertEquals(Ink, colourOf(money(284_730L, "MYR", symbol = true)))
    }

    /** The first in the summary's order, which is not always ringgit's, then a count of the rest. */
    @Test fun severalCurrenciesAreTheFirstThenACount() {
        scrolledPast(
            septemberSummary(
                totals = listOf(CurrencyTotal("SGD", 4_210L), CurrencyTotal("MYR", 284_730L), CurrencyTotal("USD", 990L)),
            ),
        )
        inTotal(money(4_210L, "SGD", symbol = true) + " +2").assertExists()
    }

    /** The hero's greyed colour: the bar repeats a number §8 says not to trust. */
    @Test fun anUntrustworthyTotalIsGreyedAsTheHeroIs() {
        scrolledPast(septemberSummary(trustworthy = false))
        assertEquals(Muted, colourOf(money(284_730L, "MYR", symbol = true)))
    }

    @Test fun aSummaryStillReadingShowsNoTotal() {
        scrolledPast(null)
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertDoesNotExist()
    }

    /** "NOTHING COUNTED THIS MONTH" has no currency, so no zero to show. */
    @Test fun aMonthWithNothingCountedShowsNoTotal() {
        scrolledPast(septemberSummary(totals = emptyList()))
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertDoesNotExist()
    }

    /**
     * Only over the feed. A list that becomes unreadable keeps its saved
     * position under the notice, and there is no summary there to have
     * scrolled past.
     */
    @Test fun anUnreadableLedgerShowsNoTotal() {
        var unavailable by mutableStateOf(false)
        scrolledPast(septemberSummary(), unavailable = { unavailable })
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertExists()
        unavailable = true
        compose.waitForIdle()
        compose.onNodeWithTag(TOP_BAR_TOTAL).assertDoesNotExist()
    }

    @Test fun theScrollPositionSurvivesATabSwitch() = assertSurvivesATabSwitch(picks = 0)

    /**
     * After a month pick too. The pick's scroll to the top is keyed on the
     * count of picks, which is saved state, so a screen recomposed from it
     * would take the same pick for a new one.
     */
    @Test fun theScrollPositionSurvivesATabSwitchAfterAPick() = assertSurvivesATabSwitch(picks = 1)

    private fun assertSurvivesATabSwitch(picks: Int) {
        var shown by mutableStateOf(true)
        val feed = loadedFeed(longFeed())
        compose.setContent {
            val tabs = rememberSaveableStateHolder()
            if (shown) {
                tabs.SaveableStateProvider("spending") {
                    PingedTheme {
                        LedgerScreenContent(
                            items = feed.collectAsLazyPagingItems(),
                            read = LedgerRead(summary = septemberSummary(), categories = listOf(FEED_FOOD)),
                            storageUnavailable = false,
                            onAssign = { _, _, _ -> },
                            onOpenSettings = {},
                            picks = picks,
                        )
                    }
                }
            }
        }
        scrollTo(ROW_20)
        compose.onNodeWithText("Row 20", useUnmergedTree = true).assertExists()

        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()

        compose.onNodeWithText("Row 20", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("SPENT SO FAR", useUnmergedTree = true).assertDoesNotExist()
    }

    /** Draws the feed under [summary] and scrolls it well past the summary. */
    private fun scrolledPast(summary: MonthSummary?, unavailable: () -> Boolean = { false }) {
        val feed = loadedFeed(longFeed())
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = feed.collectAsLazyPagingItems(),
                    read = LedgerRead(summary = summary, categories = listOf(FEED_FOOD)),
                    storageUnavailable = unavailable(),
                    onAssign = { _, _, _ -> },
                    onOpenSettings = {},
                )
            }
        }
        scrollTo(ROW_20)
        compose.onNodeWithText("SPENT SO FAR", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * Scrolls the feed to [index] once it is drawn. Paging hands over its
     * first page off the main thread, so `waitForIdle` can return with the
     * screen still `Reading`: it did in the whole suite's run, never alone.
     */
    private fun scrollTo(index: Int) {
        compose.waitUntil(FEED_TIMEOUT) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        compose.waitForIdle()
    }

    /**
     * [text] inside the total as it shows. Not by text alone: the total is
     * laid out while it is faded out too, and the unmerged tree still lists
     * the words `clearAndSetSemantics` keeps from TalkBack.
     */
    private fun inTotal(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(TOP_BAR_TOTAL)), useUnmergedTree = true)

    private fun colourOf(text: String) = layoutOf(text).layoutInput.style.color

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        inTotal(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        return layouts.single()
    }

    private companion object {
        /** The summary, then the day's heading, then Row 1: "Row 20" is the list's 22nd item. */
        const val ROW_20 = 21

        const val FEED_TIMEOUT = 5_000L
    }
}
