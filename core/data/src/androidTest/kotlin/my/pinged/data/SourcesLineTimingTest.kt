package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.parse.PackagePack
import my.pinged.parse.ParsePack
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * What the sources line's read costs (#365) beside `monthTotals`, which
 * Charts reads in the same transaction: over `merchantTotals`' constructed
 * ledger of 20,000 rows across 24 months, each median of 50 after 10 warm-up
 * reads. The figures are recorded on `SourcesLine.read`.
 */
@RunWith(AndroidJUnit4::class)
class SourcesLineTimingTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    @Measurement @Test fun aMonthsLineIsMeasuredBesideItsTotal() {
        db.seedTheRankingsLedger()
        val month = YearMonth.of(2026, 9)
        val from = LocalDates.of(month.atDay(1))
        val to = LocalDates.of(month.atEndOfMonth())
        val pack = ParsePack(packVersion = 1, packages = listOf(PackagePack("my.com.tngdigital.ewallet", "TNG")))
        listOf(
            "monthTotals" to { db.txnDao().monthTotals(from, to) },
            "SourcesLine.read" to { SourcesLine.read(db, pack, month) },
        ).forEach { (name, read) ->
            val millis = (0 until 60).map {
                val start = System.nanoTime()
                db.runInTransaction { read() }
                (System.nanoTime() - start) / 1_000_000.0
            }.drop(10).sorted()
            Log.i(OpenTest.REPORT_TAG, "sources line [$name]: median %.2fms, longest %.2fms".format(millis[millis.size / 2], millis.last()))
        }
    }
}
