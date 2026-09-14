package my.pinged.ledger.home

import my.pinged.data.LocalDate
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.Category
import my.pinged.data.entity.Txn
import java.time.YearMonth

/**
 * A row of the list, or a day header above one.
 *
 * The header carries **no number**. Its subtotal is looked up separately,
 * because the feed and the aggregate are independent flows: a header that
 * rendered a number straight out of the paging transform would show whatever
 * the aggregate last emitted, which on first composition is nothing and after
 * an insert is stale. Absent means no number yet, and never zero.
 */
sealed interface LedgerItem {
    data class Row(val txn: Txn) : LedgerItem

    /**
     * A day heading, identified by the row it sits above as well as by its day.
     *
     * [firstRowId] carries no meaning on screen and exists to make the list key
     * unique **without depending on the feed's `ORDER BY` being right**.
     * `TxnDao.feed` leads on `local_date`, so a date arrives in one piece and
     * no date can draw two headings -- but keyed by the date alone, an ordering
     * that broke that would be `IllegalArgumentException: Key "day-X" was
     * already used`, thrown out of measure rather than out of a coroutine and
     * taking the home screen down. A `Long` per heading is the cheaper side of
     * that trade. `DayHeaderRuleTest` reaches this field directly.
     */
    data class DayHeader(val date: LocalDate, val firstRowId: Long) : LedgerItem
}

/**
 * The day heading that belongs between [before] and [after], or none.
 *
 * The rule `LedgerViewModel.items` hands to `insertSeparators`, a named
 * function rather than a lambda inside that chain so it can be asserted
 * directly: through a composition it is only reachable with a `LazyColumn`
 * under it, and the list's key check answers a wrong separator before any
 * assertion about the separator can run. `DayHeaderRuleTest` asserts it.
 *
 * The rule is "the day changed", not "the day is new", so it needs only the
 * pair `insertSeparators` hands it and stays correct for any ordering.
 * Contiguity is the feed's job (`TxnDao.feed` leads on `local_date`), and a
 * heading per *run* of a date is the honest answer to a feed that did not
 * deliver one.
 *
 * A null [after] is the end of the list, which needs no heading. A null
 * [before] is the top, which always does.
 */
internal fun dayHeaderBetween(before: LedgerItem?, after: LedgerItem?): LedgerItem.DayHeader? {
    val next = (after as? LedgerItem.Row)?.txn ?: return null
    val prev = (before as? LedgerItem.Row)?.txn
    return if (prev == null || prev.localDate != next.localDate) {
        LedgerItem.DayHeader(next.localDate, next.id)
    } else {
        null
    }
}

/**
 * The pinned month summary (§9.1).
 *
 * [trustworthy] is false when the month holds days capture was not alive for
 * (§8), and the total is then greyed and labelled rather than hidden: hiding it
 * loses information, and showing it plainly asserts a number the app cannot
 * stand behind.
 *
 * [month] is carried rather than read from the clock at composition, so the
 * label naming the period cannot disagree with the numbers underneath it --
 * `LedgerViewModel.refresh` takes the month as a parameter, and the aggregate
 * is the only thing that knows which one it summed.
 *
 * **No category names here.** [CategoryTotal] holds a `category_id` and §9.1
 * asks for the name; the only caller that draws this already holds
 * [LedgerRead.categories] keyed by id. A copy would be a second answer to "what
 * is category 7 called", with nothing to keep the two in step.
 */
data class MonthSummary(
    val month: YearMonth,
    val totals: List<CurrencyTotal>,
    val top: List<CategoryTotal>,
    val trustworthy: Boolean,
)

/**
 * Everything one `LedgerViewModel.refresh` read, published in one piece.
 *
 * **The fields must be published together.** The picker's `choices` is
 * [categories] minus the row at [uncategorizedId], so a composition that saw a
 * new list beside an old id would offer an uncategorized row the category it is
 * already in -- and that tap is not a no-op: `setCategory` writes `user_edited`,
 * which §5.5 excludes from the re-parse comparison for good.
 */
data class LedgerRead(
    /** Keyed by the day the feed sections on, so a header can look its own up. */
    val daySubtotals: Map<LocalDate, List<CurrencyTotal>> = emptyMap(),
    /** Null until the first read lands, which is not the same as an empty month. */
    val summary: MonthSummary? = null,
    /**
     * Every category, in `sort_order`. **Not what the picker offers:**
     * `LedgerScreenContent` drops the row's own category before handing the
     * list on, for the reason recorded there (§9.1's chip).
     *
     * Not re-sorted in Kotlin: `CategoryDao.all()` is already
     * `ORDER BY sort_order ASC`, and a second sort would be a rule that could
     * drift from the one the rest of the app reads by.
     */
    val categories: List<Category> = emptyList(),
    /**
     * The id §7.1's gate files an unmatched merchant under, and so the id a row
     * has to carry for it to be one the chip is the right control for.
     *
     * **Nullable, and null means no row is known to be uncategorized.**
     * Resolved through `CategoryDao.uncategorizedIdOrNull`, not
     * `requireUncategorizedId`: that one throws [IllegalStateException], which
     * is outside the `DatabaseUnavailableException` family and so escapes every
     * catch in `LedgerViewModel`.
     *
     * Not `categories.firstOrNull { it.isProtected }`: protected means "cannot
     * be renamed or deleted", and it coincides with uncategorized only by seed
     * accident. Not a name comparison on the screen either -- `CategoryDao` is
     * the single place the name crosses into SQL.
     */
    val uncategorizedId: Long? = null,
)
