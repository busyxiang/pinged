package my.pinged

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.ui.monthName
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The two ways to the month's rows to sort (#273): Spending's `N TO SORT`
 * and Charts' Uncategorized bar each open Search on that month and
 * Uncategorized, and Back returns to the screen it was opened from.
 *
 * `createEmptyComposeRule`, for the reason `DayRouteTest` gives: the rows
 * have to be in the database before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class ToSortRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    private val today: LocalDate = LocalDate.now()

    /** Search's month chip, set to this month. */
    private val monthChip = monthName(YearMonth.from(today))

    @Before fun fresh() {
        discard()
        // Fourteen captured days before today open Charts' collecting gate,
        // behind which the bars are drawn.
        val days = Databases.shared(context).captureDayDao()
        for (back in 0L..15L) days.recordListenerBound(LocalDates.of(today.minusDays(back)), bound = true)
        val uncategorized = Databases.categoryDao(context).requireUncategorizedId()
        val food = Databases.categoryDao(context).all().first { it.name == "Food & Drinks" }.id
        insert(row(30_000L, uncategorized))
        insert(row(31_224L, uncategorized))
        insert(row(10_000L, food))
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun toSortOpensSearchOnTheMonthsUncategorizedAndBackReturnsToSpending() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("Spending never drew 2 TO SORT", compose.waitFor { compose.countOf(LINE) == 1 })
            compose.onNodeWithText(LINE).performScrollTo().performClick()

            awaitTheSearch("'$LINE' did not open Search on this month and Uncategorized")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertTrue(
                "Back from the search did not return to Spending",
                compose.waitFor { compose.countOf(LINE) == 1 && compose.countOf(FOUND) == 0 },
            )
        }
    }

    @Test fun chartsUncategorizedBarOpensTheSameSearchAndBackReturnsToCharts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitTheTabBar()
            compose.onNodeWithText("CHARTS").performClick()
            val bar = hasText("Uncategorized") and hasClickAction()
            assertTrue("Charts never drew a tappable Uncategorized bar", compose.waitFor { compose.onAllNodes(bar).fetchSemanticsNodes().size == 1 })
            compose.onNode(bar).performScrollTo().performClick()

            awaitTheSearch("Charts' Uncategorized bar did not open Search on this month and Uncategorized")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertTrue(
                "Back from the search did not return to Charts",
                compose.waitFor { compose.countOf("WHERE IT WENT") == 1 && compose.countOf(FOUND) == 0 },
            )
        }
    }

    /**
     * The two rows to sort and not the Food one: the month's chip, the
     * category's, and the pinned footer's count of rows found. Not the hero:
     * the field takes focus, and on CI's 640dp the keyboard leaves the lazy
     * list no room to compose it.
     */
    private fun awaitTheSearch(why: String) {
        assertTrue(
            why,
            compose.waitFor {
                compose.countOf(monthChip) == 1 && compose.countOf(CHIP_LABEL) == 1 && compose.countOf(FOUND) == 1
            },
        )
    }

    private fun insert(txn: Txn): Long = Databases.shared(context).txnDao().insert(txn)

    private fun row(amountSen: Long, categoryId: Long): Txn {
        val at = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Txn(
            // Captured, as its source says; the id names no capture row.
            rawCaptureId = captureIds++,
            amountSen = amountSen,
            direction = Direction.EXPENSE,
            occurredAt = at,
            localDate = LocalDates.of(at),
            merchantRaw = "KEDAI RUNCIT",
            merchantDisplay = "Kedai Runcit",
            merchantKey = "KEDAI RUNCIT",
            categoryId = categoryId,
            sourcePackage = "com.maybank2u.life",
            sourceLabel = "MAE",
            confidence = Confidence.HIGH,
            state = TxnState.COMMITTED,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
    }

    private fun discard() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        const val LINE = "2 TO SORT"

        /** Search's category chip, set to Uncategorized. */
        const val CHIP_LABEL = "UNCATEGORIZED"

        /** Search's footer over the two rows to sort. */
        const val FOUND = "EXPORT THESE 2 ROWS"
    }
}
