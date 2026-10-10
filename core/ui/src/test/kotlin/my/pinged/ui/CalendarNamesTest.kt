package my.pinged.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The month and day names every screen spells. Written out, not rebuilt; and
 * under a device locale with other names, since `Locale.ROOT` drew `M09` and
 * the device's would draw `SEPTEMBRE`.
 */
class CalendarNamesTest {

    @Test fun eachFormSpellsTheArtboardsWords() = underFrench {
        val day = LocalDate.of(2026, 9, 3)
        assertEquals("SEPTEMBER 2026", monthName(YearMonth.of(2026, 9)))
        assertEquals("September 2026", monthYear(YearMonth.of(2026, 9)))
        assertEquals("3 September", dayMonth(day))
        assertEquals("3 September 2026", dayMonthYear(day))
        assertEquals("Thursday 3 September 2026", weekdayDate(day))
    }

    private fun underFrench(block: () -> Unit) {
        val device = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            block()
        } finally {
            Locale.setDefault(device)
        }
    }
}
