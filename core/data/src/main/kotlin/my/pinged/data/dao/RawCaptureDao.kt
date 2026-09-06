package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.requireStorable

/**
 * Raised when a guarded write found the capture no longer in the state the
 * caller read it in. Distinct from a constraint failure on purpose: a worker
 * should classify this as "someone else did this work", not retry it.
 */
class StaleCaptureException(message: String) : IllegalStateException(message)

/**
 * Stage one writes here; stage two reads its work queue back out of here.
 *
 * The queue is a table query, not an in-memory structure: any row still at
 * `NEW` is unfinished work, so process death between stage one and stage two
 * loses nothing and an interrupted run simply resumes.
 */
@Dao
interface RawCaptureDao {
    @Insert
    fun insert(capture: RawCapture): Long

    /**
     * The batch form, for [my.pinged.ledger.transfer.ImportJson]. Room's
     * `@Insert(List)` runs the same number of INSERT statements as a loop but
     * acquires the prepared statement once and rebinds per row. Measured on a
     * Pixel 10a emulator at 20,000 rows in one transaction: 997ms per-row
     * against 231ms in batches of 500.
     */
    @Insert
    fun insertAll(captures: List<RawCapture>)

    @Query("SELECT COUNT(*) FROM raw_capture")
    fun countAll(): Int

    /**
     * Stage two's work queue. Served by `raw_capture(parse_status, posted_at)`
     * (spec 15.1) -- the same index covers both the equality and the ordering,
     * so there is no sort step.
     *
     * `status` is a bound parameter rather than a `'NEW'` literal so that
     * renaming a [ParseStatus] constant is a compile error here instead of a
     * query that silently matches nothing.
     */
    @Query(
        "SELECT * FROM raw_capture WHERE parse_status = :status " +
            "ORDER BY posted_at ASC LIMIT :limit"
    )
    fun claimNext(limit: Int, status: ParseStatus = ParseStatus.NEW): List<RawCapture>

    /**
     * Duplicate detection, **layer 1 rule 2** of spec 7.2: same `content_hash`
     * within 60 seconds with a different `sbn_key` is a `DUPLICATE_OF`, and
     * produces no transaction. The caller passes
     * `postedAt - DuplicateWindows.CONTENT_HASH_MILLIS` and `postedAt`, and
     * filters out its own row and its own `sbn_key`.
     *
     * **Bounded at both ends.** Spec 5.5's re-parse walks history, so an
     * open-ended `posted_at >= :sinceMillis` returns captures posted *later*
     * and marks a March capture a duplicate of a separate identical purchase in
     * September -- deleting the March transaction. Rule 2's "within 60 seconds"
     * is a window, not a floor.
     *
     * **No `parse_status` filter**, deliberately: the common duplicate is two
     * notifications seconds apart, where the earlier is still `NEW` because
     * stage two has not run. No package or user clause either -- `content_hash`
     * is sha256 over (package, user, normalized text) per spec 4, so an equal
     * hash already implies both.
     *
     * Served by `raw_capture(content_hash, posted_at)`, which applies both
     * halves in the index.
     */
    @Query(
        "SELECT * FROM raw_capture WHERE content_hash = :hash " +
            "AND posted_at >= :sinceMillis AND posted_at <= :untilMillis"
    )
    fun findByContentHash(hash: String, sinceMillis: Long, untilMillis: Long): List<RawCapture>

    /**
     * Duplicate detection, layer 1 **rule 1**: the earlier row with the same
     * `sbn_key` and the same `content_hash`, if there is one.
     *
     * `id < :selfId` is the self-exclusion and the "earlier" both: `id` is
     * `autoGenerate`, so it is monotonic in insertion order and, unlike
     * `posted_at`, cannot tie. Without it the row returned is *the row being
     * processed* -- stage one inserts before stage two claims -- so every
     * capture is its own duplicate and the ledger stays empty.
     *
     * `ORDER BY id ASC` makes `LIMIT 1` the *first* such row. Picking the
     * latest would walk a chain of refreshes one link at a time and make
     * `duplicate_of_id` a linked list instead of a pointer at the original.
     *
     * Deliberately unwindowed, and the **fallback**: `Dedup.layerOne` asks
     * [findEarlierInSlotSince] first, so a hit here is an older match, which
     * [my.pinged.data.slotRefreshOutcome] calls a suspect rather than a
     * refresh.
     *
     * Served by `raw_capture(sbn_key)`, with `id < ?` applied off the rowid.
     */
    @Query(
        "SELECT * FROM raw_capture WHERE sbn_key = :key AND content_hash = :hash " +
            "AND id < :selfId ORDER BY id ASC LIMIT 1"
    )
    fun findEarlierInSlot(key: String, hash: String, selfId: Long): RawCapture?

    /**
     * [findEarlierInSlot] with spec 7.2's ten-minute window, for a capture that
     * arrived through `onNotificationPosted`. The caller passes
     * `postedAt - DuplicateWindows.SLOT_REFRESH_MILLIS`.
     *
     * A match *outside* this window is not nothing -- spec 7.2 makes it a
     * `PENDING` duplicate suspect -- so a caller that gets null here must still
     * ask [findEarlierInSlot]. Two queries, because the difference between them
     * is the difference between silently dropping the money and putting it in
     * the review inbox.
     *
     * **Bounded at both ends**, for the reason [findByContentHash] gives: an
     * import preserves `id` and `posted_at` from a file the user can edit, so
     * an open-ended lower bound matches a row posted *after* the capture.
     *
     * `ORDER BY id ASC LIMIT 1` returns the **oldest** row in the window, not
     * the nearest. Inside the window the verdict is `UPDATE_OF` either way;
     * what moves is which row `duplicate_of_id` points at.
     */
    @Query(
        "SELECT * FROM raw_capture WHERE sbn_key = :key AND content_hash = :hash " +
            "AND id < :selfId AND posted_at >= :sinceMillis AND posted_at <= :untilMillis " +
            "ORDER BY id ASC LIMIT 1"
    )
    fun findEarlierInSlotSince(
        key: String,
        hash: String,
        selfId: Long,
        sinceMillis: Long,
        untilMillis: Long,
    ): RawCapture?

    /**
     * Records stage two's outcome on one capture.
     *
     * **Guarded on [expected], and it returns the row count.** Re-parse and the
     * live listener both write here, and a `REJECTED` capture that a pack fix
     * has just turned into `MATCHED` must not be reverted by a worker that read
     * it before the fix. A stale writer becomes a zero-row update the return
     * value makes visible.
     *
     * `COALESCE` preserves `rejected_by_rule_id` rather than nulling it, so a
     * re-parse that rescues a rejected capture still leaves the evidence of
     * which reject pattern ate it -- which is what spec 5.6's authoring loop
     * needs to find an over-broad pattern.
     */
    @Query(
        "UPDATE raw_capture SET parse_status = :status, matched_rule_id = :ruleId, " +
            "rejected_by_rule_id = COALESCE(:rejectId, rejected_by_rule_id), " +
            "reject_collision_id = :collisionId, " +
            "duplicate_of_id = :duplicateOf, pack_version = :packVersion " +
            "WHERE id = :id AND parse_status = :expected"
    )
    fun markOutcome(
        id: Long,
        status: ParseStatus,
        ruleId: String? = null,
        rejectId: String? = null,
        collisionId: String? = null,
        duplicateOf: Long? = null,
        packVersion: Int = 0,
        expected: ParseStatus = ParseStatus.NEW,
    ): Int

    /**
     * `SELECT id FROM txn WHERE raw_capture_id = ?`, from this DAO because
     * [commitCapture] needs it inside its own transaction. Served by the
     * unique index on `txn(raw_capture_id)`.
     */
    @Query("SELECT id FROM txn WHERE raw_capture_id = :captureId LIMIT 1")
    fun txnIdForCapture(captureId: Long): Long?

    /**
     * Plain `@Insert`, i.e. `ABORT`. [commitCapture] establishes idempotence by
     * asking [txnIdForCapture] first rather than by softening this to `IGNORE`,
     * so a unique-index violation on anything *other* than a re-run still
     * throws. Called only from [commitCapture], which applies `requireStorable`
     * first.
     */
    @Insert
    fun insertTxnRow(txn: Txn): Long

    /**
     * Writes the transaction and the capture's outcome as one unit.
     *
     * A process killed between the two writes leaves the transaction committed
     * and the capture still `NEW` -- which sorts *first* under [claimNext], so
     * the survivor is the next row the worker picks up, its re-insert hits the
     * unique index on `txn(raw_capture_id)`, and a worker answering that with
     * `Result.retry()` stalls the queue permanently at the head.
     *
     * `@Transaction` closes the window, and the ordering below makes the method
     * idempotent so a pre-existing poisoned row heals instead of throwing:
     *
     * - fresh capture: no transaction yet, status is [expected] -> mark, insert,
     *   return the new id.
     * - poisoned row: transaction exists, status still [expected] -> the mark
     *   succeeds, the existing id is returned, and the row leaves the queue.
     *   This is the heal.
     * - already finished: transaction exists, status already moved -> zero-row
     *   mark, existing id returned, nothing written.
     * - stale writer: no transaction and the status has moved under us ->
     *   [StaleCaptureException], which rolls the transaction back. Not a
     *   silent no-op, because in this case somebody else decided this
     *   capture's outcome and [txn] would have been a second opinion.
     *
     * [txn] is ignored in the two middle cases. Spec 5.5 is explicit that
     * re-parse "never modifies an existing transaction", so the stored row
     * wins and the caller gets its id.
     *
     * `requireStorable` runs here, not only on [TxnDao.insert]: this is how
     * stage two writes in production, so a guard living only on `TxnDao` would
     * be one the app never goes through. The throw lands inside the
     * `@Transaction`, so a rejected row takes the [markOutcome] down with it and
     * the capture stays on the queue.
     */
    @Transaction
    fun commitCapture(
        captureId: Long,
        txn: Txn,
        status: ParseStatus,
        ruleId: String? = null,
        rejectId: String? = null,
        collisionId: String? = null,
        duplicateOf: Long? = null,
        packVersion: Int = 0,
        expected: ParseStatus = ParseStatus.NEW,
    ): Long {
        val existing = txnIdForCapture(captureId)
        val marked = markOutcome(
            id = captureId,
            status = status,
            ruleId = ruleId,
            rejectId = rejectId,
            collisionId = collisionId,
            duplicateOf = duplicateOf,
            packVersion = packVersion,
            expected = expected,
        )
        if (marked == 0 && existing == null) {
            throw StaleCaptureException(
                "Capture $captureId is no longer $expected and has no transaction; " +
                    "another writer decided its outcome. Nothing was written.",
            )
        }
        return existing ?: run {
            txn.requireStorable()
            insertTxnRow(txn)
        }
    }

    /**
     * Non-null on purpose. Raw captures are never deleted by the app (spec 4),
     * so an id that stage two read out of [claimNext] cannot have gone away;
     * Room's generated code raises rather than returning a silent null if it
     * ever does.
     */
    @Query("SELECT * FROM raw_capture WHERE id = :id")
    fun byId(id: Long): RawCapture

    /**
     * Spec 15.5's keyset cursor: the re-parse job walks `UNMATCHED` and
     * `REJECTED` captures in chunks and "records its position so an
     * interrupted run resumes rather than restarting". The position is the
     * last `id` seen, which is what makes resumption exact -- an `OFFSET`
     * cursor's position shifts under any concurrent insert, and the listener
     * is inserting throughout.
     *
     * `OFFSET` counts and discards rows, so it degrades linearly: measured at
     * 50,000 captures, 0.065 ms at offset 0 against 1.923 ms at offset 49,950,
     * versus 0.072 ms flat here. Served by `raw_capture(parse_status, id)`,
     * which supplies the ordering too -- with only `(parse_status, posted_at)`
     * the plan adds `USE TEMP B-TREE FOR ORDER BY` on every chunk.
     */
    @Query(
        "SELECT * FROM raw_capture WHERE parse_status = :status AND id > :afterId " +
            "ORDER BY id ASC LIMIT :limit"
    )
    fun pageAfter(status: ParseStatus, afterId: Long, limit: Int): List<RawCapture>

    /**
     * [pageAfter] without the status filter: the export cursor.
     *
     * Keyset for the reason [pageAfter] measures: the export walks the whole
     * table once, so it suffers most from `OFFSET`. No index of its own -- `id`
     * is the integer primary key, so this walks the table's own B-tree.
     */
    @Query("SELECT * FROM raw_capture WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    fun pageFrom(afterId: Long, limit: Int): List<RawCapture>

    /**
     * How many captures point their `duplicate_of_id` at an id this table does
     * not hold.
     *
     * Nothing in the schema enforces this: declaring `duplicate_of_id` a Room
     * `ForeignKey` would change the frozen v1 identity hash. In the app it
     * cannot break, because `markOutcome` only ever writes an id it just read
     * from this table.
     *
     * An import is the first writer that can invent one -- a backup file is a
     * text document a user can truncate or edit -- so `ImportJson` runs this
     * inside its own transaction and rolls back on a non-zero answer.
     */
    @Query(
        "SELECT COUNT(*) FROM raw_capture c WHERE c.duplicate_of_id IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM raw_capture p WHERE p.id = c.duplicate_of_id)"
    )
    fun danglingDuplicateOfCount(): Int
}
