package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.requireStorable
import my.pinged.parse.Direction

/**
 * Before adding a query that filters `local_date` or `state`, read
 * `QueryPlanTest`. It pins plans for the month list, the day aggregate, the
 * category totals and the review inbox -- queries this DAO does not have yet.
 *
 * The one that matters is the unary `+state` hint: without it SQLite prefers
 * `index_txn_state_occurred_at` and the month list becomes "read every
 * COMMITTED transaction ever captured, then sort them". That hint lives only in
 * the test's own SQL constants; when these queries are written for real they
 * must carry it, and the SQL should move here so the test asserts the plan of
 * the query the app issues.
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
     * The batch form of [insert], checking every row before any reaches SQLite -- a
     * batch path that skipped it would be exactly the later write path the doc
     * above warns about. Room's `@Insert(List)` is the same statement count as a
     * loop but acquires the prepared statement once: 997ms against 231ms for 20,000
     * rows in one transaction.
     */
    fun insertAll(txns: List<Txn>) {
        txns.forEach { it.requireStorable() }
        insertAllRows(txns)
    }

    /**
     * The unchecked primitive behind [insert], deliberately not part of the
     * intended API surface: calling it directly is how `PendingReasonTest`
     * proves the guard above is doing the work.
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
     * The home list (spec 9.1). Served by `txn(occurred_at)`, which exists for
     * this query and nothing else: measured on emulator-5554 before that index
     * was added, this planned as `SCAN txn` plus `USE TEMP B-TREE FOR ORDER
     * BY` -- the whole table read and sorted to draw the first screen. Every
     * other index carrying `occurred_at` has it behind a leading equality
     * column, so none of them can order an unfiltered query.
     */
    @Query("SELECT * FROM txn ORDER BY occurred_at DESC LIMIT :limit")
    fun recent(limit: Int): List<Txn>

    /**
     * One transaction by primary key.
     *
     * `RawCaptureDao` always had this; `TxnDao` did not, so a test fixture
     * that needed one row by id paged the whole table 500 rows at a time and
     * filtered in Kotlin -- the exact walk [pageFrom]'s keyset cursor exists to
     * avoid, doing it to find a row SQLite can reach with one probe.
     */
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
}
