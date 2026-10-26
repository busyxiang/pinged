package my.pinged.ledger.day

import my.pinged.data.DayStatus
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
            status = DayStatus.OUTSIDE_RECORD,
        )
        assertEquals("RM45.20", heading.total)
    }

    @Test fun aDayWithNothingCountedReadsZero() {
        assertEquals("RM0.00", dayHeading(0, emptyList(), emptyList(), status = DayStatus.OUTSIDE_RECORD).total)
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
            status = DayStatus.OUTSIDE_RECORD,
        )
        assertEquals("RM17.50 kept out of the total: 3 excluded, 1 awaiting review", heading.keptOut)
        assertEquals("The part that opens the inbox is not the review count", "1 awaiting review", heading.awaitingReview)
    }

    @Test fun theKeptOutLineIsAbsentWhenNothingIsKeptOut() {
        assertNull(dayHeading(1, listOf(CurrencyTotal(MYR, 100L)), emptyList(), status = DayStatus.OUTSIDE_RECORD).keptOut)
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
            status = DayStatus.OUTSIDE_RECORD,
        )
        assertEquals(
            "Two rows kept out of the total hid each other because their money cancels",
            "RM0.00 kept out of the total: 1 excluded, 1 awaiting review",
            heading.keptOut,
        )
    }

    @Test fun aRefundKeptOutIsSigned() {
        val heading = dayHeading(
            1,
            emptyList(),
            listOf(NotInTotal(NotInTotalLine.TRANSFERS, MYR, txnCount = 1, netSen = -2_000L)),
            status = DayStatus.OUTSIDE_RECORD,
        )
        assertEquals("\u2212RM20.00 kept out of the total: 1 excluded, 0 awaiting review", heading.keptOut)
        assertNull("A day with nothing awaiting review offers to open the inbox", heading.awaitingReview)
    }

    @Test fun aDayNotWatchedWithNoRowsSaysOnlyThat() {
        val heading = dayHeading(0, emptyList(), emptyList(), status = DayStatus.NOT_CAPTURED)
        assertEquals(
            "A not-captured day with no rows drew a total, which reads as nothing spent",
            DayHeading(total = null, keptOut = null, notWatching = true, captureLine = null),
            heading,
        )
    }

    @Test fun aDayNotWatchedWithRowsKeepsItsTotalAndTheLine() {
        val heading = dayHeading(1, listOf(CurrencyTotal(MYR, 1_200L)), emptyList(), status = DayStatus.NOT_CAPTURED)
        assertEquals(DayHeading(total = "RM12.00", keptOut = null, notWatching = true), heading)
    }

    @Test fun aWatchedDaysCaptureLineCountsItsPaymentsAndSaysSo() {
        assertEquals(
            "4 PAYMENTS · PINGED WAS WATCHING",
            dayHeading(4, emptyList(), emptyList(), status = DayStatus.CAPTURED, payments = 4).captureLine,
        )
        assertEquals(
            "1 PAYMENT · PINGED WAS WATCHING",
            dayHeading(1, emptyList(), emptyList(), status = DayStatus.CAPTURED, payments = 1).captureLine,
        )
    }

    @Test fun aDayNeitherWatchedNorMissedCountsAndClaimsNothingMore() {
        assertEquals(
            "A day before the history starts claimed Pinged was watching it",
            "0 PAYMENTS",
            dayHeading(0, emptyList(), emptyList(), status = DayStatus.OUTSIDE_RECORD, payments = 0).captureLine,
        )
    }

    @Test fun todayCountsAndClaimsNothingMoreEvenWithEvidence() {
        assertEquals(
            "Today, not ended, claimed Pinged was watching it: a captured day is a past day",
            "2 PAYMENTS",
            dayHeading(2, emptyList(), emptyList(), status = DayStatus.NOT_ENDED, payments = 2).captureLine,
        )
    }

    @Test fun aDayNotWatchedHasNoCaptureLine() {
        assertNull(
            "A not-captured day counted its payments, as a watched quiet day does",
            dayHeading(1, emptyList(), emptyList(), status = DayStatus.NOT_CAPTURED, payments = 1).captureLine,
        )
    }

    private companion object {
        const val MYR = "MYR"
    }
}
