package my.pinged.ledger.day

import android.app.Application
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
import java.time.LocalDate

/**
 * The `Day` holder reads through the runner it is handed (#386): an answer
 * publishes the day, and a refusal publishes the screen's own notice. Through
 * `ScriptedRunner` over an in-memory ledger; the holder's scope is
 * `viewModelScope`, whose `Main.immediate` is the test's own thread.
 */
@RunWith(RobolectricTestRunner::class)
class DayRunnerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val model = DayViewModel(app, DAY, runner) { TODAY }

    @Test fun anAnsweredReadPublishesTheDay() {
        runner.seedOnePayment(DAY)

        model.read.refresh()

        val day = model.read.state.value.answer
        assertEquals("The day's rows are not the seeded payment", listOf(SEEDED_SEN), day?.rows?.map { it.txn.amountSen })
        assertEquals("The header's total is not over the rows", "RM45.00", day?.heading?.total)
        assertReadable(model.read)
    }

    @Test fun aRefusedReadPublishesTheScreensNotice() {
        runner.refusing = true

        model.read.refresh()

        assertRefusedRead(runner, model.read, WHAT)
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 10)
        val DAY: LocalDate = TODAY.minusDays(3)
        const val WHAT = "The day cannot be read"
    }
}
