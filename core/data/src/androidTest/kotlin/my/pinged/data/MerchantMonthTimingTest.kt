package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * What Merchant month's statements cost (#344) beside `merchantTotals`, which
 * Charts reads in the same month: over `merchantTotals`' constructed ledger
 * of 20,000 rows across 24 months, 500 merchants and 100 of them merged
 * ([seedTheRankingsLedger]), each median of 50 after 10 warm-up reads.
 * The figures are recorded on `TxnDao.merchantMonth`.
 */
@RunWith(AndroidJUnit4::class)
class MerchantMonthTimingTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    @Measurement @Test fun aMerchantsMonthIsMeasuredBesideTheMerchantRanking() {
        db.seedTheRankingsLedger()
        val month = YearMonth.of(2026, 9)
        val from = LocalDates.of(month.atDay(1))
        val to = LocalDates.of(month.atEndOfMonth())
        val txns = db.txnDao()
        // A merged merchant, so every read resolves a key through an alias.
        val key = "SHOP 7"
        Log.i(
            OpenTest.REPORT_TAG,
            "merchant month [$key]: ${txns.merchantMonthRows(key, from, to, "MYR").size} rows of " +
                "${txns.merchantTotals(from, to, Int.MAX_VALUE).sumOf { it.txnCount }} counted in the month",
        )
        listOf(
            "merchantTotals, the month" to { txns.merchantTotals(from, to, Int.MAX_VALUE) },
            "merchantMonth" to { txns.merchantMonth(key, from, to) },
            "merchantMonthRows" to { txns.merchantMonthRows(key, from, to, "MYR") },
            "merchantMonthKeptOut" to { txns.merchantMonthKeptOut(key, from, to) },
            "merchantMonthFirstHeld" to { txns.merchantMonthFirstHeld(key, from, to, "MYR") },
            "merchantName" to { txns.merchantName(key) },
        ).forEach { (name, read) ->
            val millis = (0 until 60).map {
                val start = System.nanoTime()
                db.runInTransaction { read() }
                (System.nanoTime() - start) / 1_000_000.0
            }.drop(10).sorted()
            Log.i(OpenTest.REPORT_TAG, "merchant month [$name]: median %.2fms, longest %.2fms".format(millis[millis.size / 2], millis.last()))
        }
    }
}
