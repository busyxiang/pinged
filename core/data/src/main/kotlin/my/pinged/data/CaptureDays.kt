package my.pinged.data

import androidx.annotation.VisibleForTesting
import my.pinged.data.dao.CaptureDayDao

/**
 * Spec 4's `capture_day`, written once per calendar day rather than once per
 * notification.
 *
 * A phone posts 100-300 notifications a day and both facts this table records
 * are per-day, so the writers below skip the database once the day is recorded.
 * That memo is what makes the table affordable on a path that runs before the
 * allow-list gate.
 *
 * **This lives in `:core:data`, beside the table it caches.** Two modules up,
 * every writer of `capture_day` owed an invalidation the type system could not
 * ask for -- and the one writer that deletes rows,
 * [CaptureDayDao.deleteAllForImport], is in this module and could not reach it.
 * The DAO now clears the memo in the same call that empties the table, so a
 * restore, spec 11.3's delete-all and anything arriving later get it without
 * knowing this object exists.
 *
 * Without that, today's row is deleted, both writers stay gated, and the rhythm
 * grid hatches today as *not captured* while capture runs normally -- spec 4's
 * one honest signal about the app's own coverage, saying the opposite.
 */
object CaptureDays {

    /**
     * In memory only, and that is a correctness property rather than a shortcut. A
     * durable copy lived in DataStore, where nothing could invalidate it: after a
     * restore the stale answer outlived every process restart. It bought one
     * primary-key UPDATE per process on a database the calling path has just
     * opened, which is exactly what losing the memo to process death costs.
     */
    @Volatile private var lastMarked: LocalDate? = null

    /** As [lastMarked] is, for the binding observation. */
    @Volatile private var lastBound: LocalDate? = null

    /**
     * A notification arrived on [localDate] (spec 10.2).
     *
     * The DAO arrives as a lambda so the in-memory gate can answer without
     * evaluating the accessor -- one accessor call on a path that has just opened
     * the database, and no more than that.
     *
     * `recordNotificationSeen`, never a whole-row upsert: a writer that knows only
     * "a notification arrived" would erase the rebind path's `listener_bound`.
     */
    fun markNotificationSeen(localDate: LocalDate, dao: () -> CaptureDayDao) {
        if (lastMarked == localDate) return
        dao().recordNotificationSeen(localDate)
        lastMarked = localDate
    }

    /**
     * The listener bound on [localDate] (spec 10.2).
     *
     * `onListenerConnected` is the honest signal: the system only calls it when it
     * has bound the service. Without this writer every row was created by
     * [markNotificationSeen] with `listener_bound` hardcoded true, so both columns
     * were constant and a quiet day with a healthy listener was byte-identical to a
     * day capture was dead.
     *
     * **The write first, the memo after.** Setting the memo first means a write
     * that throws is never retried; every reason this one can throw is a transient
     * storage failure the process outlives, and the only caller fires once per
     * bind, so one failure at boot lost the day.
     */
    fun markListenerBound(localDate: LocalDate, dao: () -> CaptureDayDao) {
        if (lastBound == localDate) return
        dao().recordListenerBound(localDate, true)
        lastBound = localDate
    }

    /**
     * Called by [CaptureDayDao.deleteAllForImport], in the same transaction
     * that empties the table.
     *
     * Inside the transaction rather than after it commits, which is the safe
     * direction of the two: a rollback then leaves the memo cleared when it
     * need not have been, costing one idempotent UPDATE, while a commit that
     * failed to clear leaves today missing from the grid until midnight.
     */
    internal fun forgetWhatWasWritten() {
        lastMarked = null
        lastBound = null
    }

    /**
     * Tests only: what a fresh process looks like from inside one.
     *
     * The memo is process-scoped by design, so the behaviour that matters most
     * -- what a *new* process does when it meets a table an older one wrote --
     * is otherwise unreachable from a suite that runs in one process.
     */
    @VisibleForTesting
    fun forgetProcessMemo() = forgetWhatWasWritten()

    /** Tests only: the day [markNotificationSeen] believes it has written. */
    @VisibleForTesting
    fun dayLastMarked(): LocalDate? = lastMarked
}
