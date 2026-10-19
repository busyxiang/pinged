package my.pinged.ledger

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import my.pinged.data.LocalDate
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.Category
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.MonthSummary
import java.time.YearMonth

// The Spending feed as `LedgerScreenContent` is handed it, for the tests in
// both source sets that draw the screen without a database.

/** [items] as one fully loaded page, so the feed draws them at once. */
internal fun loadedFeed(items: List<LedgerItem>): Flow<PagingData<LedgerItem>> = flowOf(
    PagingData.from(
        items,
        LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true)),
    ),
)

internal val FEED_FOOD = Category(id = 1L, name = "Food & Drinks", iconKey = "food", sortOrder = 1)

internal val FEED_DAY = LocalDate(20_260_930)

/** "Row 1" to "Row [count]" under one day: more than any screen here holds, so the list scrolls. */
internal fun longFeed(count: Long = 24L): List<LedgerItem> {
    val rows = (1L..count).map { ledgerTxn(1_000L * it, localDate = FEED_DAY, categoryId = FEED_FOOD.id, id = it, rawCaptureId = null) }
    return listOf(LedgerItem.DayHeader(FEED_DAY, 1L, listOf(CurrencyTotal("MYR", 30_000L)))) +
        rows.map { LedgerItem.Row(it, displayName = "Row ${it.id}") }
}

/** September 2026's summary, the boards' RM2,847.30 by default. */
internal fun septemberSummary(
    totals: List<CurrencyTotal> = listOf(CurrencyTotal("MYR", 284_730L)),
    trustworthy: Boolean = true,
) = MonthSummary(
    month = YearMonth.of(2026, 9),
    totals = totals,
    top = listOf(CategoryTotal(FEED_FOOD.id, "MYR", 84_250L)),
    trustworthy = trustworthy,
)
