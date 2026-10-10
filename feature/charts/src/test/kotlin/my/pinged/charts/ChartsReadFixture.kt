package my.pinged.charts

import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.DayTotal
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.MonthSplit
import my.pinged.data.dao.NotInTotal
import java.time.LocalDate
import java.time.YearMonth

/**
 * A [ChartsRead] for a test, every field named here and defaulted to nothing:
 * a test names only what its section reads, and a new field is added once,
 * here, rather than to every test's own helper. `ChartsRead` itself has no
 * defaults, so the production read cannot forget one.
 */
internal fun chartsRead(
    month: YearMonth,
    today: LocalDate,
    historyStart: LocalDate?,
    captured: Set<LocalDate> = emptySet(),
    monthTotals: List<CurrencyTotal> = emptyList(),
    monthSplit: List<MonthSplit> = emptyList(),
    previousMonthTotals: List<CurrencyTotal> = emptyList(),
    notInTotal: List<NotInTotal> = emptyList(),
    byCategory: List<CategoryTotal> = emptyList(),
    categoryNames: Map<Long, String> = emptyMap(),
    uncategorizedId: Long? = null,
    capturedSinceStart: Set<LocalDate> = emptySet(),
    merchantTotals: List<MerchantTotal> = emptyList(),
    dayTotals: List<DayTotal> = emptyList(),
) = ChartsRead(
    month = month,
    today = today,
    historyStart = historyStart,
    captured = captured,
    monthTotals = monthTotals,
    monthSplit = monthSplit,
    previousMonthTotals = previousMonthTotals,
    notInTotal = notInTotal,
    byCategory = byCategory,
    categoryNames = categoryNames,
    uncategorizedId = uncategorizedId,
    capturedSinceStart = capturedSinceStart,
    merchantTotals = merchantTotals,
    dayTotals = dayTotals,
)
