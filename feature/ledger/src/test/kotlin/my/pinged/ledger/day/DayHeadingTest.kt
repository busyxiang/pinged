package my.pinged.ledger.day

import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the `Day` screen's header says (#69), from the day's reads. Every
 * expected string is written out, not rebuilt the way the code builds it.
 */
class DayHeadingTest {

    @Test fun theTotalIsTheDaysCountedRinggitOnly() {
        val heading = dayHeading(
            rowCount = 3,
            counted = listOf(CurrencyTotal(MYR, 4_520L), CurrencyTotal("USD", 999L)),
            notInTotal = emptyList(),
            notWatching = false,
        )
        assertEquals("RM45.20", heading.total)
    }

    @Test fun aCapturedDayWithNothingCountedReadsZero() {
        assertEquals("RM0.00", dayHeading(0, emptyList(), emptyList(), notWatching = false).total)
    }

    @Test fun theKeptOutLineSumsTheThreeLinesAndCountsExcludedApartFromPending() {
        val heading = dayHeading(
            rowCount = 4,
            counted = emptyList(),
            notInTotal = listOf(
                NotInTotal(NotInTotalLine.TRANSFERS, MYR, txnCount = 1, netSen = 1_000L),
                NotInTotal(NotInTotalLine.USER_EXCLUDED, MYR, txnCount = 2, netSen = 500L),
                NotInTotal(NotInTotalLine.PENDING, MYR, txnCount = 1, netSen = 250L),
                // Not ringgit: neither its money nor its count is in a ringgit line.
                NotInTotal(NotInTotalLine.PENDING, "USD", txnCount = 7, netSen = 9_999L),
            ),
            notWatching = false,
        )
        assertEquals("RM17.50 kept out of the total: 3 excluded, 1 pending", heading.keptOut)
    }

    @Test fun theKeptOutLineIsAbsentWhenNothingIsKeptOut() {
        assertNull(dayHeading(1, listOf(CurrencyTotal(MYR, 100L)), emptyList(), notWatching = false).keptOut)
    }

    @Test fun keptOutRowsThatNetToZeroStillDrawTheLine() {
        val heading = dayHeading(
            2,
            emptyList(),
            listOf(
                // An excluded RM50 purchase and a pending RM50 refund.
                NotInTotal(NotInTotalLine.USER_EXCLUDED, MYR, txnCount = 1, netSen = 5_000L),
                NotInTotal(NotInTotalLine.PENDING, MYR, txnCount = 1, netSen = -5_000L),
            ),
            notWatching = false,
        )
        assertEquals(
            "Two rows kept out of the total hid each other because their money cancels",
            "RM0.00 kept out of the total: 1 excluded, 1 pending",
            heading.keptOut,
        )
    }

    @Test fun aRefundKeptOutIsSigned() {
        val heading = dayHeading(
            1,
            emptyList(),
            listOf(NotInTotal(NotInTotalLine.TRANSFERS, MYR, txnCount = 1, netSen = -2_000L)),
            notWatching = false,
        )
        assertEquals("\u2212RM20.00 kept out of the total: 1 excluded, 0 pending", heading.keptOut)
    }

    @Test fun aDayNotWatchedWithNoRowsSaysOnlyThat() {
        val heading = dayHeading(0, emptyList(), emptyList(), notWatching = true)
        assertEquals(
            "A not-captured day with no rows drew a total, which reads as nothing spent",
            DayHeading(total = null, keptOut = null, notWatching = true),
            heading,
        )
    }

    @Test fun aDayNotWatchedWithRowsKeepsItsTotalAndTheLine() {
        val heading = dayHeading(1, listOf(CurrencyTotal(MYR, 1_200L)), emptyList(), notWatching = true)
        assertEquals(DayHeading(total = "RM12.00", keptOut = null, notWatching = true), heading)
    }

    private companion object {
        const val MYR = "MYR"
    }
}
