package my.pinged.ledger.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneId
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.bill.ADD_CASH_PAYBACK_ROW
import my.pinged.ledger.bill.PAID_BACK_MORE
import my.pinged.ledger.bill.SEP_18
import my.pinged.ledger.bill.SEP_20
import my.pinged.ledger.bill.YOUR_SHARE_FIGURE
import my.pinged.ledger.bill.YOUR_SHARE_ROW
import my.pinged.ledger.bill.listRow
import my.pinged.ledger.bill.paybackRowTag
import my.pinged.ledger.bill.rowBill
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.REMOVE_CONFIRM
import my.pinged.parse.Direction
import my.pinged.read.LedgerAccess
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A bill's detail (spec 9.7, `design/DetailBill.dc.html`,
 * `DetailBillExcluded`, `NotPaymentBill`), with no database: its paybacks
 * listed, three and then `N more`, each opening its own screen; Add a cash
 * payback; the share as a leader; the left-out line in its place; and what
 * Not a payment says of the paybacks.
 */
@RunWith(AndroidJUnit4::class)
class BillDetailTest {
    @get:Rule val compose = createComposeRule()

    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 1)
    private val opened = mutableListOf<Long>()
    private var added = 0

    private val names = listOf("Mei Ling Tan", "Arjun Nair", "Farid", "Siti Hajar", "Daniel Lim")

    private fun paybacks(count: Int, counts: Boolean = true) = names.take(count).mapIndexed { i, name ->
        listRow(50L + i, name, 1_600L, SEP_20, Direction.REFUND, rowBill(5L, "Yuen Kee", SEP_18, counts = counts))
    }

    private fun draw(paybacks: List<FeedRow>, leftOut: Boolean = false) {
        val bill = listRow(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = if (leftOut) 0L else paybacks.sumOf { it.txn.amountSen }, excluded = leftOut, source = "Maybank MAE")
        val row = DetailRow(bill, food.name, "Maybank2u", "You have paid RM96.00 to Restoran Yuen Kee Home Town Cafe", null, "DUITNOW-PAID-V1", 7)
        compose.setContent {
            PingedTheme {
                DetailScreenContent(
                    DetailRead(row, ChooserRead(categories = listOf(food)), paybacks = paybacks),
                    ledger = LedgerAccess.Readable,
                    onBack = {},
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    actions = DetailActions(
                        saveNote = {},
                        notSpending = {},
                        countAsSpending = {},
                        remove = {},
                        openPayback = { opened += it.txn.id },
                        addCashPayback = { added++ },
                    ),
                    zone = ZoneId.systemDefault(),
                )
            }
        }
    }

    @Test fun aBillListsItsPaybacksAndEachOpens() {
        draw(paybacks(2))

        compose.onNodeWithTag(paybackRowTag(51L)).performScrollTo().performClick()

        assertEquals("Arjun's row did not open Arjun's payback", listOf(51L), opened)
        compose.onAllNodesWithTag(PAID_BACK_MORE).assertCountEquals(0)
    }

    @Test fun pastThreeTheListShowsThreeAndTheRestBehindOneRow() {
        draw(paybacks(5))

        compose.onAllNodesWithTag(paybackRowTag(53L)).assertCountEquals(0)
        compose.onNodeWithTag(PAID_BACK_MORE).performScrollTo().assertTextEquals("2 more").performClick()

        compose.onNodeWithTag(paybackRowTag(54L)).performScrollTo().performClick()
        assertEquals(listOf(54L), opened)
    }

    @Test fun aCountedBillOffersACashPaybackAndItsShare() {
        draw(paybacks(2))

        compose.onNodeWithTag(YOUR_SHARE_FIGURE, useUnmergedTree = true).performScrollTo().assertTextEquals("64.00")
        compose.onNodeWithTag(ADD_CASH_PAYBACK_ROW).performScrollTo().performClick()

        assertEquals("Add a cash payback did not reach its action", 1, added)
    }

    @Test fun aLeftOutBillSaysWhereItsPaybacksCountInsteadOfAShare() {
        draw(paybacks(2, counts = false), leftOut = true)

        compose.onNodeWithText("THEY COUNT ON THEIR OWN, ON 20 SEP, UNTIL THIS COUNTS AGAIN").performScrollTo()
        compose.onAllNodesWithTag(YOUR_SHARE_ROW).assertCountEquals(0)
        compose.onAllNodesWithTag(ADD_CASH_PAYBACK_ROW).assertCountEquals(0)
    }

    @Test fun notAPaymentOnABillSaysItsPaybacksStay() {
        draw(paybacks(2))

        compose.onNodeWithText(DELETE).performScrollTo().performClick()

        compose.onNodeWithText("Its 2 paybacks stay, as money received.", substring = true).assertExists()
        compose.onNodeWithText(REMOVE_CONFIRM).assertExists()
    }

    @Test fun aBillWithOnePaybackNamesItWhenRemoved() {
        draw(paybacks(1))

        compose.onNodeWithText(DELETE).performScrollTo().performClick()

        compose.onNodeWithText("Mei Ling Tan's RM16.00 stays, as money received on 20 Sep.", substring = true).assertExists()
    }
}
