package my.pinged.ledger.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneId
import my.pinged.data.Seed
import my.pinged.data.dao.BillChoice
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.bill.BillOffer
import my.pinged.ledger.bill.SEP_18
import my.pinged.ledger.bill.SEP_20
import my.pinged.ledger.bill.WHICH_BILL
import my.pinged.ledger.bill.billChoice
import my.pinged.ledger.bill.billRowTag
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import my.pinged.read.LedgerAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refund's detail as spec 9.7 draws it for paybacks (`design/DetailRefund.dc.html`,
 * `DetailRefundFiled`, `DetailPayback`), with no database: `+ BILL` on a
 * committed refund only, a payback's Pays back chip, Counts against and
 * small print, and Not a payback in Change category's place with its Undo.
 */
@RunWith(AndroidJUnit4::class)
class PaybackDetailTest {
    @get:Rule val compose = createComposeRule()

    private val picks = mutableListOf<Long?>()
    private var unlinkings = 0
    private var undos = 0

    private val yuenKee = billChoice(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 6_400L)
    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 1)
    private val uncategorized = Category(id = 16L, name = Seed.UNCATEGORIZED, iconKey = "circle-help", sortOrder = 16)

    private fun draw(txn: Txn, bill: BillChoice? = null, category: String = Seed.UNCATEGORIZED, unlinked: Boolean = false) {
        val row = DetailRow(
            row = FeedRow(txn, identityKey = txn.merchantKey, displayName = "Arjun Nair"),
            categoryName = category,
            captureTitle = "Touch 'n Go eWallet",
            captureText = "RM 32.00 received from SAMPLE PAYER NAME for SAMPLE USER.",
            captureBigText = null,
            matchedRuleId = "tng-received-v1",
            packVersion = 7,
        )
        compose.setContent {
            DetailScreenContent(
                DetailRead(row, ChooserRead(categories = listOf(food, uncategorized)), bill = bill),
                ledger = LedgerAccess.Readable,
                onBack = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                actions = DetailActions(
                    saveNote = {},
                    notSpending = {},
                    countAsSpending = {},
                    remove = {},
                    bills = { _, _ -> BillOffer.Recent(listOf(yuenKee), emptyList()) },
                    pickBill = { picks += it },
                    notAPayback = { unlinkings++ },
                    undo = { undos++ },
                ),
                zone = ZoneId.systemDefault(),
                unlinked = unlinked,
            )
        }
    }

    private fun refund(state: TxnState = TxnState.COMMITTED, direction: Direction = Direction.REFUND, bill: Long? = null): Txn =
        ledgerTxn(
            amountSen = 3_200L,
            occurredAt = SEP_20.atTime(13, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            state = state,
            pendingReason = if (state == TxnState.PENDING) my.pinged.data.entity.PendingReason.RULE_REVIEW else null,
            id = 52L,
        ).copy(direction = direction, billTxnId = bill)

    @Test fun aCommittedRefundOffersABillInMutedAndItsCategoryInStamp() {
        draw(refund())

        compose.onNodeWithTag(PAYS_BACK_CHIP).performScrollTo().assertTextEquals(ADD_BILL)
        compose.onNodeWithText(ADD_CATEGORY).assertExists()
        compose.onNodeWithText(CAME_BACK).assertExists()
        compose.onNodeWithText(CHANGE_CATEGORY).performScrollTo()
    }

    @Test fun aRefundAwaitingReviewAndAPaymentOfferNoBill() {
        draw(refund(state = TxnState.PENDING))
        compose.onAllNodesWithText(PAYS_BACK_FIELD).assertCountEquals(0)
    }

    @Test fun aPaymentOffersNoBill() {
        draw(refund(direction = Direction.EXPENSE), category = "Food & Drinks")
        compose.onAllNodesWithText(PAYS_BACK_FIELD).assertCountEquals(0)
    }

    @Test fun addBillOpensTheChooserAndReportsThePick() {
        draw(refund())

        compose.onNodeWithTag(PAYS_BACK_CHIP).performScrollTo().performClick()
        compose.onNodeWithText(WHICH_BILL).assertExists()
        compose.onNodeWithTag(billRowTag(5L)).performClick()

        assertEquals(listOf<Long?>(5L), picks)
    }

    @Test fun aPaybackNamesItsBillWhereItCountsAndWhyItsDayDoesNot() {
        draw(refund(bill = 5L), bill = yuenKee)

        compose.onNodeWithText(PAID_BACK).assertExists()
        compose.onAllNodesWithText(CAME_BACK).assertCountEquals(0)
        compose.onNodeWithTag(PAYS_BACK_CHIP, useUnmergedTree = false).performScrollTo()
        compose.onNodeWithText("Yuen Kee", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(COUNTS_AGAINST_VALUE).assertTextEquals("Food & Drinks · Yuen Kee · 18 Sep")
        compose.onNodeWithText("IN THE LIST ON 20 SEP · COUNTED ON 18 SEP").assertExists()
        compose.onAllNodesWithText(CATEGORY).assertCountEquals(0)
    }

    /** Left out itself, a payback counts nowhere: no line may say it is counted under its bill. */
    @Test fun aPaybackLeftOutSaysNothingOfCountingUnderItsBill() {
        draw(refund(bill = 5L).copy(isExcluded = true, exclusionReason = ExclusionReason.USER), bill = yuenKee)

        compose.onAllNodesWithTag(COUNTS_AGAINST_VALUE).assertCountEquals(0)
        compose.onAllNodesWithText("IN THE LIST ON 20 SEP · COUNTED ON 18 SEP").assertCountEquals(0)
    }

    /** Its bill left out, a payback counts on its own as money received (spec 7.4), so its eyebrow is Charts' came back. */
    @Test fun aPaybackWhoseBillStoppedCountingSaysCameBack() {
        val leftOut = yuenKee.copy(txn = yuenKee.txn.copy(isExcluded = true, exclusionReason = ExclusionReason.USER))
        draw(refund(bill = 5L), bill = leftOut)

        compose.onNodeWithText(CAME_BACK).assertExists()
        compose.onAllNodesWithText(PAID_BACK).assertCountEquals(0)
        compose.onAllNodesWithTag(COUNTS_AGAINST_VALUE).assertCountEquals(0)
    }

    @Test fun aPaybackHasNotAPaybackInChangeCategorysPlace() {
        draw(refund(bill = 5L), bill = yuenKee)

        compose.onAllNodesWithText(CHANGE_CATEGORY).assertCountEquals(0)
        compose.onNodeWithText(NOT_A_PAYBACK).performScrollTo().performClick()

        assertEquals(1, unlinkings)
    }

    @Test fun notAPaybackOffersUndoUntilItIsUsed() {
        draw(refund(), unlinked = true)

        compose.onNodeWithTag(DETAIL_UNDO_BAR).assertExists()
        compose.onNodeWithText(NOT_A_PAYBACK_SAID).assertExists()
        compose.onNodeWithText(UNDO).performClick()

        assertEquals(1, undos)
    }

    @Test fun noUndoIsOfferedUntilSomethingIsUnlinked() {
        draw(refund(bill = 5L), bill = yuenKee)
        compose.onAllNodesWithTag(DETAIL_UNDO_BAR).assertCountEquals(0)
    }
}
