package my.pinged.ledger.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.pinged.capture.CaptureCaches
import my.pinged.data.DatabaseReplacedException
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.Integrity
import my.pinged.data.IntegrityStore
import my.pinged.data.PingedDatabase
import my.pinged.data.Wipe
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ledger.transfer.ImportException
import my.pinged.ledger.transfer.Restore
import my.pinged.ledger.transfer.RestoreLedgerLostException
import my.pinged.ledger.transfer.SalvageJson
import my.pinged.ledger.transfer.TransferStore
import my.pinged.ledger.transfer.forgetWipedLedger

/**
 * What [Transfers] has running, the last thing it finished, the last lost
 * ledger no screen has shown yet, and the last operation that reached a wipe.
 *
 * [loss] is kept apart from [outcome] so that neither has to give way: a
 * check or an export settling after a lost ledger replaces [outcome], as it
 * always has, and [loss] stays until a screen acknowledges that one. Both
 * name the same [Outcome] when the loss is the latest thing that happened.
 */
data class TransferState(
    val running: TransferJob.Running? = null,
    val outcome: Outcome? = null,
    val loss: Outcome? = null,
    /**
     * The last restore or delete that reached its wipe -- restored, lost the
     * ledger, or deleted it -- for the life of the process, whatever has been
     * acknowledged. A screen's reading of storage from before it describes a
     * ledger that is gone, and the recovery notice's "nothing has been
     * deleted" is false once one that was not a restore has run.
     */
    val lastWipe: Outcome? = null,
)

/**
 * The one owner, for the whole process, of the settings screen's five
 * transfer operations: export, check, restore, delete and salvage. Ruling R41.
 *
 * **A restore's lifetime is the process** -- from its wipe on it runs to
 * completion whoever is still watching (`Restore`'s KDoc) -- so its
 * serialisation and its result have to be the process's too. Scoped to a
 * screen, a second screen's delete could queue behind a first screen's
 * restore and run beside it, and a restore's result would be written to a
 * holder already cleared. `SettingsViewModel` forwards requests here and draws
 * [state].
 *
 * ## One at a time, and a second request is refused
 *
 * [claimed] is taken at the request, so a second operation asked for while
 * one is running or waiting is refused on the spot rather than queued, and
 * the screen, which draws [TransferState.running] and disables the transfer rows
 * while it is set, has already said why. Refused rather than queued because
 * every one of them was chosen against what the screen showed, and the
 * running one is about to change that: a delete confirmed over counts a
 * restore is replacing, or a restore queued behind a delete, is a decision
 * nobody made.
 *
 * ## Every read of the ledger takes the same [turn]
 *
 * The operations and `SettingsViewModel`'s reads all run on [turn], in the
 * order they were asked for, so a read never lands inside an operation.
 * `Integrity.check` is measured at 121ms per 10,000 captures (design 2.1): a
 * resume's read beside it can see the verdict from before and publish after
 * it -- HEALTHY moments after a check found damage, or pre-wipe counts on the
 * screen a delete has just emptied. It is also what makes
 * `DatabaseBeingDeletedException` unreachable from a read: restore and delete
 * are the only callers of `Databases.whileDeleting`, and both hold [turn].
 *
 * ## Whoever asked can go away
 *
 * Each request names a `requester` -- the screen's own coroutine -- and
 * cancelling it cancels the operation. Nothing is destroyed before that is
 * checked: an operation that has not got [turn] never starts, and one that has
 * checks once more before it begins (R40's rule for restore, applied to all
 * five). From there a restore or a delete runs to completion. An export, a
 * salvage or a check has nothing to protect and is abandoned, the export and
 * the salvage deleting their documents. Whatever an operation was handed --
 * the export's or the salvage's document -- is let go of on every way out,
 * including the ones where its body never ran; a restore opens its document
 * only once its body runs.
 *
 * ## Outcomes are kept until a screen has shown them
 *
 * [TransferState.outcome] holds the last operation's result for the life of
 * the process, until [acknowledge] -- the screen's OK, or dismissing the
 * export sheet -- or until the next operation's replaces it. Every settings
 * screen opened meanwhile draws it, so one can be seen twice; that costs less
 * than a lost ledger shown to nobody. **A lost ledger is not replaced by a
 * lesser outcome**: [TransferState.loss] keeps it until it is acknowledged
 * itself, whatever settles after it. **Not across process death**: an
 * outcome nobody saw before the process went is gone, and the ledger it
 * described is what the next screen reads.
 *
 * ## Nothing escapes to the thread's handler
 *
 * An uncaught exception on a bare `SupervisorJob` scope reaches the thread's
 * default handler and kills the process, which `SettingsViewModel`'s own
 * guards were each built against. Every operation files its own failures as
 * [TransferJob.Failed]; [scope]'s handler logs anything that gets past them.
 */
object Transfers {
    private const val TAG = "PingedTransfers"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, thrown ->
            Log.e(TAG, "A transfer threw past its own handling", thrown)
        },
    )

    /** See the class KDoc: every operation and every read of the ledger. */
    private val turn = Mutex()

    /** The operation asked for and not yet finished; see the class KDoc. */
    private val claimed = AtomicReference<Claim?>(null)

    /** Identity, not the [Operation]: two checks in a row are two claims. */
    private class Claim(val operation: Operation)

    private val serials = AtomicLong(0)

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    /** What a request comes back with. */
    sealed interface Answer {
        data class Settled(val outcome: Outcome) : Answer

        /** Another operation was running; this one never started. */
        data class Refused(val busy: Operation) : Answer

        /** The requester went away, or the operation cancelled itself, first. */
        data object Abandoned : Answer
    }

    /**
     * [block] on [turn], behind whatever was asked for before it. For
     * `SettingsViewModel`'s reads; cancellable while it waits.
     */
    suspend fun <T> reading(block: suspend () -> T): T = turn.withLock { block() }

    /**
     * Clears the outcome a screen has shown, if it is still the one with
     * [serial] -- a newer one the screen has not drawn stays.
     */
    fun acknowledge(serial: Long) {
        _state.update {
            it.copy(
                outcome = it.outcome?.takeUnless { outcome -> outcome.serial == serial },
                loss = it.loss?.takeUnless { loss -> loss.serial == serial },
            )
        }
    }

    /** Tests only: one process runs every test class, and an outcome would carry over. */
    @VisibleForTesting
    internal fun forgetOutcome() {
        _state.update { it.copy(outcome = null, loss = null, lastWipe = null) }
    }

    /**
     * An integrity check, and its verdict recorded where the screen reads it.
     *
     * A database that does not open has nothing to check, and settles
     * [TransferJob.Idle]: the key-gone and unreadable states are the screen's
     * own read to report. That includes the check's own instance failing to
     * open -- code 26 on a fresh connection -- which is the unreadable state
     * and not a verdict. The record runs under [NonCancellable]: once the
     * pragma has run, its answer is true whether or not anyone is watching.
     *
     * **A record that does not land is a failure, and says what was found.**
     * The row beside "Check my data" shows the last check recorded, and the
     * screen's damaged state is read off the record too, so a verdict that
     * could not be written -- DataStore's `IOException` on a full disk -- is
     * shown nowhere else. Damage found and not recorded most of all.
     */
    fun check(context: Context, requester: Job, beforeStart: suspend () -> Unit = {}): Deferred<Answer> =
        perform(Operation.CHECK, requester, beforeStart) {
            // The shared instance opened first, as the screen's own read
            // opens it, so that a first check on a new install has a file to
            // check. The check itself runs on an instance of its own: the
            // pragma leaves the connection it walks answering code 26 once it
            // has met damage, and on this one capture would have gone with it.
            val ok = try {
                Databases.shared(context)
                Integrity.check(context) is Integrity.Result.Ok
            } catch (unavailable: DatabaseUnavailableException) {
                return@perform TransferJob.Idle
            }
            try {
                withContext(NonCancellable) { IntegrityStore.record(context, System.currentTimeMillis(), ok) }
            } catch (thrown: IOException) {
                return@perform TransferJob.Failed(
                    "Pinged read its whole database and " +
                        (if (ok) "found nothing wrong" else "found part of it unreadable") +
                        ", but could not save that result (${thrown.message ?: thrown.javaClass.simpleName}). " +
                        "Check your phone's storage, then check again.",
                )
            }
            TransferJob.Checked(ok)
        }

    /**
     * Writes the backup, and removes the document if it does not finish.
     *
     * Capture stalls for the write: measured at 334ms for 10,000 captures and
     * 1,243ms for 50,000. Sub-second at every size this is measured against,
     * which is why there is deliberately no cancel control -- a button for a
     * third of a second is ceremony, and also why there is no "capture is
     * paused" warning: that reads as alarm, not information, at this length.
     *
     * `isCancelled` is wired to this operation's own liveness instead, which
     * is its requester's: a screen that goes away cancels it, and the next
     * page boundary [ExportJson.write] checks turns that into
     * `TransferCancelledException`, a plain `RuntimeException`. **Anything
     * caught once this job is no longer active is an abandoned export, not a
     * failed one**: the document is deleted and the export ends as
     * [Answer.Abandoned], leaving whatever outcome came before it in place --
     * nobody asked for a report of it. The one abandoned export still
     * reported is the one whose document would not go: a truncated file where
     * the user put it reads as a backup, and only they can remove it now.
     *
     * Refused, or abandoned before its turn, the document the picker already
     * created is deleted too: nothing is going to be written into it.
     *
     * **A file written whole is kept, whatever recording its time does.**
     * [TransferStore.recordExport] is a DataStore write to this phone's own
     * disk, which can be full while the backup went to a provider elsewhere;
     * left to the generic catch below it would delete a complete backup and
     * report only the disk's message. Its failure is reported beside the rows
     * instead ([TransferJob.Exported.unrecorded]).
     */
    fun export(
        context: Context,
        sink: ExportSink,
        requester: Job,
        beforeStart: suspend () -> Unit = {},
    ): Deferred<Answer> = perform(Operation.EXPORT, requester, beforeStart, release = { sink.delete() }) { progress ->
        // Captured here, in the suspend body, because `isCancelled` below is
        // a plain () -> Boolean -- ExportJson calls it from inside a
        // blocking DAO loop, not a coroutine.
        val self = currentCoroutineContext().job
        // Inside the `try`, so a database that will not open still has its
        // document removed; kept, so damage can replace the instance it met.
        var opened: PingedDatabase? = null
        try {
            val db = readable(context).also { opened = it }
            val rows = sink.open().use { out ->
                ExportJson.write(
                    db,
                    out,
                    onProgress = progress,
                    isCancelled = { !self.isActive },
                )
            }
            // The file is whole from here; nothing below may delete it.
            val unrecorded = try {
                recordExport(context, System.currentTimeMillis())
                null
            } catch (thrown: CancellationException) {
                throw thrown
            } catch (thrown: Exception) {
                Log.w(TAG, "A complete export's time could not be recorded", thrown)
                thrown.message ?: thrown.javaClass.simpleName
            }
            TransferJob.Exported(rows, unrecorded)
        } catch (thrown: CancellationException) {
            // NonCancellable because this job is already cancelled -- an
            // unguarded suspend call here would be cancelled before it ran.
            withContext(NonCancellable) { sink.delete() }
            throw thrown
        } catch (thrown: SQLiteException) {
            if (!Databases.poisons(thrown)) return@perform failed(thrown, sink, self)
            // The likeliest way damage is found, because the check is weekly.
            //
            // Code 11 from the damage itself, or code 26 from an instance
            // another reader poisoned between [readable]'s probe and this.
            // Both are damage, and the instance is replaced either way: a
            // read that meets damage leaves its connection answering code 26,
            // inside a transaction as well as out (measured on emulator-5554).
            //
            // Recorded as damage met, not as a check: nothing checked the
            // file, and a check recorded here would put the weekly one off.
            opened?.let(Databases::replacePoisoned)
            val removed = sink.delete()
            IntegrityStore.recordDamage(context)
            TransferJob.Failed(
                "Pinged could not read all of your data, so this file was not " +
                    "finished" + if (removed) {
                        " and has been removed. Some of it can still be rescued."
                    } else {
                        ", and could not be removed automatically -- delete it by " +
                            "hand. Some of your data can still be rescued."
                    },
            )
        } catch (thrown: Exception) {
            failed(thrown, sink, self)
        }
    }

    /**
     * The shared instance, once a transaction on it has read, for [export].
     *
     * **Before the document is opened**, so trying again costs nothing
     * written. A connection another read has poisoned -- one through an
     * index the export never reads -- answers code 26 to everything; it is
     * replaced, the damage behind it recorded, and the next one taken, up to
     * three. Otherwise the export failed as the ledger's own corruption, with
     * the salvage wording, when every page it reads was sound.
     *
     * **A transaction**, because the export's read is one and runs on the
     * connection transactions take: a plain read can be served by another
     * connection of the pool and answer for the wrong one. Code 11 is this
     * probe's own read meeting damage, on the export's path too, and
     * [export] reports it.
     *
     * Under [Databases.whileLive], as the export is: no replacement can
     * close the instance under the transaction, and one already replaced is
     * refused and the next taken.
     */
    private suspend fun readable(context: Context): PingedDatabase {
        var db = Databases.shared(context)
        repeat(PROBES) {
            try {
                Databases.whileLive(db) { db.runInTransaction<Int> { db.categoryDao().countAll() } }
                return db
            } catch (replaced: DatabaseReplacedException) {
                db = Databases.shared(context)
            } catch (thrown: SQLiteException) {
                if (!Databases.poisons(thrown) || thrown is SQLiteDatabaseCorruptException) throw thrown
                Databases.replacePoisoned(db)
                IntegrityStore.recordDamage(context)
                db = Databases.shared(context)
            }
        }
        return db
    }

    /** Tests only: where [export] records a finished export. */
    @VisibleForTesting
    internal var recordExport: suspend (Context, Long) -> Unit = TransferStore::recordExport

    /** [readable]'s bound. */
    private const val PROBES = 3

    /** [failed]'s name for a salvage. */
    private const val A_RESCUE = "A rescue"

    /**
     * Design 5's salvage: what still reads, written to [sink] as a file
     * [restoreFrom] accepts, with what did not read counted (`SalvageJson`).
     *
     * Abandoned, refused or failed, it lets go of [sink] exactly as [export]
     * does and through the same code: [perform]'s release for a salvage that
     * never began, and [failed] for one that did, which deletes the document
     * and ends as [Answer.Abandoned] when its screen went away. Nothing on the
     * ledger changes and no verdict is recorded, so there is nothing to run
     * to completion; it is cancelled at the next read, as `RowReader`
     * asks before every one.
     *
     * Offered only from [Storage.DAMAGED], where damage is already on record,
     * so finding some is not news to record.
     */
    fun salvage(
        context: Context,
        sink: ExportSink,
        requester: Job,
        beforeStart: suspend () -> Unit = {},
    ): Deferred<Answer> = perform(Operation.SALVAGE, requester, beforeStart, release = { sink.delete() }) { progress ->
        val self = currentCoroutineContext().job
        try {
            val report = sink.open().use { out ->
                SalvageJson.write(context, out, onProgress = progress, isCancelled = { !self.isActive })
            }
            TransferJob.Salvaged(report)
        } catch (thrown: CancellationException) {
            withContext(NonCancellable) { sink.delete() }
            throw thrown
        } catch (thrown: DatabaseUnavailableException) {
            failed(thrown, sink, self, "Pinged could not open its database to rescue it: ${thrown.message}", A_RESCUE)
        } catch (thrown: SQLiteException) {
            failed(
                thrown,
                sink,
                self,
                "Pinged met damage it could not find its way past (${thrown.message}), so the " +
                    "rescue did not finish.",
                A_RESCUE,
            )
        } catch (thrown: Exception) {
            failed(thrown, sink, self, thrown.message ?: "The rescue did not finish.", A_RESCUE)
        }
    }

    /**
     * [export]'s and [salvage]'s answer to a failure that is not damage:
     * the document deleted, and [message] reported unless the screen went
     * away first. [what] names the operation to a user whose file is stuck.
     */
    private fun failed(
        thrown: Exception,
        sink: ExportSink,
        self: Job,
        message: String = thrown.message ?: "The export did not finish.",
        what: String = "An export",
    ): TransferJob {
        val removed = sink.delete()
        if (!self.isActive) {
            if (removed) throw CancellationException("The operation's screen went away", thrown)
            return TransferJob.Failed(
                "$what stopped when its screen closed, and its incomplete file " +
                    "could not be removed automatically -- delete it by hand.",
            )
        }
        return TransferJob.Failed(
            message + if (removed) {
                ""
            } else {
                " The incomplete file could not be removed automatically -- " +
                    "delete it by hand."
            },
        )
    }

    /**
     * Spec 11.2's restore.
     *
     * The two exceptions [Restore.replaceEverything] can throw are not the
     * same failure, and are not reported the same way. [ImportException] is a
     * refusal filed before the wipe ran -- the device is exactly as it was --
     * and its message is passed through exactly as written: every throw site
     * in `ImportJson` already ends its own message with "Nothing was
     * imported.". A [RestoreLedgerLostException] means the wipe already ran
     * and the rebuild or import then failed, so its message is passed through
     * the same way -- it already says the ledger is gone and the backup file
     * is safe to retry -- with [TransferJob.Failed.ledgerLost] set, so a
     * screen can title that failure as the more serious one it is.
     *
     * Anything else came from before the wipe -- staging the document or
     * probing it -- because `Restore` translates everything after it, so it
     * is a refusal too, and says so.
     *
     * [open] makes the stream once the restore has its turn, on this
     * operation's thread: the document the picker returned. A restore that
     * never starts never opens it, so has nothing to let go of.
     *
     * **A document that will not open is a refusal, reported like any
     * other**, and nothing on the ledger is touched. `openInputStream` throws
     * `FileNotFoundException` for a document gone or a provider that will not
     * serve it, and `SecurityException` for a grant it no longer honours;
     * neither may reach the picker's callback on the main thread, where it
     * would kill the app. [open] answering null is the provider declining
     * without a reason.
     */
    fun restoreFrom(
        context: Context,
        open: () -> InputStream?,
        requester: Job,
        beforeStart: suspend () -> Unit = {},
    ): Deferred<Answer> = perform(Operation.RESTORE, requester, beforeStart) { progress ->
        val input = try {
            open() ?: throw FileNotFoundException("the app holding it gave Pinged nothing to read")
        } catch (thrown: Exception) {
            return@perform TransferJob.Failed(
                "Pinged could not open that file (${thrown.message ?: thrown.javaClass.simpleName}). " +
                    "Nothing on this phone was changed.",
            )
        }
        try {
            TransferJob.Restored(Restore.replaceEverything(context, input, onProgress = progress))
        } catch (thrown: ImportException) {
            // ImportJson reads inside one transaction, so this rolled back.
            TransferJob.Failed(thrown.message.orEmpty())
        } catch (thrown: RestoreLedgerLostException) {
            TransferJob.Failed(thrown.message.orEmpty(), ledgerLost = true)
        } catch (thrown: CancellationException) {
            throw thrown
        } catch (thrown: Exception) {
            TransferJob.Failed(
                "Pinged could not read that file (${thrown.message ?: thrown.javaClass.simpleName}). " +
                    "Nothing on this phone was changed.",
            )
        } finally {
            runCatching { input.close() }
        }
    }

    /**
     * Spec 11.3's delete everything.
     *
     * **Run to completion once begun, under [NonCancellable].** The screen
     * going away after the wipe has started is not a request to stop half
     * way; [perform] has already refused to start it if the screen went first. A
     * [CancellationException] raised inside is therefore not a cancellation
     * and is filed like any other failure, as `Restore` does inside its own
     * wipe.
     *
     * **Guarded, and the guard is not an `SQLiteException` one.** None of the
     * three steps here goes through SQLite once the file is unlinked:
     * [Wipe.everything] throws `KeyStoreException` from the
     * `KeyStore.deleteEntry` inside `DatabaseKey.destroy`, and
     * [CaptureCaches.clear] is a DataStore write, which raises `IOException`
     * on a disk that will not take one. The realistic case is the one
     * `DatabaseKey`'s KDoc says must never crash-loop -- a Keystore entry
     * invalidated by an OTA.
     *
     * **[Wipe.Leftover] is the same failure arriving as a value, and is
     * reported as one.** [Wipe.everything] returns rather than throws when its
     * own [IntegrityStore.forget] is the only thing that failed, because
     * `Restore.replaceEverything` cannot afford to skip its import over one.
     * Here it is a delete that did not finish; ignored, the verdict stays on
     * disk with nothing asking for the second delete that clears it.
     *
     * **Every failure here is [TransferJob.Failed.ledgerLost], and the order
     * of [Wipe.everything] is what makes that true rather than cautious.**
     * `DatabaseKey.destroy` unlinks the database file, then the `-wal` and
     * `-shm` beside it, then the wrapped key file, and only then touches the
     * Keystore -- the one line of it that can throw. [Wipe.everything]'s own
     * [IntegrityStore.forget], then [CaptureCaches.clear] and
     * [TransferStore.forget], are later still. So anything reaching this catch
     * -- or returned as a [Wipe.Leftover], which that same `forget` is the
     * only source of -- happened after the key was unlinked, and without the
     * key this app cannot read the ledger again whether or not its file went.
     * What is left over is a stale Keystore alias, a stale integrity verdict,
     * or a DataStore still holding capture counters, all of which the next
     * delete finishes off; the message says so.
     */
    fun deleteEverything(context: Context, requester: Job, beforeStart: suspend () -> Unit = {}): Deferred<Answer> =
        perform(Operation.DELETE, requester, beforeStart) {
            withContext(NonCancellable) {
                try {
                    val leftover = Wipe.everything(context)
                    forgetWipedLedger(context)
                    if (leftover == Wipe.Leftover.NONE) {
                        TransferJob.Deleted
                    } else {
                        unfinishedDelete("the record of its last storage check could not be cleared")
                    }
                } catch (thrown: Exception) {
                    unfinishedDelete(thrown.message ?: thrown.javaClass.simpleName)
                }
            }
        }

    /**
     * The one sentence both halves of [deleteEverything]'s reporting use, so a
     * delete that got as far as unlinking the ledger cannot describe itself
     * two different ways depending on which step gave out.
     */
    private fun unfinishedDelete(reason: String) = TransferJob.Failed(
        "Everything on this phone is gone, but Pinged could not finish clearing " +
            "up after itself: $reason. Nothing is left to read. Delete everything " +
            "again to finish the job.",
        ledgerLost = true,
    )

    /**
     * Claims [operation], waits for [turn], and runs [body] on [scope] unless
     * [requester] has gone by then.
     *
     * **[requester]'s cancellation reaches the operation synchronously.** A
     * plain child [Job] of it is cancelled -- and so completes -- inside the
     * requester's own `cancel()`, and its completion handler cancels the
     * operation there and then. A handler on the requester itself would wait
     * for the requester's children to finish, and one of them, a read holding
     * [turn], releases it on the way out: the operation would be handed
     * [turn] before it heard.
     *
     * The outcome is published while [turn] is still held, so a read queued
     * behind the operation sees its result and the ledger it left together.
     * Publishing does not suspend, so it lands even when [body] has returned
     * into a cancelled job.
     *
     * [release] lets go of what the request was handed when [body] never
     * runs -- refused, or abandoned before or as it took [turn] -- and the
     * answer waits for it: see [releasing]. Once [body] has begun, letting go
     * is its own job, because only it knows whether the document it wrote is
     * finished.
     */
    private fun perform(
        operation: Operation,
        requester: Job,
        beforeStart: suspend () -> Unit,
        release: () -> Unit = {},
        body: suspend (progress: (Int) -> Unit) -> TransferJob,
    ): Deferred<Answer> {
        val claim = Claim(operation)
        if (!claimed.compareAndSet(null, claim)) {
            val busy = claimed.get()?.operation ?: operation
            Log.i(TAG, "$operation refused: $busy is running")
            val refused = CompletableDeferred<Answer>()
            releasing(release) { refused.complete(Answer.Refused(busy)) }
            return refused
        }
        _state.update { it.copy(running = TransferJob.Running(operation, 0)) }
        val progress: (Int) -> Unit = { rows ->
            _state.update {
                if (claimed.get() === claim) it.copy(running = TransferJob.Running(operation, rows)) else it
            }
        }

        var settled: Outcome? = null
        // Written before [work] completes and read in its completion handler,
        // which the job's own completion orders after it, as with [settled].
        var began = false
        // Lazy, so it cannot reach [turn] before [link] is in place to stop it.
        val work = scope.launch(start = CoroutineStart.LAZY) {
            turn.withLock {
                beforeStart()
                // The last moment the requester leaving costs nothing, and the
                // only check between a free turn and [body]. `Mutex.lock`
                // takes a free lock without suspending and without looking at
                // cancellation -- on kotlinx-coroutines 1.11.0 a `withLock`
                // block ran inside a job already cancelled -- and
                // [deleteEverything]'s body is `NonCancellable` from its first
                // line, so a requester cancelled between [work]'s dispatch and
                // here would otherwise start a delete. That window is a few
                // instructions wide in production; a cancel landing just after
                // this line loses the race, which is the screen going just
                // after the delete began. The falsifying test lands one here
                // through [beforeStart]: see TransfersTest's
                // aDeleteWhoseScreenWentAwayAsItTookItsTurnDestroysNothing.
                currentCoroutineContext().ensureActive()
                began = true
                val job = try {
                    body(progress)
                } catch (thrown: CancellationException) {
                    throw thrown
                } catch (thrown: Exception) {
                    Log.w(TAG, "$operation threw", thrown)
                    TransferJob.Failed(thrown.message ?: "$operation did not finish.")
                }
                val outcome = Outcome(serials.incrementAndGet(), operation, job)
                if (job is TransferJob.Failed) {
                    Log.w(TAG, "$operation failed (ledgerLost=${job.ledgerLost}): ${job.message}")
                } else {
                    Log.i(TAG, "$operation settled: $job")
                }
                // Published before the claim is let go, so no request can
                // claim and draw itself running in between only to be
                // overwritten here.
                _state.update {
                    TransferState(
                        running = null,
                        outcome = outcome,
                        loss = if (outcome.lostTheLedger) outcome else it.loss,
                        lastWipe = if (outcome.ranAWipe) outcome else it.lastWipe,
                    )
                }
                claimed.compareAndSet(claim, null)
                settled = outcome
            }
        }

        val answer = CompletableDeferred<Answer>()
        val link = Job(requester)
        link.invokeOnCompletion { cause -> if (cause != null) work.cancel(CancellationException("Its screen went away")) }
        work.invokeOnCompletion {
            val outcome = settled
            if (claimed.compareAndSet(claim, null)) _state.update { it.copy(running = null) }
            if (outcome == null) Log.i(TAG, "$operation abandoned")
            val finish: () -> Unit = {
                answer.complete(outcome?.let(Answer::Settled) ?: Answer.Abandoned)
                link.complete()
            }
            if (began) finish() else releasing(release, finish)
        }
        work.start()
        return answer
    }

    /**
     * Runs [release] off the caller's thread -- deleting a SAF document is
     * provider IO, and a completion handler runs on whichever thread
     * completed the job, the main one included -- and then [then].
     *
     * **[then] completes the request's answer, so it waits for [release].**
     * `SettingsViewModel.request` promises that a `join` means the operation
     * is over, and for one that never ran, letting go of the document is
     * all there is of it. The claim is not held meanwhile: a
     * slow provider delays this request's answer, not the next request.
     */
    private fun releasing(release: () -> Unit, then: () -> Unit) {
        scope.launch {
            try {
                runCatching(release)
            } finally {
                then()
            }
        }
    }

    /** What [TransferState.loss] keeps past a lesser outcome. */
    private val Outcome.lostTheLedger: Boolean get() = (job as? TransferJob.Failed)?.ledgerLost == true

    /**
     * What [TransferState.lastWipe] keeps. [TransferJob.Failed.ledgerLost] is
     * set only after a wipe ran; any other failed restore was refused before.
     */
    private val Outcome.ranAWipe: Boolean
        get() = job is TransferJob.Restored || job is TransferJob.Deleted || lostTheLedger
}
