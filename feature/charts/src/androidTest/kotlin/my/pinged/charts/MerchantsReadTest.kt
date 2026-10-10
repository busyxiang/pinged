package my.pinged.charts

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the Charts read gives the merchant ranking (#92): every merchant of the
 * month with no effective limit, since the mapping layer sums the rest itself.
 */
@RunWith(AndroidJUnit4::class)
class MerchantsReadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    private val september = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 10)

    @Before fun startClean() = runBlocking<Unit> {
        IntegrityStore.forget(context)
        discardTheDatabase()
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        discardTheDatabase()
        IntegrityStore.forget(context)
    }

    @Test(timeout = 60_000)
    fun theReadCarriesEveryMerchantOfTheMonth() = runBlocking<Unit> {
        val db = Databases.shared(context)
        val uncategorized = db.categoryDao().requireUncategorizedId()
        // More than the five drawn, so a limit at the read would show.
        val names = (1..8).map { "WARUNG $it" }
        names.forEachIndexed { i, key ->
            db.txnDao().insert(txn(1_000L * (i + 1), uncategorized, LocalDates.of(september.atDay(2)), key))
        }
        // A merchant of August is not September's.
        db.txnDao().insert(txn(9_000L, uncategorized, LocalDates.of(LocalDate.of(2026, 8, 31)), "KEDAI AUGUST"))

        val charts = withContext(Dispatchers.Main) {
            ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to september.toString())))
        }
        withContext(Dispatchers.Main) { charts.refresh(today) }.join()
        val read = charts.read.value
        assertNotNull("The read published nothing", read)

        assertEquals(
            "The read did not carry exactly the month's merchants",
            names.toSet(),
            read!!.merchantTotals.map { it.identityKey }.toSet(),
        )
    }

    private fun txn(amountSen: Long, categoryId: Long, day: my.pinged.data.LocalDate, key: String) = Txn(
        rawCaptureId = null,
        amountSen = amountSen,
        direction = Direction.EXPENSE,
        occurredAt = 1_000L,
        localDate = day,
        merchantRaw = key,
        merchantDisplay = key,
        merchantKey = key,
        categoryId = categoryId,
        sourcePackage = "my.com.tngdigital.ewallet",
        sourceLabel = null,
        confidence = Confidence.HIGH,
        state = TxnState.COMMITTED,
        pendingReason = null,
        isExcluded = false,
        exclusionReason = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun discardTheDatabase() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }
}
