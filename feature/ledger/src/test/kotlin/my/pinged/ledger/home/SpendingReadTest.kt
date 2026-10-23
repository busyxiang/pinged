package my.pinged.ledger.home

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import my.pinged.read.test.ScriptedRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * Spending's summary and its `Months` sheet as `:core:read` reads them (#299,
 * #296): which month is asked, what lands when a month is picked while another
 * is read, and a sheet opening as a new question. Through `ScriptedRunner`
 * over an in-memory ledger, which holds each call until the test lets it go.
 *
 * The scope is `Dispatchers.Unconfined` on the test's own thread, as
 * `ScreenReadTest`'s is, so each assertion is made where the test stopped the
 * runner.
 */
@RunWith(RobolectricTestRunner::class)
class SpendingReadTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val runner = ScriptedRunner(ApplicationProvider.getApplicationContext())

    /** What the clock says is today; a test moves it past a month's end. */
    private var today: LocalDate = LocalDate.of(2026, 10, 14)

    private val spending = SpendingRead(
        scope = scope,
        runner = runner,
        handle = SavedStateHandle(),
        chooser = { ChooserRead() },
        today = { today },
    )

    private val summary get() = spending.read.state.value

    @After fun end() {
        scope.cancel()
    }

    /** The month the summary on screen is of, or null while it is not read yet. */
    private fun shown(): YearMonth? = summary.answer?.summary?.month

    @Test fun aMonthPickedWhileThePreviousMonthsSummaryIsReadEndsWithThePickedMonthsOnScreen() {
        runner.holding = true
        spending.read.refresh()

        spending.pick(SEPTEMBER, thisMonth = OCTOBER)
        runner.calls[0].release()
        assertTrue("The first read did not run, so this proves nothing", runner.calls[0].ran)
        assertNull("The month replaced landed on screen after the pick", shown())

        runner.calls[1].release()

        assertEquals("The picked month's summary is not the one on screen", SEPTEMBER, shown())
        assertFalse("A summary that has landed is still being read again", summary.rereading)
    }

    @Test fun aPickedMonthIsTheOneEveryLaterReadAsks() {
        spending.pick(SEPTEMBER, thisMonth = OCTOBER)

        spending.read.refresh()

        assertEquals("A resume read asked a month other than the one picked", SEPTEMBER, shown())
        assertEquals(SEPTEMBER, spending.picked.value)
    }

    @Test fun pickingTheCurrentMonthFollowsTheClockAgain() {
        spending.pick(SEPTEMBER, thisMonth = OCTOBER)

        spending.pick(OCTOBER, thisMonth = OCTOBER)

        assertNull("The current month was kept as a pick, so a month's end would not move it", spending.picked.value)
        assertEquals(OCTOBER, shown())
    }

    @Test fun aScreenLeftOpenAcrossAMonthsEndReadsTheNewMonthOnItsNextResume() {
        spending.read.refresh()
        assertEquals(OCTOBER, shown())
        today = LocalDate.of(2026, 11, 1)
        runner.holding = true

        spending.followClock()

        assertEquals("The month after midnight was not asked", NOVEMBER, spending.read.question.value)
        assertTrue("The old month was not held, dimmed, under the new one", summary.rereading)
        assertEquals(OCTOBER, shown())
        runner.calls.last().release()
        assertEquals(NOVEMBER, shown())
    }

    @Test fun aPickedMonthIsNotMovedByTheClock() {
        spending.pick(SEPTEMBER, thisMonth = OCTOBER)
        today = LocalDate.of(2026, 11, 1)
        val calls = runner.calls.size

        spending.followClock()

        assertEquals(SEPTEMBER, spending.read.question.value)
        assertEquals("A resume read again of the month already asked", calls, runner.calls.size)
    }

    @Test fun aSheetOpenedAgainIsANewQuestionSoItsLastMonthsStayWhileItIsRead() {
        spending.openMonths()
        assertEquals("The first opening did not land", 1, spending.months.state.value.answered?.question)
        runner.holding = true

        spending.openMonths()

        val reopened = spending.months.state.value
        assertTrue("A reopening was not a read of another question", reopened.rereading)
        assertEquals("The last opening's months were not kept", 1, reopened.answered?.question)

        runner.calls.last().release()
        assertEquals(2, spending.months.state.value.answered?.question)
        assertFalse(spending.months.state.value.rereading)
    }

    private companion object {
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
        val OCTOBER: YearMonth = YearMonth.of(2026, 10)
        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)
    }
}
