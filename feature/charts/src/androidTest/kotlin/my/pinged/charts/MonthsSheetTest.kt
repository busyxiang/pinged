package my.pinged.charts

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Picking a month in the `Months` sheet changes the Charts screen, and the
 * pick survives a process death (#93).
 *
 * The process death is the selected month's only route back: the
 * `SavedStateHandle` written to a `Bundle`, parcelled, and handed to a new
 * view model, as the system hands a restored entry its saved state. The old
 * view model is dropped, so nothing it held in memory can carry the month.
 *
 * Dates are relative to the device's today, which is what the screen reads.
 */
@RunWith(AndroidJUnit4::class)
class MonthsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    private val today = LocalDate.now()
    private val now = YearMonth.from(today)
    private val previous = now.minusMonths(1)

    @Before fun startClean() = runBlocking<Unit> {
        IntegrityStore.forget(context)
        discardTheDatabase()
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        discardTheDatabase()
        IntegrityStore.forget(context)
    }

    @Test(timeout = 120_000)
    fun pickingAMonthShowsItAndItSurvivesAProcessDeath() {
        seed()
        val handle = SavedStateHandle(mapOf(SELECTED_MONTH to now.toString()))
        var viewModel by mutableStateOf(ChartsViewModel(app, handle))
        compose.setContent { ChartsScreen(viewModel) }
        awaitMonth(now, "RM50.00")

        compose.onNodeWithText(header(now)).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) { shows(rowName(previous)) }
        compose.onNodeWithText(rowName(previous)).performScrollTo().performClick()

        awaitMonth(previous, "RM123.45")
        assertEquals("The pick was not kept in the saved state", previous.toString(), handle.get<String>(SELECTED_MONTH))

        val restored = afterProcessDeath(handle)
        compose.runOnIdle { viewModel = ChartsViewModel(app, restored) }

        awaitMonth(previous, "RM123.45")
    }

    /** The header names [month] and the hero prints [figure]. */
    private fun awaitMonth(month: YearMonth, figure: String) {
        val arrived = runCatching { compose.waitUntil(TIMEOUT) { shows(header(month)) && shows(figure) } }
        assertEquals(
            "Charts did not show ${header(month)} with $figure",
            true,
            arrived.isSuccess,
        )
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The saved state as a `Bundle` would carry it across a process death. */
    private fun afterProcessDeath(handle: SavedStateHandle): SavedStateHandle {
        val saved = Bundle().apply { handle.keys().forEach { putString(it, handle.get<String>(it)) } }
        val parcel = Parcel.obtain()
        val bytes = try {
            saved.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        val back = Parcel.obtain()
        try {
            back.unmarshall(bytes, 0, bytes.size)
            back.setDataPosition(0)
            val bundle = back.readBundle(javaClass.classLoader)!!
            return SavedStateHandle(bundle.keySet().associateWith { bundle.getString(it) })
        } finally {
            back.recycle()
        }
    }

    /**
     * The history starts on the 1st of last month and every day since, up to
     * yesterday, is bound, so neither month is greyed. A purchase in each.
     */
    private fun seed() {
        val db = Databases.shared(context)
        generateSequence(previous.atDay(1)) { it.plusDays(1) }.takeWhile { it < today }.forEach {
            db.captureDayDao().recordListenerBound(LocalDates.of(it), bound = true)
        }
        db.txnDao().insert(txn(12_345L, previous.atDay(3)))
        db.txnDao().insert(txn(5_000L, today))
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

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** `OCTOBER 2026`, from the enum's own name rather than the screen's formatter. */
        fun header(month: YearMonth) = "${month.month.name} ${month.year}"

        /** `October 2026`, as the sheet's row names it. */
        fun rowName(month: YearMonth) =
            month.month.name.lowercase(Locale.ENGLISH).replaceFirstChar { it.titlecase(Locale.ENGLISH) } + " " + month.year
    }
}
