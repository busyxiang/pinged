package my.pinged

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.day.DAY_BACK
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * Every place that says money is held back leads to the review inbox (§9.2's
 * ways in, #117): a held row's badge, on that row's card; Charts' *N awaiting
 * review* line; the Day heading's. Back returns to where the inbox was opened
 * from, because it is pushed on the current tab's stack. And the Spending tab
 * carries the count while another tab is showing.
 *
 * `createEmptyComposeRule`, for the reason `DayRouteTest` gives: the rows
 * have to be in the database before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class ReviewWaysInTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    private val today: LocalDate = LocalDate.now()

    @Before fun fresh() {
        discard()
        // A history start, so Charts draws the month and not its empty state.
        Databases.shared(context).captureDayDao().recordListenerBound(LocalDates.of(today), bound = true)
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun theRowBadgeOpensTheInboxOnThatRowsCardEvenFarDownTheList() {
        // Newest first in both lists, so the oldest held row is the last card.
        val noon = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(FILLERS) { i -> insert(held(60_000L + i * 100L, noon - i * 60_000L, PendingReason.OVER_THRESHOLD)) }
        insert(held(TARGET_SEN, noon - FILLERS * 60_000L, PendingReason.RULE_REVIEW))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("The Spending list never drew", compose.waitFor { compose.countOf("${FILLERS + 1} AWAITING REVIEW") == 1 })
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(TARGET_BADGE))
            compose.onNodeWithText(TARGET_BADGE).performScrollTo().performClick()

            assertTrue(
                "The badge did not open the inbox on its own row's card: RM777.77, the last of " +
                    "${FILLERS + 1} cards, never came into view without a scroll",
                compose.waitFor { compose.countOf(TARGET_BADGE) == 0 && compose.isShown(TARGET_AMOUNT) },
            )
            assertEquals(
                "The inbox drew its first card as well as the focused one, so it did not scroll to it",
                0,
                compose.countOf("RM600.00"),
            )

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue(
                "Back from the inbox did not return to the Spending list",
                compose.waitFor { compose.countOf("${FILLERS + 1} AWAITING REVIEW") == 1 && compose.countOf(TARGET_AMOUNT) == 0 },
            )
        }
    }

    @Test fun chartsAwaitingReviewLineOpensTheInboxAndBackReturnsToCharts() {
        insert(held(65_000L, System.currentTimeMillis(), PendingReason.OVER_THRESHOLD))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("CHARTS").performClick()
            assertTrue("Charts never drew its awaiting-review line", compose.waitFor { compose.countOf(CHARTS_LINE) == 1 })
            compose.onNodeWithText(CHARTS_LINE).performScrollTo().performClick()

            awaitTheInbox("Charts' '$CHARTS_LINE' did not open the inbox")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue(
                "Back from the inbox did not return to Charts",
                compose.waitFor {
                    compose.countOf(header(YearMonth.from(today))) == 1 && compose.countOf(CHARTS_LINE) == 1 &&
                        compose.countOf(TITLE) == 0
                },
            )
        }
    }

    @Test fun theDayHeadingOpensTheInboxAndBackReturnsToTheDay() {
        insert(held(65_000L, System.currentTimeMillis(), PendingReason.OVER_THRESHOLD))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("CHARTS").performClick()
            assertTrue("Charts never drew", compose.waitFor { compose.countOf(CHARTS_LINE) == 1 })
            compose.onNodeWithTag("grid-day-$today").performScrollTo().performClick()
            assertTrue("Today's square did not open the day", compose.waitFor { compose.countOf(DAY_LINE) == 1 })
            compose.onNodeWithText(DAY_LINE).performScrollTo().performFirstLinkClick()

            awaitTheInbox("The Day heading's '1 awaiting review' did not open the inbox")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue(
                "Back from the inbox did not return to the Day screen",
                compose.waitFor {
                    compose.countOf(DAY_LINE) == 1 && compose.countOf(TITLE) == 0 &&
                        compose.onAllNodesWithContentDescription(DAY_BACK).fetchSemanticsNodes().size == 1
                },
            )
        }
    }

    @Test fun theSpendingTabCarriesTheCountWhileChartsIsShowing() {
        val now = System.currentTimeMillis()
        insert(held(65_000L, now, PendingReason.OVER_THRESHOLD))
        insert(held(70_000L, now - 60_000L, PendingReason.RULE_REVIEW))

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("The Spending list never drew", compose.waitFor { compose.countOf("2 AWAITING REVIEW") == 1 })
            assertEquals(
                "The Spending tab carries the count while Spending is showing, beside the list's own row",
                0,
                compose.badges(),
            )

            compose.onNodeWithText("CHARTS").performClick()

            assertTrue(
                "Two payments await review and the Spending tab does not say so from Charts",
                compose.waitFor {
                    compose.onAllNodesWithContentDescription(spendingBadgeDescription(2)).fetchSemanticsNodes().size == 1
                },
            )
        }
    }

    @Test fun theSpendingTabCarriesNothingWhenNothingAwaitsReview() {
        insert(held(65_000L, System.currentTimeMillis(), PendingReason.OVER_THRESHOLD).copy(state = TxnState.COMMITTED, pendingReason = null))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("CHARTS").performClick()
            assertTrue("Charts never drew", compose.waitFor { compose.countOf("RM650.00") > 0 })
            // Past the read Charts waited on, and the tab's own is no slower.
            compose.waitForIdle()
            assertEquals("The Spending tab carries a count with nothing awaiting review", 0, compose.badges())
        }
    }

    private fun awaitTheInbox(why: String) {
        assertTrue(
            why,
            compose.waitFor { compose.countOf(TITLE) == 1 && compose.countOf("RM650.00") == 1 },
        )
    }

    private fun insert(txn: Txn): Long = Databases.shared(context).txnDao().insert(txn)

    private fun held(amountSen: Long, at: Long, reason: PendingReason) = Txn(
        // Captured, as its source says: a null id is a manual entry (spec 4).
        // The id names no capture row; the column has no foreign key.
        rawCaptureId = captureIds++,
        amountSen = amountSen,
        direction = Direction.EXPENSE,
        occurredAt = at,
        localDate = LocalDates.of(at),
        merchantRaw = "RUMAH SEWA",
        merchantDisplay = "Rumah Sewa",
        merchantKey = "RUMAH SEWA",
        categoryId = Databases.categoryDao(context).requireUncategorizedId(),
        sourcePackage = "com.maybank2u.life",
        sourceLabel = "MAE",
        confidence = Confidence.HIGH,
        state = TxnState.PENDING,
        pendingReason = reason,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun discard() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    /** Whether [text] is drawn inside the window, not merely composed. */
    private fun ComposeTestRule.isShown(text: String): Boolean =
        runCatching { onNodeWithText(text).assertIsDisplayed() }.isSuccess

    /** Tab badges drawn, of any count. */
    private fun ComposeTestRule.badges(): Int =
        onAllNodesWithContentDescription(spendingBadgeDescription(0).removePrefix("0"), substring = true)
            .fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** Enough cards that the last is far below the first screen. */
        const val FILLERS = 24

        const val TARGET_SEN = 77_777L
        const val TARGET_AMOUNT = "RM777.77"
        const val TARGET_BADGE = "AWAITING REVIEW · RULE REVIEW"

        /** The inbox's title. */
        const val TITLE = "Awaiting review"

        const val CHARTS_LINE = "1 awaiting review"
        const val DAY_LINE = "RM650.00 kept out of the total: 0 excluded, 1 awaiting review"

        /** `SEPTEMBER 2026`, from the enum's own name rather than the screen's formatter. */
        fun header(month: YearMonth) = "${month.month.name} ${month.year}"
    }
}
