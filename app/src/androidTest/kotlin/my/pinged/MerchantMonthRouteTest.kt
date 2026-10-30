package my.pinged

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import my.pinged.ledger.merchantmonth.MERCHANT_MONTH_BACK
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
 * A `WHO GOT IT` row on Charts opens its merchant's month on the Charts tab's
 * own stack, and the chevron returns to Charts; a line of the `N more
 * merchants` sheet opens its merchant's month the same way (#344).
 *
 * Six merchants, so one is folded: Warung Probe twice, in two categories,
 * and five that each outrank the folded Kedai Hujung.
 * `createEmptyComposeRule`, for the reason `BackupReminderTest` gives.
 */
@RunWith(AndroidJUnit4::class)
class MerchantMonthRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    private val today = LocalDate.now()
    private val now = YearMonth.from(today)

    @Before fun seed() {
        discard()
        val db = Databases.shared(context)
        // Past the collecting gate, which the ranking sits behind (spec 8).
        generateSequence(now.minusMonths(1).atDay(1)) { it.plusDays(1) }.takeWhile { it < today }.forEach {
            db.captureDayDao().recordListenerBound(LocalDates.of(it), bound = true)
        }
        val categories = db.categoryDao().all()
        val food = categories.single { it.name == "Food & Drinks" }.id
        val groceries = categories.single { it.name == "Groceries" }.id
        db.txnDao().insert(txn("Warung Probe", 99_000L, food))
        db.txnDao().insert(txn("Warung Probe", 1_000L, groceries))
        (1..4).forEach { db.txnDao().insert(txn("Shop $it", 50_000L - it, food)) }
        db.txnDao().insert(txn("Kedai Hujung", 120L, food))
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun aRowOpensItsMerchantsMonthAndTheChevronReturnsToCharts() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCharts()
            compose.onNode(hasText("Warung Probe") and hasClickAction()).performScrollTo().performClick()

            awaitMonth("Warung Probe", "RM1,000.00", "2 TRANSACTIONS")
            compose.onNodeWithContentDescription(MERCHANT_MONTH_BACK).performClick()

            awaitCharts("The chevron did not return to Charts")
        }
    }

    @Test fun aLineOfTheMoreMerchantsSheetOpensItsMerchantsMonth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCharts()
            compose.onNode(hasText("1 more merchant") and hasClickAction()).performScrollTo().performClick()
            assertTrue("The sheet did not open", compose.waitFor { compose.countOf("Kedai Hujung") == 1 })

            compose.onNode(hasText("Kedai Hujung") and hasClickAction()).performScrollTo().performClick()

            awaitMonth("Kedai Hujung", "RM1.20", "1 TRANSACTION")
        }
    }

    private fun openCharts() {
        compose.awaitTheTabBar()
        compose.onNodeWithText("CHARTS").performClick()
        awaitCharts("Charts did not open on ${header(now)}")
    }

    /** The merchant's month: its name, the label, the figure, the count, and no Charts header. */
    private fun awaitMonth(name: String, figure: String, count: String) {
        assertTrue(
            "The $name row did not open its month",
            compose.waitFor {
                compose.countOf(name) >= 1 && compose.countOf("SPENT SO FAR IN ${header(now)}") == 1 &&
                    compose.countOf(figure) == 1 && compose.countOf(count) == 1
            },
        )
        assertEquals("Charts' header is drawn over the merchant's month", 0, compose.countOf(header(now)))
    }

    /** Charts on this month: its header and its hero. */
    private fun awaitCharts(why: String) {
        assertTrue(why, compose.waitFor { compose.countOf(header(now)) == 1 && compose.countOf("SPENT SO FAR") == 1 })
    }

    private fun txn(name: String, amountSen: Long, category: Long) = Txn(
        // Captured, as its source says; the id names no capture row.
        rawCaptureId = captureIds++,
        amountSen = amountSen,
        direction = Direction.EXPENSE,
        occurredAt = System.currentTimeMillis(),
        localDate = LocalDates.of(today),
        merchantRaw = name.uppercase(),
        merchantDisplay = name,
        merchantKey = name.uppercase(),
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
