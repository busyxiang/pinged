package my.pinged

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 11.3's delete as the user makes it: from the ledger into settings,
 * typed and confirmed there, and back.
 *
 * The ledger is not composed while settings is on top of it, but its holder
 * is -- `MainActivity` scopes it to the ledger's `NavEntry`, which stays on
 * the back stack -- and the delete closes the database that holder's feed was
 * bound to. `LedgerRebindTest` holds the same mechanism with the screen left
 * on; this is the route a user takes, through the real back stack.
 *
 * `createEmptyComposeRule`, for the reason `ExportNudgeBannerTest` gives: the
 * row has to be in the database before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class DeleteReachesTheLedgerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveAnOpenableDatabase() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun theLedgerDrawsNothingAfterDeleteEverything() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        insertOneRow()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "The fixture row never reached the ledger, so there is nothing " +
                    "for the delete to take away",
                compose.waitFor { compose.countOf(MERCHANT) == 1 },
            )

            compose.onNodeWithText("SETTINGS").performClick()
            compose.onNodeWithText("Delete everything").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("DELETE")
            compose.onNodeWithText("Delete").performScrollTo().performClick()
            assertTrue(
                "The confirmed delete never emptied the database, so nothing " +
                    "below is about the ledger",
                waitFor { runCatching { Databases.txnDao(context).countAll() }.getOrNull() == 0 },
            )

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue(
                "Back on the ledger after Delete everything, it draws " +
                    "${compose.countOf(MERCHANT)} row(s) the database no longer " +
                    "holds -- it holds ${Databases.txnDao(context).countAll()}. " +
                    "The ledger's feed is still bound to the database the delete " +
                    "closed",
                compose.waitFor { compose.countOf(EMPTY) == 1 && compose.countOf(MERCHANT) == 0 },
            )
        }
    }

    private fun insertOneRow() {
        val now = System.currentTimeMillis()
        Databases.txnDao(context).insert(
            Txn(
                rawCaptureId = null,
                amountSen = 4_200L,
                direction = Direction.EXPENSE,
                occurredAt = now,
                merchantRaw = "WARUNG PROBE",
                merchantDisplay = MERCHANT,
                merchantKey = "WARUNG PROBE",
                categoryId = 1L,
                sourcePackage = "my.com.tngdigital.ewallet",
                sourceLabel = "Touch 'n Go eWallet",
                confidence = Confidence.HIGH,
                state = TxnState.COMMITTED,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int {
        waitForIdle()
        return onAllNodesWithText(text).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
        const val MERCHANT = "Warung Probe"
        const val EMPTY = "NOTHING CAPTURED YET"
    }
}
