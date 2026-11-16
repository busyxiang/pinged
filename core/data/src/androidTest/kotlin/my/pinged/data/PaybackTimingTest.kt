package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.TxnDao
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * What spec 7.4's counting fragment costs a month's aggregates (spec 15.3):
 * over `merchantTotals`' constructed ledger of 20,000 rows across 24 months,
 * with [seedPaybacks]' 1,000 bills and 2,000 paybacks, each median of 50
 * after 10 warm-up reads. Every aggregate Charts reads for a month, alone and
 * then together in one transaction as `ChartsViewModel.readMonth` reads them,
 * and Breakdown's, Merchant month's and Search's. The figures are recorded on
 * `TxnDao.COUNTED_AS`.
 */
@RunWith(AndroidJUnit4::class)
class PaybackTimingTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    @Measurement @Test fun aMonthsAggregatesAreMeasuredWithPaybacks() {
        db.seedTheRankingsLedger()
        db.seedPaybacks()
        val month = YearMonth.of(2026, 9)
        val range = LocalDates.monthRange(month)
        val (from, to) = range.start to range.endInclusive
        val food = db.categoryId("Food & Drinks")
        val txns = db.txnDao()
        val busiest = txns.breakdownLines(food, from, to).maxBy { it.txnCount }.identityKey!!
        val aggregates = listOf<Pair<String, () -> Any>>(
            "monthTotals" to { txns.monthTotals(from, to) },
            "monthSplit" to { txns.monthSplit(from, to) },
            "dayTotals" to { txns.dayTotals(from, to) },
            "monthByCategory" to { txns.monthByCategory(from, to, Int.MAX_VALUE) },
            "merchantTotals" to { txns.merchantTotals(from, to, Int.MAX_VALUE) },
            "monthSources" to { txns.monthSources(from, to) },
            "monthTopNamed" to { txns.monthTopNamed(from, to, 3) },
            "toSortCount" to { txns.toSortCount(from, to) },
            "monthlyTotals, 24 months" to { txns.monthlyTotals(LocalDate(20241001), to) },
            "breakdownLines" to { txns.breakdownLines(food, from, to) },
            "merchantMonth" to { txns.merchantMonth(busiest, from, to) },
            "searchTotals, the month" to { txns.searchTotals("", SearchFilter(from = from, to = to)) },
        )
        val charts: () -> Any = {
            txns.monthTotals(from, to)
            txns.monthSplit(from, to)
            val previous = LocalDates.monthRange(month.minusMonths(1))
            txns.monthTotals(previous.start, previous.endInclusive)
            txns.notInTotal(from, to)
            txns.dayTotals(from, to)
            txns.monthByCategory(from, to, Int.MAX_VALUE)
            txns.merchantTotals(from, to, Int.MAX_VALUE)
            txns.monthSources(from, to)
        }
        (aggregates + ("Charts' month, together" to charts)).forEach { (name, read) ->
            val millis = (0 until 60).map {
                val start = System.nanoTime()
                db.runInTransaction { read() }
                (System.nanoTime() - start) / 1_000_000.0
            }.drop(10).sorted()
            Log.i(OpenTest.REPORT_TAG, "payback timing [$name]: median %.2fms, longest %.2fms".format(millis[millis.size / 2], millis.last()))
        }
    }

    /**
     * What a list row's link columns (`TxnDao.BILL_COLUMNS`) cost the feed:
     * a page as Room's paging source issues it, `LIMIT 40 OFFSET n`, at the
     * top and 4,000 rows deep, and a day's rows, each against the same SQL
     * without the columns, over the same ledger.
     */
    @Measurement @Test fun theFeedsLinkColumnsAreMeasured() {
        db.seedTheRankingsLedger()
        db.seedPaybacks()
        val bare = TxnDao.FEED_SQL.replace(TxnDao.BILL_COLUMNS, "")
        check(bare != TxnDao.FEED_SQL) { "the columns were not found in the feed's SQL" }
        val through = 99_999_999
        val pages = listOf(
            "feed page, top" to "LIMIT 40 OFFSET 0",
            "feed page, 4000 deep" to "LIMIT 40 OFFSET 4000",
        )
        val helper = db.openHelper.readableDatabase
        fun time(sql: String, vararg args: Any): Pair<Double, Double> {
            val millis = (0 until 60).map {
                val start = System.nanoTime()
                helper.query(sql, args).use { c -> while (c.moveToNext()) c.getLong(0) }
                (System.nanoTime() - start) / 1_000_000.0
            }.drop(10).sorted()
            return millis[millis.size / 2] to millis.last()
        }
        pages.forEach { (name, page) ->
            val (withColumns, longest) = time("SELECT * FROM (" + TxnDao.FEED_SQL.replace(":through", "?") + ") " + page, through)
            val (without, _) = time("SELECT * FROM (" + bare.replace(":through", "?") + ") " + page, through)
            Log.i(OpenTest.REPORT_TAG, "payback timing [$name]: median %.2fms (without the columns %.2fms), longest %.2fms".format(withColumns, without, longest))
        }
        val day = 20260918
        val (withColumns, longest) = time(TxnDao.DAY_ROWS_SQL.replace(":date", "?"), day)
        val (without, _) = time(TxnDao.DAY_ROWS_SQL.replace(TxnDao.BILL_COLUMNS, "").replace(":date", "?"), day)
        Log.i(OpenTest.REPORT_TAG, "payback timing [dayRows]: median %.2fms (without the columns %.2fms), longest %.2fms".format(withColumns, without, longest))
    }
}
