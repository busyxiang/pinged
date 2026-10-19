package my.pinged

import android.content.Context
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
import my.pinged.ledger.detail.DETAIL_BACK
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
import java.time.ZoneId

/**
 * A captured row's tap on the Spending list opens its detail screen (#153)
 * on the Spending stack, with the tab bar hidden; Not spending there takes
 * the row out of the month's total, which the list shows on the way back;
 * and the screen comes back after a process death.
 *
 * `createEmptyComposeRule`, for the reason `DayRouteTest` gives.
 */
@RunWith(AndroidJUnit4::class)
class DetailRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun seed() {
        discard()
        Databases.shared(context).txnDao().insert(txn())
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun notSpendingOnTheDetailLeavesTheTotalTheListShowsOnTheWayBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("The list did not show the month's total", compose.waitFor { compose.countOf(HERO) == 1 })
            openTheDetail()

            compose.onNodeWithText("Not spending").performScrollTo().performClick()
            assertTrue("Not spending did not write", compose.waitFor { compose.countOf("Count as spending") == 1 })
            compose.onNodeWithContentDescription(DETAIL_BACK).performScrollTo().performClick()

            assertTrue("Back did not return to the list", compose.waitFor { compose.countOf("WHAT PINGED READ") == 0 && compose.countOf(MERCHANT) > 0 })
            // The list comes back where it was left, at the row, so the
            // summary is scrolled back to before it is read.
            compose.scrollFeedToSummary()
            assertTrue(
                "Back on the list, the month still counted the payment the user said was not spending",
                compose.waitFor { compose.countOf("NOTHING COUNTED THIS MONTH") == 1 && compose.countOf(HERO) == 0 },
            )
        }
    }

    @Test fun theDetailSurvivesAProcessDeathAndTheSystemBackReturnsToTheList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheDetail()

            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()

            awaitDetail("The detail did not come back after the process death")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue("System back did not return to the list", compose.waitFor { compose.countOf("WHAT PINGED READ") == 0 && compose.countOf(MERCHANT) > 0 })
        }
    }

    private fun openTheDetail() {
        assertTrue("The row never drew", compose.scrollFeedTo(MERCHANT, TIMEOUT))
        compose.onNodeWithText(MERCHANT).performScrollTo().performClick()
        awaitDetail("Tapping the captured row did not open its detail")
    }

    /** The detail: its quote heading, and no tab bar under it. */
    private fun awaitDetail(why: String) {
        assertTrue(why, compose.waitFor { compose.countOf("WHAT PINGED READ") == 1 })
        assertEquals("The tab bar is drawn over a pushed screen", 0, compose.countOf("CHARTS"))
    }

    private fun txn(): Txn {
        val noon = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Txn(
            // Captured, as its source says. The id names no capture row; the
            // column has no foreign key, and the detail reads a missing
            // capture as nothing to quote.
            rawCaptureId = 1L,
            amountSen = 4_250L,
            direction = Direction.EXPENSE,
            occurredAt = noon,
            localDate = LocalDates.of(noon),
            merchantRaw = "WARUNG PROBE",
            merchantDisplay = MERCHANT,
            merchantKey = "WARUNG PROBE",
            categoryId = Databases.categoryDao(context).requireUncategorizedId(),
            sourcePackage = "my.com.tngdigital.ewallet",
            sourceLabel = "Touch 'n Go eWallet",
            confidence = Confidence.HIGH,
            state = TxnState.COMMITTED,
            createdAt = noon,
            updatedAt = noon,
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
        const val MERCHANT = "Warung Probe"

        /**
         * The month's hero while the one payment counts, and the detail's own
         * hero: the row draws `42.50` without the symbol.
         */
        const val HERO = "RM42.50"
    }
}
