package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * What Breakdown's statements cost (#342) beside `merchantTotals`, which
 * Charts reads in the same month: over `merchantTotals`' constructed ledger
 * of 20,000 rows across 24 months, 500 merchants and 100 of them merged, each
 * median of 50 after 10 warm-up reads. The figures are recorded on
 * `TxnDao.breakdownLines`.
 */
@RunWith(AndroidJUnit4::class)
class BreakdownTimingTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    @Measurement @Test fun aCategorysMonthIsMeasuredBesideTheMerchantRanking() {
        db.seedTheRankingsLedger()
        val month = YearMonth.of(2026, 9)
        val from = LocalDates.of(month.atDay(1))
        val to = LocalDates.of(month.atEndOfMonth())
        val food = db.categoryId("Food & Drinks")
        val txns = db.txnDao()
        val busiest = txns.breakdownLines(food, from, to).maxBy { it.txnCount }.identityKey
        listOf(
            "merchantTotals, the month" to { txns.merchantTotals(from, to, Int.MAX_VALUE) },
            "breakdownLines" to { txns.breakdownLines(food, from, to) },
            "breakdownRows, the busiest line" to { txns.breakdownRows(food, from, to, busiest, "MYR") },
            "breakdownKeptOut" to { txns.breakdownKeptOut(food, from, to) },
            "breakdownFirstHeld" to { txns.breakdownFirstHeld(food, from, to, "MYR") },
        ).forEach { (name, read) ->
            val millis = (0 until 60).map {
                val start = System.nanoTime()
                db.runInTransaction { read() }
                (System.nanoTime() - start) / 1_000_000.0
            }.drop(10).sorted()
            Log.i(OpenTest.REPORT_TAG, "breakdown [$name]: median %.2fms, longest %.2fms".format(millis[millis.size / 2], millis.last()))
        }
    }
}
