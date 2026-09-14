package my.pinged.data.dao

import my.pinged.data.LocalDate

/**
 * One day's net spending, in one currency.
 *
 * Read by the list's day header and never summed from the rows the header sits
 * above: a page boundary can fall mid-day (§9.1), so a subtotal derived from
 * loaded rows is short by whatever is on the next page and short silently.
 */
data class DayTotal(
    val localDate: LocalDate,
    val currency: String,
    val netSen: Long,
)

/** A period total, per currency. Never one row across currencies. */
data class CurrencyTotal(
    val currency: String,
    val netSen: Long,
)

/** A period total for one category, per currency, for the pinned summary's top three. */
data class CategoryTotal(
    val categoryId: Long,
    val currency: String,
    val netSen: Long,
)
