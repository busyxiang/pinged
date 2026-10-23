package my.pinged.charts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import my.pinged.charts.model.MonthsItem
import my.pinged.read.Answered
import my.pinged.read.ReadState
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.UPDATING
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * Jump to opened again (#285): the last opening's months stay while this
 * opening's read runs, under `UPDATING` once it has run 200ms, through
 * [monthsReading] as every host draws the sheet from its `ScreenRead`'s state.
 * The 45% dim is a pixel, asserted on a device by `MonthPickerTest`.
 */
@RunWith(RobolectricTestRunner::class)
class MonthsSheetUpdatingTest {
    @get:Rule val compose = createComposeRule()

    private val lastOpening = listOf(
        MonthsItem.Row(YearMonth.of(2026, 10), "October 2026", "1,101.40", now = true, notCaptured = null, greyed = false),
        MonthsItem.Row(YearMonth.of(2026, 9), "September 2026", "2,760.10", now = false, notCaptured = null, greyed = false),
    )

    /**
     * What the sheet's `ScreenRead` has published: this opening's read is in
     * flight, over the last opening's answer, until the test moves it on.
     */
    private var state: ReadState<Int, Unit> by mutableStateOf(ReadState(Answered(1, Unit), rereading = true, unavailable = false))

    /** This opening's read lands. */
    private fun landed() {
        state = ReadState(Answered(2, Unit), rereading = false, unavailable = false)
    }

    /** This opening's read was refused. */
    private fun refused() {
        state = state.copy(rereading = false, unavailable = true)
    }

    private fun open(items: List<MonthsItem>?) {
        // A first opening has no last answer to hold, and so is not a re-read.
        if (items == null) state = ReadState(answered = null, rereading = false, unavailable = false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                MonthsSheet(items, onPick = {}, onDismiss = {}, reading = monthsReading(state))
            }
        }
    }

    private val clock = HeldClock(compose) { now -> if (count(UPDATING) > 0) updatingAt += now }
    private val updatingAt = mutableListOf<Long>()

    private fun at(millis: Long) {
        clock.at(millis)
    }

    private fun texts(): List<String> = clock.texts()

    private fun count(text: String): Int = clock.count(text)

    @Test fun aReopenedSheetKeepsTheLastMonthsAndSaysUpdatingOnceItsReadHasRun200ms() {
        open(lastOpening)

        at(READING_DELAY_MILLIS - 40)
        assertEquals("UPDATING was drawn before 200ms", 0, count(UPDATING))

        at(READING_DELAY_MILLIS + 40)
        assertEquals("No UPDATING once the reopened sheet's read had run 200ms: ${texts()}", 1, count(UPDATING))
        assertEquals("The last opening's months were not kept", 1, count("September 2026"))

        landed()
        at(READING_DELAY_MILLIS + 80)
        assertEquals("UPDATING outlived the read it waited for", 0, count(UPDATING))
    }

    @Test fun aReadThatLandsInside200msNeverSaysUpdating() {
        open(lastOpening)

        at(READING_DELAY_MILLIS - 60)
        landed()
        at(READING_DELAY_MILLIS * 3)

        assertEquals("A read that landed inside 200ms drew UPDATING, at these ms", emptyList<Long>(), updatingAt)
    }

    /**
     * The last opening's months are not this one's: a reopening whose read
     * fails says it cannot read, at once, rather than drawing them undimmed
     * as if they were current.
     */
    @Test fun aReopeningWhoseReadFailsSaysSoAtOnceAndDropsTheLastMonths() {
        open(lastOpening)

        at(READING_DELAY_MILLIS + 40)
        refused()
        at(READING_DELAY_MILLIS + 40 + HeldClock.FRAME * 2)

        assertEquals("A failed reopening did not say it cannot read: ${texts()}", 1, count(CANNOT_READ_YOUR_DATA))
        assertEquals("A failed reopening drew the last opening's months", 0, count("September 2026"))
        assertEquals("A failed reopening still said UPDATING", 0, count(UPDATING))
    }

    @Test fun aReopeningThatFailsInside200msSaysSoBeforeTheDelay() {
        open(lastOpening)

        at(48)
        refused()
        at(READING_DELAY_MILLIS - 40)

        assertEquals("A failed reopening at 48ms waited for the delay: ${texts()}", 1, count(CANNOT_READ_YOUR_DATA))
        assertEquals("A failed reopening drew the last opening's months", 0, count("September 2026"))
    }

    @Test fun aFirstOpeningWhoseReadFailsSaysSo() {
        open(null)

        at(48)
        refused()
        at(READING_DELAY_MILLIS + 40)

        assertEquals("A failed first opening did not say it cannot read: ${texts()}", 1, count(CANNOT_READ_YOUR_DATA))
        assertEquals("A failed first opening was drawn as still being read", 0, count(READING))
    }

    @Test fun aFirstOpeningIsNotAReReadInPlace() {
        open(null)

        at(READING_DELAY_MILLIS + 40)

        assertEquals("A sheet with no last months said UPDATING", 0, count(UPDATING))
    }
}
