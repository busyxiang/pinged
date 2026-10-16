package my.pinged.ledger

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.TxnDao
import my.pinged.ledger.home.CHOOSE_A_MONTH
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.home.MONTH_BUTTON
import my.pinged.ledger.home.MONTH_CHEVRON
import my.pinged.ledger.home.dayLabel
import my.pinged.ui.monthName
import my.pinged.ui.monthYear
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/**
 * Spending's header picks a month (#192): the label opens the `Months` sheet,
 * and a pick moves the pinned summary and starts the feed at that month.
 *
 * A real holder over a real ledger with a payment this month and one last
 * month, so the summary's read, the feed's bound and the sheet's read are all
 * the production ones. `createAndroidComposeRule` for the resume, which is
 * what re-reads the summary and must re-read the picked month, not today's.
 */
@RunWith(AndroidJUnit4::class)
class MonthPickerTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // One read of the clock, so the month and the day cannot disagree at midnight.
    private val today = java.time.LocalDate.now()
    private val thisMonth = YearMonth.from(today)
    private val lastMonth = thisMonth.minusMonths(1)
    private val lastMonthsDay = lastMonth.atDay(15)
    private lateinit var viewModel: LedgerViewModel
    private lateinit var dao: TxnDao
    private var category = 0L

    @Before fun ledger() {
        dao = context.freshLedger()
        category = Databases.categoryDao(context).all().first().id
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = noonOf(today), categoryId = category))
        dao.insert(ledgerTxn(amountSen = 1_234L, occurredAt = noonOf(lastMonthsDay), categoryId = category))

        viewModel = LedgerViewModel(context.applicationContext as Application, SavedStateHandle())
        compose.setContent { PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSettings = {}) } }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM50.00") > 0 }
    }

    @After fun leaveAnOpenableDatabase() = context.discardTheDatabase()

    @Test fun pickingAMonthMovesTheSummaryAndTheFeed() {
        // Before: today's day heads the feed.
        assertEquals(1, compose.onAllNodesWithTextSafely(dayLabel(LocalDates.of(today))))

        pick(lastMonth)

        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM12.34") > 0 }
        compose.onNodeWithText(monthName(lastMonth)).assertExists()
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely(dayLabel(LocalDates.of(today))) == 0 }
        compose.onNodeWithText(dayLabel(LocalDates.of(lastMonthsDay))).performScrollTo().assertExists()
        assertEquals(
            "The summary still names this month's total after another was picked",
            0,
            compose.onAllNodesWithTextSafely("RM50.00"),
        )
    }

    /** The resume's re-read is the picked month's, or a trip away undoes the pick. */
    @Test fun aPickSurvivesAResume() {
        pick(lastMonth)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM12.34") > 0 }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertEquals(
            "A resume put this month's total back under last month's name",
            0,
            compose.onAllNodesWithTextSafely("RM50.00"),
        )
        compose.onNodeWithText(monthName(lastMonth)).assertExists()
    }

    /** Picking now again is the whole ledger, today's rows first. */
    @Test fun pickingNowComesBack() {
        pick(lastMonth)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM12.34") > 0 }

        pick(thisMonth)

        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM50.00") > 0 }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely(dayLabel(LocalDates.of(today))) == 1 }
    }

    /**
     * A pick starts the list at its top, wherever it was scrolled to: once
     * for a month whose rows replace the ones drawn, and once for a pick back
     * to now, where the old month's header is still in the list and the
     * `LazyColumn` would otherwise follow it down.
     */
    @Test fun aPickStartsTheListAtItsTop() {
        repeat(30) {
            dao.insert(ledgerTxn(amountSen = 100L, occurredAt = noonOf(today) - it - 1, categoryId = category))
            dao.insert(ledgerTxn(amountSen = 100L, occurredAt = noonOf(lastMonthsDay) - it - 1, categoryId = category))
        }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM80.00") > 0 }

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        pick(lastMonth)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM42.34") > 0 }
        waitUntilShown(dayLabel(LocalDates.of(lastMonthsDay)))

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        pick(thisMonth)
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM80.00") > 0 }
        waitUntilShown(dayLabel(LocalDates.of(today)))
    }

    /** The pick is the entry's saved state, so a process death comes back to it. */
    @Test fun aPickIsKeptInSavedState() {
        val handle = SavedStateHandle()
        val app = context.applicationContext as Application
        compose.runOnUiThread { LedgerViewModel(app, handle).pick(lastMonth, today = thisMonth) }
        assertEquals(lastMonth, LedgerViewModel(app, handle).picked.value)
    }

    /**
     * A picked month older than every row has an empty feed, and that is not
     * day one: there is a ledger, only none of it that old. Picked through the
     * holder, since the sheet lists no such month without a history start
     * before the first row.
     */
    @Test fun aMonthBeforeEveryRowIsNotDayOne() {
        val before = thisMonth.minusMonths(3)
        compose.runOnUiThread { viewModel.pick(before) }

        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely(monthName(before)) > 0 }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("NOTHING COUNTED THIS MONTH") > 0 }
        assertEquals(
            "A month with nothing in it drew day one's copy over a ledger that has rows",
            0,
            compose.onAllNodesWithTextSafely("NOTHING CAPTURED YET"),
        )
    }

    /**
     * The board's chevron: 14dp, 6dp after the label, drawn in Muted, and the
     * label and it one button with a 44dp target that TalkBack names.
     */
    @Test fun theLabelIsAButtonWithTheBoardsChevron() {
        val button = compose.onNodeWithTag(MONTH_BUTTON).fetchSemanticsNode()
        assertTrue(
            "The month label is ${button.size.height / compose.density.density}dp tall, under the 44dp target",
            button.size.height / compose.density.density >= 44f - 0.5f,
        )
        assertEquals(CHOOSE_A_MONTH, button.config.getOrNull(SemanticsActions.OnClick)?.label)

        val chevron = compose.onNodeWithTag(MONTH_CHEVRON, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(14f, chevron.size.width / compose.density.density, 0.5f)
        val label = compose.onNodeWithText(monthName(thisMonth), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(6f, (chevron.boundsInRoot.left - label.boundsInRoot.right) / compose.density.density, 0.5f)

        val pixels = compose.onNodeWithTag(MONTH_CHEVRON, useUnmergedTree = true).captureToImage().toPixelMap()
        // The stroke's turn, at (12, 15) of 24.
        val turn = pixels[pixels.width / 2, pixels.height * 15 / 24]
        assertTrue(
            "The chevron's turn is $turn, not Muted",
            abs(turn.red - Muted.red) + abs(turn.green - Muted.green) + abs(turn.blue - Muted.blue) < 0.25f,
        )
    }

    private fun waitUntilShown(text: String) {
        val shown = runCatching {
            compose.waitUntil(TIMEOUT) {
                runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess
            }
        }.isSuccess
        assertTrue("The pick left the list scrolled away from \"$text\" at its top", shown)
    }

    // The header is outside every scroll container, where `performScrollTo`
    // has no parent to scroll and throws; it is always on screen.
    private fun pick(month: YearMonth) {
        compose.onNodeWithTag(MONTH_BUTTON).performClick()
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely(monthYear(month)) > 0 }
        compose.onNodeWithText(monthYear(month)).performScrollTo().performClick()
    }

    private fun noonOf(day: java.time.LocalDate): Long =
        day.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
