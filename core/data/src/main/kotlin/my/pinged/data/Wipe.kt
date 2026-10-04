package my.pinged.data

import android.content.Context
import android.util.Log
import androidx.annotation.CheckResult
import kotlinx.coroutines.CancellationException

/**
 * Spec 11.3's delete everything.
 *
 * **The file goes, rather than `clearAllTables()` plus `VACUUM`.** Deleting it
 * makes the `VACUUM` moot and rotates the SQLCipher key in the same act. The
 * FTS shadow tables spec 11.3 names do not exist -- §15.2's search is not built.
 *
 * `CaptureCaches.clear` is the other half of a delete, and the caller makes
 * both: the DataStore is `internal` to `:feature:capture`.
 */
object Wipe {
    private const val TAG = "PingedWipe"

    /**
     * What [everything] could not clear up after itself, for a caller that has
     * to decide what that is worth.
     *
     * Not an exception, and not nothing: the two callers disagree about it.
     * For `Transfers.deleteEverything` the wipe is the whole point and
     * a verdict left on disk is an unfinished delete; for
     * `Restore.replaceEverything` it is a step on the way to an import that
     * can still succeed. See [everything].
     */
    enum class Leftover {
        NONE,

        /**
         * The database is gone and [IntegrityStore]'s verdict about it is not.
         * Whatever the last check said is now a statement about a file that
         * does not exist, and the settings screen reads it as a statement
         * about the one that replaced it.
         */
        INTEGRITY_VERDICT,
    }

    /**
     * Behind [Databases.whileDeleting], because the listener shares this
     * process and would otherwise rebuild the database between the file being
     * removed and the key being destroyed.
     *
     * `suspend` so that [IntegrityStore.forget] can run here rather than in a
     * caller. The verdict has two writers -- the settings screen and
     * `ParseWorker` -- so a delete cleaned up after by only one of them is a
     * delete whose completeness depends on which of them last ran. Both
     * production callers, `Transfers.deleteEverything` and
     * `Restore.replaceEverything`, are suspend already.
     *
     * **The forget is last, and the order is the claim callers make off it.**
     * [DatabaseKey.destroy] unlinks the files and the wrapped key first and
     * ends on `KeyStore.deleteEntry`, the one line of it that throws, so a
     * caller catching anything out of this function knows the key is already
     * gone -- `Transfers.deleteEverything`'s `ledgerLost` rests on
     * exactly that. A forget placed first would break it: a DataStore write
     * failing on a full disk would report a ledger lost that was never
     * touched.
     *
     * **A destroy that fails throws, and the forget still runs.** A
     * `deleteEntry` that throws leaves both database files already unlinked,
     * so the next open *succeeds* on a fresh empty database -- and a forget
     * skipped by that throw would have the settings screen headline a days-old
     * `DAMAGED` verdict about a database created seconds ago, and offer to
     * rescue it. It runs there best-effort: the exception the destroy raised
     * is what the caller's `ledgerLost` and the sentence the user reads are
     * about, and a cleanup failure replacing it would report the wrong thing
     * about a delete that had already lost the ledger for another reason.
     *
     * **A forget that fails after a destroy that succeeded is returned, not
     * thrown, because the two callers owe the user different answers.** For
     * `Transfers.deleteEverything` the wipe is the point, so
     * [Leftover.INTEGRITY_VERDICT] is an unfinished delete: it files
     * `TransferJob.Failed(ledgerLost = true)` asking for another delete, and
     * skips the `refresh()` that would otherwise publish `Storage.DAMAGED`
     * over the empty database the settings screen had just opened. For
     * `Restore.replaceEverything` the wipe is only the means -- throwing there
     * becomes `RestoreLedgerLostException` before `rebuildAndImport` runs, so
     * the old ledger is gone, the new one was never written, and a retry
     * re-enters the same failing DataStore write and never completes. That is
     * why this is not one policy: measured with the provocation `WipeTest`
     * uses -- a directory where `datastore/integrity.preferences_pb.tmp` goes
     * -- SQLite is unaffected and the import succeeds, so the whole cost of
     * carrying on is a stale banner.
     *
     * Marked [CheckResult] rather than trusted to be read: `lint` is a gate
     * here (`abortOnError`, root `build.gradle.kts`), so a third caller
     * dropping the value fails the build rather than quietly deciding the
     * leftover does not matter. Measured on this tree: ignoring it in
     * `SettingsViewModel` fails `:feature:ledger:lintDebug` with "Error: The
     * result of everything is not used [CheckResult]". Test sources are
     * checked too, so the call sites in `WipeTest` and `IntegrityStoreTest`
     * assert on it.
     *
     * A [CancellationException] from either forget is rethrown rather than
     * reported: the caller's scope is gone, and a cancellable caller files
     * nothing at a screen that left. Caught as an ordinary failure instead,
     * the one below a successful destroy becomes a [Leftover] and the one
     * below a failed destroy lets the destroy's own exception through.
     * `WipeTest` pins the first, `WipeCancellationTest` in `:feature:ledger`
     * the second. Neither production caller meets either:
     * `Restore.replaceEverything` and `Transfers.deleteEverything` both run
     * this under `NonCancellable`.
     */
    @CheckResult
    suspend fun everything(context: Context): Leftover {
        try {
            Databases.whileDeleting { DatabaseKey.destroy(context) }
        } catch (destroyFailed: Exception) {
            try {
                forgetVerdict(context)
            } catch (cancelled: CancellationException) {
                // `JobSupport` hands one `CancellationException` to every
                // suspension point of a cancelled job, so this and
                // [destroyFailed] are the same object whenever the destroy
                // failed by being cancelled -- and `Throwable.addSuppressed`
                // answers an exception added to itself with
                // `IllegalArgumentException`. Kotlin's extension is what saves
                // this: it returns before reaching that method when the two
                // are identical (`kotlin-stdlib` 2.3.20, `if_acmpeq`), so an
                // identity check here would only repeat it. Measured on
                // emulator-5554: nothing attached, nothing thrown.
                cancelled.addSuppressed(destroyFailed)
                throw cancelled
            }
            throw destroyFailed
        }
        return if (forgetVerdict(context)) Leftover.NONE else Leftover.INTEGRITY_VERDICT
    }

    /**
     * [IntegrityStore.forget], and whether it landed: a failure is logged and
     * answered false, a [CancellationException] rethrown (see [everything]).
     */
    private suspend fun forgetVerdict(context: Context): Boolean = try {
        IntegrityStore.forget(context)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (forgetFailed: Exception) {
        Log.w(TAG, "The database is gone and its integrity verdict is not", forgetFailed)
        false
    }
}
