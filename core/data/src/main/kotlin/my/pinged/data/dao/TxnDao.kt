package my.pinged.data.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.DuplicateWindows
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.requireStorable
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason

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
     * frozen (see "A released schema is frozen" in the repository's CLAUDE.md).
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
     * [monthTotals] split into its two halves, for the Charts hero's
     * `SPENT RMx · CAME BACK RMy` under a net of zero or less (#81, #68).
     * Every direction that is not `EXPENSE` counts as came back, as it
     * subtracts in [monthTotals], so the halves always difference to its net.
     */
    @Query(MONTH_SPLIT_SQL)
    fun monthSplit(from: LocalDate, to: LocalDate): List<MonthSplit>

    /**
     * [monthTotals] for every month in `[from, to]` in one statement, for the
     * `Months` sheet (#75): one row per month and currency, so the sheet is
     * this and one `capturedDates` whatever the history's length. A month
     * with nothing counted has no row; the sheet draws it at zero. Measured
     * on emulator-5554 over 5,000 rows across 24 months, the whole span:
     * 2.1ms median of 21.
     *
     * #75 wrote the grouping as `substr(local_date, 1, 7)`, for an ISO text
     * date. The column is `yyyymmdd` as an `INTEGER`, so the month is
     * `local_date / 100`, as [MONTH_COUNT_SQL] already takes it.
     */
    @Query(MONTHLY_TOTALS_SQL)
    fun monthlyTotals(from: LocalDate, to: LocalDate): List<MonthlyTotal>

    /**
     * The money that moved in `[from, to]` without entering its total, per
     * [NotInTotalLine] and currency (#89; #81, The Charts read): the Charts
     * "not in the total" block over a month, and `Day`'s header over one day.
     *
     * **Each row falls on exactly one line.** `PENDING` is tested first, so a
     * pending row that is also excluded counts once, as pending: it is
     * awaiting a decision, and its exclusion is not yet the user's. Every
     * other row read is `COMMITTED` and excluded, split by whether the user
     * excluded it. `REJECTED` is not a transaction and is on no line; a
     * counted row is in the total and is on no line either.
     *
     * Grouped in SQL (spec 15.3). A line with no rows has no result row.
     * Measured on emulator-5554 over a year of 5,000 rows, a tenth of them on
     * each line: 0.29ms for a month and 0.21ms for one day.
     */
    @Query(NOT_IN_TOTAL_SQL)
    fun notInTotal(from: LocalDate, to: LocalDate): List<NotInTotal>

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
     * Assign a category as a one-off (§9.1's chooser with "Always call this"
     * off): this payment only, and no rule. A teaching save is
     * `MerchantRuleDao.teach`, which leaves `user_edited` at 0.
     *
     * A targeted `UPDATE` naming its own columns, for the reason
     * [CaptureSourceDao] has no whole-row upsert: SQLite's `REPLACE` deletes
     * and re-inserts, so a partial write would revert every other column on the
     * row.
     *
     * `user_edited` is set because §5.5's re-parse must not overwrite a
     * decision a person made: `RawCaptureDao.matchedStaleAfter` never
     * returns an edited row.
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
     * Correct manual entry [id] (#105, spec 9.4): write each column group
     * [edit] names, then `updated_at`, and answer whether the row was
     * written. False for a captured row, a row that is gone, a row
     * [id] names that was not created at [createdAt], and an [edit] that
     * names nothing.
     *
     * **[createdAt] tells the entry from a later row given its id.** A
     * delete-everything restarts ids, and a restore brings in another
     * ledger's, so an edit screen opened before either can hold an id that
     * now names a different payment. `created_at` is written once and kept
     * by a restore, so the same payment brought back by one still matches.
     * Checked in this transaction, so nothing moves between the check and
     * the write.
     *
     * **One targeted `UPDATE` per group, naming only its own columns**, for
     * the reason [setCategory] gives: a column the user did not change is
     * not in any statement, so it cannot be reverted by a write built from a
     * stale read. **Every statement's `WHERE` says `raw_capture_id IS
     * NULL`**, and that is the only guard: a captured row is the bank's word
     * (spec 9.4, story 45), and no id handed in can reach one. In one
     * transaction, so the row is never seen half corrected.
     */
    @Transaction
    fun editManualEntry(id: Long, createdAt: Long, edit: ManualEdit, updatedAt: Long): Boolean {
        if (edit == ManualEdit() || byId(id)?.createdAt != createdAt) return false
        edit.amountSen?.let { setManualAmount(id, it) }
        edit.occurredAt?.let { setManualDate(id, it, LocalDates.of(it)) }
        edit.merchant?.let { setManualMerchant(id, it.raw, it.display, it.key) }
        edit.note?.let { setManualNote(id, it.text) }
        edit.filing?.let { setManualFiling(id, it.categoryId, it.userEdited) }
        return touchManualEntry(id, updatedAt) == 1
    }

    /** [editManualEntry]'s amount; call that. */
    @Query("UPDATE txn SET amount_sen = :amountSen WHERE id = :id AND raw_capture_id IS NULL")
    fun setManualAmount(id: Long, amountSen: Long): Int

    /** [editManualEntry]'s date: `occurred_at`, and the `local_date` derived from it as every writer derives it. */
    @Query("UPDATE txn SET occurred_at = :occurredAt, local_date = :localDate WHERE id = :id AND raw_capture_id IS NULL")
    fun setManualDate(id: Long, occurredAt: Long, localDate: LocalDate): Int

    /** [editManualEntry]'s merchant columns; call that. */
    @Query(
        """
        UPDATE txn SET merchant_raw = :raw, merchant_display = :display, merchant_key = :key
        WHERE id = :id AND raw_capture_id IS NULL
        """,
    )
    fun setManualMerchant(id: Long, raw: String?, display: String?, key: String?): Int

    /** [editManualEntry]'s note; call that. */
    @Query("UPDATE txn SET note = :note WHERE id = :id AND raw_capture_id IS NULL")
    fun setManualNote(id: Long, note: String?): Int

    /** [editManualEntry]'s category and how it was filed; call that. */
    @Query("UPDATE txn SET category_id = :categoryId, user_edited = :userEdited WHERE id = :id AND raw_capture_id IS NULL")
    fun setManualFiling(id: Long, categoryId: Long, userEdited: Boolean): Int

    /** [editManualEntry]'s last statement, whose count is its answer; call that. */
    @Query("UPDATE txn SET updated_at = :updatedAt WHERE id = :id AND raw_capture_id IS NULL")
    fun touchManualEntry(id: Long, updatedAt: Long): Int

    /**
     * Delete manual entry [id] (#106, spec 9.4), answering the rows deleted:
     * 1, or 0 for a captured row, a row that is gone, and a row [id] names
     * that was not created at [createdAt] ([editManualEntry] says why).
     * `raw_capture_id IS NULL` keeps a captured row out, as in
     * [editManualEntry]: it is the bank's word. No table references
     * `txn.id`, so nothing is left dangling.
     */
    @Query("DELETE FROM txn WHERE id = :id AND created_at = :createdAt AND raw_capture_id IS NULL")
    fun deleteManualEntry(id: Long, createdAt: Long): Int

    /**
     * How many transactions are awaiting review (§9.2): the Spending list's
     * pinned `N AWAITING REVIEW` row, every currency.
     *
     * Answered from `index_txn_state_occurred_at` as a covering index, which
     * `QueryPlanTest.theReviewInboxBadgeCountUsesTheSameIndex` pins.
     */
    @Query(AWAITING_REVIEW_COUNT_SQL)
    fun awaitingReviewCount(): Int

    /**
     * Every transaction awaiting review, newest first, with what its card
     * shows beside it: spec 6.4's resolved merchant name, its category's name
     * and the notification it was read from.
     *
     * **Newest by `occurred_at`, not by the feed's `local_date`.** The two
     * orders differ only for a row whose day was written in another zone (an
     * import), and this one is the whole of `index_txn_state_occurred_at`
     * after the `state` seek, with `id` -- the rowid every index entry ends
     * in -- as the tiebreak, so it needs no sort.
     * `QueryPlanTest.reviewInboxDoesNotScanAndDoesNotSort` pins it.
     *
     * A list, not paged: it is the minority §7.1 holds back.
     */
    @Query(AWAITING_REVIEW_SQL)
    fun awaitingReview(): List<ReviewRow>

    /**
     * The transaction a duplicate card (§7.2, §9.2) shows beside [held], or
     * null when it has none and the card is a plain one.
     *
     * 1. When [held]'s capture has a `duplicate_of_id` -- layer 1's slot
     *    suspect, or layer 2 against another capture -- the pair is that
     *    capture's transaction, and only it.
     * 2. Otherwise the closest-in-time manual entry with [held]'s amount and
     *    direction within `DuplicateWindows.LAYER_TWO_MILLIS`, not
     *    `REJECTED`: layer 2 matching a manual entry has no capture to record.
     *
     * A pair that is itself `REJECTED` is no pair: Merge would then leave
     * neither row counted. Read in one transaction so the two steps see one
     * ledger.
     */
    @Transaction
    fun duplicatePair(held: Txn): Txn? {
        val recorded = duplicateOfCapture(held.id)
        val pair = if (recorded != null) {
            byCapture(recorded)
        } else {
            closestInLayerTwo(selfId = held.id, amountSen = held.amountSen, direction = held.direction, captured = false, at = held.occurredAt)
        }
        return pair?.takeIf { it.state != TxnState.REJECTED && it.id != held.id }
    }

    /**
     * A captured payment the manual entry about to be saved may repeat
     * (#104, spec 9.4), or null: a row with a capture, of [amountSen], an
     * `EXPENSE`, not `REJECTED`, and not [selfId] (the entry being edited;
     * null for a new one), closest to [at].
     *
     * The mirror of [duplicatePair]'s search from the other side, through
     * the same query and window: within layer 2's ten minutes of [at], or
     * anywhere on [at]'s `local_date` when [wholeDay] -- a backdated entry,
     * whose time of day is made up. By `local_date`, not a range of millis,
     * because the day a row is filed under is the day the user sees it on.
     */
    fun nearbyCapture(selfId: Long?, amountSen: Long, at: Long, wholeDay: Boolean): Txn? =
        if (wholeDay) {
            closestCaptureOnDay(selfId, amountSen, Direction.EXPENSE, LocalDates.of(at), at)
        } else {
            closestInLayerTwo(selfId, amountSen, Direction.EXPENSE, captured = true, at = at)
        }

    /**
     * The row closest to [at] within layer 2's window
     * (`DuplicateWindows.layerTwo`) with [amountSen] and [direction], not
     * `REJECTED` and not [selfId]: a capture when [captured], else a manual
     * entry. Each side of a capture/manual-entry pair finds the other here.
     */
    fun closestInLayerTwo(selfId: Long?, amountSen: Long, direction: Direction, captured: Boolean, at: Long): Txn? =
        DuplicateWindows.layerTwo(at).let { window ->
            closestBetween(selfId, amountSen, direction, captured, at, window.first, window.last)
        }

    /** [closestInLayerTwo]'s query; call that, which owns the window. */
    @Query(CLOSEST_IN_WINDOW_SQL)
    fun closestBetween(selfId: Long?, amountSen: Long, direction: Direction, captured: Boolean, at: Long, from: Long, to: Long): Txn?

    /** [nearbyCapture]'s whole-day search; see there. */
    @Query(CLOSEST_CAPTURE_ON_DAY_SQL)
    fun closestCaptureOnDay(selfId: Long?, amountSen: Long, direction: Direction, date: LocalDate, at: Long): Txn?

    /** [duplicatePair]'s first step: the capture [heldId]'s capture repeats, if any. */
    @Query(DUPLICATE_OF_CAPTURE_SQL)
    fun duplicateOfCapture(heldId: Long): Long?

    /** The transaction read from capture [captureId], if it made one. */
    @Query("SELECT * FROM txn WHERE raw_capture_id = :captureId")
    fun byCapture(captureId: Long): Txn?

    /**
     * Settle one transaction awaiting review (§9.2): [to] with no reason,
     * **only if it still is the row the card showed** -- `PENDING`, for
     * [reason]. Returns the rows written; 0 means the row moved underneath
     * (a Corrections re-read, another answer) and nothing was written.
     *
     * One targeted `UPDATE` naming its own columns, for the reason
     * [setCategory] gives. `confidence`, `category_id`, `user_edited`, the
     * merchant columns and the exclusion columns are left as they are, so an
     * accepted row filed by a rule stays eligible for learned rules.
     *
     * `pending_reason IS :reason`, not `=`, so a row stored with no reason --
     * which [requireStorable] refuses, but an older build's write might hold
     * -- can still be answered from the card that drew it.
     */
    fun settle(id: Long, reason: PendingReason?, to: TxnState, updatedAt: Long): Int {
        require(to != TxnState.PENDING) { "settling a row leaves it PENDING" }
        return settleRow(id, reason, to, updatedAt)
    }

    /** [settle]'s statement, unchecked. */
    @Query(
        """
        UPDATE txn SET state = :to, pending_reason = NULL, updated_at = :updatedAt
        WHERE id = :id AND state = 'PENDING' AND pending_reason IS :reason
        """,
    )
    fun settleRow(id: Long, reason: PendingReason?, to: TxnState, updatedAt: Long): Int

    /**
     * Accept all (§9.2): [settle] each of [answers] -- its row to its
     * `state`, guarded on its `reason` -- **in one transaction**, and return
     * the ones that wrote, which is what the batch's Undo takes back.
     *
     * A row that moved underneath is skipped, as a single answer to it would
     * write nothing, rather than refusing the batch: the rest are still the
     * cards the user saw. A statement that throws rolls back every row before
     * it, which `SettleAllTest.aBatchThatFailsPartWayWritesNothing` pins. The
     * exclusion columns are not written, so each [Answered] carries the
     * row's own.
     */
    @Transaction
    fun settleAll(answers: List<Answered>, updatedAt: Long): List<Answered> =
        answers.filter { settle(it.id, it.reason, it.state, updatedAt) == 1 }

    /**
     * Not a payment, on a captured row already counted (#132): `COMMITTED` to
     * `REJECTED`, the answer the review inbox gives a held one. Returns the
     * rows written, 0 for a manual entry (deleted instead, `deleteManualEntry`),
     * a row still awaiting review (the inbox's, guarded on its reason) or one
     * already rejected.
     *
     * Never a `DELETE`: the row's `raw_capture` stays as evidence, and the row
     * itself is what keeps Corrections (`Reread.NotComparable`) and a re-parse
     * from putting the payment back. The exclusion columns are left as they
     * are, as [settle] leaves them.
     */
    @Query(
        """
        UPDATE txn SET state = 'REJECTED', pending_reason = NULL, updated_at = :updatedAt
        WHERE id = :id AND state = 'COMMITTED' AND raw_capture_id IS NOT NULL
        """,
    )
    fun removeCaptured(id: Long, updatedAt: Long): Int

    /**
     * Not spending, from a captured row's detail screen (#153): a committed
     * row the user says moved no spending, kept and drawn greyed but out of
     * every total (§7.3), with `exclusion_reason = USER`. Returns the rows
     * written, 0 for a row already excluded for any reason, one awaiting
     * review (the inbox's Leave out, [leaveOut]), one rejected, or a manual
     * entry, which is deleted rather than excluded.
     *
     * `user_edited` is not set: it marks a decision the re-parse must not
     * overwrite about what the parser read, and `Corrections.reread` carries
     * the exclusion columns over unchanged, so a pack fix keeps this answer.
     */
    @Query(
        """
        UPDATE txn SET is_excluded = 1, exclusion_reason = 'USER', updated_at = :updatedAt
        WHERE id = :id AND state = 'COMMITTED' AND is_excluded = 0 AND raw_capture_id IS NOT NULL
        """,
    )
    fun excludeByUser(id: Long, updatedAt: Long): Int

    /**
     * Count as spending: undo [excludeByUser], both columns in one statement
     * (`requireStorable` refuses either alone). Only a `USER` exclusion: a
     * transfer, card payment or ATM withdrawal is the pack's, and taking that
     * back is the inbox's Keep as expense. Committed only, as [excludeByUser]
     * is: a row since rejected is in no list to count. Returns the rows written.
     */
    @Query(
        """
        UPDATE txn SET is_excluded = 0, exclusion_reason = NULL, updated_at = :updatedAt
        WHERE id = :id AND state = 'COMMITTED' AND exclusion_reason = 'USER'
        """,
    )
    fun countAsSpending(id: Long, updatedAt: Long): Int

    /**
     * A captured row's note (#153), stored trimmed, or null when blank, as a
     * manual entry's is (`ManualEntries`). `user_edited` is left alone: a note
     * is not something the parser decided. A manual entry's note is its edit
     * screen's (`editManualEntry`), so this writes nothing to one. Returns
     * the rows written.
     */
    fun setCapturedNote(id: Long, note: String, updatedAt: Long): Int =
        writeCapturedNote(id, note.trim().ifEmpty { null }, updatedAt)

    /** [setCapturedNote]'s statement; call that, which trims. */
    @Query("UPDATE txn SET note = :note, updated_at = :updatedAt WHERE id = :id AND raw_capture_id IS NOT NULL")
    fun writeCapturedNote(id: Long, note: String?, updatedAt: Long): Int

    /**
     * One transaction for its detail screen (#153): the row as the list draws
     * it, its category's name, and the capture it was read from -- the
     * notification verbatim, the rule that matched it and the pack that rule
     * came from. Null when no row has [id]. A manual entry reads with every
     * capture field null.
     */
    @Query(DETAIL_SQL)
    fun detail(id: Long): DetailRow?

    /**
     * Merge (§7.2, §9.2): [settle] to `REJECTED`, and **only while [pairId]
     * is not `REJECTED` itself** -- a pair dismissed after the card was drawn
     * would otherwise leave neither row counted. The pair is never written:
     * Merge dismisses the row awaiting review, which is the later-arriving one
     * of a pair, and keeps the other. `is_authoritative` plays no part.
     * Returns the rows written, 0 or 1.
     */
    @Query(
        """
        UPDATE txn SET state = 'REJECTED', pending_reason = NULL, updated_at = :updatedAt
        WHERE id = :id AND state = 'PENDING' AND pending_reason IS :reason
          AND EXISTS (SELECT 1 FROM txn AS pair WHERE pair.id = :pairId AND pair.state != 'REJECTED')
        """,
    )
    fun merge(id: Long, reason: PendingReason?, pairId: Long, updatedAt: Long): Int

    /**
     * Leave out (§7.3, §9.2): count a transfer the user says moved their own
     * money as no spending -- `COMMITTED`, no reason, `is_excluded = 1` with
     * [exclusion] -- **only if it still is the row the card showed**, as
     * [settle] guards. Returns the rows written; 0 means nothing was.
     *
     * Its own statement rather than [settle] with two more columns: [settle]
     * leaves the exclusion columns as they are, and that is what Accept, Keep
     * as expense and Not a payment rely on.
     */
    @Query(
        """
        UPDATE txn SET state = 'COMMITTED', pending_reason = NULL, is_excluded = 1,
                       exclusion_reason = :exclusion, updated_at = :updatedAt
        WHERE id = :id AND state = 'PENDING' AND pending_reason IS :reason
        """,
    )
    fun leaveOut(id: Long, reason: PendingReason?, exclusion: ExclusionReason, updatedAt: Long): Int

    /**
     * Undo (§9.2): put every one of [rows] back to awaiting review for its
     * own reason, with `is_excluded = 0` and no exclusion reason, **all or
     * none**. Returns false, having written nothing, when any row is no longer
     * in the state its answer wrote -- another writer has changed it since,
     * and putting the rest back would leave a set the user never had.
     *
     * Each row is one targeted `UPDATE` guarded on what [Answered] records,
     * so any answer's written state -- `COMMITTED`, `REJECTED`, or
     * `COMMITTED` and excluded -- is taken back by the same statement, and
     * Accept all's batch is taken back in this one transaction. `updated_at`
     * becomes [updatedAt] rather than the value before the answer: an undo is
     * a write, and a category changed between the answer and the undo would
     * otherwise be dated before it happened.
     */
    fun unsettle(rows: List<Answered>, updatedAt: Long): Boolean = try {
        unsettleOrThrow(rows, updatedAt)
        true
    } catch (_: StaleAnswerException) {
        false
    }

    /**
     * [unsettle]'s transaction. Throws on the first row that has moved, which
     * rolls back every row already put back; call [unsettle].
     */
    @Transaction
    fun unsettleOrThrow(rows: List<Answered>, updatedAt: Long) {
        for (row in rows) {
            val written = unsettleRow(
                id = row.id,
                reason = row.reason,
                updatedAt = updatedAt,
                wroteState = row.state,
                wroteExcluded = row.exclusionReason != null,
                wroteExclusion = row.exclusionReason,
            )
            if (written != 1) throw StaleAnswerException()
        }
    }

    /** One row of [unsettle], unchecked. */
    @Query(
        """
        UPDATE txn SET state = 'PENDING', pending_reason = :reason, is_excluded = 0,
                       exclusion_reason = NULL, updated_at = :updatedAt
        WHERE id = :id AND state = :wroteState AND pending_reason IS NULL
          AND is_excluded = :wroteExcluded AND exclusion_reason IS :wroteExclusion
        """,
    )
    fun unsettleRow(
        id: Long,
        reason: PendingReason?,
        updatedAt: Long,
        wroteState: TxnState,
        wroteExcluded: Boolean,
        wroteExclusion: ExclusionReason?,
    ): Int

    /**
     * The home list (§9.1), newest day first and newest within a day, from
     * [through] back: the month Spending's header picked (#192) ends there,
     * and nothing newer is drawn above it. [EVERY_LATER_DAY] for the whole
     * ledger, hand-entered future dates included.
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
     * total (§8) and still drawn: a row awaiting review is answered in §9.2's
     * inbox, but the list is where the user sees that the money moved.
     *
     * Served by `index_txn_local_date_occurred_at`, which supplies the whole
     * `ORDER BY` as a reverse scan with no temp b-tree -- measured at 0.443ms
     * against the previous ordering's 0.446ms for a page 4000 rows deep, so
     * the day-major order costs nothing. `state != 'REJECTED'` is a negative
     * condition SQLite cannot range-scan, so it is filtered after the scan
     * either way. Pinned by `QueryPlanTest.theFeedTakesTheDayOrderedIndex`.
     */
    @Query(FEED_SQL)
    fun feed(through: LocalDate): PagingSource<Int, FeedRow>

    /**
     * One day's rows for the `Day` screen (#69): what [feed] shows under that
     * day's heading, in the same order and with the same names, because both
     * are [FEED_ROWS] and [FEED_ORDER]. `PENDING` and excluded rows are in it
     * for the reason they are in the feed.
     *
     * A list, not paged: a day holds dozens of rows, not thousands. Nothing
     * observes it, so its reader re-reads after any write it makes.
     */
    @Query(DAY_ROWS_SQL)
    fun dayRows(date: LocalDate): List<FeedRow>

    /**
     * Section 8's "Top merchants" for a period: net spending per merchant,
     * largest first, grouped by spec 6.4's resolved identity rather than by
     * `merchant_key`, so a shop paid through two rails is one row.
     *
     * [COUNTED]'s rows only, and per currency, for the reasons [dayTotals]
     * gives. A row with no merchant is left out rather than ranked as a
     * merchant called nothing.
     *
     * The join costs `txn(merchant_key)` its ordered walk of the `GROUP BY`:
     * the period's rows are found through `index_txn_local_date_occurred_at`
     * and grouped in a temporary B-tree, which `QueryPlanTest` pins. Measured
     * on emulator-5554 over a year of 5,000 rows, 200 merchants and 50 merged:
     * 3.2ms.
     *
     * Charts reads one month with no effective limit and ranks in the mapping
     * layer (#92). Measured on emulator-5554 over a constructed ledger of
     * 20,000 rows across 24 months, 500 merchants and 100 merged, median of 50
     * after 10 warm-up reads: a month (333 merchants) 1.74ms, a year (400)
     * 7.51ms.
     */
    @Query(MERCHANT_TOTALS_SQL)
    fun merchantTotals(from: LocalDate, to: LocalDate, limit: Int): List<MerchantTotal>

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
        /**
         * The feed's select, joins and `REJECTED` filter, without its order,
         * so [DAY_ROWS_SQL] can add its own clause between the two halves and
         * cannot otherwise differ from the feed.
         */
        const val FEED_ROWS =
            """
            SELECT txn.*,
                   """ + MerchantSql.IDENTITY + """ AS identity_key,
                   """ + MerchantSql.NAME + """ AS display_name
            FROM txn """ + MerchantSql.JOINS + """
            WHERE txn.state != 'REJECTED'
            """

        /** The feed's order; see [feed] for why each key is there. */
        const val FEED_ORDER = "ORDER BY txn.local_date DESC, txn.occurred_at DESC, txn.id DESC"

        /**
         * `txn.*` plus spec 6.4's identity and name, both through primary-key
         * joins on the two small tables, so a rename redraws the rows it
         * names without touching any of them.
         *
         * The joins cost: on emulator-5554, 5,000 rows over 200 merchants with
         * 50 merged, a page 4,000 rows deep in Room's `LIMIT 40 OFFSET 4000`
         * wrapper took 0.90ms against 0.29ms without them, since `OFFSET`
         * evaluates the joins for every row it skips. Under a millisecond at a
         * depth few users scroll to, so the name is joined rather than cached
         * on `txn`, which would need a writer per rename.
         */
        const val FEED_SQL = FEED_ROWS + "AND txn.local_date <= :through " + FEED_ORDER

        /** Past every `yyyymmdd`: [feed]'s bound that leaves no day out. */
        val EVERY_LATER_DAY = LocalDate(99_999_999)

        /**
         * [dayRows]' query: the feed cut to one `local_date`. Its plan seeks
         * the day in `index_txn_local_date_occurred_at`, ordered by the index
         * with no sort, as `QueryPlanTest.aDaysRowsSeekTheDayInTheFeedsIndex`
         * pins.
         */
        const val DAY_ROWS_SQL = FEED_ROWS + "AND txn.local_date = :date " + FEED_ORDER

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
         * (see "A released schema is frozen" in the repository's CLAUDE.md).
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

        /** [awaitingReviewCount]'s query. */
        const val AWAITING_REVIEW_COUNT_SQL = "SELECT COUNT(*) FROM txn WHERE state = 'PENDING'"

        /**
         * [awaitingReview]'s query. Every join is by primary key; a manual
         * entry has no capture and draws no notification text.
         */
        const val AWAITING_REVIEW_SQL =
            """
            SELECT txn.*,
                   """ + MerchantSql.IDENTITY + """ AS identity_key,
                   """ + MerchantSql.NAME + """ AS display_name,
                   category.name AS category_name,
                   raw_capture.title AS capture_title,
                   raw_capture.text AS capture_text,
                   raw_capture.big_text AS capture_big_text
            FROM txn """ + MerchantSql.JOINS + """
            LEFT JOIN category ON category.id = txn.category_id
            LEFT JOIN raw_capture ON raw_capture.id = txn.raw_capture_id
            WHERE txn.state = 'PENDING'
            ORDER BY txn.occurred_at DESC, txn.id DESC
            """

        /**
         * [detail]'s query. Every join is by primary key, as
         * [AWAITING_REVIEW_SQL]'s are; a manual entry joins no capture.
         */
        const val DETAIL_SQL =
            """
            SELECT txn.*,
                   """ + MerchantSql.IDENTITY + """ AS identity_key,
                   """ + MerchantSql.NAME + """ AS display_name,
                   category.name AS category_name,
                   raw_capture.title AS capture_title,
                   raw_capture.text AS capture_text,
                   raw_capture.big_text AS capture_big_text,
                   raw_capture.matched_rule_id AS matched_rule_id,
                   raw_capture.pack_version AS capture_pack_version
            FROM txn """ + MerchantSql.JOINS + """
            LEFT JOIN category ON category.id = txn.category_id
            LEFT JOIN raw_capture ON raw_capture.id = txn.raw_capture_id
            WHERE txn.id = :id
            """

        /** [duplicateOfCapture]'s query: two primary-key lookups. */
        const val DUPLICATE_OF_CAPTURE_SQL =
            """
            SELECT raw_capture.duplicate_of_id FROM txn
            JOIN raw_capture ON raw_capture.id = txn.raw_capture_id
            WHERE txn.id = :heldId
            """

        /**
         * [closestBetween]'s query. A seek on `txn(amount_sen,
         * occurred_at)`, as layer 2's own search is; the `ORDER BY` sorts only
         * the window's rows of one amount, which
         * `QueryPlanTest.theLayerTwoPairSeeksTheAmountWindow` pins.
         * `id` breaks a tie in distance, so the same pair is drawn every read.
         *
         * `+raw_capture_id`: without the hint SQLite seeks `IS NULL` in the
         * unique `raw_capture_id` index instead -- every manual entry ever
         * typed, then sorted (`SEARCH txn USING INDEX index_txn_raw_capture_id
         * (raw_capture_id=?)` plus a temp b-tree, measured on emulator-5554
         * over `QueryPlanTest`'s fixture). `:captured` binds as 0 or 1, which
         * is what `IS NOT NULL` yields. `id IS NOT :selfId`, not `!=`: a null
         * self -- a new manual entry -- excludes nothing, where `!=` would
         * exclude every row.
         */
        const val CLOSEST_IN_WINDOW_SQL =
            """
            SELECT * FROM txn
            WHERE amount_sen = :amountSen AND occurred_at BETWEEN :from AND :to
              AND (+raw_capture_id IS NOT NULL) = :captured AND direction = :direction
              AND state != 'REJECTED' AND id IS NOT :selfId
            ORDER BY ABS(occurred_at - :at), id
            LIMIT 1
            """

        /**
         * [closestCaptureOnDay]'s query: [CLOSEST_IN_WINDOW_SQL] with the
         * window a `local_date`, sought in `txn(local_date, occurred_at)`.
         * `+amount_sen`: without it SQLite seeks `amount_sen = ?` alone, every
         * row of that amount ever (measured on emulator-5554 over
         * `QueryPlanTest`'s fixture), where one day's rows stay a day's.
         * Pinned by `QueryPlanTest.theNearbyCaptureOnADaySeeksTheDay`.
         */
        const val CLOSEST_CAPTURE_ON_DAY_SQL =
            """
            SELECT * FROM txn
            WHERE +amount_sen = :amountSen AND local_date = :date
              AND +raw_capture_id IS NOT NULL AND direction = :direction
              AND state != 'REJECTED' AND id IS NOT :selfId
            ORDER BY ABS(occurred_at - :at), id
            LIMIT 1
            """

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

        const val MONTHLY_TOTALS_SQL =
            """
            SELECT local_date / 100 AS month, currency,
                   SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
            FROM txn
            WHERE """ + COUNTED + """
              AND local_date BETWEEN :from AND :to
            GROUP BY local_date / 100, currency
            """

        const val MONTH_SPLIT_SQL =
            """
            SELECT currency,
                   SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE 0 END) AS spentSen,
                   SUM(CASE WHEN direction = 'EXPENSE' THEN 0 ELSE amount_sen END) AS cameBackSen
            FROM txn
            WHERE """ + COUNTED + """
              AND local_date BETWEEN :from AND :to
            GROUP BY currency
            """

        /**
         * [notInTotal]'s query. Every reason but `USER` is one a pack rule
         * set, so the `ELSE` is "Transfers, left out" by definition rather
         * than by listing `TRANSFER`, `CARD_PAYMENT` and `ATM_WITHDRAWAL`: a
         * reason added to the pack later lands there instead of on no line.
         *
         * `+state` on both arms, for the reason [COUNTED] carries it: the
         * period's rows are reached through the `local_date` range, not
         * through every pending or committed row ever written.
         * `QueryPlanTest.theNotInTotalAggregateTakesThePeriodRange` pins it.
         */
        const val NOT_IN_TOTAL_SQL =
            """
            SELECT CASE WHEN state = 'PENDING' THEN 'PENDING'
                        WHEN exclusion_reason = 'USER' THEN 'USER_EXCLUDED'
                        ELSE 'TRANSFERS' END AS line,
                   currency,
                   COUNT(*) AS txnCount,
                   SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
            FROM txn
            WHERE (+state = 'PENDING' OR (+state = 'COMMITTED' AND is_excluded = 1))
              AND local_date BETWEEN :from AND :to
            GROUP BY line, currency
            """

        const val MERCHANT_TOTALS_SQL =
            """
            SELECT g.identity_key AS identityKey,
                   COALESCE(merchant_name.display, g.derived) AS displayName,
                   g.currency AS currency, g.net_sen AS netSen, g.txn_count AS txnCount
            FROM (
                SELECT """ + MerchantSql.IDENTITY + """ AS identity_key, txn.currency AS currency,
                       SUM(CASE WHEN txn.direction = 'EXPENSE' THEN txn.amount_sen
                                ELSE -txn.amount_sen END) AS net_sen,
                       COUNT(*) AS txn_count,
                       MAX(txn.occurred_at) AS last_at,
                       COALESCE(txn.merchant_display, txn.merchant_raw) AS derived
                FROM txn """ + MerchantSql.ALIAS_JOIN + """
                WHERE """ + COUNTED + """
                  AND txn.local_date BETWEEN :from AND :to
                  AND txn.merchant_key IS NOT NULL
                GROUP BY identity_key, txn.currency
            ) g
            LEFT JOIN merchant_name ON merchant_name.merchant_key = g.identity_key
            ORDER BY g.net_sen DESC
            LIMIT :limit
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
