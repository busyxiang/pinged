package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * How long search's read holds Room's one connection: `SearchViewModel`'s
 * transaction -- the rows at its limit of 200, their totals, the categories --
 * over a constructed ledger of 20,000 rows across 24 months and 500
 * merchants. The figures are recorded on `SearchViewModel.read`.
 */
@RunWith(AndroidJUnit4::class)
class SearchTimingTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    @Measurement @Test fun searchesOverTwentyThousandRowsAreMeasured() {
        seed()
        val month = YearMonth.of(2026, 9)
        val inMonth = SearchFilter(from = LocalDates.of(month.atDay(1)), to = LocalDates.of(month.atEndOfMonth()))
        listOf(
            "month, no words" to ("" to inMonth),
            "every month, no words" to ("" to SearchFilter()),
            "month, 'grab'" to ("grab" to inMonth),
            "every month, 'grab'" to ("grab" to SearchFilter()),
            "every month, 'g'" to ("g" to SearchFilter()),
        ).forEach { (name, question) ->
            val (text, filter) = question
            val millis = (0 until 50).map {
                val start = System.nanoTime()
                db.runInTransaction {
                    db.txnDao().searchRows(text, filter, 200)
                    db.txnDao().searchTotals(text, filter)
                    db.categoryDao().all()
                }
                (System.nanoTime() - start) / 1_000_000.0
            }.drop(10).sorted()
            Log.i(OpenTest.REPORT_TAG, "search [$name]: median %.2fms, longest %.2fms".format(millis[millis.size / 2], millis.last()))
        }
    }

    /**
     * 20,000 rows, about 830 a month from October 2024 to September 2026;
     * one merchant in ten starts with "Grab", and one row in
     * ten carries a note.
     */
    private fun seed() {
        val cat = db.categoryDao().requireUncategorizedId()
        val start = YearMonth.of(2024, 10).atDay(1)
        db.runInTransaction {
            (0 until 20_000).chunked(500).forEach { chunk ->
                db.txnDao().insertAll(
                    chunk.map { i ->
                        val day = start.plusDays((i * 731L) / 20_000)
                        val merchant = if (i % 500 % 10 == 0) "Grab ${i % 500}" else "Shop ${i % 500}"
                        sampleTxn(
                            amountSen = 100L + i % 9_000,
                            occurredAt = 1_000L + i,
                            localDate = LocalDates.of(day),
                            categoryId = cat,
                        ).copy(
                            merchantDisplay = merchant,
                            merchantRaw = merchant.uppercase(),
                            merchantKey = merchant.uppercase(),
                            note = if (i % 10 == 0) "note number $i" else null,
                        )
                    },
                )
            }
        }
    }
}
