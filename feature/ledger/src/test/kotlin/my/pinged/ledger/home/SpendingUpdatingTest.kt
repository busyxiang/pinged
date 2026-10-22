package my.pinged.ledger.home

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.monthName
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.UPDATING
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Spending after a month pick (#285), `design/LoadingSearch`'s treatment:
 * until August's summary lands, September's stays, under `UPDATING` once the
 * re-read has run 200ms; the header names the month picked, and the pinned
 * review row's count of the month is a dash. The 45% dim is a pixel,
 * asserted on a device by `MonthPickerTest`.
 */
@RunWith(AndroidJUnit4::class)
class SpendingUpdatingTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val august = YearMonth.of(2026, 8)

    private val feed = MutableStateFlow(
        PagingData.from(
            listOf<LedgerItem>(LedgerItem.Row(ledgerTxn(1_250L, id = 7L))),
            LoadStates(
                refresh = LoadState.NotLoading(endOfPaginationReached = true),
                prepend = LoadState.NotLoading(true),
                append = LoadState.NotLoading(true),
            ),
        ),
    )
    private val picked = MutableStateFlow(september)
    private val rereading = MutableStateFlow(false)

    private fun draw() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = feed.collectAsLazyPagingItems(),
                    read = LedgerRead(
                        summary = MonthSummary(september, listOf(CurrencyTotal("MYR", 31_240L)), emptyList(), trustworthy = true),
                        awaitingReview = 11,
                        awaitingReviewInMonth = 5,
                    ),
                    storageUnavailable = false,
                    onAssign = { _, _, _ -> },
                    onOpenSettings = {},
                    picked = picked.collectAsState().value,
                    rereading = rereading.collectAsState().value,
                )
            }
        }
        at(HeldClock.FRAME)
    }

    private fun pick() {
        picked.value = august
        rereading.value = true
        clock.restart()
    }

    private val clock = HeldClock(compose) { now -> if (count(UPDATING) > 0) updatingAt += now }
    private val updatingAt = mutableListOf<Long>()

    private fun at(millis: Long) {
        clock.at(millis)
    }

    private fun texts(): List<String> = clock.texts()

    private fun count(text: String): Int = clock.count(text)

    @Test fun aPickedMonthKeepsTheLastSummaryAndSaysUpdatingOnceTheReReadHasRun200ms() {
        draw()
        assertEquals("The settled month drew a status line", 0, count(UPDATING))
        assertEquals("The settled review row lost its month's count", 1, count("11 AWAITING REVIEW · 5 IN SEPTEMBER"))

        pick()
        at(READING_DELAY_MILLIS - 40)
        assertEquals("UPDATING was drawn before 200ms", 0, count(UPDATING))

        at(READING_DELAY_MILLIS + 40)
        assertEquals("No UPDATING once the re-read had run 200ms: ${texts()}", 1, count(UPDATING))
        assertEquals("The re-read went back to READING", 0, count(READING))
        assertEquals("The last month's total was not kept", 1, texts().count { it.startsWith("RM312") })
        assertEquals("The header does not name the month picked: ${texts()}", 1, count(monthName(august)))
        assertEquals(
            "The review row's count of the month, about to change, is not a dash: ${texts()}",
            1,
            count("11 AWAITING REVIEW · $NOT_READ_YET IN AUGUST"),
        )
    }

    @Test fun aReReadThatLandsInside200msNeverSaysUpdating() {
        draw()

        pick()
        at(READING_DELAY_MILLIS - 60)
        rereading.value = false
        at(READING_DELAY_MILLIS * 3)

        assertEquals("A re-read that landed inside 200ms drew UPDATING, at these ms", emptyList<Long>(), updatingAt)
    }
}
