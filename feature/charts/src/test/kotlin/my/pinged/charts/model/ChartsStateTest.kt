package my.pinged.charts.model

import my.pinged.charts.chartsRead
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.MonthSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * A read, turned into what the screen draws: the empty state, the trust the
 * whole month carries, and the MYR figures the hero takes (#81).
 */
class ChartsStateTest {
    private val september = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 10)

    private fun read(
        historyStart: LocalDate? = LocalDate.of(2026, 9, 1),
        captured: Set<LocalDate> = (1..9).map { september.atDay(it) }.toSet(),
        totals: List<CurrencyTotal> = listOf(CurrencyTotal("MYR", 5_000L)),
        split: List<MonthSplit> = listOf(MonthSplit("MYR", 5_000L, 0L)),
    ) = chartsRead(
        month = september,
        today = today,
        historyStart = historyStart,
        captured = captured,
        monthTotals = totals,
        monthSplit = split,
        previousMonthTotals = emptyList(),
        notInTotal = emptyList(),
    )

    @Test fun noReadYetIsReadingAndNeverEmpty() {
        assertEquals(ChartsState.Reading, chartsState(null))
    }

    @Test fun noHistoryStartIsTheEmptyStateEvenWithTransactions() {
        // A hand-entered row is not evidence (#78), so it starts no history.
        val state = chartsState(read(historyStart = null, captured = emptySet()))

        assertTrue("No history start drew a month: $state", state is ChartsState.Empty)
        state as ChartsState.Empty
        assertEquals("NOTHING CAPTURED YET", state.label)
        assertEquals("Charts begin on the first day Pinged sees a notification.", state.line)
        assertEquals("The empty state lost the month it opened on", "SEPTEMBER 2026", state.month)
    }

    @Test fun aHistoryStartDrawsTheMonth() {
        assertTrue(chartsState(read()) is ChartsState.Month)
    }

    @Test fun theTrustIsTheCountOfDaysWithNoEvidence() {
        val state = chartsState(read(captured = setOf(september.atDay(1), september.atDay(5)))) as ChartsState.Month

        assertEquals("Days 2-4 and 6-9 are not captured; today is not counted", 7, state.view.trust.notCaptured)
        assertTrue(state.view.trust.untrusted)
        assertTrue("The hero did not take the month's trust", state.view.hero.greyed)
        assertEquals("7 DAYS NOT CAPTURED", state.view.header.chip?.text)
    }

    @Test fun anOpenMonthWithNoGapsIsNotUntrusted() {
        val state = chartsState(read()) as ChartsState.Month

        assertEquals(0, state.view.trust.notCaptured)
        assertFalse(state.view.trust.untrusted)
        assertFalse(state.view.hero.greyed)
    }

    @Test fun theHeroTakesTheRinggitLinesOnly() {
        val state = chartsState(
            read(
                totals = listOf(CurrencyTotal("SGD", 90_000L), CurrencyTotal("MYR", -2_000L)),
                split = listOf(MonthSplit("SGD", 90_000L, 0L), MonthSplit("MYR", 1_000L, 3_000L)),
            ),
        ) as ChartsState.Month

        assertEquals("−RM20.00", state.view.hero.figure)
        assertEquals("SPENT RM10.00 · CAME BACK RM30.00", state.view.hero.split)
    }

    @Test fun aMonthWithNoRinggitIsZeroNotAbsent() {
        val state = chartsState(read(totals = emptyList(), split = emptyList())) as ChartsState.Month

        assertEquals("NET SPENT", state.view.hero.label)
        assertEquals("RM0.00", state.view.hero.figure)
        assertEquals("SPENT RM0.00 · CAME BACK RM0.00", state.view.hero.split)
        assertNull(state.view.hero.incomplete)
    }
}
