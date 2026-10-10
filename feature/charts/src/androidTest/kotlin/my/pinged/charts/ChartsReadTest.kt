package my.pinged.charts

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.pinged.capture.CaptureStorage
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.MonthSplit
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The Charts read and the `Months` read each run under a `Databases` lease, in
 * one transaction, and land as one snapshot (#81, The Charts read). Prior art:
 * `:feature:ledger`'s `LeasedReadTest`.
 *
 * Two things land between a read's first statement and the rest, from another
 * thread: a guarded caller replacing the instance for damage, and a commit.
 * The first must not close the instance under the read; the second must be
 * wholly in the read or wholly out of it.
 */
@RunWith(AndroidJUnit4::class)
class ChartsReadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    private val september = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 10)

    /** A throw out of `viewModelScope` reaches the thread's handler; kept here as a failure. */
    private val uncaught = AtomicReference<Throwable?>(null)
    private var systemHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun startClean() = runBlocking<Unit> {
        systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> uncaught.compareAndSet(null, thrown) }
        IntegrityStore.forget(context)
        context.discardTheDatabase()
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        context.discardTheDatabase()
        IntegrityStore.forget(context)
        Thread.setDefaultUncaughtExceptionHandler(systemHandler)
    }

    @Test(timeout = 60_000)
    fun theReadFinishesThroughAnInstanceReplacedUnderItAndLandsOnce() = runBlocking<Unit> {
        seedSeptember()
        val charts = withContext(Dispatchers.Main) {
            ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to september.toString())))
        }
        val midway = AtomicReference<ChartsRead?>(null)
        val replacing = AtomicReference<Thread?>(null)
        charts.midRead = {
            midway.set(charts.read.value)
            replacing.set(alongside { runBlocking { context.replaceFromAnotherCaller() } })
        }

        withContext(Dispatchers.Main) { charts.refresh(today) }.join()
        replacing.get()!!.join()

        assertNull("The read threw out of the holder: ${uncaught.get()}", uncaught.get())
        assertFalse("The read was refused", charts.unavailable.value)
        assertNull(
            "Part of the read was published before the rest was read, so the " +
                "screen could draw a figure beside another moment's trust: ${midway.get()}",
            midway.get(),
        )
        val read = landed(charts.read.value)
        assertEquals(listOf(CurrencyTotal("MYR", -12_000L)), read.monthTotals)
        assertEquals(listOf(MonthSplit("MYR", 4_500L, 16_500L)), read.monthSplit)
        assertEquals(
            "The comparison's month before is not August's own net",
            listOf(CurrencyTotal("MYR", 7_000L)),
            read.previousMonthTotals,
        )
        assertEquals(
            "The not-in-the-total read is not September's pending row",
            listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, 900L)),
            read.notInTotal,
        )
        assertEquals(september.atDay(1), read.historyStart)
        assertEquals(setOf(september.atDay(1), september.atDay(2)), read.captured)
        assertEquals(today, read.today)
        assertWritable()
    }

    /**
     * **A commit landing between the read's statements is wholly in it or
     * wholly out of it.** A purchase committed after the month's net is read
     * and before its split would publish a hero whose spent less came back is
     * not its net, and a grid that does not sum to it.
     */
    @Test(timeout = 60_000)
    fun aCommitLandingMidReadIsWhollyInTheReadOrWhollyOut() = runBlocking<Unit> {
        seedSeptember()
        val charts = withContext(Dispatchers.Main) {
            ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to september.toString())))
        }
        val writer = AtomicReference<Thread?>(null)
        charts.midRead = {
            writer.set(alongside { Databases.shared(context).txnDao().insert(txn(1_000L, Direction.EXPENSE, september2)) })
        }

        withContext(Dispatchers.Main) { charts.refresh(today) }.join()
        writer.get()!!.join()

        val read = landed(charts.read.value)
        val net = read.monthTotals.single { it.currency == "MYR" }.netSen
        val split = read.monthSplit.single { it.currency == "MYR" }
        assertEquals(
            "The hero's spent less came back is not its net: a commit landed between the two",
            net,
            split.spentSen - split.cameBackSen,
        )
        assertEquals(
            "The grid's days do not sum to the hero's net: a commit landed between the two",
            net,
            read.dayTotals.filter { it.currency == "MYR" }.sumOf { it.netSen },
        )
    }

    /**
     * **The same for the `Months` sheet.** A captured purchase on a new day,
     * committed after the totals are read and before the captured days, would
     * mark the day captured while the month's total leaves its money out.
     */
    @Test(timeout = 60_000)
    fun aCommitLandingMidMonthsReadIsWhollyInItOrWhollyOut() = runBlocking<Unit> {
        seedSeptember()
        val charts = withContext(Dispatchers.Main) {
            ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to september.toString())))
        }
        val september3 = september.atDay(3)
        val writer = AtomicReference<Thread?>(null)
        charts.midRead = {
            writer.set(
                alongside {
                    val db = Databases.shared(context)
                    db.runInTransaction {
                        db.captureDayDao().recordListenerBound(LocalDates.of(september3), bound = true)
                        db.txnDao().insert(txn(2_000L, Direction.EXPENSE, LocalDates.of(september3)))
                    }
                },
            )
        }

        withContext(Dispatchers.Main) { charts.readMonths(today) }.join()
        writer.get()!!.join()

        val read = charts.months.value
        assertNotNull("The months read published nothing", read)
        val septemberNet = read!!.totals.single { it.month == 202609 && it.currency == "MYR" }.netSen
        assertEquals(
            "3 September's capture and its money are from different moments: " +
                "captured=${september3 in read.captured}, net=$septemberNet",
            september3 in read.captured,
            septemberNet == -10_000L,
        )
    }

    /**
     * Starts [block] on another thread and gives it [GRACE_MS] to finish. A
     * read holding its transaction keeps it waiting past that; a read without
     * one lets it commit inside the grace.
     */
    private fun alongside(block: () -> Unit): Thread = thread { block() }.also { it.join(GRACE_MS) }

    private val september2 get() = LocalDates.of(september.atDay(2))

    private fun landed(read: ChartsRead?): ChartsRead {
        assertNotNull("The read published nothing", read)
        return read!!
    }

    /** Two captured days and a purchase, a larger refund and a pending row in September; a hand-entered purchase in August. */
    private fun seedSeptember() {
        val db = Databases.shared(context)
        listOf(1, 2).forEach {
            db.captureDayDao().recordListenerBound(LocalDates.of(september.atDay(it)), bound = true)
        }
        val day = LocalDates.of(september.atDay(2))
        db.txnDao().insert(txn(4_500L, Direction.EXPENSE, day))
        db.txnDao().insert(txn(16_500L, Direction.REFUND, day))
        // Awaiting review, so in no total and on the "not in the total" block (#89).
        db.txnDao().insert(
            txn(900L, Direction.EXPENSE, day).copy(state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW),
        )
        // August, for the comparison's read of the month before (#94).
        db.txnDao().insert(txn(7_000L, Direction.EXPENSE, LocalDates.of(september.minusMonths(1).atEndOfMonth())))
    }

    private fun txn(amountSen: Long, direction: Direction, day: my.pinged.data.LocalDate) = Txn(
        rawCaptureId = null,
        amountSen = amountSen,
        direction = direction,
        occurredAt = 1_000L,
        localDate = day,
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

    /** Another caller meets code 26 on the shared instance, replaces it, and is done. */
    private suspend fun Context.replaceFromAnotherCaller() {
        CaptureStorage.guarded(this, "another read", { Unit }, damageStopsCapture = false) {
            Databases.replacePoisoned(Databases.shared(this))
        }
    }

    private fun Context.discardTheDatabase() {
        Databases.reset()
        deleteDatabase(DatabaseFactory.NAME)
    }

    private fun assertWritable() {
        val opened = runCatching {
            DatabaseFactory.build(context).let { own ->
                try { own.captureSourceDao().setEnabled("my.pinged.test.bank", true) } finally { own.close() }
            }
        }
        assertNull("pinged.db would not open and take a write: ${opened.exceptionOrNull()}", opened.exceptionOrNull())
    }

    private companion object {
        /** Far past an unblocked insert, which commits in milliseconds. */
        const val GRACE_MS = 500L
    }
}
