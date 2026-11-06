package my.pinged.ledger.merchantmonth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import my.pinged.ledger.MERCHANT_KEY
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
 * Merchant month's holder reads through the runner it is handed (#386): an
 * answer publishes the month, and a refusal publishes the screen's own
 * notice. Through `ScriptedRunner` over an in-memory ledger, as `DayRunnerTest`.
 */
@RunWith(RobolectricTestRunner::class)
class MerchantMonthRunnerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val model = MerchantMonthViewModel(app, MERCHANT_KEY, MONTH, runner) { TODAY }

    @Test fun anAnsweredReadPublishesTheMonth() {
        runner.seedOnePayment(MONTH.atDay(15))

        model.read.refresh()

        val month = model.read.state.value.answer
        assertEquals("The month's rows are not the seeded payment", listOf(SEEDED_SEN), month?.rows?.map { it.txn.amountSen })
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
        const val WHAT = "The merchant's month cannot be read"
    }
}
