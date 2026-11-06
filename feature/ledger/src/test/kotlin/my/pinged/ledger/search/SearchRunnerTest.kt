package my.pinged.ledger.search

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
import java.time.YearMonth

/**
 * Search's holder reads through the runner it is handed (#386): an answer
 * publishes what the search found, and a refusal publishes the screen's own
 * notice. Through `ScriptedRunner` over an in-memory ledger, as
 * `DayRunnerTest`. Opened on a month and a category, as `N TO SORT` opens it,
 * so the question does not hang on the clock.
 */
@RunWith(RobolectricTestRunner::class)
class SearchRunnerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val model = SearchViewModel(app, SavedStateHandle(), runner, SearchStart(MONTH, SEEDED_CATEGORY))

    @Test fun anAnsweredReadPublishesWhatTheSearchFound() {
        runner.seedOnePayment(MONTH.atDay(15))

        model.read.refresh()

        val found = model.read.state.value.answer
        assertEquals("The search's rows are not the seeded payment", listOf(SEEDED_SEN), found?.rows?.map { it.txn.amountSen })
        assertReadable(model.read)
    }

    @Test fun aRefusedReadPublishesTheScreensNotice() {
        runner.refusing = true

        model.read.refresh()

        assertRefusedRead(runner, model.read, WHAT)
    }

    private companion object {
        val MONTH: YearMonth = YearMonth.of(2026, 9)
        const val WHAT = "The search cannot be read"
    }
}
