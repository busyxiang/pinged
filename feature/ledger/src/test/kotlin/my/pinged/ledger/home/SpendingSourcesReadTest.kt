package my.pinged.ledger.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.capture.Graph
import my.pinged.data.LocalDate
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Spending's read carries the sources line of the month it read (#367), from
 * the loaded pack: the month asked, not the clock's, since an app switched
 * off since is named only on the months it paid in. The same line as
 * Charts' on a device is `SourcesLineScreensTest`'s.
 *
 * On Robolectric's SQLite, in memory, as `ScriptedRunner`'s ledger is.
 */
@RunWith(AndroidJUnit4::class)
class SpendingSourcesReadTest {
    private val db: PingedDatabase = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PingedDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After fun close() = db.close()

    private fun read(month: YearMonth) =
        readLedger(db, month, ChooserRead(), java.time.LocalDate.of(2026, 10, 14), Graph.parsePack()).summary?.sources?.text

    @Test fun eachMonthNamesItsOwnApps() {
        val food = db.categoryDao().insert(Category(name = "Food & Drinks", iconKey = "food", sortOrder = 1))
        val sources = db.captureSourceDao()
        sources.insertIfNew(CaptureSource(TNG, "Touch 'n Go", enabled = true, firstSeenAt = 0L))
        sources.insertIfNew(CaptureSource(MAE, "MAE", enabled = true, firstSeenAt = 0L))
        // Switched off since: named on September, which it paid in, only.
        sources.insertIfNew(CaptureSource(GRAB, "GrabPay", enabled = false, firstSeenAt = 0L))
        db.txnDao().insert(ledgerTxn(1_250L, localDate = LocalDate(20_260_915), categoryId = food).copy(sourcePackage = GRAB, sourceLabel = "GrabPay"))

        assertEquals("FROM GRABPAY, MAYBANK MAE AND TOUCH 'N GO EWALLET", read(YearMonth.of(2026, 9)))
        assertEquals("FROM MAYBANK MAE AND TOUCH 'N GO EWALLET", read(YearMonth.of(2026, 10)))
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
    }
}
