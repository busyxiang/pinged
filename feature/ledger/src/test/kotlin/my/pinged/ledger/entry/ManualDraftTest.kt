package my.pinged.ledger.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 9.4's chip selection: a tap selects, a tap on the selected chip
 * deselects (#100), and a pre-selection from the typed merchant never
 * replaces a tapped chip (#102).
 */
class ManualDraftTest {
    @Test fun aTapSelectsTheChipAsTapped() {
        assertEquals(CategoryChoice(3L, ChosenBy.TAPPED), ManualDraft().tapped(3L).category)
    }

    @Test fun aTapOnTheSelectedChipDeselectsIt() {
        assertNull(ManualDraft().tapped(3L).tapped(3L).category)
    }

    @Test fun aTapOnAnotherChipMovesTheSelection() {
        assertEquals(CategoryChoice(5L, ChosenBy.TAPPED), ManualDraft().tapped(3L).tapped(5L).category)
    }

    @Test fun aTapLeavesTheAmountAlone() {
        assertEquals(1_250L, ManualDraft(amountSen = 1_250L).tapped(3L).amountSen)
    }

    @Test fun aPreselectionFillsAnEmptySelection() {
        assertEquals(
            CategoryChoice(3L, ChosenBy.PRESELECTED),
            ManualDraft(merchant = "99 Speedmart").preselected("99 Speedmart", 3L).category,
        )
    }

    @Test fun aPreselectionNeverReplacesATappedChip() {
        val tapped = ManualDraft(merchant = "99 Speedmart").tapped(5L)
        assertEquals(CategoryChoice(5L, ChosenBy.TAPPED), tapped.preselected("99 Speedmart", 3L).category)
        assertEquals(CategoryChoice(5L, ChosenBy.TAPPED), tapped.preselected("99 Speedmart", null).category)
    }

    @Test fun aNewMerchantReplacesOrRemovesAPreselection() {
        val kk = ManualDraft(merchant = "KK Mart").preselected("KK Mart", 3L)
        assertEquals(CategoryChoice(4L, ChosenBy.PRESELECTED), kk.copy(merchant = "Grab").preselected("Grab", 4L).category)
        assertNull(kk.copy(merchant = "").preselected("", null).category)
    }

    @Test fun anAnswerForAMerchantNoLongerTypedIsIgnored() {
        val draft = ManualDraft(merchant = "KK Mart").preselected("KK Mart", 3L).copy(merchant = "Grab")
        assertEquals(CategoryChoice(3L, ChosenBy.PRESELECTED), draft.preselected("KK", 9L).category)
    }
}
