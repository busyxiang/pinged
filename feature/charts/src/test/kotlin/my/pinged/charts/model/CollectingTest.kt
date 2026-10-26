package my.pinged.charts.model

import my.pinged.charts.chartsRead
import my.pinged.data.Collecting
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The collecting block's words (#81, Collecting; #66). The count and the
 * gate are `CaptureRecord.collecting`'s, pinned by `CaptureRecordTest`.
 */
class CollectingTest {
    private val today = LocalDate.of(2026, 9, 20)

    @Test fun installDayIsZeroOfFourteen() {
        val gate = Collecting(0)

        assertEquals("COLLECTING · 0 OF 14 DAYS", gate.label)
        assertEquals("Category bars and top merchants appear after two weeks of capture.", gate.line)
    }

    @Test fun theLabelCountsTheDays() {
        assertEquals("COLLECTING · 13 OF 14 DAYS", Collecting(13).label)
    }

    @Test fun theScreenTakesTheGateFromTheHistoryNotFromTheSelectedMonth() {
        // August, selected on 20 September: the month's own evidence is all
        // of August, but the history since the start is what counts.
        val august = YearMonth.of(2026, 8)
        val read = chartsRead(
            month = august,
            today = today,
            historyStart = august.atDay(1),
            captured = (1..31).map { august.atDay(it) }.toSet() + (11..19).map { today.withDayOfMonth(it) },
        )

        assertEquals(Collecting(40), monthView(read).collecting)
    }
}
