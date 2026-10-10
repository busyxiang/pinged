package my.pinged.data.dao

import my.pinged.data.CaptureDays
import my.pinged.data.LocalDate
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.CaptureDay

/**
 * Spec 4: one row per local date, so the daily rhythm grid can tell "you spent
 * nothing" apart from "Pinged was not watching".
 *
 * **There is no whole-row upsert, for the same reason as `CaptureSourceDao`
 * and with a sharper edge.** REPLACE deletes and re-inserts, so both booleans
 * take the incoming object's value -- and `saw_any_notification` is not a
 * state but an accumulation across the day. The two writers do not know each
 * other's facts: the notification path knows a notification arrived and nothing
 * about binding, the rebind path the reverse, so either writing a whole row
 * erases the other's answer.
 *
 * A day whose `saw_any_notification` got reset renders as a hatched cell and
 * counts toward "N days not captured" -- the app reporting, in the one place
 * spec 4 built for honesty about its own coverage, that it was blind on a day
 * it was watching.
 */
@Dao
interface CaptureDayDao {
    /**
     * Creates the day's row if it is not there. `IGNORE`, so a concurrent
     * creator is not a failure and does not overwrite.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfNew(day: CaptureDay): Long

    /**
     * `= 1`, not `= :value`. The column accumulates, so there is no value a
     * caller could pass that would mean "and it did not happen either"; a
     * parameter here would just be the REPLACE bug in a narrower shape.
     * Clearing the flag is not an operation this table has.
     */
    @Query("UPDATE capture_day SET saw_any_notification = 1 WHERE local_date = :localDate")
    fun markSawNotification(localDate: LocalDate): Int

    /**
     * Takes a value because `listener_bound` is a state, not an accumulation.
     *
     * **No production caller passes `false`.** Both production writers go
     * through [recordListenerBound] -- `onListenerConnected`, and the
     * foreground check (`ListenerStatus.onAppForeground`) while the listener
     * is bound -- and both pass `true`; no heartbeat writes this table (#63,
     * spec 4). The table has no part-day state, so a `false` written mid-day
     * would hatch a day that was partly watched. `listener_bound = 0` reaches
     * a device only through an import, and [capturedDates] treats such a row
     * as no evidence.
     */
    @Query("UPDATE capture_day SET listener_bound = :bound WHERE local_date = :localDate")
    fun setListenerBound(localDate: LocalDate, bound: Boolean): Int

    /**
     * A notification arrived on [localDate].
     *
     * `listener_bound = true` on the row this may create is a fact, not an
     * assumption: a notification reached this app, so the listener was bound. If
     * the row exists the flag is left as its own writer set it.
     *
     * The second [markSawNotification] covers the race where another writer created
     * the row between the update and the insert, where `IGNORE` dropped ours.
     */
    @Transaction
    fun recordNotificationSeen(localDate: LocalDate) {
        if (markSawNotification(localDate) == 0) {
            insertIfNew(
                CaptureDay(
                    localDate = localDate,
                    listenerBound = true,
                    sawAnyNotification = true,
                ),
            )
            markSawNotification(localDate)
        }
    }

    /**
     * Spec 10.2's binding observation for [localDate]. `sawAnyNotification =
     * false` on a row this creates is the honest initial value -- this writer
     * has seen no notification -- and it can never overwrite a `true`,
     * because [setListenerBound] does not name that column.
     */
    @Transaction
    fun recordListenerBound(localDate: LocalDate, bound: Boolean) {
        if (setListenerBound(localDate, bound) == 0) {
            insertIfNew(
                CaptureDay(
                    localDate = localDate,
                    listenerBound = bound,
                    sawAnyNotification = false,
                ),
            )
            setListenerBound(localDate, bound)
        }
    }

    @Query("SELECT * FROM capture_day WHERE local_date = :localDate LIMIT 1")
    fun byDate(localDate: LocalDate): CaptureDay?

    @Query("SELECT COUNT(*) FROM capture_day")
    fun countAll(): Int

    /**
     * The export reads this table whole.
     *
     * No keyset cursor, unlike `raw_capture` and `txn`: this table gains at
     * most one row a day, so five years of use is under two thousand rows and
     * the paging machinery would cost more to read than it saves. `local_date`
     * is the primary key, so this walks its B-tree in order.
     */
    @Query("SELECT * FROM capture_day ORDER BY local_date ASC")
    fun all(): List<CaptureDay>

    /**
     * A restore writing a day's record back, whole.
     *
     * `ABORT`, and therefore **not** the upsert the note at the top forbids: that
     * hazard is a writer holding one flag overwriting the other, where this one
     * either creates the row or fails, and its caller has both flags because they
     * came out of an export of this same table.
     */
    @Insert
    fun insertForImport(day: CaptureDay): Long

    /**
     * The batch form, for [my.pinged.ledger.transfer.ImportJson].
     *
     * Room's `@Insert(List)` runs the same number of INSERT statements as a
     * loop does -- see `CategoryDao.seedIfEmpty` -- but acquires the prepared
     * statement once and rebinds per row instead of acquiring and releasing
     * per row. Measured on a Pixel 10a emulator at 20,000 rows inside one
     * transaction: 997ms per-row against 231ms in batches of 500.
     */
    @Insert
    fun insertAllForImport(days: List<CaptureDay>)

    /**
     * **Import only.** The file's record of which days Pinged was watching replaces
     * the target's.
     *
     * The one row this can destroy is today's, on a device where the listener has
     * already bound since the reinstall; every other row is older than the export
     * and is in the file. That row comes back because [recordListenerBound] and
     * [recordNotificationSeen] re-create the row they cannot find -- but only if
     * they run, and [CaptureDays] gates both on a per-day memo.
     *
     * **That is why this is not a bare `@Query`:** it clears the memo in the same
     * transaction, so spec 11.3's delete-all-data gets the invalidation rather than
     * inheriting an obligation written down in a paragraph.
     */
    @Transaction
    fun deleteAllForImport(): Int {
        val deleted = deleteAll()
        CaptureDays.forgetWhatWasWritten()
        return deleted
    }

    @Query("DELETE FROM capture_day")
    fun deleteAll(): Int

    /**
     * The **captured days** in `[from, to]` (#81, Capture evidence; #64,
     * #75): every date with a row saying the listener was bound, and every
     * date holding a transaction captured from a notification.
     *
     * - **A capture-backed `txn` counts whatever its state**, pending,
     *   excluded or refunded: each proves the listener was watching that day,
     *   and a day drawn as spent must not also count as not captured.
     * - **A hand-entered `txn` (null `raw_capture_id`) is not evidence.**
     * - **`saw_any_notification` plays no part.** A quiet phone posts nothing
     *   on a day it is still watching.
     *
     * Unordered, deduplicated by the `UNION`. `CaptureEvidence.capturedDates`
     * is the calendar-day form `notCapturedDays` takes.
     *
     * **An inverted range answers empty**, the same answer as a range with no
     * evidence: `BETWEEN` matches nothing when `from > to`.
     *
     * **Today's row is written by a notification, a bind, or a foreground
     * that finds the listener bound** (#82), so a process bound since
     * yesterday gets its row from the first notification or the first
     * foreground, whichever is sooner. The foreground's write is launched, not
     * awaited, and `LedgerViewModel.refresh` reads on resume, so the first
     * read of a day can run before it lands; `notCapturedDays` never counts
     * today, so that race cannot grey a month.
     */
    @Query(CAPTURED_DATES_SQL)
    fun capturedDates(from: LocalDate, to: LocalDate): List<LocalDate>

    /**
     * The first day this table records, or null when it is empty: one half of
     * the history start (`CaptureEvidence.historyStart`). `MIN` of the primary
     * key, so one step down its B-tree.
     */
    @Query(EARLIEST_DATE_SQL)
    fun earliestDate(): LocalDate?

    companion object {
        /**
         * [capturedDates]'s query, hoisted so `QueryPlanTest` pins the plan of
         * this string and not a copy.
         *
         * Measured on emulator-5554: a primary-key range on `capture_day`, and
         * `index_txn_local_date_occurred_at`'s range on `txn`, merged for the
         * `UNION`. The planner prefers that range to `IS NOT NULL` on
         * `index_txn_raw_capture_id` (every captured transaction ever made)
         * without a `+` hint, unlike `TxnDao.COUNTED`'s `state`. Measured
         * there over 5,000 captured transactions and 740 rows: 0.30ms for a
         * month, 1.9ms for two years.
         */
        const val CAPTURED_DATES_SQL =
            """
            SELECT local_date FROM capture_day
            WHERE local_date BETWEEN :from AND :to AND listener_bound = 1
            UNION
            SELECT local_date FROM txn
            WHERE local_date BETWEEN :from AND :to AND raw_capture_id IS NOT NULL
            """

        /** [earliestDate]'s query, hoisted for `QueryPlanTest`. */
        const val EARLIEST_DATE_SQL = "SELECT MIN(local_date) FROM capture_day"
    }
}

