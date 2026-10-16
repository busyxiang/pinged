package my.pinged.ledger.teach

import my.pinged.parse.TaughtTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Teach screen's two taps (spec 5.8, `design/Teach.dc.html`). */
class TeachSelectionTest {
    // 0 Paid, 1 RM12.00, 2 to, 3 KEDAI, 4 RUNCIT, 5 AH, 6 SENG., 7 Ref, 8 0000
    private val words = TaughtTemplate.words(null, "Paid RM12.00 to KEDAI RUNCIT AH SENG. Ref 0000", null)
    private val start = TeachSelection(words)

    @Test fun theFirstTapIsTheAmountAndTheSecondTheMerchant() {
        val amount = start.tap(1)
        assertEquals(TeachSelection.Step.MERCHANT, amount.step)
        assertEquals(1, amount.amount)

        val merchant = amount.tap(3)
        assertEquals(3..3, merchant.merchant)
        assertEquals(TeachSelection.Step.READY, merchant.step)
    }

    @Test fun aWordWithNoAmountIsNotTakenForOne() {
        assertEquals(start, start.tap(0))
        assertEquals(TeachSelection.Step.AMOUNT, start.tap(0).step)
    }

    @Test fun tappingTheNextWordGrowsTheMerchantAndOneInsideStartsAgain() {
        val grown = start.tap(1).tap(3).tap(4).tap(5).tap(6)
        assertEquals(3..6, grown.merchant)
        assertEquals(5..5, grown.tap(5).merchant)
        // A word away from it starts a new merchant there.
        assertEquals(8..8, grown.tap(8).merchant)
    }

    @Test fun tappingTheAmountAgainStartsOver() {
        val again = start.tap(1).tap(3).tap(1)
        assertNull(again.amount)
        assertNull(again.merchant)
        assertEquals(TeachSelection.Step.AMOUNT, again.step)
    }

    @Test fun noMerchantIsReadyWithoutASecondTap() {
        val none = start.tap(1).noMerchant()
        assertEquals(TeachSelection.Step.READY, none.step)
        assertFalse(none.derived()!!.hasMerchant)
        // And a later tap on a word makes it one.
        assertTrue(none.tap(3).derived()!!.hasMerchant)
    }

    @Test fun nothingDerivesBeforeTheAmount() {
        assertNull(start.derived())
        assertNull(start.noMerchant().derived())
    }

    @Test fun theMerchantNeverGrowsOverTheAmount() {
        // 0 Paid, 1 KEDAI, 2 RM12.00
        val selection = TeachSelection(TaughtTemplate.words(null, "Paid KEDAI RM12.00", null)).tap(2).tap(1)
        assertEquals(1..1, selection.merchant)
        // The amount's own word is "tap again to change", never the merchant's next word.
        assertNull(selection.tap(2).merchant)
    }
}
