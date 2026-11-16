package my.pinged.ledger.bill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 7.4's chooser ranking and its words, in the boards' own figures (`design/BillChooser.dc.html`, `InboxPayback`). */
class BillWordsTest {
    private val yuenKee = billChoice(1L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 3_200L)

    /** Spec 7.4's 60 days count the payback's own: 20 Sep reaches back to 23 Jul, not 22 Jul. */
    @Test fun theUnsearchedWindowIsSixtyDaysTheDayItselfIncluded() {
        val day = java.time.LocalDate.of(2026, 9, 20)
        assertEquals(java.time.LocalDate.of(2026, 7, 23), windowStart(day))
        assertEquals(60L, java.time.temporal.ChronoUnit.DAYS.between(windowStart(day), day) + 1)
    }

    @Test fun theAmountOrAWholeMultipleOfItRanksFirst() {
        assertTrue("the payback's own amount", isMultiple(3_200L, 3_200L))
        assertTrue("a dinner for three", isMultiple(9_600L, 3_200L))
        assertFalse(isMultiple(6_400L, 3_000L))
        assertFalse("a part of the payback is no multiple of it", isMultiple(1_600L, 3_200L))
        assertFalse("a payback of nothing ranks nothing", isMultiple(0L, 0L))
    }

    @Test fun aBillPaidBackSaysHowMuchAndWhatTheShareIsBelowZeroWithATrueMinus() {
        assertEquals("32.00 PAID BACK · YOUR SHARE 64.00", paidBackLine(yuenKee))
        val rounded = billChoice(2L, "foodpanda", 6_400L, SEP_18, paidBackSen = 6_450L)
        assertEquals("64.50 PAID BACK · YOUR SHARE \u22120.50", paidBackLine(rounded))
        assertNull("nothing paid back draws no line", paidBackLine(billChoice(3L, "Tealive", 890L, SEP_20)))
    }

    @Test fun theCountsAgainstLineSubtractsOnlyWhatIsNotCountedYet() {
        assertEquals(
            "Counts against Food & Drinks on 18 Sep. Your share of Yuen Kee becomes RM32.00.",
            countsAgainstLine(yuenKee, 3_200L),
        )
        // A typed payback already counted against it, retyped from RM32.00 to RM30.00.
        val counted = billChoice(1L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 6_400L)
        assertEquals(
            "Counts against Food & Drinks on 18 Sep. Your share of Yuen Kee becomes RM34.00.",
            countsAgainstLine(counted, paybackSen = 3_000L, countedSen = 3_200L),
        )
    }

    @Test fun aBillIsWrittenAsItsNameThenItsDate() {
        assertEquals("Yuen Kee · 18 Sep", billLabel(yuenKee))
        assertEquals("FRI 18 SEP", chooserDay(SEP_18))
        assertEquals("32.00, OR A MULTIPLE OF IT", multiplesHeading(3_200L, "MYR"))
        assertEquals("ONLY BILLS THAT COUNT, ON OR BEFORE 20 SEP. A TAP PICKS AND CLOSES", chooserFootnote(SEP_20, searched = false))
        assertEquals("No bill matches “kfc” on or before 20 Sep, in all of your history.", nothingLine(SEP_20, "kfc"))
    }

    @Test fun aSearchSaysHowManyItFoundOrThatItHoldsOnlyTheNewest() {
        assertEquals("4 BILLS, ALL HISTORY", foundHeading(BillOffer.Found("64", List(4) { yuenKee }, capped = false)))
        assertEquals("1 BILL, ALL HISTORY", foundHeading(BillOffer.Found("64", listOf(yuenKee), capped = false)))
        assertEquals("THE NEWEST 200, ALL HISTORY", foundHeading(BillOffer.Found("a", List(200) { yuenKee }, capped = true)))
    }

    @Test fun aBillsPaybacksAreWordedByHowManyPointHereAndWhereTheyCount() {
        assertEquals("RECEIVED IS OFF: 2 PAYBACKS POINT HERE", receivedOffLine(2))
        assertEquals("RECEIVED IS OFF: 1 PAYBACK POINTS HERE", receivedOffLine(1))
        val bill = rowBill(5L, "Yuen Kee", SEP_18, counts = false)
        val arjun = listRow(52L, "Arjun Nair", 3_200L, SEP_20, my.pinged.parse.Direction.REFUND, bill)
        val farid = listRow(53L, "Farid", 3_200L, SEP_20.plusDays(2), my.pinged.parse.Direction.REFUND, bill)
        assertEquals("THEY COUNT ON THEIR OWN, ON 20 SEP, UNTIL THIS COUNTS AGAIN", onTheirOwnLine(listOf(arjun, arjun.copy())))
        assertEquals("IT COUNTS ON ITS OWN, ON 20 SEP, UNTIL THIS COUNTS AGAIN", onTheirOwnLine(listOf(arjun)))
        assertEquals("THEY COUNT ON THEIR OWN, EACH ON THE DAY IT ARRIVED, UNTIL THIS COUNTS AGAIN", onTheirOwnLine(listOf(arjun, farid)))
        assertEquals("6 more", moreLine(6))
        assertNull("a bill nothing pays back says nothing of paybacks", paybacksStayLine(emptyList()))
    }
}
