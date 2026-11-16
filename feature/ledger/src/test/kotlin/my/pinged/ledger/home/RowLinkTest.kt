package my.pinged.ledger.home

import my.pinged.data.entity.TxnState
import my.pinged.ledger.bill.SEP_18
import my.pinged.ledger.bill.SEP_20
import my.pinged.ledger.bill.listRow
import my.pinged.ledger.bill.rowBill
import my.pinged.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a list row says of spec 7.4's link (spec 9.1, `design/PaybackList.dc.html`,
 * `PaybackListLeftOut`): which rows carry a tag or a paid-back line, and what
 * TalkBack reads for a payback. The board's own figures.
 */
class RowLinkTest {
    private val yuenKee = rowBill(5L, "Yuen Kee", SEP_18)

    private fun arjun(bill: my.pinged.data.dao.RowBill = yuenKee, state: TxnState = TxnState.COMMITTED, excluded: Boolean = false) =
        listRow(52L, "Arjun Nair", 3_200L, SEP_20, Direction.REFUND, bill, state = state, excluded = excluded)

    @Test fun aPaybackWhoseBillCountsIsTaggedWithItsBillAndCountsThere() {
        val link = rowLink(arjun())
        assertEquals(
            RowLink.Pays(yuenKee, here = false, spoken = "32 ringgit received from Arjun Nair, counted on 18 September with Yuen Kee"),
            link,
        )
    }

    @Test fun aPaybackWhoseBillCountsNowhereCountsHere() {
        val link = rowLink(arjun(rowBill(5L, "Yuen Kee", SEP_18, counts = false))) as RowLink.Pays
        assertEquals("While its bill counts nowhere, the payback counts on its own day", true, link.here)
        assertEquals("32 ringgit received from Arjun Nair, counted here, since its bill, Yuen Kee, is not counted", link.spoken)
    }

    @Test fun aPaybackLeftOutItselfDoesNotCountHereEither() {
        val link = rowLink(arjun(rowBill(5L, "Yuen Kee", SEP_18, counts = false), excluded = true)) as RowLink.Pays
        assertEquals("An excluded payback counts nowhere, so its tag must not say it counts here", false, link.here)
    }

    @Test fun aPaybackAwaitingReviewIsDrawnAsAnyHeldRow() {
        assertNull("A held card's pick counts nowhere yet, and is the inbox's to answer", rowLink(arjun(state = TxnState.PENDING)))
    }

    @Test fun aCountedBillPaidBackSaysWhatIsPaidBackAndTheShare() {
        val bill = listRow(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 6_400L)
        assertEquals(RowLink.PaidBack("64.00 PAID BACK · YOUR SHARE 32.00"), rowLink(bill))
    }

    @Test fun aBillLeftOutOrPaidNothingBackHasNoLine() {
        assertNull("A share of a bill in no total is a figure about nothing", rowLink(listRow(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 6_400L, excluded = true)))
        assertNull(rowLink(listRow(5L, "Yuen Kee", 9_600L, SEP_18)))
    }

    @Test fun moneyIsSpokenInRinggitAndSen() {
        assertEquals("32 ringgit", spokenMoney(3_200L, "MYR"))
        assertEquals("27 ringgit 95 sen", spokenMoney(2_795L, "MYR"))
    }

    @Test fun theFeedKeepsARowsLinkOnTheWayToTheList() {
        val payback = arjun()
        val bill = listRow(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 6_400L)

        assertEquals("The Spending feed dropped a payback's bill", payback, LedgerItem.Row.of(payback).feedRow)
        assertEquals("The Spending feed dropped a bill's paid back", bill, LedgerItem.Row.of(bill).feedRow)
    }
}
