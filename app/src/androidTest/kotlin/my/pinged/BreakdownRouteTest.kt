package my.pinged

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import my.pinged.ledger.breakdown.BREAKDOWN_BACK
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

/**
 * A category's bar on Charts opens its Breakdown on the Charts tab's own
 * stack, and the chevron returns to Charts (#342). A Breakdown restored after
 * a process death for a category merged away since pops itself back to
 * Charts rather than drawing a category that is not there.
 *
 * The category is one the test adds, so merging it away touches no seeded
 * row. `createEmptyComposeRule`, for the reason `BackupReminderTest` gives.
 */
@RunWith(AndroidJUnit4::class)
class BreakdownRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    private val today = LocalDate.now()
    private val now = YearMonth.from(today)
    private var kopi = 0L

    @Before fun seed() {
        discard()
        val db = Databases.shared(context)
        // Past the collecting gate, which the bars sit behind (spec 8).
        generateSequence(now.minusMonths(1).atDay(1)) { it.plusDays(1) }.takeWhile { it < today }.forEach {
            db.captureDayDao().recordListenerBound(LocalDates.of(it), bound = true)
        }
        kopi = db.categoryDao().add("Kopi", "coffee")
        db.txnDao().insert(txn(1_250L, kopi))
        db.txnDao().insert(txn(990L, kopi))
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun aBarOpensItsBreakdownAndTheChevronReturnsToCharts() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openKopi()

            compose.onNodeWithContentDescription(BREAKDOWN_BACK).performClick()

            awaitCharts("The chevron did not return to Charts")
        }
    }

    @Test fun aBreakdownRestoredForACategoryMergedAwayReturnsToCharts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openKopi()

            Databases.categoryDao(context).mergeInto(kopi, Databases.categoryDao(context).requireUncategorizedId())
            // Every holder dropped, then the Activity rebuilt from its saved
            // state, which carries the `Breakdown` key and nothing read.
            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()

            awaitCharts("A Breakdown of a category merged away did not return to Charts")
            assertEquals(
                "The Breakdown is still drawn",
                0,
                compose.onAllNodesWithContentDescription(BREAKDOWN_BACK).fetchSemanticsNodes().size,
            )
        }
    }

    /** Charts, then the Kopi bar, then its Breakdown: the label, the line, and no Charts header. */
    private fun openKopi() {
        compose.awaitTheTabBar()
        compose.onNodeWithText("CHARTS").performClick()
        awaitCharts("Charts did not open on ${header(now)}")
        compose.onNode(hasText("Kopi") and hasClickAction()).performScrollTo().performClick()
        assertTrue(
            "Tapping the Kopi bar did not open its Breakdown",
            compose.waitFor {
                compose.countOf("SPENT SO FAR IN ${header(now)}") == 1 && compose.countOf("Warung Probe") == 1 &&
                    compose.countOf("2 TRANSACTIONS") == 1
            },
        )
        assertEquals("Charts' header is drawn over the Breakdown", 0, compose.countOf(header(now)))
    }

    /** Charts on this month: its header and its Kopi bar. */
    private fun awaitCharts(why: String) {
        assertTrue(why, compose.waitFor { compose.countOf(header(now)) == 1 && compose.countOf("SPENT SO FAR") == 1 })
    }

    private fun txn(amountSen: Long, category: Long) = Txn(
        // Captured, as its source says; the id names no capture row.
        rawCaptureId = captureIds++,
        amountSen = amountSen,
        direction = Direction.EXPENSE,
        occurredAt = System.currentTimeMillis(),
        localDate = LocalDates.of(today),
        merchantRaw = "WARUNG PROBE",
        merchantDisplay = "Warung Probe",
        merchantKey = "WARUNG PROBE",
        categoryId = category,
        sourcePackage = "my.com.tngdigital.ewallet",
        sourceLabel = "Touch 'n Go eWallet",
        confidence = Confidence.HIGH,
        state = TxnState.COMMITTED,
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

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** `OCTOBER 2026`, from the enum's own name rather than the screen's formatter. */
        fun header(month: YearMonth) = "${month.month.name} ${month.year}"
    }
}
