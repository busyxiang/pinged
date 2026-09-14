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
     * `listener_bound` is a genuine state and does take a value: spec 10.2's
     * throttled heartbeat writes what it observed, and a day that started
     * bound and lost the grant should end up saying so.
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
     * How many days in `[from, to]` the listener was bound on.
     *
     * The caller compares this against the number of days the month has
     * *elapsed*, and greys the total when they differ (§8). Two reasons the
     * comparison is not in SQL: a day with no row at all is an uncaptured day,
     * and SQLite has no calendar table to left-join against, so the absent days
     * cannot be counted here; and "elapsed" depends on today, which a query
     * would have to be told anyway.
     *
     * `listener_bound = 1` and not `saw_any_notification`: a genuinely quiet
     * phone posts nothing for a day and is still being captured, so keying trust
     * on notifications seen would grey out a correct total.
     *
     * **An inverted range answers 0, which is indistinguishable from a month
     * with no coverage at all.** `BETWEEN` is `from <= x AND x <= to` and
     * matches nothing when `from > to`, so a caller that swapped its arguments
     * would grey out every total and read as a total capture failure. The
     * obligation is the caller's -- there is no answer this query could give
     * that would be more honest than the wrong one -- and
     * `LedgerViewModel.refresh` derives both ends from one `YearMonth`.
     *
     * **Today's row is written by a notification or by a rebind, and by
     * nothing else.** `CaptureDays.markListenerBound` is called only from
     * `onListenerConnected`, and the other writer is the row
     * [recordNotificationSeen] creates -- so a process that stays bound across
     * midnight writes neither, and the first foreground of a day counts today
     * as a gap until something posts. The month greys and self-corrects at the
     * next notification, erring toward the warning, but it will fire on
     * ordinary mornings. Fixing it means changing when `capture_day` rows are
     * written, which is capture-path work and not this table's.
     */
    @Query(
        """
        SELECT COUNT(*) FROM capture_day
        WHERE local_date BETWEEN :from AND :to AND listener_bound = 1
        """,
    )
    fun boundDayCount(from: LocalDate, to: LocalDate): Int
}
