package my.pinged.ledger

import android.annotation.SuppressLint
import android.app.Application
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.settings.ExportSink
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.settings.Storage
import my.pinged.ledger.settings.TransferJob
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.sources.SourcesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ledger's figures and the settings screen on a ledger with one damaged
 * page, where a read that meets it outside a transaction leaves the shared
 * connection answering code 26 to everything after (`DamagedConnectionTest`
 * in `:feature:capture` has the measurement and the capture half).
 *
 * **No Compose rule in this class, deliberately.** The rule collects
 * uncaught coroutine exceptions and hands them to the next failure as
 * suppressed, so a refresh that would kill the app is invisible under it;
 * the feed's half is `DamagedFeedTest`.
 */
@RunWith(AndroidJUnit4::class)
class DamagedLedgerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    @Before fun startClean() = runBlocking<Unit> {
        Transfers.forgetOutcome()
        IntegrityStore.forget(context)
        context.discardTheDatabase()
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        context.discardTheDatabase()
        IntegrityStore.forget(context)
        Transfers.forgetOutcome()
    }

    /**
     * "Check my data" finds the damage and records it, and the instance the
     * listener writes through reads afterwards exactly as it did before.
     */
    @Test(timeout = 60_000)
    fun aCheckFromSettingsLeavesTheSharedDatabaseAsItWas() = runBlocking<Unit> {
        context.damagedLedger()
        val shared = Databases.shared(context)
        val generation = Databases.generation.value

        val answer = Transfers.check(context, Job()).await()

        assertEquals(
            "the check did not find the damage, so this test proves nothing",
            TransferJob.Checked(ok = false),
            (answer as? Transfers.Answer.Settled)?.outcome?.job,
        )
        val read = runCatching { shared.captureSourceDao().all() }
        assertNull("a read on the shared instance after the check threw ${read.exceptionOrNull()}", read.exceptionOrNull())
        assertSame("the check replaced the shared instance", shared, Databases.shared(context))
        assertEquals("the check replaced the shared instance", generation, Databases.generation.value)
    }

    /**
     * The month's figures read through a poisoned connection. Uncaught on
     * `viewModelScope`, code 26 reached the main thread's handler, which in
     * the app is a process kill on opening the ledger. It is damage met, so
     * settings says DAMAGED afterwards.
     */
    @Test(timeout = 60_000)
    fun theLedgerOnAPoisonedConnectionReadsAndSettingsSaysDamaged() = runBlocking<Unit> {
        context.damagedLedger()
        context.poisonTheSharedInstance()
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> uncaught += thrown }
        val ledger = withContext(Dispatchers.Main) { LedgerViewModel(app) }
        try {
            withContext(Dispatchers.Main) { ledger.refresh() }.join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertEquals("the ledger's refresh threw past its guard", emptyList<Throwable>(), uncaught.toList())
        assertEquals("the ledger reported its storage unavailable", false, ledger.storageUnavailable.value)
        val settings = withContext(Dispatchers.Main) { SettingsViewModel(app) }
        settings.refresh()
        assertEquals(
            "settings does not say what met the ledger",
            Storage.DAMAGED,
            settings.state.value.storage,
        )
    }

    /**
     * An export on an instance an earlier read has poisoned, with the damage
     * on a page the export reads. It reports damage, the salvage wording, not
     * "file is not a database"; the damage is recorded, which is what moves
     * the settings screen to DAMAGED; and the instance is replaced, so the
     * next statement anyone makes on the shared instance reads.
     */
    @Test(timeout = 60_000)
    fun anExportOnAPoisonedConnectionReportsDamageAndReplacesIt() = runBlocking<Unit> {
        context.damagedLedger()
        context.poisonTheSharedInstance()
        val sink = object : ExportSink {
            override fun open(): OutputStream = ByteArrayOutputStream()
            override fun delete() = true
        }

        val answer = Transfers.export(context, sink, Job()).await()

        val job = (answer as? Transfers.Answer.Settled)?.outcome?.job
        assertTrue(
            "an export on a poisoned connection ended $job, not in the salvage wording",
            (job as? TransferJob.Failed)?.message?.contains(RESCUE_WORDING) == true,
        )
        assertTrue("the damage the export met was not recorded", IntegrityStore.damaged(context))
        val settings = withContext(Dispatchers.Main) { SettingsViewModel(app) }
        settings.refresh()
        assertEquals("settings after the export does not say DAMAGED", Storage.DAMAGED, settings.state.value.storage)
        val next = runCatching { Databases.shared(context).captureSourceDao().all() }
        assertNull("the shared instance after the export threw ${next.exceptionOrNull()}", next.exceptionOrNull())
    }

    /**
     * **An export on a connection another read poisoned, with the damage on
     * no page the export reads, finishes.** The damage is in an index; the
     * read that met it was a lookup through it, in a transaction, as the
     * feed's first page is. Run on the poisoned connection, the export
     * failed as the ledger's own corruption.
     */
    @Test(timeout = 60_000)
    fun anExportOnAConnectionAnotherReadPoisonedIsRunOnANewOne() = runBlocking<Unit> {
        context.damagedIndex()
        context.poisonTheSharedInstanceThroughItsIndex()
        val sink = object : ExportSink {
            override fun open(): OutputStream = ByteArrayOutputStream()
            override fun delete() = true
        }

        val answer = Transfers.export(context, sink, Job()).await()

        val job = (answer as? Transfers.Answer.Settled)?.outcome?.job
        assertTrue("an export whose own pages are sound ended $job", job is TransferJob.Exported)
        assertTrue("the damage the poisoned connection stood for was not recorded", IntegrityStore.damaged(context))
    }

    /**
     * **An export that meets damage records damage, not a check.** Nothing
     * checked the file; a check recorded here would move the last check's
     * time to now, putting the weekly one off a week and drawing a check
     * nobody ran.
     */
    @Test(timeout = 60_000)
    fun anExportThatMeetsDamageRecordsNoCheck() = runBlocking<Unit> {
        context.damagedLedger()
        val sink = object : ExportSink {
            override fun open(): OutputStream = ByteArrayOutputStream()
            override fun delete() = true
        }

        val answer = Transfers.export(context, sink, Job()).await()

        val job = (answer as? Transfers.Answer.Settled)?.outcome?.job
        assertTrue(
            "fixture: the export did not meet the damage but ended $job",
            (job as? TransferJob.Failed)?.message?.contains(RESCUE_WORDING) == true,
        )
        assertTrue("the damage the export met was not recorded", IntegrityStore.damaged(context))
        assertEquals("an export recorded a check", 0L, IntegrityStore.lastCheckAt(context))
    }

    /**
     * **Settings' counts on a damaged month say DAMAGED, and leave the
     * shared instance reading.** Swallowed, their failure would draw HEALTHY
     * with nothing beside it, leave the damage unrecorded, and leave the
     * instance answering code 26 to the next caller.
     */
    @Test(timeout = 60_000)
    fun settingsCountsThatMeetDamageSayDamagedAndReplaceTheInstance() = runBlocking<Unit> {
        context.damagedMonth()
        val settings = withContext(Dispatchers.Main) { SettingsViewModel(app) }

        settings.refresh()

        assertEquals("settings over counts that met damage", Storage.DAMAGED, settings.state.value.storage)
        assertTrue("the damage the counts met was not recorded", IntegrityStore.damaged(context))
        assertEquals("the counts recorded a check", 0L, IntegrityStore.lastCheckAt(context))
        val next = runCatching { Databases.shared(context).captureSourceDao().all() }
        assertNull("the shared instance after the counts threw ${next.exceptionOrNull()}", next.exceptionOrNull())
    }

    /**
     * **An allow-list toggle on a poisoned connection neither kills the app
     * nor half applies.** Its three statements ran unguarded on
     * `viewModelScope`: code 26 from the first reached the main thread's
     * handler, and a toggle OFF that died there left the source enabled and
     * its notifications' text still being stored.
     */
    @Test(timeout = 60_000)
    fun aToggleOnAPoisonedConnectionNeitherKillsTheAppNorHalfApplies() = runBlocking<Unit> {
        context.damagedLedger()
        val sources = withContext(Dispatchers.Main) { SourcesViewModel(app) }
        withContext(Dispatchers.Main) { sources.setEnabled(BANK, true) }.join()
        assertEquals("fixture: the bank was not enabled", true, enabledOnAnInstanceOfItsOwn())
        context.poisonTheSharedInstance()

        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> uncaught += thrown }
        try {
            withContext(Dispatchers.Main) { sources.setEnabled(BANK, false) }.join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertEquals("the toggle threw past its guard", emptyList<Throwable>(), uncaught.toList())
        assertEquals("the bank the user switched off is still enabled", false, enabledOnAnInstanceOfItsOwn())
    }

    /**
     * **A failure of Room's own work, swallowed, replaces the instance it
     * happened on.** A trigger sync that fails leaves the tracker believing
     * its triggers are in (room-runtime 2.8.4's `ObservedTableStates`), so
     * the feed would never hear of a write on that instance again; replaced,
     * it rebinds onto one whose tracker syncs afresh.
     */
    @SuppressLint("RestrictedApi")
    @Test(timeout = 60_000)
    fun aFailureOfRoomsOwnWorkReplacesTheInstanceItHappenedOn() = runBlocking<Unit> {
        val shared = Databases.shared(context)
        val generation = Databases.generation.value

        shared.getCoroutineScope().launch { throw full() }.join()

        // Within the bound's interval, which an earlier test's failure may
        // have started.
        val announced = withTimeoutOrNull(Databases.ROOM_FAILURE_INTERVAL_MILLIS + 2_000) {
            Databases.generation.first { it > generation }
        }
        assertNotNull("the replacement was never announced", announced)
        assertNotSame("the instance whose own work failed is still served", shared, Databases.shared(context))
    }

    /**
     * **At most one replacement an interval, and the interval doubling**,
     * for a failure that recurs on every instance -- a full disk -- which
     * would otherwise replace as fast as the feed can rebind, and at a fixed
     * interval would replace every five seconds for as long as the disk was
     * full. The second is made once the interval ends, not dropped: dropped,
     * it would leave that instance's feed deaf. The third waits twice as
     * long.
     */
    @SuppressLint("RestrictedApi")
    @Test(timeout = 60_000)
    fun aFailureOfRoomsOwnWorkThatRecursReplacesOnceAnInterval() = runBlocking<Unit> {
        val first = Databases.shared(context)
        val generation = Databases.generation.value
        first.getCoroutineScope().launch { throw full() }.join()
        val replaced = withTimeoutOrNull(Databases.ROOM_FAILURE_INTERVAL_MILLIS + 2_000) {
            Databases.generation.first { it > generation }
        }
        assertNotNull("the first failure was never replaced", replaced)

        val second = Databases.shared(context)
        second.getCoroutineScope().launch { throw full() }.join()
        val tooSoon = withTimeoutOrNull(1_000) { Databases.generation.first { it > generation + 1 } }
        assertNull("a second failure inside the interval replaced at once", tooSoon)
        val later = withTimeoutOrNull(Databases.ROOM_FAILURE_INTERVAL_MILLIS + 2_000) {
            Databases.generation.first { it > generation + 1 }
        }

        assertNotNull("a second failure inside the interval was never replaced", later)
        assertNotSame("the second instance whose own work failed is still served", second, Databases.shared(context))

        val third = Databases.shared(context)
        third.getCoroutineScope().launch { throw full() }.join()
        val atTheFirstInterval = withTimeoutOrNull(Databases.ROOM_FAILURE_INTERVAL_MILLIS + 1_000) {
            Databases.generation.first { it > generation + 2 }
        }
        assertNull("a third failure in a row replaced at the first interval", atTheFirstInterval)
        val atTheSecond = withTimeoutOrNull(Databases.ROOM_FAILURE_INTERVAL_MILLIS + 2_000) {
            Databases.generation.first { it > generation + 2 }
        }
        assertNotNull("a third failure in a row was never replaced", atTheSecond)
    }

    private fun full() = SQLiteFullException("database or disk is full (code 13), for the test")

    /**
     * **Not for damage**, which the guarded callers replace: replaced from
     * here it loops, the feed reading its damaged page onto every new
     * instance, whose sync then fails in turn.
     */
    @SuppressLint("RestrictedApi")
    @Test(timeout = 60_000)
    fun damageInRoomsOwnWorkIsLeftToTheGuardedCallers() = runBlocking<Unit> {
        val shared = Databases.shared(context)
        val generation = Databases.generation.value

        shared.getCoroutineScope()
            .launch { throw SQLiteDatabaseCorruptException("database disk image is malformed (code 11), for the test") }
            .join()

        assertSame("damage in Room's own work replaced the instance", shared, Databases.shared(context))
        assertEquals("damage in Room's own work moved the generation", generation, Databases.generation.value)
    }

    /**
     * **Room's own work on a poisoned connection does not kill the app.**
     * Measured on emulator-5554 with the ledger's month on a damaged page:
     * the invalidation tracker synced its triggers for the feed's
     * `PagingSource` with a `BEGIN` on a connection the month's read had just
     * poisoned, and code 26 came out of
     * `TriggerBasedInvalidationTracker.syncTriggers`, in a coroutine on
     * Room's scope, to the thread's handler -- a process kill.
     *
     * That interleaving is a race, and not provoked here: a feed loaded
     * straight onto an instance this class poisons was measured to return an
     * empty page rather than meet code 26. What is asserted is the thing that
     * decides where such a throw goes: Room's scope, the one the tracker runs
     * on, carries a handler. `getCoroutineScope` is Room's library-group API,
     * reached from a test only to throw into it.
     */
    @SuppressLint("RestrictedApi")
    @Test(timeout = 60_000)
    fun whatRoomsOwnWorkThrowsDoesNotReachTheThread() = runBlocking<Unit> {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> uncaught += thrown }
        try {
            Databases.shared(context).getCoroutineScope()
                .launch { throw SQLiteException("file is not a database (code 26), for the test") }
                .join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertEquals("a throw on Room's scope reached the thread's handler", emptyList<Throwable>(), uncaught.toList())
    }

    private fun enabledOnAnInstanceOfItsOwn(): Boolean? = DatabaseFactory.build(context).let { own ->
        try { own.captureSourceDao().byPackage(BANK)?.enabled } finally { own.close() }
    }

    private companion object {
        const val BANK = "my.pinged.test.bank"

        /** What `Transfers.export` says only of a file it stopped on damage. */
        const val RESCUE_WORDING = "can still be rescued"
    }
}
