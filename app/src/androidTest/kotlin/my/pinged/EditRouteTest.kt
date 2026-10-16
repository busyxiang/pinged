package my.pinged

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.day.DAY_BACK
import my.pinged.ledger.detail.DETAIL_BACK
import my.pinged.ledger.entry.AMOUNT
import my.pinged.ledger.entry.CONFIRM_DELETE
import my.pinged.ledger.entry.DELETE_PAYMENT
import my.pinged.ledger.entry.SAVE_CHANGES
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.4's correction (#105): a tap on a manual entry's row body opens the
 * entry screen in edit mode, filled in, and a tap on a captured row's body
 * opens its detail (#153) rather than the entry screen. On the Ledger and on
 * the Day screen, which draw the same row.
 *
 * `createEmptyComposeRule`, for the reason `ExportNudgeBannerTest` gives: the
 * rows have to be in the database before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class EditRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val today = LocalDate.now()

    @Before fun seed() {
        discard()
        val db = Databases.shared(context)
        // Captured, so Charts draws the month's grid, as `DayRouteTest` seeds it.
        db.captureDayDao().recordListenerBound(LocalDates.of(today), bound = true)
        val dao = db.txnDao()
        val food = Databases.categoryDao(context).all().first { it.name == FOOD }.id
        dao.insert(row(1_250L, MANUAL, rawCaptureId = null, categoryId = food, userEdited = true, note = NOTE))
        dao.insert(row(5_678L, CAPTURED, rawCaptureId = 1L, categoryId = food, userEdited = false, note = null))
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun onTheLedgerAManualRowOpensFilledInAndACapturedRowOpensItsDetail() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("The Ledger did not draw both rows", compose.waitFor { compose.reached(MANUAL) && compose.reached(CAPTURED) })

            tapAndCheckDetailOpens(CAPTURED)
            tapAndCheckEditMode()
        }
    }

    @Test fun onTheDayScreenAManualRowOpensFilledInAndACapturedRowOpensItsDetail() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("CHARTS").performClick()
            val month = "${today.month.name} ${today.year}"
            assertTrue("Charts did not open on $month", compose.waitFor { compose.countOf(month) == 1 })
            compose.onNodeWithTag("grid-day-$today").performScrollTo().performClick()
            assertTrue(
                "Today's Day screen did not draw both rows",
                compose.waitFor {
                    compose.onAllNodesWithContentDescription(DAY_BACK).fetchSemanticsNodes().size == 1 &&
                        compose.reached(MANUAL) && compose.reached(CAPTURED)
                },
            )

            tapAndCheckDetailOpens(CAPTURED)
            tapAndCheckEditMode()
        }
    }

    @Test fun aConfirmedDeleteReturnsToTheListWithoutTheRow() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("The Ledger did not draw both rows", compose.waitFor { compose.reached(MANUAL) && compose.reached(CAPTURED) })
            tapAndCheckEditMode()

            // Delete waits for the stored entry, which the fill above has shown.
            compose.onNodeWithText(DELETE_PAYMENT).performScrollTo().performClick()
            compose.onNodeWithText(CONFIRM_DELETE).performScrollTo().performClick()

            assertTrue(
                "A confirmed delete did not close the entry screen (#106)",
                compose.waitFor { compose.onAllNodesWithContentDescription(AMOUNT).fetchSemanticsNodes().isEmpty() },
            )
            compose.onNodeWithText("SPENDING").assertIsSelected()
            assertTrue("The list lost the captured row", compose.waitFor { compose.reached(CAPTURED) })
            assertTrue("The list still draws the deleted row", compose.waitFor { compose.countOf(MANUAL) == 0 })
        }
    }

    /**
     * A tap on [merchant]'s row body opens its detail and never the entry
     * screen; the detail's chevron then returns to the list.
     */
    private fun tapAndCheckDetailOpens(merchant: String) {
        assertTrue("$merchant's row could not be scrolled to", compose.reached(merchant))
        compose.onNodeWithText(merchant).performScrollTo().performClick()
        assertTrue("A captured row's body did not open its detail", compose.waitFor { compose.countOf(WHAT_PINGED_READ) == 1 })
        assertEquals("A captured row's body opened the entry screen", 0, compose.onAllNodesWithContentDescription(AMOUNT).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription(DETAIL_BACK).performScrollTo().performClick()
        assertTrue("The detail's chevron did not return to the list", compose.waitFor { compose.countOf(WHAT_PINGED_READ) == 0 })
    }

    /** A tap on the manual row's body, then the entry screen in edit mode with every field filled in. */
    private fun tapAndCheckEditMode() {
        assertTrue("$MANUAL's row could not be scrolled to", compose.reached(MANUAL))
        compose.onNodeWithText(MANUAL).performScrollTo().performClick()
        assertTrue(
            "The manual row did not open the entry screen filled in with its amount, merchant and note",
            compose.waitFor {
                compose.onAllNodes(hasContentDescription(AMOUNT) and hasText("RM12.50")).fetchSemanticsNodes().size == 1 &&
                    compose.countOf(MANUAL) == 1 && compose.countOf(NOTE) == 1
            },
        )
        assertEquals("Save does not read Save changes in edit mode", 1, compose.countOf(SAVE_CHANGES))
        compose.onNodeWithText(FOOD).assertIsSelected()
    }

    private fun row(amountSen: Long, merchant: String, rawCaptureId: Long?, categoryId: Long, userEdited: Boolean, note: String?): Txn {
        val at = System.currentTimeMillis() - 60_000L
        return Txn(
            rawCaptureId = rawCaptureId,
            amountSen = amountSen,
            direction = Direction.EXPENSE,
            occurredAt = at,
            localDate = LocalDates.of(today),
            merchantRaw = merchant,
            merchantDisplay = merchant,
            merchantKey = merchant.uppercase(),
            categoryId = categoryId,
            // A manual entry has no source (spec 4); a capture has its app's.
            sourcePackage = rawCaptureId?.let { "my.com.tngdigital.ewallet" },
            sourceLabel = rawCaptureId?.let { "Touch 'n Go eWallet" },
            confidence = Confidence.HIGH,
            state = TxnState.COMMITTED,
            note = note,
            userEdited = userEdited,
            createdAt = at,
            updatedAt = at,
        )
    }

    private fun discard() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    /**
     * The list scrolled until [merchant]'s row is composed: on CI's short
     * screen a lazy list has not composed it, and `performScrollTo` cannot
     * reach a row that was never composed (see `CLAUDE.md`).
     */
    private fun ComposeTestRule.reached(merchant: String): Boolean = runCatching {
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(merchant))
    }.isSuccess && countOf(merchant) == 1

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        const val MANUAL = "Kopi Pagi"
        const val CAPTURED = "Warung Probe"
        const val NOTE = "teh tarik"
        const val FOOD = "Food & Drinks"

        /** The detail's heading over the quoted notification. */
        const val WHAT_PINGED_READ = "WHAT PINGED READ"
    }
}
