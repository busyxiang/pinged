package my.pinged.charts

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.LocalDates
import my.pinged.data.dao.MonthlyTotal
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The selected month as `ChartsViewModel` holds it, and the `Months` sheet's read (#93). */
@RunWith(AndroidJUnit4::class)
class MonthSelectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    private val august = YearMonth.of(2026, 8)
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

    /**
     * A month picked while the last month's read is still running gets its
     * own read, and the older one, landing after it, does not put the month
     * the user left back on the screen.
     */
    @Test(timeout = 60_000)
    fun aReadOfTheMonthLeftBehindDoesNotLandOverThePick() = runBlocking<Unit> {
        seed()
        val charts = withContext(Dispatchers.Main) {
            ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to september.toString())))
        }
        val once = AtomicBoolean(false)
        val picked = AtomicReference<Job?>(null)
        charts.midRead = {
            if (once.compareAndSet(false, true)) {
                // Not joined here: August's read waits for this one's
                // transaction to end.
                picked.set(runBlocking(Dispatchers.Main) { charts.select(august, today) })
            }
        }

        withContext(Dispatchers.Main) { charts.refresh(today) }.join()
        picked.get()!!.join()

        assertEquals(august, charts.month.value)
        assertEquals(
            "September's read landed after August's and drew the month the user left",
            august,
            charts.read.value?.month,
        )
    }

    /** The sheet's read: the history start, every month's totals, and the captured days. */
    @Test(timeout = 60_000)
    fun theMonthsReadCarriesEveryMonthAndTheCapturedDays() = runBlocking<Unit> {
        seed()
        val charts = withContext(Dispatchers.Main) {
            ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to september.toString())))
        }

        withContext(Dispatchers.Main) { charts.readMonths(today) }.join()

        val read = charts.months.value
        assertNotNull("The sheet's read published nothing", read)
        assertEquals(august.atDay(30), read!!.historyStart)
        assertEquals(
            "A month of money before the history start, or after it, is missing",
            listOf(
                MonthlyTotal(202601, "MYR", 4_200L),
                MonthlyTotal(202608, "MYR", 12_345L),
                MonthlyTotal(202609, "MYR", 5_000L),
            ),
            read.totals.sortedBy { it.month },
        )
        assertEquals(setOf(august.atDay(30), september.atDay(2)), read.captured)
        assertEquals(today, read.today)
    }

    /**
     * Bound on 30 August and 2 September; money in August, September and,
     * hand-entered before the history start, January.
     */
    private fun seed() {
        val db = Databases.shared(context)
        db.captureDayDao().recordListenerBound(LocalDates.of(august.atDay(30)), bound = true)
        db.captureDayDao().recordListenerBound(LocalDates.of(september.atDay(2)), bound = true)
        db.txnDao().insert(txn(4_200L, LocalDate.of(2026, 1, 15)))
        db.txnDao().insert(txn(12_345L, august.atDay(30)))
        db.txnDao().insert(txn(5_000L, september.atDay(2)))
    }

    private fun txn(amountSen: Long, day: LocalDate) = Txn(
        rawCaptureId = null,
        amountSen = amountSen,
        direction = Direction.EXPENSE,
        occurredAt = 1_000L,
        localDate = LocalDates.of(day),
        merchantRaw = "WARUNG PAK ALI",
        merchantDisplay = "Warung Pak Ali",
        merchantKey = "WARUNG PAK ALI",
        categoryId = Databases.categoryDao(context).requireUncategorizedId(),
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
