package my.pinged.charts

import my.pinged.read.Answered
import my.pinged.read.ReadState
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the `Months` sheet draws what its read says (#285): the one place a read's state becomes a [MonthsReading]. */
class MonthsReadingTest {
    private val held = Answered(1, "last opening's months")

    @Test fun anOpeningWhoseReadIsRunningWhileTheLastOpeningsMonthsAreHeldIsInFlight() {
        assertEquals(MonthsReading.InFlight, monthsReading(ReadState(held, rereading = true, unavailable = false)))
    }

    @Test fun anOpeningWhoseReadHasLandedIsLanded() {
        assertEquals(MonthsReading.Landed, monthsReading(ReadState(held, rereading = false, unavailable = false)))
    }

    @Test fun aFirstOpeningHasNothingHeldToDimSoItIsLandedAndTheSheetDrawsNotReadYetItself() {
        assertEquals(MonthsReading.Landed, monthsReading(ReadState<Int, String>(null, rereading = false, unavailable = false)))
    }

    /** At once, and in place of the months held: the last opening's are not this one's. */
    @Test fun aRefusedOpeningIsUnreadableWhateverIsHeld() {
        assertEquals(MonthsReading.Unreadable, monthsReading(ReadState(held, rereading = true, unavailable = true)))
        assertEquals(MonthsReading.Unreadable, monthsReading(ReadState<Int, String>(null, rereading = false, unavailable = true)))
    }
}
