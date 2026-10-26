package my.pinged.charts.model

import my.pinged.charts.recordFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The legend's lines for a month still running (`ChartsMonth.dc.html`, #232)
 * and a finished one (`Charts.dc.html`).
 */
class GridLegendTest {
    private val september = YearMonth.of(2026, 9)
    private val everyDay = (1..30).map(september::atDay).toSet()

    private fun legend(today: LocalDate, captured: Set<LocalDate> = everyDay) =
        rhythmGrid(september, recordFor(september, today, september.atDay(1), captured), emptyList()).legend

    @Test fun aRunningMonthSaysItsDaysToComeAreBlankAndDropsTheHatchsKey() {
        val legend = legend(today = september.atDay(14))
        assertTrue("A running month did not say its days to come are blank", legend.toCome)
        assertFalse("A running month with nothing hatched kept the hatch's key", legend.notCaptured)
    }

    @Test fun aRunningMonthWithAHatchedDayKeepsTheHatchsKey() {
        val legend = legend(today = september.atDay(14), captured = everyDay - september.atDay(3))
        assertTrue("A hatched day was left unexplained", legend.notCaptured)
    }

    @Test fun itsLastDayStillHasNothingToCome() {
        assertFalse(legend(today = september.atDay(30)).toCome)
    }

    @Test fun aFinishedMonthKeepsTheHatchsKeyWithNothingHatched() {
        val legend = legend(today = LocalDate.of(2026, 10, 5))
        assertFalse(legend.toCome)
        assertTrue(legend.notCaptured)
    }
}
