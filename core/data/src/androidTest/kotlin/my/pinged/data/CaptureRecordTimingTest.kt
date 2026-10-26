package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.ParseStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * What `CaptureRecord.read` costs while it holds Room's one connection
 * (#305): the whole read, and each of its three evidence statements, over two
 * years of 20,000 captures, `MATCHED` with a transaction in either a quarter or
 * three quarters of them, and 730 `capture_day` rows. The figures are recorded on `CaptureRecord.read`.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRecordTimingTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    /** 5,000 of 20,000 captures `MATCHED`: three in four kept with no transaction. */
    @Measurement @Test fun theRecordOverTwoYearsIsMeasuredWhenMostCapturesAreUnfinished() = measure("stress", matchedOfFour = 1)

    /** 15,000 of 20,000 `MATCHED`: one in four kept with no transaction. */
    @Measurement @Test fun theRecordOverTwoYearsIsMeasuredWhenMostCapturesAreMatched() = measure("typical", matchedOfFour = 3)

    private fun measure(mix: String, matchedOfFour: Int) {
        seed(matchedOfFour)
        val zone = ZoneId.of("Asia/Kuala_Lumpur")
        val today = LocalDate.of(2026, 9, 30)
        val firstDay = LocalDates.of(today.minusDays(730))
        val lastDay = LocalDates.of(today.minusDays(1))
        val fromMs = today.minusDays(730).atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val withoutTxn = CaptureRecord.WITHOUT_TXN
        val month = today.withDayOfMonth(1).minusMonths(1)..today.withDayOfMonth(1).minusDays(1)
        listOf(
            "history, collecting" to { CaptureRecord.read(db, today, month, collecting = true, zone = zone); Unit },
            "one month" to { CaptureRecord.read(db, today, month, zone = zone); Unit },
            "one day" to { CaptureRecord.read(db, today, month.start..month.start, zone = zone); Unit },
            "capture_day and txn" to { db.captureDayDao().evidenceDates(firstDay, lastDay); Unit },
            "kept notifications, no txn" to { db.rawCaptureDao().postedAtWithoutTxn(withoutTxn, fromMs, toMs); Unit },
        ).forEach { (name, read) ->
            val millis = (0 until 50).map {
                val start = System.nanoTime()
                db.runInTransaction { read() }
                (System.nanoTime() - start) / 1_000_000.0
            }.drop(10).sorted()
            Log.i(OpenTest.REPORT_TAG, "capture record $mix [$name]: median %.2fms, longest %.2fms".format(millis[millis.size / 2], millis.last()))
        }
        val rows = db.rawCaptureDao().postedAtWithoutTxn(withoutTxn, fromMs, toMs).size
        Log.i(OpenTest.REPORT_TAG, "capture record $mix: $rows posted_at values read for ${withoutTxn.size} statuses")
    }

    /** Two years to 29 September 2026, about 27 captures and one bound row a day. */
    private fun seed(matchedOfFour: Int) {
        val cat = db.categoryDao().requireUncategorizedId()
        val start = LocalDate.of(2026, 9, 30).minusDays(730)
        val zone = ZoneId.of("Asia/Kuala_Lumpur")
        db.runInTransaction {
            db.captureDayDao().insertAllForImport(
                (0 until 730).map { CaptureDay(LocalDates.of(start.plusDays(it.toLong())), listenerBound = true, sawAnyNotification = true) },
            )
            val others = CaptureRecord.WITHOUT_TXN
            (0 until 20_000).chunked(500).forEach { chunk ->
                val captures = chunk.map { i ->
                    val at = start.plusDays((i * 730L) / 20_000).atStartOfDay(zone).plusMinutes(i % 1_300L).toInstant().toEpochMilli()
                    val status = if (i % 4 < matchedOfFour) ParseStatus.MATCHED else others[i % others.size]
                    sampleCapture(hash = "h$i", sbnKey = "k$i", postedAt = at, status = status)
                }
                db.rawCaptureDao().insertAll(captures)
            }
            val matched = db.rawCaptureDao().claimNext(Int.MAX_VALUE, ParseStatus.MATCHED)
            db.txnDao().insertAll(
                matched.map {
                    sampleTxn(rawCaptureId = it.id, categoryId = cat, occurredAt = it.postedAt, localDate = LocalDates.of(it.postedAt, zone))
                },
            )
        }
    }
}
