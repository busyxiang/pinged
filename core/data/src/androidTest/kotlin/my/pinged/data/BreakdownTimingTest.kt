package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.TxnState
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
        seed()
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

    /**
     * 20,000 rows, about 830 a month from October 2024 to September 2026,
     * across 500 merchants and the sixteen seeded categories, so Food &
     * Drinks holds about 52 a month; one row in fifty awaits review and one
     * in twenty is excluded. Merchants 0 to 99 are merged into 100 to 199.
     */
    private fun seed() {
        val categories = db.categoryDao().all().map { it.id }
        val start = YearMonth.of(2024, 10).atDay(1)
        db.runInTransaction {
            (0 until 20_000).chunked(500).forEach { chunk ->
                db.txnDao().insertAll(
                    chunk.map { i ->
                        val day = start.plusDays((i * 731L) / 20_000)
                        val merchant = "SHOP ${i % 500}"
                        val pending = i % 50 == 0
                        sampleTxn(
                            amountSen = 100L + i % 9_000,
                            occurredAt = 1_000L + i,
                            localDate = LocalDates.of(day),
                            categoryId = categories[i % categories.size],
                            state = if (pending) TxnState.PENDING else TxnState.COMMITTED,
                            isExcluded = !pending && i % 20 == 1,
                        ).copy(merchantDisplay = merchant.lowercase(), merchantRaw = merchant, merchantKey = merchant)
                    },
                )
            }
        }
        (0 until 100).forEach { db.merchantIdentityDao().merge(source = "SHOP $it", target = "SHOP ${it + 100}", targetName = "Shop ${it + 100}") }
    }
}
