package my.pinged.data.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import my.pinged.data.LocalDate
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.requireStorable
import my.pinged.parse.Direction

/**
 * Before adding a query that filters `local_date` or `state`, read
 * `QueryPlanTest`. It pins the plan of every ledger query below, reading the
 * SQL out of this interface's companion rather than holding a copy, so the
 * plan it asserts is the plan the app issues. [feed] is the exception, because
 * Room wraps a `PagingSource` query in `LIMIT`/`OFFSET` and `COUNT(*)`
 * subqueries before preparing it; both wrapped forms take the same index as
 * the bare one, measured in `QueryPlanTest.theFeedTakesTheDayOrderedIndex`.
 *
 * The one that matters is the unary `+state` hint on the three aggregates.
 * Without it SQLite prefers `index_txn_state_occurred_at`, because an equality
 * outranks a range when the planner has no statistics saying otherwise, and
 * each aggregate then reads every `COMMITTED` transaction ever captured to
 * answer for one month. Measured against the frozen v1 DDL in sqlite3 3.53.4
 * with 5000 rows over six months -- 4750 `COMMITTED`, 819 in the month asked
 * for -- and **no `ANALYZE`**, which is the only condition this app runs in:
 * nothing in it calls `ANALYZE` or `PRAGMA optimize`, and
 * `QueryPlanTest.analyzeFixesTheNaivePlanButOnlyOnceTheConnectionReloads`
 * records why neither is added.
 *
 *     as written   SEARCH txn USING INDEX index_txn_state_occurred_at (state=?)
 *     with +state  SEARCH txn USING INDEX index_txn_local_date_occurred_at
 *                         (local_date>? AND local_date<?)
 *
 * 4750 index entries walked against 819, three times per foreground and again
 * on every category tap: 0.388ms against 0.124ms per aggregate at 5000 rows,
 * and the ratio grows with the table. The hint is SQLite's documented unary
 * plus, which makes that one term unusable as an index seek without changing
 * its meaning -- narrower than `INDEXED BY`, which would forbid the planner
 * from ever doing better and hard-code a Room-generated index name into
 * hand-written SQL.
 */
@Dao
interface TxnDao {
    /**
     * The transaction write path. Checks spec 7.1's `state`/`pending_reason`
     * invariant in both directions before the row reaches SQLite, because Room
     * cannot declare a CHECK constraint -- see
     * [my.pinged.data.entity.requireStorable].
     *
     * Every caller goes through here rather than [insertRow], so a write path added
     * later inherits the check by using the DAO at all.
     */
    fun insert(txn: Txn): Long {
        txn.requireStorable()
        return insertRow(txn)
    }

    /**
     * The batch form of [insert], checking every row before any reaches SQLite.
     * Room's `@Insert(List)` is the same statement count as a loop but acquires
     * the prepared statement once: 997ms against 231ms for 20,000 rows in one
     * transaction.
     */
    fun insertAll(txns: List<Txn>) {
        txns.forEach { it.requireStorable() }
        insertAllRows(txns)
    }

    /**
     * The unchecked primitive behind [insert], not part of the intended API
     * surface: calling it directly is how `PendingReasonTest` proves the guard
     * above is doing the work.
     *
     * Default `OnConflictStrategy.ABORT`. `txn.raw_capture_id` carries a unique
     * index, so a second insert for a capture that already produced a
     * transaction throws instead of double-counting the money -- which is what
     * makes stage two idempotent under retries and process death. REPLACE or
     * IGNORE would both hide the bug.
     */
    @Insert
    fun insertRow(txn: Txn): Long

    /** [insertRow]'s batch form, behind [insertAll]. Same ABORT reasoning. */
    @Insert
    fun insertAllRows(txns: List<Txn>)

    @Query("SELECT COUNT(*) FROM txn")
    fun countAll(): Int

    /**
     * How many calendar months the ledger touches, for the delete sheet's
     * `Months of history` row (spec 11.3) and nothing else.
     *
     * **`local_date / 100`, and not `substr(local_date, 1, 7)`.** `local_date`
     * is a [my.pinged.data.LocalDate], packed as the integer `yyyymmdd`
     * (spec 15.7) and stored with INTEGER affinity -- not the `YYYY-MM-DD`
     * text a `substr` reading of it assumes. `substr` would coerce the number
     * to eight digits and keep seven of them, which groups by *ten-day block*
     * rather than by month: measured on emulator-5554 against 5,000 rows over
     * 180 consecutive days, the `substr` form answered 24 where this one
     * answers 7. Integer division truncates `20260930` to `202609` exactly,
     * with no text conversion in the loop.
     *
     * `REJECTED` is excluded for the same reason [feed] excludes it -- a
     * rejected capture is not a transaction, and a month holding nothing else
     * is not a month of history.
     *
     * **Nothing indexes this and nothing can.** `state != 'REJECTED'` is a
     * negative condition SQLite cannot range-scan, and no v1 index carries
     * `local_date` and `state` together; the schema is frozen, so adding one
     * is not available. It reads the table itself and sorts the groups --
     * `SCAN txn` plus `USE TEMP B-TREE FOR count(DISTINCT)` -- at 0.354ms over
     * 5,000 transactions on emulator-5554. Pinned in
     * `QueryPlanTest.theTwoDeleteSheetCountsScanAndAreAcceptedAtThat`, because
     * no behavioural test can see a plan; see `SettingsViewModel.wipeCounts`
     * for the budget that number is spent from.
     */
    @Query(MONTH_COUNT_SQL)
    fun distinctMonthCount(): Int

    /**
     * How many transactions the delete sheet says it is about to destroy
     * (spec 11.3).
     *
     * **`REJECTED` is excluded, so this disagrees with [countAll] on
     * purpose.** Those rows are physically destroyed too, so counting them
     * would be defensible in isolation -- but [feed] hides them from every
     * screen the user has ever looked at, so a sheet that counted them would
     * be the one number in the app that reconciles against nothing. The
     * surrounding copy already says everything goes; under-itemising a row
     * the user was never shown costs nothing that sentence does not cover.
     *
     * Nothing here needs a column outside `index_txn_state_occurred_at`, so
     * unlike [distinctMonthCount] this walks that index as a covering one and
     * never touches the table: 0.195ms over 5,000 transactions on
     * emulator-5554. Pinned beside the month count's plan, which shares its
     * predicate and not its cost.
     */
    @Query(UNREJECTED_COUNT_SQL)
    fun countUnrejected(): Int

    /**
     * Duplicate detection, **layer 2** of spec 7.2: one card swipe firing both the
     * banking app and a separate card-alert app. Identical `amount_sen`, within 10
     * minutes, **different** `source_package`. The caller passes
     * `occurredAt -/+ DuplicateWindows.LAYER_TWO_MILLIS` and applies the
     * merchant-token-overlap and `is_authoritative` parts to what comes back.
     *
     * Different from layer 1 and must stay so: layer 1 is the same notification
     * seen twice and produces no transaction, layer 2 is a real second transaction
     * flagged `DUPLICATE_SUSPECT` for the review inbox.
     *
     * `BETWEEN` is inclusive at both ends, the intended reading of "within 10
     * minutes". A NULL `source_package` (a manual entry, spec 4) stays a candidate:
     * it is by definition not the capturing package.
     *
     * **`direction` is matched, which spec 7.2's clause list does not say.** Every
     * clause there is a test of sameness, and direction went unmentioned because a
     * pair arising that way trivially shares it. Without the clause a refund
     * reversing a payment is a textbook match -- identical amount by construction,
     * inside ten minutes if the till voids the sale, different package if the
     * card-alert app posts the reversal, same shop -- and the **Merge** the inbox
     * would offer deletes the reversal, leaving a payment the user got their money
     * back for. A refund still pairs with a second copy of the same refund, which
     * is the case layer 2 is actually for.
     *
     * Served by `txn(amount_sen, occurred_at)` (spec 15.1); without it this is a
     * full scan of `txn` on every capture.
     */
    @Query(
        "SELECT * FROM txn WHERE amount_sen = :amountSen " +
            "AND occurred_at BETWEEN :from AND :to " +
            "AND direction = :direction " +
            "AND (source_package IS NULL OR source_package != :excludePackage) " +
            "AND state != :excludeState"
    )
    fun findDuplicateSuspects(
        amountSen: Long,
        from: Long,
        to: Long,
        direction: Direction,
        excludePackage: String,
        // A bound parameter rather than a 'REJECTED' literal, so renaming the
        // enum constant fails to compile instead of matching nothing.
        excludeState: TxnState = TxnState.REJECTED,
    ): List<Txn>

    /**
     * The newest transactions by `occurred_at`, **for tests only**: `DaoTest`,
     * `CategoryDeleteTest` and `CommitAtomicityTest` read a small fixture back
     * in one call, and there is no production caller. Not the query to copy
     * for a new screen -- a list a user scrolls wants [feed]'s day-major order
     * and its `PagingSource`, and a test after one known row wants [byId].
     *
     * Served by `txn(occurred_at)`, which exists for this query and nothing
     * else: measured on emulator-5554 before that index was added, this
     * planned as `SCAN txn` plus `USE TEMP B-TREE FOR ORDER BY` -- the whole
     * table read and sorted. Every other index carrying `occurred_at` has it
     * behind a leading equality column, so none of them can order an
     * unfiltered query. The index therefore costs a B-tree per insert for a
     * query only tests make; dropping it is a schema change and the schema is
     * frozen (see "Schema v1 is frozen" in the module's CLAUDE.md).
     */
    @Query("SELECT * FROM txn ORDER BY occurred_at DESC LIMIT :limit")
    fun recent(limit: Int): List<Txn>

    /** One transaction by primary key. */
    @Query("SELECT * FROM txn WHERE id = :id")
    fun byId(id: Long): Txn?

    /**
     * The export cursor, keyset on `id` rather than `OFFSET`, for the
     * reason `RawCaptureDao.pageAfter` measures: `OFFSET` is implemented by
     * counting and discarding rows, and an export walks the whole table.
     * Served by the integer primary key.
     */
    @Query("SELECT * FROM txn WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    fun pageFrom(afterId: Long, limit: Int): List<Txn>

    /**
     * [pageFrom] starting **at** [fromId]. Salvage's read: why it is inclusive
     * is on `RawCaptureDao.pageStartingAt`.
     */
    @Query("SELECT * FROM txn WHERE id >= :fromId ORDER BY id ASC LIMIT :limit")
    fun pageStartingAt(fromId: Long, limit: Int): List<Txn>

    /**
     * Every id, off `txn(raw_capture_id)` and not the table's pages. SQLite
     * indexes a NULL like any other value, so a transaction with no capture is
     * listed too.
     */
    @Query("SELECT id FROM txn INDEXED BY index_txn_raw_capture_id")
    fun idsFromRawCaptureIndex(): List<Long>

    /** [idsFromRawCaptureIndex] off a second index. */
    @Query("SELECT id FROM txn INDEXED BY index_txn_occurred_at")
    fun idsFromOccurredAtIndex(): List<Long>

    /** The highest id ever assigned, from `sqlite_sequence`; null if none. */
    @Query("SELECT seq FROM sqlite_sequence WHERE name = 'txn'")
    fun highestIdEver(): Long?

    /**
     * How many transactions point their `raw_capture_id` at a capture that is not
     * in `raw_capture`.
     *
     * That column carries a **unique index and no foreign key**, so SQLite accepts
     * a transaction whose capture does not exist. In the app nothing can produce
     * one -- `commitCapture` is handed the id of the row it just claimed -- but an
     * import can, and a transaction pointing at a missing capture is money with no
     * evidence behind it, which is what spec 4 keeps raw captures forever to
     * prevent.
     *
     * So this runs inside `ImportJson`'s transaction and a non-zero answer rolls
     * the import back. Not a query the app makes otherwise.
     */
    @Query(
        "SELECT COUNT(*) FROM txn t WHERE t.raw_capture_id IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM raw_capture r WHERE r.id = t.raw_capture_id)"
    )
    fun danglingRawCaptureIdCount(): Int

    /**
     * Net spending per day, for the list's day headers.
     *
     * Filtered by [COUNTED], §8's "excluded and pending rows never enter a
     * total", shared with [monthTotals] and [monthByCategory].
     *
     * Signed, so a `REFUND` reduces its day. Adding it would report a refunded
     * purchase as twice the spending.
     *
     * Grouped by `currency` as well as `local_date` (§4.5 of the milestone
     * design): the pack emits only MYR today, so a bare `SUM` would be correct
     * now and silently wrong at the first non-MYR rule.
     *
     * Served by `index_txn_local_date_occurred_at`, which is what the `+state`
     * hint is for. See this interface's own documentation for the measurement,
     * and [DAY_TOTALS_SQL] for the string `QueryPlanTest` pins.
     */
    @Query(DAY_TOTALS_SQL)
    fun dayTotals(from: LocalDate, to: LocalDate): List<DayTotal>

    /** [dayTotals]'s predicate over a whole period, for the pinned summary's headline. */
    @Query(MONTH_TOTALS_SQL)
    fun monthTotals(from: LocalDate, to: LocalDate): List<CurrencyTotal>

    /**
     * The summary's top categories, largest first.
     *
     * `LIMIT` is a parameter rather than the literal 3 §9.1 asks for, so the
     * caller states how many it is going to draw and the query cannot silently
     * disagree with the layout.
     */
    @Query(MONTH_BY_CATEGORY_SQL)
    fun monthByCategory(from: LocalDate, to: LocalDate, limit: Int): List<CategoryTotal>

    /**
     * Assign a category by hand (§9.1's chip, without §6.1's learned rule).
     *
     * A targeted `UPDATE` naming its own columns, for the reason
     * [CaptureSourceDao] has no whole-row upsert: SQLite's `REPLACE` deletes
     * and re-inserts, so a partial write would revert every other column on the
     * row.
     *
     * `user_edited` is set because §5.5's re-parse must not overwrite a
     * decision a person made. Nothing re-parses yet.
     *
     * Returns the rows written, which is 0 when the row is gone. The caller
     * decides what that means -- see `LedgerViewModel.assignCategory`.
     */
    @Query(
        """
        UPDATE txn SET category_id = :categoryId, user_edited = 1, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    fun setCategory(id: Long, categoryId: Long, updatedAt: Long): Int

    /**
     * The home list (§9.1), newest day first and newest within a day.
     *
     * A `PagingSource` and not a `Flow<List<Txn>>` (§15.4): the flow re-emits
     * every row on every insert, and this app inserts from a background worker
     * while the screen is open. Room invalidates this source instead, so a
     * captured payment appears without re-reading the list.
     *
     * **`local_date` leads, and that is a correctness clause rather than a
     * preference.** The list is sectioned by day, and `local_date` is not a
     * function of `occurred_at`: it is the day the money moved in the zone it
     * moved in (§15.7), so an imported row keeps the exporting device's day
     * and a device that flies west writes a day it has already passed. Ordered
     * by `occurred_at` alone the day sequence is not monotone -- X, Y, X -- and
     * a day-sectioned list then draws two headings for one date, each looking
     * that date's subtotal up and printing the whole day's number twice.
     *
     * **`id DESC` is the paging tiebreaker.** Two captures inside the same
     * second -- a payment and the bank's own confirmation of it -- are
     * otherwise ordered arbitrarily, and under paging an arbitrary order is an
     * unstable one: the same row can land on two pages or on none. The clause
     * looks redundant, because the index below is non-unique and its B-tree
     * entries carry `id` (a rowid alias) to break ties, so a reverse scan
     * already returns descending `id` within a tied key without it -- but that
     * is a property of the current plan, not a guarantee, and a
     * table-scan-plus-sort plan ties the wrong way, ascending `id`. Covered by
     * `LedgerFeedTest.rowsSharingASecondHaveAStableOrder`.
     *
     * `REJECTED` is excluded because a rejected capture is not a transaction.
     * `PENDING` and excluded rows are **included** -- they are kept out of every
     * total (§8) and still drawn, because until §9.2's review inbox exists a
     * hidden `PENDING` row is money on disk and nowhere on screen.
     *
     * Served by `index_txn_local_date_occurred_at`, which supplies the whole
     * `ORDER BY` as a reverse scan with no temp b-tree -- measured at 0.443ms
     * against the previous ordering's 0.446ms for a page 4000 rows deep, so
     * the day-major order costs nothing. `state != 'REJECTED'` is a negative
     * condition SQLite cannot range-scan, so it is filtered after the scan
     * either way. Pinned by `QueryPlanTest.theFeedTakesTheDayOrderedIndex`.
     */
    @Query(FEED_SQL)
    fun feed(): PagingSource<Int, Txn>

    /**
     * The ledger's SQL, hoisted so `QueryPlanTest` pins the plan of the string
     * the app issues instead of the plan of a copy of it -- see this
     * interface's own documentation.
     *
     * Concatenated `const val`s and not an interpolation or a function: Room
     * reads `@Query` at compile time, so only what the Kotlin compiler folds
     * into the annotation reaches it.
     */
    companion object {
        const val FEED_SQL =
            """
            SELECT * FROM txn WHERE state != 'REJECTED'
            ORDER BY local_date DESC, occurred_at DESC, id DESC
            """

        /**
         * The rows §8 counts -- "excluded and pending rows never enter a total"
         * -- and the `+state` hint that decides how they are reached.
         *
         * One string rather than three copies because of the hint, not the
         * predicate: the two clauses are covered behaviourally, by
         * `LedgerAggregateTest` exercising each half and the `currency`
         * grouping against every query they apply to, but no behavioural test
         * can see a plan, so a fourth aggregate copied from here without the
         * `+` reads every `COMMITTED` row ever captured while the suite stays
         * green. A `@DatabaseView` would have shared the predicate too and is
         * ruled out on its own terms: it would move the schema's identity hash
         * (see "Schema v1 is frozen" in the module's CLAUDE.md).
         */
        const val COUNTED = "+state = 'COMMITTED' AND is_excluded = 0"

        /**
         * [distinctMonthCount]'s query, hoisted for the same reason the
         * aggregates above are: no behavioural test can see a plan, and this
         * one reads the `txn` table itself at a cost its KDoc asserts.
         * `QueryPlanTest` reads it from here rather than from a copy.
         */
        const val MONTH_COUNT_SQL =
            "SELECT COUNT(DISTINCT local_date / 100) FROM txn WHERE state != 'REJECTED'"

        /**
         * [countUnrejected]'s query, hoisted for the same reason. It shares
         * the predicate above and not its plan: needing no column outside
         * `index_txn_state_occurred_at`, it is answered from that index as a
         * covering one and never touches the table, which is what
         * `QueryPlanTest` pins by reading both strings from here.
         */
        const val UNREJECTED_COUNT_SQL = "SELECT COUNT(*) FROM txn WHERE state != 'REJECTED'"

        const val DAY_TOTALS_SQL =
            """
            SELECT local_date AS localDate, currency,
                   SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
            FROM txn
            WHERE """ + COUNTED + """
              AND local_date BETWEEN :from AND :to
            GROUP BY local_date, currency
            """

        const val MONTH_TOTALS_SQL =
            """
            SELECT currency,
                   SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
            FROM txn
            WHERE """ + COUNTED + """
              AND local_date BETWEEN :from AND :to
            GROUP BY currency
            """

        const val MONTH_BY_CATEGORY_SQL =
            """
            SELECT category_id AS categoryId, currency,
                   SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
            FROM txn
            WHERE """ + COUNTED + """
              AND local_date BETWEEN :from AND :to
            GROUP BY category_id, currency
            ORDER BY netSen DESC
            LIMIT :limit
            """
    }
}
