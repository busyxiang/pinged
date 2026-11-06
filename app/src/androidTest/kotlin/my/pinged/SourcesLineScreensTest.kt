package my.pinged

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * The sources line on both screens that draw a month's total (#367): for
 * one ledger and month, Spending and Charts draw the same words, each from
 * its own holder's read against the pack the app loaded, and Spending's
 * `NO APP IS READ YET` opens Capture sources as Charts' does.
 *
 * `createEmptyComposeRule`, for the reason `DayRouteTest` gives: the rows
 * have to be in the database before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class SourcesLineScreensTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val today: LocalDate = LocalDate.now()

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    @Before fun fresh() {
        discard()
        // Fifteen captured days to today open Charts' collecting gate, behind
        // which its bars and their `WHERE IT WENT` are drawn.
        val days = Databases.shared(context).captureDayDao()
        for (back in 0L..14L) days.recordListenerBound(LocalDates.of(today.minusDays(back)), bound = true)
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun spendingAndChartsDrawTheSameLine() {
        val sources = Databases.shared(context).captureSourceDao()
        for ((pkg, label) in listOf(TNG to "Touch 'n Go", MAE to "MAE", GRAB to "GrabPay")) {
            sources.insertIfNew(CaptureSource(pkg, label, enabled = true, firstSeenAt = 0L))
        }
        insert(row(rawCaptureId = captureIds++, pkg = TNG))
        insert(row(rawCaptureId = null, pkg = null))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitTheTabBar()
            assertTrue("Spending never drew the month's sources line: $LINE", compose.waitFor { compose.countOf(LINE) == 1 })

            compose.onNodeWithText("CHARTS").performClick()
            // `WHERE IT WENT` too, which only Charts draws: Spending's line
            // alone would pass before the tab had changed.
            assertTrue(
                "Charts did not draw the line Spending drew for the same month: $LINE",
                compose.waitFor { compose.countOf("WHERE IT WENT") == 1 && compose.countOf(LINE) == 1 },
            )
        }
    }

    /** A row last month keeps Spending on its feed; this month has no app to name. */
    @Test fun spendingsNoAppIsReadYetOpensCaptureSources() {
        insert(row(rawCaptureId = captureIds++, pkg = TNG, on = today.minusMonths(1)))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitTheTabBar()
            assertTrue("Spending never drew NO APP IS READ YET", compose.waitFor { compose.countOf(NO_APP) == 1 })

            compose.onNodeWithText(NO_APP).performClick()

            assertTrue("Spending's NO APP IS READ YET did not open Capture sources", compose.waitFor { compose.countOf(SOURCES_SECTION) == 1 })
        }
    }

    private fun insert(txn: Txn): Long = Databases.shared(context).txnDao().insert(txn)

    /** A counted payment from [pkg], or by hand when [rawCaptureId] is null. */
    private fun row(rawCaptureId: Long?, pkg: String?, on: LocalDate = today): Txn {
        val at = on.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Txn(
            rawCaptureId = rawCaptureId,
            amountSen = 1_250L,
            direction = Direction.EXPENSE,
            occurredAt = at,
            localDate = LocalDates.of(at),
            merchantRaw = "KEDAI RUNCIT",
            merchantDisplay = "Kedai Runcit",
            merchantKey = "KEDAI RUNCIT",
            categoryId = Databases.categoryDao(context).requireUncategorizedId(),
            sourcePackage = pkg,
            sourceLabel = null,
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

        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"

        /** The pack names its two apps by its own labels; GrabPay no rule reads. */
        const val LINE = "FROM MAYBANK MAE AND TOUCH 'N GO EWALLET, AND BY HAND. GRABPAY IS ON BUT PINGED CANNOT READ IT YET"

        const val NO_APP = "NO APP IS READ YET"

        /** A section only the Sources screen draws, as `SourcePromptRouteTest` waits for it. */
        const val SOURCES_SECTION = "ON YOUR PHONE"
    }
}
