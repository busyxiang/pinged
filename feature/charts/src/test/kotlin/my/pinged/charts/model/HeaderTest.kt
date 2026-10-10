package my.pinged.charts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The month name and the chip beside it (#81, Header chip; #68, #73). */
class HeaderTest {
    private val september = YearMonth.of(2026, 9)

    @Test fun theMonthIsNamedInCapitalsWithItsYear() {
        val header = header(september, today = LocalDate.of(2026, 9, 10), trust = MonthTrust(0))

        assertEquals("SEPTEMBER 2026", header.month)
    }

    @Test fun aRunningMonthSaysHowFarIn() {
        val chip = header(september, today = LocalDate.of(2026, 9, 10), trust = MonthTrust(0)).chip

        assertEquals("DAY 10 OF 30", chip?.text)
        assertFalse("A running month is not an alert", chip!!.alert)
    }

    @Test fun anEndedMonthIsComplete() {
        val chip = header(september, today = LocalDate.of(2026, 10, 1), trust = MonthTrust(0)).chip

        assertEquals("MONTH COMPLETE", chip?.text)
        assertFalse(chip!!.alert)
    }

    @Test fun notCapturedDaysWinOverAnEndedMonth() {
        val chip = header(september, today = LocalDate.of(2026, 10, 5), trust = MonthTrust(3)).chip

        assertEquals("3 DAYS NOT CAPTURED", chip?.text)
        assertTrue("The not-captured chip is not in the accent", chip!!.alert)
    }

    @Test fun notCapturedDaysWinOverARunningMonth() {
        val chip = header(september, today = LocalDate.of(2026, 9, 10), trust = MonthTrust(1)).chip

        assertEquals("1 DAY NOT CAPTURED", chip?.text)
        assertTrue(chip!!.alert)
    }
}
