package my.pinged.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.requireStorable
import my.pinged.parse.Confidence
import my.pinged.parse.Direction

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
interface RawCaptureDao : MerchantDecisions {
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
     * [claimNext]'s ids alone, from the first capture after
     * ([afterPostedAt], [afterId]) in the queue's order: stage two's claim.
     *
     * **Ids, off `raw_capture(parse_status, posted_at)`**, whose entries
     * carry the rowid, so no row is read: a row on a damaged page then fails
     * its own [byId] and nothing else, where [claimNext] fails for every
     * capture in the batch.
     *
     * **A cursor, not the head of the queue.** A capture stage two skips --
     * one that failed, or one damage leaves undecidable -- stays `NEW` and
     * sorts where it did, so a claim from the head returns it again, and a
     * head of nothing else ended the run with every capture behind it
     * unclaimed. `(posted_at, id)` is the order the index already holds, the
     * rowid being its last column, so the cursor costs no sort; the
     * `posted_at >= ?` half is what lets the index seek to it. `QueryPlanTest`
     * pins both, from [CLAIM_IDS_SQL].
     */
    @Query(CLAIM_IDS_SQL)
    fun claimNextIds(
        limit: Int,
        afterPostedAt: Long = Long.MIN_VALUE,
        afterId: Long = Long.MIN_VALUE,
        status: ParseStatus = ParseStatus.NEW,
    ): List<CaptureRef>

    /**
     * Duplicate detection, **layer 1 rule 2** of spec 7.2: same `content_hash`
     * within 60 seconds with a different `sbn_key` is a `DUPLICATE_OF`, and
     * produces no transaction. The caller passes
     * `postedAt - DuplicateWindows.CONTENT_HASH_MILLIS` and `postedAt`, and
     * its own `sbn_key` as [key], and filters out rows that are not
     * earlier than its own.
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
     * **Read off two indexes and never the table**, as [findEarlierInSlot]
     * is and for its reason: the slot test is `raw_capture(sbn_key)`'s
     * rowids, and `raw_capture(content_hash, posted_at)` applies the rest.
     */
    @Query(CONTENT_REPEATS_SQL)
    fun findByContentHash(
        hash: String,
        key: String,
        sinceMillis: Long,
        untilMillis: Long,
    ): List<CaptureRef>

    /**
     * Duplicate detection, layer 1 **rule 1**: the earlier row with the same
     * `sbn_key` and the same `content_hash`, if there is one, and not one of
     * [unreadable].
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
     * **Read off two indexes and never the table.** The earlier row is the
     * notification this one repeats, and a listener rebind stores a
     * still-posted notification again each time, so with that row on a
     * damaged page a lookup reading it met code 11 once per copy -- measured
     * on emulator-5554, 60 copies ended every run at the head of the queue.
     * `raw_capture(content_hash, posted_at)` carries the hash, the post time
     * and the rowid; `raw_capture(sbn_key)` answers the slot test from its
     * rowids. `QueryPlanTest` pins both as covering.
     *
     * [unreadable] is stage two's: captures it has skipped as undecidable on
     * this file, whose row cannot be the refreshed one because nothing of it
     * reached the ledger. See `Dedup.layerOne`.
     */
    @Query(EARLIER_IN_SLOT_SQL)
    fun findEarlierInSlot(
        key: String,
        hash: String,
        selfId: Long,
        unreadable: List<Long> = emptyList(),
    ): CaptureRef?

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
    @Query(EARLIER_IN_SLOT_SINCE_SQL)
    fun findEarlierInSlotSince(
        key: String,
        hash: String,
        selfId: Long,
        sinceMillis: Long,
        untilMillis: Long,
        unreadable: List<Long> = emptyList(),
    ): CaptureRef?

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
     * Spec 5.5's re-parse: hands captures an older pack could not read back to
     * stage two, bounded to [limit] rows.
     *
     * **[from] must only ever be [ParseStatus.REVISITABLE].** A `MATCHED`
     * capture has already written a `txn`, so requeueing one is how spending
     * gets counted twice. That filter is the defence; spec 7.2's
     * `content_hash` layer is a second net under it, not the first. It is a
     * bound parameter rather than a literal because Room cannot take a list
     * from the enum's companion into the SQL, so the single caller states it.
     *
     * **No cursor, unlike spec 15.5's keyset sketch.** This moves every row it
     * selects out of `parse_status IN (:from)`, so the candidate set strictly
     * shrinks and calling it until it returns zero terminates. `ORDER BY` would
     * only add a temp b-tree, since `IN` over several statuses cannot take its
     * ordering from `raw_capture(parse_status, id)`.
     *
     * @return rows moved. Equal to [limit] means there may be more.
     */
    @Query(
        "UPDATE raw_capture SET parse_status = :to WHERE id IN (" +
            "SELECT id FROM raw_capture WHERE parse_status IN (:from) " +
            "AND pack_version < :packVersion LIMIT :limit)"
    )
    fun requeueStale(
        from: List<ParseStatus>,
        packVersion: Int,
        limit: Int,
        to: ParseStatus,
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
     * so an id that stage two read out of [claimNextIds] cannot have gone away;
     * Room's generated code raises rather than returning a silent null if it
     * ever does.
     */
    @Query("SELECT * FROM raw_capture WHERE id = :id")
    fun byId(id: Long): RawCapture

    /**
     * Spec 15.5's keyset cursor, and **no production caller reads it.**
     * Re-parse went to [requeueStale] instead, which needs no position: it
     * moves rows out of the statuses it selects on, so the candidate set
     * shrinks on its own; spec 5.5's third mode reads through
     * [matchedStaleAfter], which needs the join. Kept for the measurement
     * below, which other cursors cite.
     *
     * The position is the last `id` seen, which is what makes resumption exact
     * -- an `OFFSET` cursor's position shifts under any concurrent insert, and
     * the listener is inserting throughout.
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
     * [pageFrom] starting **at** [fromId] rather than after it: salvage's read,
     * `my.pinged.ledger.transfer.RowReader`.
     *
     * `id > b`, where b is the last id on a damaged leaf, still descends into
     * that leaf and throws; `id >= b + 1` does not. Measured on emulator-5554,
     * and why salvage cannot step over damage with [pageFrom] without losing
     * the first row after it.
     */
    @Query("SELECT * FROM raw_capture WHERE id >= :fromId ORDER BY id ASC LIMIT :limit")
    fun pageStartingAt(fromId: Long, limit: Int): List<RawCapture>

    /**
     * Every id, read off `raw_capture(parse_status, id)` without touching the
     * table's own pages, so it answers on a file whose table will not read.
     * Salvage's bound: see `RowReader`.
     */
    @Query("SELECT id FROM raw_capture INDEXED BY index_raw_capture_parse_status_id")
    fun idsFromStatusIndex(): List<Long>

    /** [idsFromStatusIndex] off a second index, for when the first is damaged too. */
    @Query("SELECT id FROM raw_capture INDEXED BY index_raw_capture_sbn_key")
    fun idsFromSbnKeyIndex(): List<Long>

    /**
     * The highest id this table has ever assigned, from `sqlite_sequence`,
     * which `AUTOINCREMENT` keeps on a page of its own. Null if none has been.
     */
    @Query("SELECT seq FROM sqlite_sequence WHERE name = 'raw_capture'")
    fun highestIdEver(): Long?

    /**
     * How many captures point their `duplicate_of_id` at an id this table does
     * not hold.
     *
     * Nothing in the schema enforces this: declaring `duplicate_of_id` a Room
     * `ForeignKey` would change a released schema's identity hash. In the app it
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

    /**
     * Hands every `MATCHED` capture with no transaction naming it back to
     * stage two, at `NEW`, and says how many it moved.
     *
     * **Not a breach of [requeueStale]'s rule**, which keeps `MATCHED` out
     * because its transaction exists: here the `NOT EXISTS` is that rule,
     * row by row. [commitCapture] then writes the one transaction the capture
     * lacks, and finding one already there it writes nothing.
     *
     * In the app every `MATCHED` capture has its transaction -- [commitCapture]
     * writes both as one, and nothing deletes a `txn` row -- so on a ledger
     * the app wrote this moves nothing. `ImportJson` runs it for the one file
     * that breaks that: a salvage whose `txn` page would not read (see there).
     * **Anything that comes to delete a transaction must move its capture off
     * `MATCHED` too**, or the next restore writes the transaction again.
     * Served by `raw_capture(parse_status, posted_at)` and the unique index on
     * `txn(raw_capture_id)`.
     */
    @Query(
        "UPDATE raw_capture SET parse_status = :to WHERE parse_status = :from " +
            "AND NOT EXISTS (SELECT 1 FROM txn WHERE txn.raw_capture_id = raw_capture.id)"
    )
    fun requeueMatchedWithoutTxn(from: ParseStatus = ParseStatus.MATCHED, to: ParseStatus = ParseStatus.NEW): Int

    /**
     * Spec 5.5's third mode: `MATCHED` captures an older pack read, whose
     * transaction nobody has edited, after [afterId] in id order.
     *
     * A reader with a cursor, unlike [requeueStale]: a capture whose re-read
     * differs from its transaction stays at its old `pack_version` until the
     * user answers, so the candidate set does not shrink on its own and a
     * claim from the head would return the same rows every time.
     *
     * `user_edited = 0` is spec 5.5's "excluded from the comparison entirely",
     * in the query rather than the caller, so an edited row is never read,
     * never re-parsed and never stamped with a pack that did not decide it.
     * `QueryPlanTest` pins the plan: `raw_capture(parse_status, id)` for the
     * order, and the unique `txn(raw_capture_id)` for the join.
     */
    @Query(MATCHED_STALE_SQL)
    fun matchedStaleAfter(
        packVersion: Int,
        afterId: Long,
        limit: Int,
        status: ParseStatus = ParseStatus.MATCHED,
    ): List<RawCapture>

    @Query("SELECT merchant_key FROM txn WHERE id = :id")
    fun merchantKeyOf(id: Long): String?

    /** The transaction [captureId] wrote, off the unique `txn(raw_capture_id)`. */
    @Query("SELECT * FROM txn WHERE raw_capture_id = :captureId")
    fun txnForCapture(captureId: Long): Txn?

    /**
     * Records that the pack at [toPackVersion] has compared this `MATCHED`
     * capture against its transaction and [ruleId] is the rule it would
     * use: the capture leaves spec 5.5's third mode until the next pack.
     *
     * Guarded on [fromPackVersion], the version the caller read, so a second
     * writer answering the same capture -- the sweep and a tap, or two taps --
     * becomes a zero-row update rather than a second answer.
     */
    @Query(
        "UPDATE raw_capture SET pack_version = :toPackVersion, matched_rule_id = :ruleId " +
            "WHERE id = :id AND parse_status = :status AND pack_version = :fromPackVersion"
    )
    fun markCompared(
        id: Long,
        fromPackVersion: Int,
        toPackVersion: Int,
        ruleId: String?,
        status: ParseStatus = ParseStatus.MATCHED,
    ): Int

    /**
     * The parse-derived columns of one transaction, rewritten from a re-read.
     *
     * Targeted, naming its columns: category, note, exclusion and the dates
     * are not the parse's to change. Guarded on `user_edited = 0`, so an edit
     * that lands between the comparison and the write is never overwritten.
     */
    @Query(
        "UPDATE txn SET amount_sen = :amountSen, direction = :direction, " +
            "merchant_raw = :merchantRaw, merchant_display = :merchantDisplay, " +
            "merchant_key = :merchantKey, confidence = :confidence, state = :state, " +
            "pending_reason = :pendingReason, updated_at = :updatedAt " +
            "WHERE id = :id AND user_edited = 0"
    )
    fun rewriteParsedFields(
        id: Long,
        amountSen: Long,
        direction: Direction,
        merchantRaw: String?,
        merchantDisplay: String?,
        merchantKey: String?,
        confidence: Confidence,
        state: TxnState,
        pendingReason: PendingReason?,
        updatedAt: Long,
    ): Int

    /**
     * A re-read applied: [corrected]'s parse-derived columns onto its row,
     * the user's merchant decisions carried to its new key
     * ([MerchantDecisions.carry]), and the capture marked compared, as one unit.
     *
     * Returns false, having written nothing, when the transaction has been
     * edited. A capture another writer has answered since [fromPackVersion]
     * was read throws [StaleCaptureException], which rolls the transaction's
     * rewrite back with it -- so of two answers to one re-read, one lands.
     */
    @Transaction
    fun applyReread(
        captureId: Long,
        fromPackVersion: Int,
        toPackVersion: Int,
        ruleId: String,
        corrected: Txn,
    ): Boolean {
        corrected.requireStorable()
        val previousKey = merchantKeyOf(corrected.id)
        val rewritten = rewriteParsedFields(
            id = corrected.id,
            amountSen = corrected.amountSen,
            direction = corrected.direction,
            merchantRaw = corrected.merchantRaw,
            merchantDisplay = corrected.merchantDisplay,
            merchantKey = corrected.merchantKey,
            confidence = corrected.confidence,
            state = corrected.state,
            pendingReason = corrected.pendingReason,
            updatedAt = corrected.updatedAt,
        )
        if (rewritten == 0) return false
        val key = corrected.merchantKey
        if (previousKey != null && key != null) carry(from = previousKey, to = key)
        if (markCompared(captureId, fromPackVersion, toPackVersion, ruleId) == 0) {
            throw StaleCaptureException(
                "Capture $captureId is no longer MATCHED at pack $fromPackVersion; " +
                    "another writer answered its re-read. Nothing was written.",
            )
        }
        return true
    }

    companion object {
        /** [matchedStaleAfter]'s query, hoisted for `QueryPlanTest`. */
        const val MATCHED_STALE_SQL =
            "SELECT c.* FROM raw_capture AS c JOIN txn AS t ON t.raw_capture_id = c.id " +
                "WHERE c.parse_status = :status AND c.id > :afterId " +
                "AND c.pack_version < :packVersion AND t.user_edited = 0 " +
                "ORDER BY c.id ASC LIMIT :limit"

        /** [claimNextIds]'s query, hoisted so `QueryPlanTest` plans this string and not a copy. */
        const val CLAIM_IDS_SQL =
            "SELECT id, posted_at FROM raw_capture WHERE parse_status = :status " +
                "AND posted_at >= :afterPostedAt AND (posted_at > :afterPostedAt OR id > :afterId) " +
                "ORDER BY posted_at ASC, id ASC LIMIT :limit"

        /** Layer one's slot test, off `raw_capture(sbn_key)` alone. */
        private const val SLOT_IDS_SQL =
            "SELECT k.id FROM raw_capture AS k INDEXED BY index_raw_capture_sbn_key WHERE k.sbn_key = :key"

        private const val BY_HASH =
            "SELECT h.id AS id, h.posted_at AS posted_at " +
                "FROM raw_capture AS h INDEXED BY index_raw_capture_content_hash_posted_at " +
                "WHERE h.content_hash = :hash "

        /** [findByContentHash]'s query, hoisted for `QueryPlanTest`. */
        const val CONTENT_REPEATS_SQL =
            BY_HASH + "AND h.posted_at >= :sinceMillis AND h.posted_at <= :untilMillis " +
                "AND h.id NOT IN (" + SLOT_IDS_SQL + ")"

        /** [findEarlierInSlot]'s query, hoisted for `QueryPlanTest`. */
        const val EARLIER_IN_SLOT_SQL =
            BY_HASH + "AND h.id < :selfId AND h.id IN (" + SLOT_IDS_SQL + ") " +
                "AND h.id NOT IN (:unreadable) ORDER BY h.id ASC LIMIT 1"

        /** [findEarlierInSlotSince]'s query, hoisted for `QueryPlanTest`. */
        const val EARLIER_IN_SLOT_SINCE_SQL =
            BY_HASH + "AND h.posted_at >= :sinceMillis AND h.posted_at <= :untilMillis " +
                "AND h.id < :selfId AND h.id IN (" + SLOT_IDS_SQL + ") " +
                "AND h.id NOT IN (:unreadable) ORDER BY h.id ASC LIMIT 1"
    }
}

/**
 * A capture's id and post time, which is all layer one and the queue need
 * of a row, and all two of its indexes hold: read through this, a row on a
 * damaged page is never touched.
 */
data class CaptureRef(
    val id: Long,
    @ColumnInfo(name = "posted_at") val postedAt: Long,
)
