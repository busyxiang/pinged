package my.pinged.charts

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What lands on screen, and in what order, when Charts reads again while a
 * read is still running (#297): the sequence of answers the screen is given,
 * not only the one it ends on.
 *
 * A read of the ledger holds Room's one connection, so a second read cannot
 * run inside the first. These tests hold the first inside its transaction
 * and ask for the second meanwhile, which is the arrangement an unordered
 * holder gets wrong when the older answer is published last.
 */
@RunWith(AndroidJUnit4::class)
class ReadOrderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    private val august = YearMonth.of(2026, 8)
    private val september = YearMonth.of(2026, 9)

    /** Two days of September, which tell one read from the other by `ChartsRead.today`. */
    private val earlier = LocalDate.of(2026, 9, 10)
    private val later = LocalDate.of(2026, 9, 11)

    private val collecting = CoroutineScope(Dispatchers.Unconfined)

    @Before fun startClean() = runBlocking<Unit> {
        IntegrityStore.forget(context)
        discardTheDatabase()
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        collecting.cancel()
        discardTheDatabase()
        IntegrityStore.forget(context)
    }

    /**
     * Read A of September is held in its transaction and read B of the same
     * month is asked for after it. A, the older read, is the first answer the
     * screen is given and B, the later, the last: never B and then A, which
     * would put older figures back over newer ones.
     */
    @Test(timeout = 60_000)
    fun twoReadsOfOneMonthPublishInTheOrderTheyWereAskedFor() = runBlocking<Unit> {
        seed()
        val charts = chartsOn(september)
        val published = publications(charts)
        val (started, release) = holdTheNextRead(charts)

        val a = withContext(Dispatchers.Main) { charts.read.refresh() }
        started.await()
        now = later
        val b = withContext(Dispatchers.Main) { charts.read.refresh() }
        release.countDown()
        a.join()
        b.join()

        assertEquals(
            "The reads did not publish A then B: the sequence of `today`s the screen was given",
            listOf(earlier, later),
            published.map { it.today },
        )
    }

    /**
     * Read A of September is held and August is picked meanwhile. A answers
     * a question that has changed, so the screen is never given September:
     * it would show the month the user left, and a screen that draws a
     * dimmed last month would draw it undimmed.
     */
    @Test(timeout = 60_000)
    fun aReadOfAMonthLeftBehindIsNeverPublished() = runBlocking<Unit> {
        seed()
        val charts = chartsOn(september)
        val published = publications(charts)
        val (started, release) = holdTheNextRead(charts)

        val a = withContext(Dispatchers.Main) { charts.read.refresh() }
        started.await()
        now = later
        val b = withContext(Dispatchers.Main) { charts.select(august) }
        release.countDown()
        a.join()
        b.join()

        assertEquals(
            "September, the month left behind, was published",
            listOf(august),
            published.map { it.month },
        )
    }

    /** What `today` is for the read that starts next: the test moves it between the reads. */
    @Volatile private var now = earlier

    private suspend fun chartsOn(month: YearMonth): ChartsViewModel = withContext(Dispatchers.Main) {
        ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to month.toString())), today = { now })
    }

    /** Every read [charts] publishes, in order, collected as it is set. */
    private fun publications(charts: ChartsViewModel): CopyOnWriteArrayList<ChartsRead> {
        val seen = CopyOnWriteArrayList<ChartsRead>()
        val collected = CountDownLatch(1)
        collecting.launch {
            collected.countDown()
            // Each answer once: the state also moves when nothing was published.
            charts.read.state.collect { held -> held.answer?.let { if (seen.lastOrNull() !== it) seen.add(it) } }
        }
        collected.await()
        return seen
    }

    /**
     * Holds [charts]' next read inside its transaction: the first latch says it
     * has begun, with its `today` already taken, and the second releases it.
     */
    private fun holdTheNextRead(charts: ChartsViewModel): Pair<CountDownLatch, CountDownLatch> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = AtomicBoolean(false)
        charts.midRead = {
            if (held.compareAndSet(false, true)) {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        return started to release
    }

    private fun seed() {
        val db = Databases.shared(context)
        db.captureDayDao().recordListenerBound(LocalDates.of(august.atDay(30)), bound = true)
        db.captureDayDao().recordListenerBound(LocalDates.of(september.atDay(2)), bound = true)
        db.txnDao().insert(txn(12_345L, august.atDay(30)))
        db.txnDao().insert(txn(5_000L, september.atDay(2)))
    }

    private fun txn(amountSen: Long, day: LocalDate) = Txn(
        // Captured, as its source says: a null id is a manual entry (spec 4).
        // The id names no capture row; the column has no foreign key.
        rawCaptureId = captureIds++,
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
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }
}
