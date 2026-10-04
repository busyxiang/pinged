package my.pinged.ledger.transfer

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import my.pinged.capture.CaptureCaches
import my.pinged.capture.SourceCounters
import my.pinged.data.DatabaseBeingDeletedException
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.entity.Arrival
import my.pinged.ledger.settings.HostileApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ImportJson.requireEmptyLedger` refuses a device holding anything, so a
 * restore onto a working phone is discard-then-import or nothing.
 */
@RunWith(AndroidJUnit4::class)
class RestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aBackupReplacesALedgerThatAlreadyHasRowsInIt() = runBlocking {
        val source = freshDatabase(context)
        val seeded = seedOneOfEverything(source)
        val bytes = exportBytes(source)
        source.close()

        // A device that has been capturing: ImportJson alone would refuse this.
        val busy = freshDatabase(context)
        busy.rawCaptureDao().insertAll(List(5) { bulkCapture(it) })
        busy.close()

        val report = Restore.replaceEverything(context, ByteArrayInputStream(bytes))

        assertTrue("nothing was restored", report.rows > 0)
        Databases.reset()
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(
                "the restored ledger does not match the file",
                seeded.captures.size,
                db.rawCaptureDao().countAll(),
            )
        }
    }

    /**
     * **The wipe is inside the translation, and only a throw out of it says
     * so.** `Wipe.everything` ends on `KeyStore.deleteEntry` and can also fail
     * on its own `IntegrityStore.forget`, both after `DatabaseKey.destroy` has
     * unlinked the ledger -- so what escapes it is a `KeyStoreException` or an
     * `IOException`. Left bare, `Transfers.restoreFrom` files it as a refusal
     * from before the wipe -- "Nothing on this phone was changed" -- over a
     * ledger that is already gone.
     *
     * [HostileApp] refuses `getDatabasePath(DatabaseFactory.NAME)`, which is
     * `DatabaseKey.destroy`'s second line: the ledger is destroyed by the
     * `deleteDatabase` above it and the throw lands immediately after, so the
     * exception this asserts on is raised exactly where the real ones are.
     * The probe that runs first is untouched, because it opens an absolute
     * path under a different name.
     */
    @Test fun aWipeThatThrowsIsReportedAsALostLedgerRatherThanEscaping() = runBlocking {
        val source = freshDatabase(context)
        seedOneOfEverything(source)
        val bytes = exportBytes(source)
        source.close()

        val hostile = HostileApp(context.applicationContext as Application).apply { refusing = true }
        val thrown = runCatching {
            Restore.replaceEverything(hostile, ByteArrayInputStream(bytes))
        }.exceptionOrNull()

        assertTrue(
            "a throw out of Wipe.everything was not translated: $thrown",
            thrown is RestoreLedgerLostException,
        )
        assertFalse(
            "the ledger survived, so RestoreLedgerLostException would be a false claim",
            context.getDatabasePath(DatabaseFactory.NAME).exists(),
        )
    }

    /**
     * **A restore cancelled before its wipe is cancelled, and the ledger it
     * would have replaced is untouched.** The user backing out of settings
     * mid-restore cancels the operation `Transfers` runs for that screen.
     * Nothing between staging and `DatabaseKey.destroy` suspends, so without
     * a check of its own the restore would go on to destroy the ledger, and
     * the first suspension point to notice the cancellation would be
     * `IntegrityStore.forget` inside the wipe: the ledger gone, the import
     * never run, and nothing told.
     *
     * Cancelled from the user's own stream, on its first read, which is the
     * earliest a restore can be cancelled once it has started. A verdict is
     * recorded first so the wipe's forget is a real suspension point -- as it
     * is on any device `PeriodicIntegrity` has checked -- and would surface
     * the cancellation there if nothing earlier did.
     */
    @Test fun aRestoreCancelledBeforeItsWipeLeavesTheLedgerAlone() = runBlocking {
        val bytes = aBackup()
        aLiveLedgerOfOneCapture()
        IntegrityStore.record(context, at = 1L, ok = false)

        val caller = Job()
        var cancelled = false
        val input = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            private fun cancelOnce() {
                if (!cancelled) {
                    cancelled = true
                    caller.cancel(CancellationException("the screen went away, for the test"))
                }
            }
            override fun read(): Int = inner.read().also { cancelOnce() }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, len).also { cancelOnce() }
        }
        val outcome = try {
            runIn(caller) { Restore.replaceEverything(context, input) }
        } finally {
            IntegrityStore.forget(context)
        }

        assertTrue("precondition: the stream was never read", cancelled)
        assertEquals(
            "A restore cancelled before anything was destroyed destroyed the " +
                "ledger anyway (outcome: $outcome)",
            1,
            ledgerCaptures(),
        )
        assertTrue(
            "A restore cancelled before its wipe did not cancel: $outcome",
            outcome?.exceptionOrNull() is CancellationException,
        )
    }

    /**
     * **Once the wipe has begun, a cancellation does not stop the import.**
     * A user leaving the screen is not asking for their ledger to be deleted
     * without its replacement, and half way is the worst place this
     * milestone can stop: the old ledger gone, the new one unwritten.
     *
     * Cancelled at the moment `DatabaseKey.destroy` has just unlinked the
     * file (the first [HostileApp.atResolution]), inside the stretch nothing
     * can undo. The verdict makes the wipe's forget a suspension point a
     * cancellation would surface at. The capture store is asserted too: its clear belongs
     * to the same finished restore, and a cancellation that skipped it would
     * leave the swept-pack mark of the ledger that just went.
     */
    @Test fun aRestoreCancelledOnceItsWipeHasBegunStillImports() = runBlocking {
        val source = freshDatabase(context)
        val expected = seedOneOfEverything(source).captures.size
        val bytes = exportBytes(source)
        source.close()
        aLiveLedgerOfOneCapture()
        IntegrityStore.record(context, at = 1L, ok = false)
        val counted = "com.example.counted"
        SourceCounters.countOne(context, counted, Arrival.POSTED, postTime = 1L)

        val caller = Job()
        val hostile = HostileApp(context.applicationContext as Application).apply {
            atResolution = { n ->
                if (n == 1) caller.cancel(CancellationException("the screen went away, for the test"))
            }
        }
        val (outcome, survivingCount) = try {
            val outcome = runIn(caller) { Restore.replaceEverything(hostile, ByteArrayInputStream(bytes)) }
            outcome to SourceCounters.seenCount(context, counted)
        } finally {
            IntegrityStore.forget(context)
            CaptureCaches.clear(context)
        }

        assertTrue("precondition: the destroy was never reached", caller.isCancelled)
        assertEquals(
            "A restore cancelled after its wipe had begun stopped there: the " +
                "previous ledger is gone and the backup was never written " +
                "(outcome: $outcome)",
            expected,
            ledgerCaptures(),
        )
        assertEquals(
            "The restore imported and then skipped clearing the capture store " +
                "of the ledger it replaced (outcome: $outcome)",
            0,
            survivingCount,
        )
    }

    /**
     * **A destroy that fails while the caller is being cancelled is still a
     * lost ledger, and says so.** The realistic pair is a Keystore alias
     * invalidated by an OTA and a user leaving the screen. Cancelled, the
     * restore's report would be a bare cancellation -- nothing filed, while
     * the ledger is already gone. Run to completion, the wipe's forget is not
     * cancelled either, so the verdict of the deleted ledger goes with it.
     */
    @Test fun aDestroyThatFailsWhileTheCallerIsCancelledIsStillALostLedger() = runBlocking {
        val bytes = aBackup()
        aLiveLedgerOfOneCapture()
        IntegrityStore.record(context, at = 1L, ok = false)

        val caller = Job()
        val hostile = HostileApp(context.applicationContext as Application).apply {
            refusing = true
            refusalIs = {
                caller.cancel(CancellationException("the screen went away, for the test"))
                IllegalStateException("the Keystore alias is gone, for the test")
            }
        }
        val (outcome, checkAt) = try {
            val outcome = runIn(caller) { Restore.replaceEverything(hostile, ByteArrayInputStream(bytes)) }
            outcome to IntegrityStore.lastCheckAt(context)
        } finally {
            IntegrityStore.forget(context)
        }

        assertTrue(
            "The destroy failed after unlinking the ledger and the restore " +
                "reported ${outcome?.exceptionOrNull()} rather than the lost ledger",
            outcome?.exceptionOrNull() is RestoreLedgerLostException,
        )
        assertEquals(
            "The wipe's forget was cancelled, so the settings screen goes on " +
                "reading the deleted ledger's verdict as the new one's",
            0L,
            checkAt,
        )
    }

    /**
     * **Inside the stretch that runs to completion, a `CancellationException`
     * is not a cancellation, and is reported like any other failure.** That
     * stretch ignores its caller's cancellation, so whatever raises one there
     * is not the screen going away -- and rethrown as one it would be filed
     * as nothing, the job ending with the ledger gone and the screen still
     * saying "Restoring". Raised by [HostileApp] from inside
     * `DatabaseKey.destroy`, after the file is unlinked.
     */
    @Test fun aCancellationRaisedInsideTheWipeIsALostLedger() = runBlocking {
        val bytes = aBackup()

        val hostile = HostileApp(context.applicationContext as Application).apply {
            refusing = true
            refusalIs = { CancellationException("raised inside the wipe, for the test") }
        }
        val thrown = runCatching {
            Restore.replaceEverything(hostile, ByteArrayInputStream(bytes))
        }.exceptionOrNull()

        assertTrue(
            "A CancellationException raised after the ledger was unlinked was " +
                "passed on as a cancellation, which files nothing: $thrown",
            thrown is RestoreLedgerLostException,
        )
    }

    /**
     * **A restore that fails after its wipe still tells every holder to
     * rebind, and still lifts its gate.** The wipe closed the database those
     * holders are bound to whether or not the import then ran, so without the
     * announcement the ledger goes on drawing the wiped rows while settings
     * says the ledger is gone; and a gate left up is capture refused for the
     * life of the process.
     */
    @Test fun aRestoreThatFailsAfterItsWipeStillAnnouncesAndLiftsItsGate() = runBlocking {
        val bytes = aBackup()
        Databases.reset()

        val hostile = HostileApp(context.applicationContext as Application).apply { refusing = true }
        val before = Databases.generation.value
        val thrown = runCatching {
            Restore.replaceEverything(hostile, ByteArrayInputStream(bytes))
        }.exceptionOrNull()

        assertTrue("precondition: the restore did not fail after its wipe: $thrown", thrown is RestoreLedgerLostException)
        assertEquals(
            "A restore that failed after wiping the ledger did not announce the " +
                "replacement, so every holder stays bound to the database it closed",
            before + 1,
            Databases.generation.value,
        )
        val reopened = runCatching { Databases.shared(context) }.exceptionOrNull()
        assertNull("A failed restore left its gate up: $reopened", reopened)
        Databases.reset()
    }

    /**
     * **Nothing else opens the database while a restore has it half written,
     * and the restore's own open is not refused.** The listener, `ParseWorker`
     * and `MainActivity`'s resume probe all reach `Databases.shared` from
     * outside settings' chain -- the listener on any app's notification. Let
     * through between the wipe and the import, the first of them mints a key
     * beside the import's own (`DatabaseKey.rawKeyPassphrase` checks for the
     * file and then creates it, unlocked, on a device whose Keystore alias
     * the wipe just removed), and opens a second Room instance on the file.
     *
     * Asked from `isCancelled`, which `ImportJson` calls after every row: the
     * one moment the file is certainly half written. The race itself -- two
     * keys minted -- is not reproduced here; what is pinned is that the
     * opener that would lose it is refused.
     */
    @Test fun nothingElseOpensTheDatabaseWhileARestoreImports() = runBlocking {
        val bytes = aBackup()
        Databases.reset()

        var during: Result<Any>? = null
        val report = Restore.replaceEverything(
            context,
            ByteArrayInputStream(bytes),
            isCancelled = {
                if (during == null) during = runCatching { Databases.shared(context) }
                false
            },
        )

        assertTrue("precondition: the import asked nothing per row", during != null)
        assertTrue(
            "Databases.shared answered $during while the restore's " +
                "import was writing the file",
            during?.exceptionOrNull() is DatabaseBeingDeletedException,
        )
        assertTrue("The restore's own open was refused: nothing was restored", report.rows > 0)
        Databases.reset()
    }

    /**
     * **The other half of `Wipe.everything`'s two-caller split, and the
     * expensive one.** A forget that fails after `DatabaseKey.destroy`
     * succeeded is an unfinished delete on the settings screen, which is why
     * `Wipe` reports it -- but on this path the wipe is only the means. Thrown
     * instead of returned, it becomes a [RestoreLedgerLostException] before
     * [Restore.rebuildAndImport] runs: the previous ledger gone, the new one
     * never written, the user told to restore again, and the retry re-entering
     * the same failing DataStore write, so the restore can never complete.
     * What is lost is a whole ledger; what failed is a preference write.
     *
     * The provocation is `WipeTest`'s, because it is the one that is
     * specifically DataStore's: a directory standing where
     * `datastore/integrity.preferences_pb.tmp` goes makes `edit` fail with
     * `IOException: Inoperable file`, and leaves SQLite untouched -- which is
     * what makes carrying on with the import the right answer rather than a
     * gamble. Copied rather than shared: `:core:data`'s androidTest source set
     * is not on this module's classpath.
     *
     * **The surviving timestamp is asserted as well as the import.** If
     * DataStore ever renames that scratch file, the forget quietly succeeds
     * and every assertion below passes for the ordinary reason.
     *
     * That the verdict is *left* on disk is the accepted cost, named in
     * [Restore.replaceEverything]: a stale `DAMAGED` banner over the restored
     * ledger until the weekly check records its own.
     */
    @Test fun aRestoreWhoseForgetFailsStillImports() = runBlocking {
        val source = freshDatabase(context)
        val seeded = seedOneOfEverything(source)
        val expected = seeded.captures.size
        val bytes = exportBytes(source)
        source.close()

        IntegrityStore.record(context, at = 1L, ok = false)
        val scratch = File(context.filesDir, "datastore/integrity.preferences_pb.tmp")
        assertTrue("the scratch path was already taken", scratch.mkdirs())
        val (report, checkAt) = try {
            val report = Restore.replaceEverything(context, ByteArrayInputStream(bytes))
            report to IntegrityStore.lastCheckAt(context)
        } finally {
            scratch.deleteRecursively()
            // `integrity` is one store for the whole process, so a verdict
            // left here is read by every class that runs after this one.
            IntegrityStore.forget(context)
        }

        assertEquals(
            "the forget succeeded after all, so this case no longer provokes what it " +
                "is named for",
            1L,
            checkAt,
        )
        assertTrue("nothing was restored", report.rows > 0)
        Databases.reset()
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(
                "the wipe reported a preference it could not clear and the import was " +
                    "skipped, so the ledger is gone and the backup was never written",
                expected,
                db.rawCaptureDao().countAll(),
            )
        }
    }

    /**
     * **A cache that will not clear stops nothing, so it is neither a lost
     * ledger nor a failure at all.** `CaptureCaches.clear` and
     * `TransferStore.forget` write the same `filesDir/datastore/` the wipe's
     * own `IntegrityStore.forget` does, so one unwritable directory fails all
     * three: the wipe returns its `Leftover`, the clear throws an
     * `IOException`, and the import would still succeed on the untouched
     * `databases/`. Let through, that exception skips the import, and
     * `Transfers.restoreFrom` files it as a refusal that changed nothing -- with
     * the ledger gone.
     *
     * The provocation is `WipeTest`'s, moved to the store it has to fail here:
     * a directory standing where `datastore/capture_health.preferences_pb.tmp`
     * goes. A count is put in that store first, because DataStore skips the
     * file write entirely when `edit` leaves the preferences unchanged --
     * measured, clearing an empty store under this directory succeeds. The
     * surviving count is asserted afterwards, so a DataStore that renames its
     * scratch file cannot leave this passing for the ordinary reason.
     */
    @Test fun aRestoreWhoseCacheClearFailsStillReportsTheImport() = runBlocking {
        val source = freshDatabase(context)
        val seeded = seedOneOfEverything(source)
        val expected = seeded.captures.size
        val bytes = exportBytes(source)
        source.close()

        val counted = "com.example.counted"
        SourceCounters.countOne(context, counted, Arrival.POSTED, postTime = 1L)
        val scratch = File(context.filesDir, "datastore/capture_health.preferences_pb.tmp")
        assertTrue("the scratch path was already taken", scratch.mkdirs())
        val (outcome, survivingCount) = try {
            val outcome = runCatching {
                Restore.replaceEverything(context, ByteArrayInputStream(bytes))
            }
            outcome to SourceCounters.seenCount(context, counted)
        } finally {
            scratch.deleteRecursively()
            // One store for the whole process, so a count left here is read by
            // every class that runs after this one.
            CaptureCaches.clear(context)
        }

        assertNull(
            "a DataStore write that failed after the import committed escaped the " +
                "restore, and nothing above it catches this: ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull(),
        )
        assertEquals(
            "the clear succeeded after all, so this case no longer provokes what it is " +
                "named for",
            1,
            survivingCount,
        )
        assertTrue("nothing was restored", outcome.getOrThrow().rows > 0)
        Databases.reset()
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(
                "the ledger does not match the file",
                expected,
                db.rawCaptureDao().countAll(),
            )
        }
    }

    /**
     * **A restore announces the replacement once, and only after its import.**
     * The wipe resets the shared handle and so does the import, and the
     * ledger screen rebinds on `Databases.generation` by opening the database
     * again. Announced at the wipe, that open lands on the file the import is
     * about to write -- beside `Restore`'s own instance, on a device that has
     * just lost its key.
     *
     * Read from `isCancelled`, which `ImportJson` asks after every row it
     * writes: the one moment the file is certainly half written.
     */
    @Test fun aRestoreAnnouncesItsReplacementOnceTheImportHasFinished() = runBlocking {
        val source = freshDatabase(context)
        seedOneOfEverything(source)
        val bytes = exportBytes(source)
        source.close()
        Databases.reset()

        val before = Databases.generation.value
        val seenDuringImport = mutableSetOf<Long>()
        Restore.replaceEverything(
            context,
            ByteArrayInputStream(bytes),
            isCancelled = {
                seenDuringImport += Databases.generation.value
                false
            },
        )

        assertTrue("precondition: the import asked nothing per row", seenDuringImport.isNotEmpty())
        assertEquals(
            "The restore announced a replacement while its import was still " +
                "writing, so every holder rebinding on it opens the half-written " +
                "file",
            setOf(before),
            seenDuringImport,
        )
        assertEquals(
            "The restore did not announce exactly one replacement once it had " +
                "finished",
            before + 1,
            Databases.generation.value,
        )
        Databases.reset()
    }

    /**
     * **The key race, made to happen.** Between the wipe and the restore's own
     * open there is no key file and no Keystore alias, and
     * `DatabaseKey.rawKeyPassphrase` checks for the file, then for a
     * database, then creates a key -- unlocked. An open by anyone else
     * landing between those steps creates a database and a key of its own:
     * here the restore's check then finds a database beside no key and
     * refuses, and the ledger is lost; landing a moment later instead, the
     * two opens mint two keys and whichever writes `db.key` last wins, so the
     * import can succeed into a file that never opens again.
     *
     * The other open is made from inside the restore's own key lookup (the
     * second [HostileApp.atResolution]), through the real context, which is
     * what the listener does on a notification arriving then.
     */
    @Test fun anOpenAtTheRestoresOwnKeyLookupIsRefused() = runBlocking {
        val source = freshDatabase(context)
        val expected = seedOneOfEverything(source).captures.size
        val bytes = exportBytes(source)
        source.close()
        aLiveLedgerOfOneCapture()

        var during: Result<Any>? = null
        val hostile = HostileApp(context.applicationContext as Application).apply {
            atResolution = { n -> if (n == 2) during = runCatching { Databases.shared(context) } }
        }
        val outcome = runCatching { Restore.replaceEverything(hostile, ByteArrayInputStream(bytes)) }

        assertTrue("precondition: the restore's own key lookup was never reached", during != null)
        assertTrue(
            "Databases.shared answered $during in the gap between the wipe and " +
                "the restore's own open",
            during?.exceptionOrNull() is DatabaseBeingDeletedException,
        )
        assertNull("The restore did not finish: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("the restored ledger does not match the file", expected, ledgerCaptures())
    }

    /** A backup of one of everything, written from a database that is then discarded. */
    private fun aBackup(): ByteArray {
        val source = freshDatabase(context)
        seedOneOfEverything(source)
        return exportBytes(source).also { source.close() }
    }

    /** The app's own database, holding one capture, and not held open. */
    private fun aLiveLedgerOfOneCapture() {
        freshDatabase(context).useDb { it.rawCaptureDao().insert(bulkCapture(0)) }
        Databases.reset()
    }

    /** Captures in the app's own database, read the way the next open reads them. */
    private fun ledgerCaptures(): Int {
        Databases.reset()
        return DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() }
    }

    /**
     * [block] on a scope of its own under [caller], as `SettingsViewModel`
     * runs a restore on `viewModelScope`, so a test can cancel it the way a
     * screen going away does.
     */
    private suspend fun <T> runIn(caller: Job, block: suspend () -> T): Result<T>? {
        var outcome: Result<T>? = null
        CoroutineScope(Dispatchers.Default + caller).launch {
            outcome = runCatching { block() }
        }.join()
        return outcome
    }
}
