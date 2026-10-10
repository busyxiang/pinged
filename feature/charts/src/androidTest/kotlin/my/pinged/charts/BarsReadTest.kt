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
 * What the Charts read gives the bars and the collecting gate (#91): every
 * category's net with no effective limit, the names to label them, which one
 * is Uncategorized, and the captured days from the history start to yesterday.
 */
@RunWith(AndroidJUnit4::class)
class BarsReadTest {
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
    fun theReadCarriesEveryCategoryAndTheHistoryToYesterday() = runBlocking<Unit> {
        val db = Databases.shared(context)
        val categories = db.categoryDao().all()
        // More categories than the Ledger's top three, so a limit would show.
        val spent = categories.take(8)
        spent.forEachIndexed { i, category ->
            db.txnDao().insert(txn(1_000L * (i + 1), category.id, LocalDates.of(september.atDay(2))))
        }
        val history = listOf(LocalDate.of(2026, 8, 20), september.atDay(1), september.atDay(9), today)
        history.forEach { db.captureDayDao().recordListenerBound(LocalDates.of(it), bound = true) }

        val charts = withContext(Dispatchers.Main) {
            ChartsViewModel(app, SavedStateHandle(mapOf(SELECTED_MONTH to september.toString())))
        }
        withContext(Dispatchers.Main) { charts.refresh(today) }.join()
        val read = charts.read.value
        assertNotNull("The read published nothing", read)
        read!!

        assertEquals(
            "The read limited the categories",
            spent.map { it.id }.toSet(),
            read.byCategory.map { it.categoryId }.toSet(),
        )
        assertEquals(categories.associate { it.id to it.name }, read.categoryNames)
        assertEquals(db.categoryDao().requireUncategorizedId(), read.uncategorizedId)
        assertEquals(
            "The collecting count's days are not the history start to yesterday",
            history.filter { it < today }.toSet(),
            read.capturedSinceStart,
        )
    }

    private fun txn(amountSen: Long, categoryId: Long, day: my.pinged.data.LocalDate) = Txn(
        rawCaptureId = null,
        amountSen = amountSen,
        direction = Direction.EXPENSE,
        occurredAt = 1_000L,
        localDate = day,
        merchantRaw = "WARUNG PAK ALI",
        merchantDisplay = "Warung Pak Ali",
        merchantKey = "WARUNG PAK ALI",
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
