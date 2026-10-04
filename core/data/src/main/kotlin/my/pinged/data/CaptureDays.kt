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
 * restore and anything arriving later get it without knowing this object
 * exists. Spec 11.3's delete-all never empties the table -- it deletes the file
 * -- and is covered by `CaptureCaches.clear` and by the generation below.
 *
 * Without that, today's row is deleted, both writers stay gated, and the rhythm
 * grid hatches today as *not captured* while capture runs normally -- spec 4's
 * one honest signal about the app's own coverage, saying the opposite.
 *
 * **Each memo also names the database it was written into**, as a
 * [Databases.generation]. A delete or restore that fails after closing the
 * database never reaches `CaptureCaches.clear`, and a `CaptureCaches.clear`
 * whose own DataStore write fails stops before it reaches this object; the
 * table the memo describes is gone either way, and a memo that compares its
 * generation needs neither of them to say so.
 */
object CaptureDays {

    /**
     * In memory only, and that is a correctness property rather than a shortcut. A
     * durable copy lived in DataStore, where nothing could invalidate it: after a
     * restore the stale answer outlived every process restart. It bought one
     * primary-key UPDATE per process on a database the calling path has just
     * opened, which is exactly what losing the memo to process death costs.
     */
    @Volatile private var lastMarked: Written? = null

    /** As [lastMarked] is, for the binding observation. */
    @Volatile private var lastBound: Written? = null

    /**
     * A day recorded, and the [Databases.generation] it was recorded under.
     * One object rather than two fields, so a reader never pairs one write's
     * day with another's generation.
     */
    private class Written(val day: LocalDate, val generation: Long) {
        /**
         * Whether [day] is recorded in the database open now. The generation is
         * read by the caller before its write, so a reset landing during the
         * write leaves the older number here and the next call writes again --
         * one idempotent UPDATE, the safe direction.
         */
        fun covers(day: LocalDate, generation: Long) =
            this.day == day && this.generation == generation
    }

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
        val generation = Databases.generation.value
        if (lastMarked?.covers(localDate, generation) == true) return
        dao().recordNotificationSeen(localDate)
        lastMarked = Written(localDate, generation)
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
        val generation = Databases.generation.value
        if (lastBound?.covers(localDate, generation) == true) return
        dao().recordListenerBound(localDate, true)
        lastBound = Written(localDate, generation)
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
     * What a fresh process looks like from inside one -- called from
     * production, not only tests.
     *
     * `:feature:capture`'s `CaptureHealth.forgetProcessMemo()` calls this from
     * `CaptureCaches.clear`, which runs after spec 11.2's restore and spec
     * 11.3's delete-all. Both replace `capture_day` out from under this
     * process without restarting it, so without this call [lastMarked] and
     * [lastBound] would still name a day this object wrote into a table that
     * no longer has that row: the next notification or listener bind on that
     * same calendar day would see its memo already satisfied and skip the
     * write, so the first row in the new table would be silently lost until
     * midnight rolled the date over.
     *
     * Also what makes the same behaviour reachable from a test: the memo is
     * process-scoped by design, so what a *new* process does when it meets a
     * table an older one wrote is otherwise unreachable from a suite that
     * runs in one process.
     */
    fun forgetProcessMemo() = forgetWhatWasWritten()

    /** Tests only: the day [markNotificationSeen] believes it has written. */
    @VisibleForTesting
    fun dayLastMarked(): LocalDate? = lastMarked?.day
}
