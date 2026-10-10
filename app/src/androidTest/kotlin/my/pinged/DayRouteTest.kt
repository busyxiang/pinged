package my.pinged

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.day.DAY_BACK
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * A Charts grid square opens that day's `Day` screen on the Charts tab's own
 * stack, and back -- chevron or system -- returns to Charts on the month that
 * was selected (#69, #90), including after a process death.
 *
 * The month is last month, picked in the `Months` sheet, so "the same month"
 * is not the month Charts opens on by default: a back that rebuilt Charts from
 * nothing would land on this month and fail.
 *
 * `createEmptyComposeRule`, for the reason `ExportNudgeBannerTest` gives: the
 * rows have to be in the database before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class DayRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val today = LocalDate.now()
    private val now = YearMonth.from(today)
    private val previous = now.minusMonths(1)
    private val day = previous.atDay(3)

    @Before fun seed() {
        discard()
        val db = Databases.shared(context)
        generateSequence(previous.atDay(1)) { it.plusDays(1) }.takeWhile { it < today }.forEach {
            db.captureDayDao().recordListenerBound(LocalDates.of(it), bound = true)
        }
        db.txnDao().insert(txn(12_345L, day))
        db.txnDao().insert(txn(5_000L, today))
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun tappingADayOpensItAndTheChevronReturnsToTheSameMonth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openTheDayOnLastMonth()

            compose.onNodeWithContentDescription(DAY_BACK).performClick()

            awaitCharts("The chevron did not return to Charts on ${header(previous)}")
        }
    }

    @Test fun theMonthAndTheDaySurviveAProcessDeathAndTheSystemBackReturnsToTheMonth() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheDayOnLastMonth()

            // Every holder dropped, then the Activity rebuilt from its saved
            // state: what comes back is what the saved state carries -- the
            // back stack's `Day` key and Charts' `SavedStateHandle` -- and
            // nothing a holder kept in memory.
            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()

            awaitDay("The Day screen did not come back after the process death")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            awaitCharts("System back after the process death did not return to Charts on ${header(previous)}")
        }
    }

    /** Charts, last month by the `Months` sheet, then the 3rd's square. */
    private fun openTheDayOnLastMonth() {
        compose.onNodeWithText("CHARTS").performClick()
        assertTrue("Charts did not open on ${header(now)}", compose.waitFor { compose.countOf(header(now)) == 1 })
        compose.onNodeWithText(header(now)).performScrollTo().performClick()
        assertTrue("The Months sheet did not open", compose.waitFor { compose.countOf(rowName(previous)) > 0 })
        compose.onNodeWithText(rowName(previous)).performScrollTo().performClick()
        awaitCharts("Charts did not move to ${header(previous)}")

        compose.onNodeWithTag("grid-day-$day").performScrollTo().performClick()

        awaitDay("Tapping the square for $day did not open that day")
    }

    /** The `Day` screen for [day]: its title, its row, and no Charts header. */
    private fun awaitDay(why: String) {
        assertTrue(
            why,
            compose.waitFor {
                compose.countOf(title(day)) == 1 && compose.countOf("123.45") == 1 &&
                    compose.onAllNodesWithContentDescription(DAY_BACK).fetchSemanticsNodes().size == 1
            },
        )
        assertEquals("Charts' header is drawn over the Day screen", 0, compose.countOf(header(previous)))
    }

    /** Charts on [previous]: its header and its hero. */
    private fun awaitCharts(why: String) {
        assertTrue(why, compose.waitFor { compose.countOf(header(previous)) == 1 && compose.countOf("RM123.45") == 1 })
    }

    private fun txn(amountSen: Long, on: LocalDate) = Txn(
        rawCaptureId = null,
        amountSen = amountSen,
        direction = Direction.EXPENSE,
        occurredAt = 1_000L,
        localDate = LocalDates.of(on),
        merchantRaw = "WARUNG PROBE",
        merchantDisplay = "Warung Probe",
        merchantKey = "WARUNG PROBE",
        categoryId = Databases.categoryDao(context).requireUncategorizedId(),
        sourcePackage = "my.com.tngdigital.ewallet",
        sourceLabel = "Touch 'n Go eWallet",
        confidence = Confidence.HIGH,
        state = TxnState.COMMITTED,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun discard() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** `SEPTEMBER 2026`, from the enum's own name rather than the screen's formatter. */
        fun header(month: YearMonth) = "${month.month.name} ${month.year}"

        /** `September 2026`, as the Months sheet's row names it. */
        fun rowName(month: YearMonth) = titled(month.month.name) + " " + month.year

        /** `Thursday 3 September 2026`, the Day screen's title, from the enums' names. */
        fun title(date: LocalDate) =
            titled(date.dayOfWeek.name) + " " + date.dayOfMonth + " " + titled(date.month.name) + " " + date.year

        fun titled(name: String) =
            name.lowercase(Locale.ENGLISH).replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }
}
