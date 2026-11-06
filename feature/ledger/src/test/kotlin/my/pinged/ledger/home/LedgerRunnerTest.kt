package my.pinged.ledger.home

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import my.pinged.ledger.SEEDED_SEN
import my.pinged.ledger.assertReadable
import my.pinged.ledger.assertRefusedRead
import my.pinged.ledger.seedOnePayment
import my.pinged.read.test.ScriptedRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * The Ledger's holder reads its summary through the runner it is handed
 * (#386): an answer publishes the month's summary, and a refusal publishes the
 * screen's own notice. Through `ScriptedRunner` over an in-memory ledger, as
 * `DayRunnerTest`. The feed is not the runner's: it leases through
 * `DatabaseAccess`, and nothing here collects it.
 *
 * A month is picked, with the clock's month named, so the summary asked is
 * not the clock's.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerRunnerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val model = LedgerViewModel(app, SavedStateHandle(), runner)

    @Test fun anAnsweredReadPublishesTheMonthsSummary() {
        runner.seedOnePayment(SEPTEMBER.atDay(15))

        model.pick(SEPTEMBER, today = OCTOBER)

        val summary = model.read.state.value.answer?.summary
        assertEquals("The summary is not of the month picked", SEPTEMBER, summary?.month)
        assertEquals("The summary's total is not the seeded payment", listOf(SEEDED_SEN), summary?.totals?.map { it.netSen })
        assertReadable(model.read)
    }

    @Test fun aRefusedReadPublishesTheScreensNotice() {
        runner.refusing = true

        model.pick(SEPTEMBER, today = OCTOBER)

        assertRefusedRead(runner, model.read, WHAT)
    }

    private companion object {
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
        val OCTOBER: YearMonth = YearMonth.of(2026, 10)
        const val WHAT = "The ledger cannot be read"
    }
}
