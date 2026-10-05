package my.pinged.ledger.settings

import android.app.Application
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.pinged.capture.Corrections
import my.pinged.capture.Graph
import my.pinged.data.DatabaseKeyUnavailableException
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.PingedDatabase
import my.pinged.ledger.transfer.TransferStore

/**
 * Where an export goes, and how to take it back.
 *
 * An interface rather than a `ContentResolver` and a `Uri`: the behaviour worth
 * guarding is that a write which does not finish is *removed*, and a test
 * needing a document provider to assert that is a test nobody writes.
 */
interface ExportSink {
    fun open(): java.io.OutputStream

    /**
     * Called when the write did not finish. Must leave nothing behind, and
     * report whether it managed to: a caller that tells the user a truncated
     * file is gone when it is not has told them something that gets a
     * restore attempted against it one day.
     */
    fun delete(): Boolean
}

/**
 * The values the settings screen shows that live outside the database, each
 * in a DataStore: an interface so a test can hand in one that throws.
 * **Handed in rather than provoked on disk** because DataStore caches its
 * first successful read, so a preferences file corrupted from a test is never
 * read again -- see `ExportNudgeGuardTest`.
 */
internal interface StoredSettings {
    suspend fun damaged(context: Context): Boolean
    suspend fun lastCheckAt(context: Context): Long
    suspend fun lastExportAt(context: Context): Long

    object OnDisk : StoredSettings {
        override suspend fun damaged(context: Context) = IntegrityStore.damaged(context)
        override suspend fun lastCheckAt(context: Context) = IntegrityStore.lastCheckAt(context)
        override suspend fun lastExportAt(context: Context) = TransferStore.lastExportAt(context)
    }
}

/** The real one: a SAF document the user picked. */
class DocumentSink(
    private val resolver: android.content.ContentResolver,
    private val uri: android.net.Uri,
) : ExportSink {
    override fun open(): java.io.OutputStream =
        resolver.openOutputStream(uri) ?: error("The document provider would not open $uri")

    override fun delete(): Boolean =
        // Best effort: the document is on storage this app does not own, and a
        // provider that refuses is no reason to crash after a failed export.
        // `getOrDefault(false)` rather than `getOrThrow`: a provider that
        // throws instead of returning false is still a document not removed,
        // not a reason to crash after a failed export.
        runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
            .getOrDefault(false)
}

/**
 * Spec 9.5's screen, for the two sections this milestone builds: a view over
 * [Transfers], which runs its operations, and the reads that draw it.
 *
 * [Transfers] rather than WorkManager: surviving process death buys nothing
 * when a process death mid-export leaves a document that has to be discarded
 * anyway. [Transfers] rather than this holder's own scope: a restore outlives
 * the screen that started it (ruling R41), so its queue and its result have to
 * as well.
 *
 * **Every read here takes [Transfers.reading]**, the same turn every operation
 * takes, so a resume's read is ordered with them in the order they were asked
 * for -- see [Transfers]' KDoc for the two races that closes.
 */
class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(SettingsState().mirroring(Transfers.state.value))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /**
     * The [Outcome.serial] this holder last reacted to, under [settling].
     *
     * Starts at whatever [Transfers] already holds: an outcome that settled
     * before this screen existed is drawn (it is mirrored into [state]) but
     * not reacted to, because this screen's own first [load] reads the ledger
     * it left anyway.
     */
    private var settled = Transfers.state.value.outcome?.serial ?: 0L
    private val settling = Mutex()

    init {
        viewModelScope.launch {
            _state.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { watched -> if (watched) watchTransfers() }
        }
    }

    /**
     * Follows [Transfers] for as long as something draws [state].
     *
     * **Only while watched**, because reacting to an outcome is a read of the
     * ledger, and a holder nobody is drawing -- one kept in the back stack,
     * or a test's -- has no reason to spend one. [request] brings this
     * holder's own requests up to date whether or not it is watched, and a
     * screen coming back reads the ledger on its resume anyway.
     */
    private suspend fun watchTransfers() = coroutineScope {
        // Mirror, never suspending, so a running operation's progress reaches
        // the screen while [settle] below waits for its turn.
        launch { Transfers.state.collect { transfers -> _state.update { it.mirroring(transfers) } } }
        // Outcomes of operations this holder did not ask for -- another
        // screen's restore, finishing while this one is open.
        launch(Dispatchers.IO) {
            Transfers.state.map { it.outcome }.distinctUntilChanged().collect { outcome ->
                if (outcome != null) settleOnce(outcome)
            }
        }
    }

    /**
     * Tests only: a suspension point at the head of each operation and each
     * [load], once it has its turn. Ordering is invisible when every
     * operation takes the same few milliseconds, so holding one open is what
     * makes another's wait observable.
     */
    @VisibleForTesting
    internal var beforeEachOperation: suspend () -> Unit = {}

    /**
     * Fires a read from a non-suspend caller, in order with everything else
     * on [Transfers]' turn.
     *
     * `LifecycleResumeEffect`'s body is not a coroutine scope, and [refresh]
     * is suspend, so the effect needs a plain function to call.
     */
    fun load(): Job = viewModelScope.launch {
        Transfers.reading {
            beforeEachOperation()
            read()
        }
    }

    /**
     * Reads what the rows display, on [Transfers]' turn. **Runs no integrity
     * check**: 121ms per 10,000 captures (design 2.1) is not a cost to pay on
     * arrival at a screen.
     */
    suspend fun refresh() = Transfers.reading { read() }

    /**
     * Tests only: where [read] finds the three values it keeps outside the
     * database. See [StoredSettings].
     */
    @VisibleForTesting
    internal var stored: StoredSettings = StoredSettings.OnDisk

    /**
     * **No preference read may throw out of here.** Its callers are [load]'s
     * bare `viewModelScope.launch` and [request]'s, neither with a handler,
     * so a preferences file that will not read -- DataStore raises
     * `CorruptionException` for a truncated one, and a failing disk an
     * `IOException`, both [IOException] -- would crash this screen on every
     * arrival, and this is the screen a user comes to when something is
     * wrong. Each value it could not read is published as null and drawn as
     * nothing; see [SettingsState.lastExportAt]. Caught here rather than by a
     * `ReplaceFileCorruptionHandler` on the stores, which would turn a
     * truncated integrity file's damage verdict into no bad news, and does
     * nothing for a disk that will not read.
     *
     * **An unreadable verdict files [Storage.HEALTHY] with no counts.** The
     * database opened, and that is all that is known. [Storage.DAMAGED] would
     * say "part of Pinged's database is unreadable" with nothing behind it,
     * and send the user to salvage a ledger that is probably fine; HEALTHY
     * with counts would itemise the delete sheet off a database that may be
     * the damaged one [WipeCounts] refuses to count. Drawn this way the
     * screen makes neither claim, and "Check my data" -- drawn with no time
     * beside it, the time being in the same store -- is the one action that
     * finds out.
     */
    private suspend fun read() = withContext(Dispatchers.IO) {
        // Leased, as every statement on the shared instance is, so an instance
        // retired for damage while these counts run is not closed under them.
        Databases.leasing { readLeased() }
    }

    /**
     * [read]'s body.
     *
     * **A count that meets damage is damage**, and goes where every other
     * reader's does: the instance it poisoned is replaced and the damage
     * recorded, and the screen says DAMAGED. Swallowed, the counts would
     * draw HEALTHY with nothing beside it and leave the shared instance
     * answering code 26, for the next export to fail on as corruption.
     */
    private suspend fun readLeased() {
        // On the turn, so no restore or delete can land between this and the
        // file [opened] reaches: both publish before they let go of it.
        val asOf = Transfers.state.value.lastWipe?.serial ?: 0L
        val opened = opened()
        val db = (opened as? Opened.Database)?.db
        val damaged = if (db == null) null else storedOrNull("the integrity verdict") { stored.damaged(app) }
        val recorded = when {
            opened is Opened.Refused -> opened.storage
            damaged == true -> Storage.DAMAGED
            else -> Storage.HEALTHY
        }
        val met = Met()
        val sourcesOn = db?.let { countEnabledSources(it, met) }
        val correctionsPending = if (recorded == Storage.HEALTHY) db?.let { countCorrections(it, met) } else null
        val counts = if (damaged == null) null else db?.let { wipeCounts(recorded, it, met) }
        if (met.damage && db != null) {
            Databases.replacePoisoned(db)
            IntegrityStore.recordDamage(app)
        }
        val storage = if (met.damage) Storage.DAMAGED else recorded
        val lastExportAt = storedOrNull("the last export") { stored.lastExportAt(app) }
        val lastCheckAt = storedOrNull("the last check") { stored.lastCheckAt(app) }
        _state.update {
            it.copy(
                storage = storage,
                storageAsOf = asOf,
                sourcesOn = sourcesOn,
                correctionsPending = correctionsPending,
                wipeCounts = counts,
                lastExportAt = lastExportAt,
                lastCheckAt = lastCheckAt,
                loaded = true,
                reads = it.reads + 1,
            )
        }
    }

    /** [read]'s value, or null with the reason logged; see [read] for why. */
    private suspend fun <T : Any> storedOrNull(what: String, read: suspend () -> T): T? = try {
        read()
    } catch (thrown: IOException) {
        Log.w(TAG, "Could not read $what", thrown)
        null
    }

    /**
     * Null on anything but a healthy ledger, and null again if the scan does
     * not finish. See [WipeCounts] for why a count from a damaged database is
     * a confident lie, and [countEnabledSources] for why `HEALTHY` is not
     * proof the scan completes.
     *
     * **[storage] is a parameter rather than a read of `_state.value`.** The
     * field still holds whatever the last read left there when this runs, so
     * the case that matters is the *first* read of a device whose damage is
     * already on record: [opened] succeeds, `IntegrityStore.damaged` answers
     * true, [read] resolves [Storage.DAMAGED] -- and the field is still
     * [SettingsState]'s `HEALTHY` default. A version reading the field would
     * run all four counts against a database already known to be damaged and
     * publish exactly the confident lie [WipeCounts] exists to refuse.
     *
     * **[db] is the instance [opened] returned, not a second
     * `Databases.shared`.** A second call could meet a gate that rose between
     * the two and throw `DatabaseBeingDeletedException`, which the catch
     * below does not see. [Transfers.reading] already keeps every gate down
     * for the length of a read; passing [db] makes that true of this function
     * on its own. A reset closing [db] instead lands in the catch.
     *
     * Four queries on every resume, measured together at 0.648ms on
     * emulator-5554 over 10,000 captures and 5,000 transactions: two plain
     * `COUNT(*)`s at 0.048ms off a covering index,
     * [my.pinged.data.dao.TxnDao.countUnrejected] at 0.195ms, and
     * [my.pinged.data.dao.TxnDao.distinctMonthCount] at 0.354ms, which reads
     * the table. Well inside what a resume can spend.
     */
    private fun wipeCounts(storage: Storage, db: PingedDatabase, met: Met): WipeCounts? {
        if (storage != Storage.HEALTHY || met.damage) return null
        return try {
            WipeCounts(
                txns = db.txnDao().countUnrejected(),
                captures = db.rawCaptureDao().countAll(),
                merchants = db.merchantRuleDao().countAll(),
                months = db.txnDao().distinctMonthCount(),
            )
        } catch (thrown: SQLException) {
            // [SQLException], not [SQLiteException]: see [countEnabledSources].
            met.record(thrown)
            null
        }
    }

    /** Whether a count in one [readLeased] met damage. */
    private class Met {
        var damage = false
            private set

        fun record(thrown: SQLException) {
            if (Databases.poisons(thrown)) damage = true
        }
    }

    /**
     * `null` rather than `0` on a query that could not finish.
     *
     * [Storage.DAMAGED] comes from a persisted `IntegrityStore` flag, not a
     * live check, so every resume after a check has recorded damage re-runs
     * this count against a database that can abort mid-scan: SQLCipher
     * authenticates every page (`Integrity.check`'s KDoc), and the page this
     * query walks need not be the page the earlier check found ok. Uncaught,
     * that exception reaches [load]'s bare `viewModelScope.launch` with no
     * handler and kills the process -- `SourcesViewModel.refresh`'s KDoc names
     * the same hazard -- crashing the one screen built to offer salvage
     * exactly when the damage is real.
     *
     * **[SQLException], the superclass, not [SQLiteException].** A count on a
     * closed instance throws "Error code: 21, message: connection is closed"
     * as [SQLException] itself -- measured on emulator-5554 -- which a
     * `catch (SQLiteException)` never sees. One reaches here as [db] closed
     * underneath: `SettingsStateTest` closes it directly.
     */
    private fun countEnabledSources(db: PingedDatabase, met: Met): Int? = try {
        db.captureSourceDao().enabledCount()
    } catch (thrown: SQLException) {
        met.record(thrown)
        null
    }

    /**
     * Spec 5.5's corrections awaiting an answer, or null while the sweep is
     * unfinished or the read fails, as [countEnabledSources]'s does.
     *
     * Only on a [Storage.HEALTHY] ledger: it re-reads every unanswered
     * capture, so on a damaged one it walks pages the integrity check has
     * already said will not all read.
     */
    private suspend fun countCorrections(db: PingedDatabase, met: Met): Int? = try {
        Corrections.pending(app, db.rawCaptureDao(), Graph.ruleMatcher())?.size
    } catch (thrown: SQLException) {
        met.record(thrown)
        null
    } catch (thrown: IOException) {
        Log.w(TAG, "Could not read the corrections sweep's mark", thrown)
        null
    }

    /** [opened]'s answer: the database, or the [Storage] its refusal files as. */
    private sealed interface Opened {
        class Database(val db: PingedDatabase) : Opened

        class Refused(val storage: Storage) : Opened
    }

    /**
     * The database, or the reason it would not open filed as [Storage]: the
     * two states that differ only by which exception came out.
     *
     * **`DatabaseBeingDeletedException` cannot arrive here.** Restore and
     * delete are the only holders of `Databases.whileDeleting`, both run on
     * [Transfers]' turn, and every caller of this holds it -- so a screen
     * opened over another screen's restore waits for the turn rather than
     * meeting the gate. Were one to arrive it would file as
     * [Storage.UNREADABLE], whose "Nothing has been deleted" would be false.
     */
    private fun opened(): Opened = try {
        Opened.Database(Databases.shared(app))
    } catch (thrown: DatabaseKeyUnavailableException) {
        Opened.Refused(Storage.KEY_GONE)
    } catch (thrown: DatabaseUnavailableException) {
        Opened.Refused(Storage.UNREADABLE)
    }

    /** See [Transfers.check]. */
    fun check(): Job = request { requester -> Transfers.check(app, requester, beforeEachOperation) }

    /**
     * See [Transfers.export]. Cancelling the [Job] this returns, or leaving
     * the screen, abandons the export and deletes the document.
     */
    fun exportTo(sink: ExportSink): Job =
        request { requester -> Transfers.export(app, sink, requester, beforeEachOperation) }

    /**
     * See [Transfers.salvage]. Cancelling the [Job] this returns, or leaving
     * the screen, abandons it and deletes the document, as for [exportTo].
     */
    fun salvageTo(sink: ExportSink): Job = request { requester -> Transfers.salvage(app, sink, requester, beforeEachOperation) }

    /**
     * See [Transfers.restoreFrom]: [open] runs off the main thread, and a
     * document it cannot open is reported rather than thrown.
     */
    fun restoreFrom(open: () -> InputStream?): Job =
        request { requester -> Transfers.restoreFrom(app, open, requester, beforeEachOperation) }

    /** See [Transfers.deleteEverything]. */
    fun deleteEverything(): Job =
        request { requester -> Transfers.deleteEverything(app, requester, beforeEachOperation) }

    /** The screen has shown [outcome]; see [Transfers.acknowledge]. */
    fun acknowledge(outcome: Outcome) = Transfers.acknowledge(outcome.serial)

    /**
     * Hands an operation to [Transfers] with this call's own coroutine as its
     * requester, and completes once this holder has reacted to its outcome.
     *
     * **Undispatched**, so the request is made -- and claimed, or refused --
     * on the caller's own thread, before this returns: a screen cleared a
     * moment after a tap still made the request it tapped for, and
     * [Transfers] then abandons it because the requester is gone rather than
     * never hearing of it.
     *
     * An operation abandoned ends this [Job] cancelled, so a caller can tell
     * it from one that finished. **Cancelled, it still waits for the
     * operation to let go** -- an export to delete its document, a restore
     * past its wipe to finish, and one refused or never started to delete
     * the document it was handed -- so a `join` means the
     * operation is over.
     */
    private fun request(start: (requester: Job) -> Deferred<Transfers.Answer>): Job =
        viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            val pending = start(coroutineContext.job)
            val answer = try {
                pending.await()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { pending.await() }
                throw cancelled
            }
            _state.update { it.mirroring(Transfers.state.value) }
            when (answer) {
                is Transfers.Answer.Settled -> settleOnce(answer.outcome)
                is Transfers.Answer.Refused -> Log.i(TAG, "Refused while ${answer.busy} runs")
                Transfers.Answer.Abandoned -> throw CancellationException("The operation was abandoned")
            }
        }

    /** [settle], once per [Outcome.serial] and in serial order. */
    private suspend fun settleOnce(outcome: Outcome) = settling.withLock {
        if (outcome.serial <= settled) return@withLock
        settled = outcome.serial
        settle(outcome)
    }

    /**
     * Brings the rows up to date with what [outcome] did to the ledger.
     *
     * **A delete publishes a whole fresh state rather than a `copy`.** The
     * counts and the source total in the old one describe a database that no
     * longer exists, so they cannot be carried forward.
     *
     * **A delete that failed is not re-read.** Re-reading would re-enter
     * whatever just threw -- a full disk fails DataStore and Room alike -- and
     * the [Wipe.Leftover][my.pinged.data.Wipe.Leftover] case has a sharper
     * reason: the verdict it could not clear is on disk and true of the
     * ledger that just went, so a read would publish [Storage.DAMAGED] with a
     * salvage offer over the empty database [opened] had just created. The
     * next resume reads it, by which time the condition has either cleared or
     * is reportable on its own terms.
     *
     * Everything else re-reads: a check recorded its verdict, an export its
     * timestamp -- or damage -- and a restore replaced the ledger.
     */
    private suspend fun settle(outcome: Outcome) {
        if (outcome.operation == Operation.DELETE) {
            _state.value = SettingsState(loaded = true, reads = _state.value.reads).mirroring(Transfers.state.value)
            if (outcome.job is TransferJob.Failed) return
        }
        refresh()
    }

    private companion object {
        const val TAG = "PingedSettings"
    }
}

/** This state, with [Transfers]' half replaced by [transfers]. */
private fun SettingsState.mirroring(transfers: TransferState) =
    copy(running = transfers.running, outcome = transfers.outcome, loss = transfers.loss, lastWipe = transfers.lastWipe)
