package my.pinged.ledger.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ledger.FreshUiDispatcher
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.monthName
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import my.pinged.ui.theme.WAITING_FOR_A_CHECK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Spending while its first read is in flight (#280): `design/LoadingSpending`
 * and `LoadingWaiting`, through `ReadingLayout`.
 *
 * The read is held back by handing the screen a feed whose refresh is still
 * `Loading`, and the main clock does not advance on its own, so "before
 * 200ms" is a time this test chose rather than one the machine happened to
 * reach.
 *
 * A page landing after `setContent` is presented on `AndroidUiDispatcher.Main`,
 * which an earlier test in the JVM can leave unable to run anything; see
 * [FreshUiDispatcher].
 */
@RunWith(AndroidJUnit4::class)
class SpendingNotReadYetTest {
    @get:Rule val freshUiDispatcher = FreshUiDispatcher()

    @get:Rule val compose = createComposeRule()

    private val feed = MutableStateFlow(page(LoadState.Loading))

    private fun page(refresh: LoadState, items: List<LedgerItem> = emptyList()): PagingData<LedgerItem> = PagingData.from(
        items,
        LoadStates(refresh = refresh, prepend = LoadState.NotLoading(true), append = LoadState.NotLoading(true)),
    )

    private val unavailable = MutableStateFlow(false)

    private fun draw(heldBy: HeldBy? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    LedgerScreenContent(
                        items = feed.collectAsLazyPagingItems(),
                        read = LedgerRead(summary = null, categories = emptyList(), uncategorizedId = null),
                        storageUnavailable = unavailable.collectAsState().value,
                        onAssign = { _, _, _ -> },
                        onOpenSettings = {},
                    )
                }
            }
        }
    }

    private val clock = HeldClock(compose)

    private fun at(millis: Long) {
        clock.at(millis)
    }

    private fun texts(): List<String> = clock.texts()

    private fun count(text: String): Int = clock.count(text)

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, count(READING))
        assertEquals("The hero's label was drawn before 200ms", 0, count("SPENT SO FAR"))

        at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, count(READING))
        assertEquals("The hero's label, known without a read, was not drawn", 1, count("SPENT SO FAR"))
        assertTrue(
            "The layout drew no em-dash where a value goes: ${texts()}",
            texts().count { it.contains(NOT_READ_YET) } >= 4,
        )
        val month = monthName(YearMonth.now())
        val figures = texts().filter { it != month && it.any(Char::isDigit) }
        assertEquals("A read in flight drew a figure, an amount or a count", emptyList<String>(), figures)
    }

    /**
     * The rows have landed and the month's aggregate has not: the hero alone
     * is not read yet, and says so the same way rather than as a bare
     * `READING`.
     */
    @Test fun rowsWithTheTotalNotReadYetDrawTheHerosDashAfterTheDelay() {
        feed.value = page(LoadState.NotLoading(endOfPaginationReached = true), listOf(LedgerItem.Row(ledgerTxn(1_250L, id = 7L))))
        draw(heldBy = HeldBy.Export)

        at(READING_DELAY_MILLIS - 40)
        assertEquals("The hero's dash was drawn before 200ms", 0, count(HERO_NOT_READ_YET))

        at(READING_DELAY_MILLIS + 40)
        assertEquals("The total not read yet was not drawn as the hero's dash", 1, count(HERO_NOT_READ_YET))
        assertEquals("The hero's status line did not name the export", 1, count(WAITING_FOR_AN_EXPORT))
    }

    /**
     * The rows land after the layout has shown and before the total: the
     * hero's dash, already drawn, stays drawn. One delay per read, not one
     * per branch the read passes through.
     */
    @Test fun rowsLandingAfterTheDelayKeepTheHerosDashDrawn() {
        draw()
        at(READING_DELAY_MILLIS + 40)
        assertEquals("The layout's hero dash was not drawn", 1, count(HERO_NOT_READ_YET))

        feed.value = page(LoadState.NotLoading(endOfPaginationReached = true), listOf(LedgerItem.Row(ledgerTxn(1_250L, id = 7L))))
        // Paging hands the page over on its own dispatcher: step until it lands.
        val landBy = clock.now + 30 * HeldClock.FRAME
        while (clock.tagged(MONTH_SUMMARY) == 0 && clock.now < landBy) at(clock.now + HeldClock.FRAME)
        assertEquals("The landed rows were never drawn", 1, clock.tagged(MONTH_SUMMARY))

        val gone = mutableListOf<Long>()
        val until = clock.now + READING_DELAY_MILLIS + 40
        while (clock.now < until) {
            if (count(HERO_NOT_READ_YET) != 1) gone += clock.now
            at(clock.now + HeldClock.FRAME)
        }
        assertEquals("The hero's dash went away when the rows landed, at these ms", emptyList<Long>(), gone)
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, count(READING))
        assertEquals("An export was blamed on the integrity check", 0, count(WAITING_FOR_A_CHECK))
    }

    @Test fun anIntegrityCheckHoldingTheDatabaseIsNamedAndNotBlamedOnAnExport() {
        draw(heldBy = HeldBy.IntegrityCheck)
        at(READING_DELAY_MILLIS + 40)

        assertEquals("An integrity check holding the database was not named", 1, count(WAITING_FOR_A_CHECK))
        assertEquals("The background check was blamed on an export", 0, count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the check's wording", 0, count(READING))
    }

    @Test fun anEmptyLedgerLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        at(48)

        feed.value = page(LoadState.NotLoading(endOfPaginationReached = true))
        at(READING_DELAY_MILLIS - 40)

        assertEquals("An empty ledger that landed at 48ms waited for the delay", 1, count("NOTHING CAPTURED YET"))
    }

    @Test fun anUnreadableLedgerLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        at(48)

        unavailable.value = true
        at(READING_DELAY_MILLIS - 40)

        assertEquals("An unreadable ledger found at 48ms waited for the delay", 1, count(CANNOT_READ_YOUR_DATA))
    }
}
