package my.pinged.ledger.breakdown

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import my.pinged.ledger.SEEDED_CATEGORY
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
import java.time.YearMonth

/**
 * Breakdown's holder reads through the runner it is handed (#386): an answer
 * publishes the category's month, and a refusal publishes the screen's own
 * notice. Through `ScriptedRunner` over an in-memory ledger, as `DayRunnerTest`.
 */
@RunWith(RobolectricTestRunner::class)
class BreakdownRunnerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val model = BreakdownViewModel(app, SEEDED_CATEGORY, MONTH, SavedStateHandle(), runner) { TODAY }

    @Test fun anAnsweredReadPublishesTheCategorysMonth() {
        runner.seedOnePayment(MONTH.atDay(15))

        model.read.refresh()

        val breakdown = model.read.state.value.answer
        assertEquals("The month's lines are not the seeded payment's", listOf(SEEDED_SEN), breakdown?.lines?.map { it.netSen })
        assertEquals("The category was not read with them", "Food & Drinks", breakdown?.category?.name)
        assertReadable(model.read)
    }

    @Test fun aRefusedReadPublishesTheScreensNotice() {
        runner.refusing = true

        model.read.refresh()

        assertRefusedRead(runner, model.read, WHAT)
    }

    private companion object {
        val MONTH: YearMonth = YearMonth.of(2026, 9)
        val TODAY: LocalDate = LocalDate.of(2026, 10, 14)
        const val WHAT = "The breakdown cannot be read"
    }
}
