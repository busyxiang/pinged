package my.pinged.capture

import android.content.Context
import android.util.Log
import my.pinged.data.DatabaseReplacedException
import my.pinged.data.Integrity
import my.pinged.data.IntegrityStore

/**
 * Spec 11.1's corruption detection, on the worker that already runs.
 *
 * **Not at open, and not per notification.** `integrity_check` costs 121ms at
 * 10,000 captures and 644ms at 50,000 against a 4ms open (design 2.1), and this
 * process starts per notification burst.
 *
 * **Not on the shared instance.** [Integrity.check] runs on one of its own,
 * because the pragma leaves the connection it walks answering code 26 to
 * everything once it has met damage -- measured, and on the shared instance
 * that would be every capture after the week's check lost. Beside it, stage one's
 * insert does not wait for the check: `Databases.aside` has the numbers.
 */
internal object PeriodicIntegrity {
    /** A week. Corruption keeps no schedule; this bounds the cost of looking. */
    const val EVERY_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    private const val TAG = "PeriodicIntegrity"

    /**
     * Returns whether a check actually ran, which is what the tests assert on.
     *
     * **A file a delete or restore has just removed is not checked**, and
     * nothing is recorded: `Integrity.check` refuses with
     * [DatabaseReplacedException] rather than open, so it neither recreates
     * the ledger between a restore's wipe and its own open nor writes a
     * verdict about no file. The check is still due on the next run. A gate
     * up while this runs throws `DatabaseBeingDeletedException` out, which
     * `ParseWorker`'s guard answers.
     *
     * @param check injected only so a test can produce a damaged verdict
     *   without a corrupted file; production has one caller and it takes the
     *   default.
     */
    suspend fun checkIfDue(
        context: Context,
        now: Long,
        check: (Context) -> Integrity.Result = Integrity::check,
    ): Boolean {
        val last = IntegrityStore.lastCheckAt(context)
        // `now < last` is due, not throttled: a clock moved backwards would
        // otherwise mute the check until wall time caught up.
        if (last != 0L && now >= last && now - last < EVERY_MILLIS) return false

        val result = try {
            check(context)
        } catch (replaced: DatabaseReplacedException) {
            Log.i(TAG, "The database was removed before its check could run; skipped until next due")
            return false
        }
        if (result is Integrity.Result.Damaged) {
            Log.e(TAG, "integrity_check reported: ${result.firstProblem}")
        }
        // **A delete that starts while the pragma runs can leave this record
        // behind.** The check runs inside `Databases.aside`, which holds the
        // delete's reset, and so its unlink, off until the pragma returns, so
        // the verdict is at least about the file as it was. But `Databases.whileDeleting` gates the
        // database, not this store: the delete's `IntegrityStore.forget` can
        // still run before the write below lands, and the user then sees a
        // wiped phone whose settings screen says it was checked moments ago.
        // Left open: closing it means teaching the gate about DataStore, and
        // the next check overwrites the record.
        IntegrityStore.record(context, at = now, ok = result is Integrity.Result.Ok)
        return true
    }
}
