package my.pinged.charts.model

import my.pinged.charts.MonthsRead
import my.pinged.data.dao.MonthlyTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The `Months` sheet's rows and divider, from its read (#81, `Months` sheet; #75). */
class MonthsTest {
    private val today = LocalDate.of(2026, 9, 10)

    /** Every day from [from] to [to] inclusive. */
    private fun days(from: LocalDate, to: LocalDate): Set<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toSet()

    private fun read(
        historyStart: LocalDate? = LocalDate.of(2026, 4, 4),
        totals: List<MonthlyTotal> = emptyList(),
        captured: Set<LocalDate> = historyStart?.let { days(it, today.minusDays(1)) } ?: emptySet(),
    ) = MonthsRead(today, historyStart, totals, captured)

    private fun rows(items: List<MonthsItem>) = items.filterIsInstance<MonthsItem.Row>()

    @Test fun everyMonthFromTheHistoryStartToNowIsListedWithNoGapsNewestFirst() {
        // Only September and May have money; the months between still list.
        val items = monthsSheet(
            read(totals = listOf(MonthlyTotal(202609, "MYR", 284_730L), MonthlyTotal(202605, "MYR", 268_000L))),
        )

        assertEquals(
            listOf("September 2026", "August 2026", "July 2026", "June 2026", "May 2026", "April 2026"),
            rows(items).map { it.name },
        )
        assertEquals(YearMonth.of(2026, 4), rows(items).last().month)
    }

    @Test fun onlyTheCurrentMonthIsNow() {
        val rows = rows(monthsSheet(read()))

        assertEquals("September 2026", rows.single { it.now }.name)
    }

    @Test fun eachRowShowsItsRinggitNetWithoutTheSymbol() {
        val rows = rows(
            monthsSheet(
                read(
                    totals = listOf(
                        MonthlyTotal(202609, "MYR", 284_730L),
                        // Another currency's figure is never a row's (#81, Out of scope).
                        MonthlyTotal(202608, "SGD", 9_900L),
                        MonthlyTotal(202608, "MYR", 276_010L),
                    ),
                ),
            ),
        )

        assertEquals("2,847.30", rows[0].net)
        assertEquals("2,760.10", rows[1].net)
    }

    @Test fun aNetNegativeMonthIsSigned() {
        val row = rows(monthsSheet(read(totals = listOf(MonthlyTotal(202607, "MYR", -12_000L)))))
            .single { it.month == YearMonth.of(2026, 7) }

        assertEquals("A month of refunds read as spending", "−120.00", row.net)
    }

    @Test fun aMonthWithNotCapturedDaysIsGreyedAndSaysHowMany() {
        val notCaptured = setOf(LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 20))
        val rows = rows(monthsSheet(read(captured = days(LocalDate.of(2026, 4, 4), today.minusDays(1)) - notCaptured)))

        val may = rows.single { it.month == YearMonth.of(2026, 5) }
        assertEquals("3 DAYS NOT CAPTURED", may.notCaptured)
        assertTrue("An untrusted total was not greyed", may.greyed)
        val june = rows.single { it.month == YearMonth.of(2026, 6) }
        assertNull("A whole month was flagged", june.notCaptured)
        assertFalse("A whole month was greyed", june.greyed)
    }

    @Test fun theCurrentMonthCountsOnlyTheDaysBeforeToday() {
        // Nothing captured today yet, which does not make it not captured (#64).
        val september = rows(monthsSheet(read(captured = days(LocalDate.of(2026, 4, 4), today.minusDays(2)))))
            .single { it.now }

        assertEquals("1 DAY NOT CAPTURED", september.notCaptured)
    }

    @Test fun anUntrustedMonthWithNoTransactionsStillShowsZeroGreyed() {
        val july = YearMonth.of(2026, 7)
        val row = rows(monthsSheet(read(captured = days(LocalDate.of(2026, 4, 4), today.minusDays(1)) - days(july.atDay(1), july.atEndOfMonth()))))
            .single { it.month == july }

        assertEquals("0.00", row.net)
        assertEquals("31 DAYS NOT CAPTURED", row.notCaptured)
        assertTrue(row.greyed)
    }

    @Test fun aMonthWithNoTransactionsShowsZero() {
        val row = rows(monthsSheet(read(totals = listOf(MonthlyTotal(202606, "SGD", 500L)))))
            .single { it.month == YearMonth.of(2026, 6) }

        assertEquals("0.00", row.net)
    }

    @Test fun theDividerSitsDirectlyUnderTheHistoryStartsMonth() {
        val items = monthsSheet(read())

        assertEquals(
            "The divider is not the last line, under April",
            MonthsItem.Divider("NOTHING BEFORE 4 APRIL 2026"),
            items.last(),
        )
        assertEquals(YearMonth.of(2026, 4), (items[items.size - 2] as MonthsItem.Row).month)
        assertEquals(1, items.count { it is MonthsItem.Divider })
    }

    @Test fun aHandEnteredMonthBeforeTheHistoryStartSitsBelowTheDividerWithNoCount() {
        // Manual entry (§9.4) can date a row before the record began (#75).
        // January has money and February none, so only January lists.
        val items = monthsSheet(read(totals = listOf(MonthlyTotal(202601, "MYR", 4_200L))))

        val divider = items.indexOfFirst { it is MonthsItem.Divider }
        assertEquals(
            "Not April, the divider, then January",
            listOf(YearMonth.of(2026, 4), null, YearMonth.of(2026, 1)),
            items.subList(divider - 1, items.size).map { (it as? MonthsItem.Row)?.month },
        )
        val january = items.last() as MonthsItem.Row
        assertEquals("42.00", january.net)
        assertNull("A month before the record began carried a count", january.notCaptured)
        assertFalse("A month before the record began was greyed", january.greyed)
    }

    @Test fun aHandEnteredMonthAfterNowIsNeverListed() {
        val items = monthsSheet(read(totals = listOf(MonthlyTotal(202611, "MYR", 4_200L))))

        assertEquals(YearMonth.of(2026, 9), (items.first() as MonthsItem.Row).month)
    }

    @Test fun withNoHistoryStartTheSheetListsNowAndAnyHandEnteredMonthsWithNoDivider() {
        val items = monthsSheet(
            read(
                historyStart = null,
                totals = listOf(MonthlyTotal(202606, "MYR", 1_000L), MonthlyTotal(202603, "MYR", 2_000L)),
            ),
        )

        assertEquals(
            "Not now and the two months with money, newest first",
            listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 6), YearMonth.of(2026, 3)),
            rows(items).map { it.month },
        )
        assertTrue("A divider was drawn with no history start", items.none { it is MonthsItem.Divider })
        assertTrue("A row carried a count with no history start", rows(items).none { it.greyed || it.notCaptured != null })
    }

    @Test fun withNoHistoryStartAndNoTransactionsTheSheetIsTheCurrentMonth() {
        val items = monthsSheet(read(historyStart = null))

        assertEquals(listOf(YearMonth.of(2026, 9)), rows(items).map { it.month })
        assertEquals("0.00", rows(items).single().net)
    }
}
