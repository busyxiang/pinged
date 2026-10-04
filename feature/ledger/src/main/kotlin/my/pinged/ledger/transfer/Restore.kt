package my.pinged.ledger.transfer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.Wipe

/**
 * Spec 11.2's restore, as the single act it has to be.
 *
 * **Nothing is destroyed until the document is known to be a backup.** Staged
 * to the cache directory first, because SAF gives an `InputStream` that cannot
 * be rewound and the read happens twice: once to validate, once to write in.
 *
 * **Discard, then import, under one confirmation.** `ImportJson` refuses a
 * device holding any capture, transaction or learned rule -- both files number
 * their rows from one, so no merge rule avoids throwing something away -- and
 * in the key-gone state there is no database to write into at all. Importing
 * *over* never happens, so offering it as a peer action would be offering
 * something that always fails.
 *
 * The caller confirms before this runs.
 *
 * ## The window between the wipe and a written ledger
 *
 * `Wipe.everything` cannot be made to wait until an import is certain to
 * succeed -- the import needs a database to write into, and that database is
 * the one just wiped. So there is an unavoidable stretch, [rebuildAndImport],
 * where the previous ledger is already gone and the new one is not yet
 * written. It opens at the `Wipe.everything` below rather than at
 * [rebuildAndImport], because the wipe's own last throwing line runs after the
 * files are unlinked. Anything that throws from either is reported as
 * [RestoreLedgerLostException] rather than left to look like an ordinary
 * refusal: the two are not the same failure. A refusal (any [ImportException],
 * thrown by [validate] before the wipe) means the device is exactly as it was.
 * A throw after it means it is not, and the message has to say so.
 *
 * **Nobody else is let into that window.** It runs inside
 * `Databases.whileDeleting`, so every other caller of `Databases.shared` --
 * the listener on any app's notification, `ParseWorker`, `MainActivity`'s
 * resume probe -- is refused for its whole length, and records that it was.
 * Let in, the first of them would call `DatabaseKey.rawKeyPassphrase` beside
 * [rebuildAndImport]'s own open, on a device whose Keystore alias the wipe
 * has just removed: that function checks for the key file and then creates
 * it, unlocked, so two opens can mint two keys, and the import can succeed
 * with `db.key` holding the other one -- a ledger reported restored that
 * never opens again. The refusal costs a notification arriving in the window,
 * which the empty new file would have dropped by default-deny anyway; refused,
 * it is recorded, and the storage banner says so until the next open.
 *
 * A caller that resolved the database before the gate rose holds an instance
 * the gate's own reset has closed, and `Databases.whileLive` refuses the
 * paths that would reopen the file through it (ruling R42).
 *
 * **Nor is the window left half way.** The cancellation check is the last
 * thing before the wipe, and from the wipe on the restore runs to completion
 * under `NonCancellable`: a user backing out of settings cancels the
 * operation `Transfers` runs for that screen, and that is not a request to
 * have the ledger deleted without its replacement -- the worst outcome this
 * can produce. Cancelled before the check, nothing has been touched.
 *
 * One thing inside that window is deliberately not reported as a lost ledger,
 * and it is below: a `Wipe.Leftover`, which is a preference this could not
 * clear and no reason to abandon an import that still works.
 */
object Restore {
    private const val TAG = "PingedRestore"

    /**
     * [isCancelled] is `ImportJson`'s per-row question, asked after the wipe:
     * a caller answering true there chooses the lost ledger this otherwise
     * refuses to stop at, which is why `Transfers` passes nothing.
     * Tests use it as the one hook inside the import.
     */
    suspend fun replaceEverything(
        context: Context,
        input: InputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ImportReport {
        val staged = File.createTempFile("restore", ".json", context.cacheDir)
        try {
            staged.outputStream().use { input.copyTo(it) }
            validate(context, staged)

            // The last point at which a cancellation costs nothing. Past it,
            // see the class KDoc: the wipe and the import finish together or
            // report why not.
            currentCoroutineContext().ensureActive()

            return withContext(NonCancellable) {
                Databases.whileDeleting {
                    wipe(context)
                    clearCaches(context)
                    rebuildAndImport(context, staged, onProgress, isCancelled)
                }
            }
        } finally {
            staged.delete()
        }
    }

    /**
     * [Wipe.everything], inside the same translation [rebuildAndImport] gets
     * and for the same reason. `DatabaseKey.destroy` unlinks the database, its
     * `-wal` and `-shm` and the wrapped key before it reaches
     * `KeyStore.deleteEntry`, the one line of it that throws -- an alias
     * invalidated by an OTA, which `DatabaseKey`'s KDoc says must never
     * crash-loop. So a throw here has already lost the previous ledger, which
     * is exactly what [RestoreLedgerLostException] states; left unwrapped it
     * is a `KeyStoreException` that `Transfers.restoreFrom` would file as a
     * refusal from before the wipe, "Nothing on this phone was changed", over
     * a ledger already gone.
     *
     * **A `CancellationException` is translated too.** This runs under
     * `NonCancellable`, so whatever raises one here is not the caller going
     * away -- and passed on as a cancellation it would end the job filing
     * nothing, with the ledger gone and the screen still saying "Restoring".
     */
    private suspend fun wipe(context: Context) {
        val leftover = try {
            Wipe.everything(context)
        } catch (thrown: Exception) {
            throw RestoreLedgerLostException(thrown)
        }
        if (leftover != Wipe.Leftover.NONE) {
            // Logged and carried on with. The wipe is the means here, not the
            // point: what failed is a DataStore write, measured (see
            // `Wipe.everything`) not to stop SQLite, so the import still
            // succeeds and is what the user asked for. Reporting it instead
            // would skip [rebuildAndImport] entirely -- the old ledger gone,
            // the new one never written, and a retry re-entering the same
            // failing write, so the restore could never complete.
            //
            // **The accepted cost is a stale banner.** The verdict left on
            // disk describes the ledger this just deleted, so the settings
            // screen headlines `DAMAGED` over the freshly restored one until
            // `PeriodicIntegrity`'s weekly check records its own. That is a
            // week of a wrong label against a restore that cannot otherwise
            // finish at all.
            Log.w(TAG, "The restored ledger carries a verdict left by the one it replaced")
        }
    }

    /**
     * What the process remembers about the ledger the wipe just removed.
     * `Reparse`'s KDoc names this as where a restore's cache invalidation
     * belongs -- without it a restored backlog arrives stamped at old pack
     * versions and is never re-read.
     *
     * **After the wipe and before the import, not after the import.**
     * `CaptureHealth`'s storage flag is in the store this clears, and a capture
     * refused during the import records itself there; cleared afterwards,
     * every refusal the import caused would be erased and the drop would be
     * silent after all. `CaptureDuringRestoreTest` holds the order.
     *
     * **Guarded, and deliberately not reported as anything.** Both are
     * DataStore writes into the same `filesDir/datastore/` the wipe's own
     * `IntegrityStore.forget` uses, so the fault that makes that one return
     * `Leftover.INTEGRITY_VERDICT` -- the directory unwritable -- reaches
     * these too, as an `IOException`. Not a [RestoreLedgerLostException]: it
     * does not stop the import, so every word of that sentence would be false
     * after a restore that went on to succeed. Nothing is reported to the user
     * either, because what is left is not something they can act on: a
     * swept-pack mark, which delays the re-read of the restored backlog only
     * until the next pack version ships (`Reparse.sweep` requeues once
     * `swept < packVersion`), and a last-export timestamp, which keeps section
     * 8's nudge quiet for one interval.
     */
    private suspend fun clearCaches(context: Context) {
        try {
            forgetWipedLedger(context)
        } catch (thrown: Exception) {
            Log.w(TAG, "The restored ledger carries caches left by the one it replaced", thrown)
        }
    }

    /**
     * Into a throwaway database, because that is the only honest test of
     * whether it restores: `ImportJson`'s guards -- the format version, the six
     * sections, `verifyReferences` -- need a real schema to run at all.
     *
     * [scratch] is unique per call, the way [replaceEverything]'s staged file
     * already is: a fixed name here would let two concurrent restores race on
     * `deleteRecursively`, `mkdirs` and `probe.db`.
     */
    private fun validate(context: Context, staged: File) {
        val scratch = File.createTempFile("restore-probe", "", context.cacheDir).apply {
            delete()
            mkdirs()
        }
        try {
            val probe = DatabaseFactory.buildAt(context, File(scratch, "probe.db"))
            try {
                staged.inputStream().use { ImportJson.read(probe, it) }
            } finally {
                probe.close()
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * The one stretch of [replaceEverything] that runs with no ledger at all:
     * the previous one is gone ([Wipe.everything] already ran) and this either
     * finishes writing the new one or it does not. Anything thrown here --
     * `DatabaseFactory.build` failing, or `ImportJson.read` failing on a disk
     * problem after [staged] already passed [validate] against an identical
     * schema -- is rethrown as [RestoreLedgerLostException], because none of
     * this module's other exceptions say the one thing the caller now has to:
     * the previous ledger is gone, [staged]'s source file is untouched, and
     * running the restore again is the way through.
     */
    private suspend fun rebuildAndImport(
        context: Context,
        staged: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): ImportReport = try {
        val db = DatabaseFactory.build(context)
        try {
            staged.inputStream().use { ImportJson.read(db, it, onProgress, isCancelled) }
        } finally {
            // The process keeps its own handle; drop this one so
            // `Databases` rebuilds against the file the import just wrote.
            db.close()
            Databases.reset()
        }
    } catch (e: Exception) {
        throw RestoreLedgerLostException(e)
    }
}

/**
 * `replaceEverything` wiped the previous ledger and then could not finish
 * writing the new one.
 *
 * Not an [ImportException]: those describe a document refused before
 * anything was touched, which is not what happened here -- the document
 * already passed [Restore]'s probe. The file the user chose is still on
 * their storage, untouched, so the caller's message has to say the two
 * things an ordinary "nothing was imported" refusal does not: the ledger
 * that was here before is gone, and running the same restore again is safe
 * and is how it comes back.
 */
class RestoreLedgerLostException(cause: Throwable) : RuntimeException(
    "Restoring this backup deleted the previous ledger, and this device could not " +
        "finish writing the new one: ${cause.message}. The backup file you chose is " +
        "unchanged -- restoring it again is safe, and is how to recover.",
    cause,
)
