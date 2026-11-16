package my.pinged.ledger.entry

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.data.entity.TxnState
import my.pinged.ledger.bill.YOUR_SHARE_FIGURE
import my.pinged.ledger.bill.listRow
import my.pinged.ledger.bill.paybackRowTag
import my.pinged.ledger.bill.rowBill
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A typed bill's edit screen (spec 9.4, `design/CashBill.dc.html`,
 * `DeleteBill`), with no database: Received off while paybacks point at it
 * and why, the share following the retyped amount, the question asked before
 * unsaved edits are left, and Delete's sentence on the paybacks.
 */
@RunWith(AndroidJUnit4::class)
class CashBillTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 1)
    private val aug15 = LocalDate.of(2026, 8, 15)
    private val stored = ManualDraft(amountSen = 6_000L, merchant = "Warung Pak Din", day = aug15)
    private val warung = rowBill(70L, "Warung Pak Din", aug15, typed = true)
    private val daniel = listRow(71L, "Daniel Lim", 2_000L, LocalDate.of(2026, 8, 16), Direction.REFUND, warung)
    private val siti = listRow(72L, "Siti Hajar", 2_000L, LocalDate.of(2026, 8, 17), Direction.REFUND, warung, typed = true)

    private val directions = mutableListOf<Direction>()
    private val opened = mutableListOf<Long>()
    private var backs = 0

    private fun bill(amountSen: Long = 6_000L, paybacks: List<FeedRow> = listOf(daniel, siti), pointers: Int = paybacks.size) = ManualEntryState(
        draft = stored.copy(amountSen = amountSen),
        categories = listOf(food),
        today = LocalDate.of(2026, 8, 30),
        editing = 70L,
        loaded = stored,
        paybacks = paybacks,
        pointers = pointers,
    )

    private val state = mutableStateOf(bill())

    private fun draw(initial: ManualEntryState) {
        state.value = initial
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state.value,
                    onAmount = {}, onMerchant = {}, onNote = {}, onSave = {},
                    onBack = { backs++ },
                    onSaved = {},
                    onDirection = { directions += it },
                    onOpenPayback = { opened += it.txn.id },
                )
            }
        }
    }

    @Test fun receivedIsOffWhilePaybacksPointHereAndSaysWhy() {
        draw(bill())

        val received = compose.onNodeWithTag(RECEIVED_SEGMENT)
        received.assertIsNotEnabled()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "RECEIVED IS OFF: 2 PAYBACKS POINT HERE"),
        ).assertExists()
        compose.onNodeWithTag(RECEIVED_OFF).assertTextEquals("RECEIVED IS OFF: 2 PAYBACKS POINT HERE")
        received.performClick()
        assertEquals("Received took a tap on a bill paybacks point at", emptyList<Direction>(), directions)
    }

    @Test fun aRejectedPaybackStillKeepsReceivedOff() {
        // `TxnDao` refuses the flip while any row names the bill, listed or not.
        draw(bill(paybacks = emptyList(), pointers = 1))

        compose.onNodeWithTag(RECEIVED_SEGMENT).assertIsNotEnabled()
    }

    @Test fun theShareFollowsTheRetypedAmount() {
        draw(bill())
        compose.onNodeWithTag(YOUR_SHARE_FIGURE, useUnmergedTree = true).performScrollTo().assertTextEquals("20.00")

        state.value = bill(amountSen = 6_600L)

        compose.onNodeWithTag(YOUR_SHARE_FIGURE, useUnmergedTree = true).assertTextEquals("26.00")
    }

    @Test fun theShareCountsOnlyCountedPaybacks() {
        val held = listRow(73L, "Farid", 2_000L, LocalDate.of(2026, 8, 18), Direction.REFUND, warung, state = TxnState.PENDING)
        assertEquals("A payback awaiting review counts nowhere yet", 2_000L, bill(paybacks = listOf(daniel, siti, held)).shareSen)
    }

    @Test fun backWithUnsavedEditsAsksFirst() {
        draw(bill(amountSen = 6_600L))

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertEquals("Back left before asking", 0, backs)
        compose.onNodeWithText(LEAVE_QUESTION).assertExists()
        compose.onNodeWithText(LEAVE).performClick()
        assertEquals(1, backs)
    }

    @Test fun aPaybacksRowWithUnsavedEditsAsksFirstAndKeepEditingStays() {
        draw(bill(amountSen = 6_600L))

        compose.onNodeWithTag(paybackRowTag(71L)).performScrollTo().performClick()
        compose.onNodeWithText(KEEP_EDITING).performClick()

        assertEquals("Keep editing opened the payback", emptyList<Long>(), opened)
        compose.onAllNodesWithText(LEAVE_QUESTION).assertCountEquals(0)
        compose.onNodeWithTag(paybackRowTag(71L)).performClick()
        compose.onNodeWithText(LEAVE).performClick()
        assertEquals(listOf(71L), opened)
    }

    @Test fun withNothingEditedLeavingAsksNothing() {
        draw(bill())

        compose.onNodeWithTag(paybackRowTag(72L)).performScrollTo().performClick()

        compose.onAllNodesWithText(LEAVE_QUESTION).assertCountEquals(0)
        assertEquals(listOf(72L), opened)
    }

    @Test fun deletesQuestionSaysThePaybacksStay() {
        assertEquals(
            "Delete this RM60.00 payment? Its 2 paybacks stay, as money received. This can't be undone.",
            deleteQuestion(6_000L, listOf(daniel, siti)),
        )
        assertEquals(
            "Delete this RM60.00 payment? Daniel Lim's RM20.00 stays, as money received on 16 Aug. This can't be undone.",
            deleteQuestion(6_000L, listOf(daniel)),
        )
        assertEquals("Delete this RM12.50 payment? This can't be undone.", deleteQuestion(1_250L))
    }
}
