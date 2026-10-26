package my.pinged

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import my.pinged.capture.CaptureReport
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.Category
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.test.ScriptedRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [AppStatus]'s sequencing (#304): which banner follows which sample, what
 * re-samples, and what the backup reminder hears, against a fake of every
 * store and an in-memory ledger. The decisions it is built from are
 * `BannerPriorityTest`, `ExportNudgeTest` and `HeardLineTest`; what needs the
 * real Activity, navigation and a real restore is still on the device.
 *
 * [Guarded] keeps the storage flag as `CaptureStorage.guarded` does: a read
 * that opens clears it, one refused raises it. Nothing re-samples on its
 * own: the flag's changes reach [AppStatus] only when a test sends them, so
 * each test is about the one trigger it names.
 *
 * The scope is `Dispatchers.Unconfined` on the test's own thread, as
 * `SpendingReadTest`'s is, and every fake answers at once, so each input
 * has landed by the next line. Anything that escapes into it is caught in
 * [escaped] and fails the test: in production it would kill the process.
 */
@RunWith(RobolectricTestRunner::class)
class AppStatusTest {
    private val escaped = ArrayList<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + SupervisorJob() + CoroutineExceptionHandler { _, thrown -> escaped += thrown },
    )
    private val scripted = ScriptedRunner(ApplicationProvider.getApplicationContext())
    private val sources = FakeSources()
    private val runner = Guarded()

    @After fun end() {
        scope.cancel()
        assertTrue("Something escaped into the scope, which kills the process: $escaped", escaped.isEmpty())
    }

    /** An [AppStatus] being drawn, as `MainActivity`'s is while started. */
    private fun shown(): AppStatus = AppStatus(scope, runner, sources).also { status ->
        scope.launch { status.state.collect {} }
    }

    private val AppStatus.current get() = state.value

    // -- The capture banner --------------------------------------------------

    /**
     * **A clear that lands after the banner sampled takes it down.** A
     * refusal raises the flag between the probe and the report -- a ledger
     * read refused mid-restore -- and the next open clears it; nothing
     * resumes, so only the flag's own change can re-sample (issue #16).
     */
    @Test fun aClearAfterTheBannerSampledTakesItDown() {
        val status = shown()
        sources.afterProbe = { sources.flag = true }
        status.resumed()
        assertEquals("precondition: the refusal never reached the banner", BannerKind.STORAGE_UNAVAILABLE, status.current.banner)

        sources.afterProbe = {}
        sources.flag = false
        sources.flagChanges.tryEmit(false)

        assertNull("The flag was cleared after the banner sampled it, and the banner stayed up", status.current.banner)
    }

    /**
     * **A cause that clears while the user is on Settings takes the banner
     * down with Settings' own read.** A full disk that clears, say: Settings
     * reads HEALTHY, and "Settings says more" above it is false.
     */
    @Test fun aBannerThatSettingsNoLongerBearsOutGoesAway() {
        val status = shown()
        scripted.refusing = true
        status.resumed()
        assertEquals("precondition: no storage banner", BannerKind.STORAGE_UNAVAILABLE, status.current.banner)

        scripted.refusing = false
        status.settingsRead()

        assertEquals(
            "Settings read the database and the banner over it still says it cannot be opened",
            null,
            status.current.banner,
        )
    }

    /**
     * **Every Settings read samples again, not only one that found something
     * new.** A capture refused as a gate lifts raises the flag between a
     * sample's probe and its report, over a ledger Settings reads as healthy
     * on every read after.
     */
    @Test fun aSettingsReadThatFindsNothingNewStillTakesAStaleBannerDown() {
        val status = shown()
        status.resumed()
        assertNull("precondition: a banner over a healthy ledger", status.current.banner)
        // The sample a rewrite makes, as the gate lifts.
        sources.afterProbe = { sources.flag = true }
        sources.rewrites.value++
        assertEquals("precondition: the refusal never reached the banner", BannerKind.STORAGE_UNAVAILABLE, status.current.banner)

        sources.afterProbe = {}
        status.settingsRead()

        assertNull("A Settings read found the ledger healthy, and the stale banner stayed up", status.current.banner)
    }

    /**
     * **A rewrite samples again.** A restore from key-gone replaces the
     * database and no resume follows, so without it "cannot open" stands over
     * Settings' "BACKUP RESTORED" for the rest of the visit.
     */
    @Test fun aRewriteOfTheLedgerSamplesTheBannerAgain() {
        val status = shown()
        scripted.refusing = true
        status.resumed()
        assertEquals("precondition: no storage banner", BannerKind.STORAGE_UNAVAILABLE, status.current.banner)

        scripted.refusing = false
        sources.rewrites.value++

        assertNull("The ledger was replaced by one that opens, and the banner still says it cannot", status.current.banner)
    }

    /**
     * **A count that throws still clears the flag.** A scan that aborts
     * mid-count raises `SQLiteException` out of `guarded`'s body, so the
     * clear it makes after a body that returned never happens for the count.
     * A foreground spent entirely in Settings has no ledger read to clear it
     * either, so the probe has to.
     */
    @Test fun aCountThatThrowsInASettingsOnlyForegroundStillTakesTheBannerDown() {
        val status = shown()
        scripted.refusing = true
        status.resumed()
        assertEquals("precondition: no storage banner", BannerKind.STORAGE_UNAVAILABLE, status.current.banner)

        scripted.refusing = false
        runner.countFails = true
        status.settingsRead()

        assertNull(
            "The database opened and the banner stayed up, because the reminder's count threw before " +
                "the flag was cleared",
            status.current.banner,
        )
    }

    /**
     * **A flag that will not write leaves the sample to finish.** `guarded`
     * clears the flag with a DataStore write after the body returns, and a
     * disk that will not write raises `IOException` from it, out of the
     * probe's read.
     */
    @Test fun aFlagThatWillNotWriteStillLetsTheSampleReport() {
        val status = shown()
        runner.flagWriteFails = true

        status.settingsRead()

        assertEquals("The sample stopped at the probe and never reached the report", "HEARD <5 MIN", status.current.heard)
    }

    /**
     * **The probe comes before the report.** The flag is written by whatever
     * opens the database, so a report read first describes the previous
     * foreground.
     */
    @Test fun eachSampleOpensTheDatabaseBeforeItReadsTheReport() {
        val status = shown()
        sources.events.clear()

        // Not `resumed`, which also reads the tab bar's count: that read
        // opens the database too, and is not the sample's.
        status.settingsRead()

        val opened = sources.events.indexOf("read:${AppStatus.PROBE}")
        val reported = sources.events.indexOf("report")
        assertTrue("A sample never opened the database: ${sources.events}", opened >= 0)
        assertTrue("A sample read the report before it opened the database: ${sources.events}", opened < reported)
    }

    /**
     * **Onboarding is decided from the report the sample read, after it**, so
     * first run cannot end on a grant the banner has not yet heard of; and
     * before the first sample, an install without the grant draws neither.
     */
    @Test fun onboardingFollowsTheGrantTheSampleReported() {
        sources.granted = false
        val status = shown()
        assertNull("Onboarding was decided before anything was sampled", status.current.onboarding)

        status.resumed()
        assertEquals("No grant and first run not over, and onboarding was not drawn", true, status.current.onboarding)

        // The grant lands after this sample's report and before its first
        // run is decided: the banner has not heard of it, so neither has
        // onboarding.
        sources.afterReport = { sources.granted = true }
        status.resumed()
        assertEquals(
            "Onboarding ended on a grant the banner it would uncover has not heard of",
            true to BannerKind.NOT_GRANTED,
            status.current.onboarding to status.current.banner,
        )
        sources.afterReport = {}

        sources.events.clear()
        status.resumed()

        assertEquals("The grant was reported and onboarding stayed", false, status.current.onboarding)
        assertEquals(
            "Onboarding was not decided once, after the report: ${sources.events}",
            listOf("report", "onboards:true"),
            sources.events.filter { it == "report" || it.startsWith("onboards") },
        )
    }

    // -- The backup reminder -------------------------------------------------

    /**
     * **An export recorded while the reminder is on screen takes it down.**
     * The export happens in Settings, and nothing about that is a resume.
     */
    @Test fun theReminderGoesWhenAnExportIsRecordedWhileItIsOnScreen() {
        val status = aReminderOnScreen()

        sources.lastExport = sources.time
        sources.exportChanges.tryEmit(sources.time)

        assertNull("The export was recorded and the reminder stayed up", status.current.backupReminder)
    }

    /**
     * **Its X writes the snooze, and it is the stored snooze that takes it
     * down**: the next launch reads the same store, so it stays down there
     * too.
     */
    @Test fun dismissingTheReminderStoresTheSnoozeAndReadsItBack() {
        val status = aReminderOnScreen()

        status.dismissBackupReminder()

        assertEquals("The X stored no dismissal", sources.time, sources.dismissedAt)
        assertNull("The X was tapped and the reminder stayed up", status.current.backupReminder)
        val relaunched = shown()
        relaunched.resumed()
        assertNull("The snoozed reminder came back on the next launch", relaunched.current.backupReminder)
    }

    /** A dismissal that did not store leaves the reminder up: that is what the next launch will show. */
    @Test fun aDismissalThatWillNotStoreLeavesTheReminderUp() {
        val status = aReminderOnScreen()
        sources.dismissalFails = true

        status.dismissBackupReminder()

        assertEquals(
            "The reminder went though its dismissal was never stored, so it comes back on the next launch",
            BackupReminderAction.Export,
            status.current.backupReminder,
        )
    }

    /**
     * **A ledger replaced under the reminder takes it down.** A delete whose
     * Keystore step throws after the files are unlinked, or a restore whose
     * import does not finish, writes nothing to the transfer store; the
     * reminder weighs the row count too.
     */
    @Test fun theReminderGoesWhenTheLedgerIsReplacedWhileItIsOnScreen() {
        val status = aReminderOnScreen()

        seed { db -> db.openHelper.writableDatabase.execSQL("DELETE FROM txn") }
        sources.rewrites.value++

        assertNull("The ledger was replaced by an empty one and the reminder stayed up", status.current.backupReminder)
    }

    /** On a damaged ledger its action is the rescue, which Settings offers there in place of the export. */
    @Test fun onADamagedLedgerTheReminderLeadsToTheRescue() {
        val status = aReminderOnScreen()

        sources.damaged.value = true

        assertEquals(BackupReminderAction.Rescue, status.current.backupReminder)
    }

    /**
     * **A transfer store that will not read silences the reminder** rather
     * than killing the process: DataStore raises `IOException` for a
     * truncated file or a disk that will not read.
     */
    @Test fun aStoreThatWillNotReadSilencesTheReminderInsteadOfKillingTheProcess() {
        val status = aReminderOnScreen()
        sources.exportReadFails = true

        status.resumed()

        assertNull("The reminder answered \"overdue\" for a store it could not read", status.current.backupReminder)
    }

    /**
     * **A ledger that will not count silences the reminder** rather than
     * killing the process: a scan that aborts mid-count raises
     * `SQLiteException`, which the runner lets through.
     */
    @Test fun aLedgerThatWillNotCountSilencesTheReminderInsteadOfKillingTheProcess() {
        val status = aReminderOnScreen()
        runner.countFails = true

        status.resumed()

        assertNull("The reminder answered \"overdue\" over a ledger it could not count", status.current.backupReminder)
    }

    /**
     * **A ledger being replaced silences the reminder** (#328): its count is of
     * the ledger going away, and the replacement is itself a restore or a
     * delete the user started.
     */
    @Test fun aLedgerBeingReplacedSilencesTheReminder() {
        val status = aReminderOnScreen()
        scripted.replacing = true

        status.resumed()

        assertNull("The reminder answered \"overdue\" over a ledger being replaced", status.current.backupReminder)
    }

    // -- The tab bar's count --------------------------------------------------

    /**
     * **A count read from the ledger being replaced is not drawn** (#328): a
     * refused read keeps its last answer, which is the ledger going away, so
     * the bar draws the dash, as it does for a ledger that cannot be read.
     */
    @Test fun theTabBarsCountIsADashWhileTheLedgerIsBeingReplaced() {
        val status = shown()
        status.barMayShow()
        assertEquals("precondition: the count was not read", 0, status.current.awaitingReviewCount)

        scripted.replacing = true
        status.barMayShow()

        assertNull("The tab bar drew the count of the ledger being replaced", status.current.awaitingReviewCount)
    }

    // -- The heard line ------------------------------------------------------

    /** A ledger left open keeps counting: the line is measured again each minute. */
    @Test fun theHeardLineIsMeasuredAgainEachMinute() {
        sources.lastHeard = sources.time - 4 * MINUTE
        val status = shown()
        status.resumed()
        assertEquals("precondition", "HEARD <5 MIN", status.current.heard)

        sources.aMinutePasses()

        assertEquals("A minute passed and the heard line did not move", "HEARD 5 MIN", status.current.heard)
    }

    // -- Helpers -------------------------------------------------------------

    /** A ledger with a row, an export a month old, capture healthy, and the reminder up. */
    private fun aReminderOnScreen(): AppStatus {
        seed { db ->
            db.categoryDao().insert(Category(UNCATEGORIZED, "Uncategorized", "other", 99, isProtected = true))
            db.txnDao().insert(aTxn())
        }
        sources.lastExport = sources.time - EXPORT_STALE_AFTER_MILLIS - 1
        val status = shown()
        status.resumed()
        assertEquals(
            "precondition: no reminder over a ledger with a row and a month-old export",
            BackupReminderAction.Export,
            status.current.backupReminder,
        )
        return status
    }

    private fun seed(body: (PingedDatabase) -> Unit) {
        scope.launch { scripted.read("The test cannot be seeded", body) }
    }

    private fun aTxn() = Txn(
        rawCaptureId = 1L,
        amountSen = 1_250L,
        direction = Direction.EXPENSE,
        occurredAt = sources.time,
        merchantRaw = "WARUNG PAK ALI",
        merchantDisplay = "Warung Pak Ali",
        merchantKey = "WARUNG PAK ALI",
        categoryId = UNCATEGORIZED,
        sourcePackage = "my.com.tngdigital.ewallet",
        sourceLabel = "Touch 'n Go eWallet",
        confidence = Confidence.HIGH,
        state = TxnState.COMMITTED,
        createdAt = sources.time,
        updatedAt = sources.time,
    )

    /**
     * [scripted], keeping [FakeSources.flag] as `CaptureStorage.guarded`
     * does, and running [FakeSources.afterProbe] after each read that opens.
     */
    private inner class Guarded : ReadRunner {
        /** The backup reminder's count throws as a scan aborting mid-count does. */
        var countFails = false

        /** The flag's write after a read throws, as a disk that will not write does. */
        var flagWriteFails = false

        override suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T> {
            sources.events += "read:$what"
            val outcome = scripted.read(what) { db ->
                if (countFails && what == AppStatus.COUNTING) throw SQLiteException("simulated scan that aborts mid-count")
                body(db)
            }
            if (flagWriteFails) throw IOException("simulated capture_health file that will not write")
            sources.flag = outcome is Outcome.Refused
            sources.afterProbe()
            return outcome
        }

        override suspend fun <T> write(what: String, oneTransaction: Boolean, body: suspend (PingedDatabase) -> T): Outcome<T> =
            scripted.write(what, oneTransaction, body)
    }

    /** Every store [AppStatus] reads, answering at once, with a clock the test moves. */
    private class FakeSources : StatusSources {
        val events = ArrayList<String>()

        var time = 1_800_000_000_000L
        var granted = true
        var flag = false
        var afterProbe: suspend () -> Unit = {}
        var afterReport: () -> Unit = {}
        var lastHeard = time - MINUTE
        var lastExport = 0L
        var dismissedAt = 0L
        var exportReadFails = false
        var dismissalFails = false

        val flagChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
        val exportChanges = MutableSharedFlow<Long>(extraBufferCapacity = 8)
        val damaged = MutableStateFlow(false)
        override val rewrites = MutableStateFlow(0L)

        private var minute = CompletableDeferred<Unit>()

        fun aMinutePasses() {
            time += MINUTE
            // Replaced before it completes: the waiter resumes inline, and
            // waits again at once.
            val passed = minute
            minute = CompletableDeferred()
            passed.complete(Unit)
        }

        override fun isGranted() = granted

        override suspend fun report(): CaptureReport {
            events += "report"
            return CaptureReport(
                granted = granted,
                lastNotificationAt = lastHeard,
                staleForMillis = time - lastHeard,
                storageUnavailable = flag,
            ).also { afterReport() }
        }

        override fun storageUnavailableChanges(): Flow<Boolean> = flagChanges

        override suspend fun lastExportAt(): Long {
            if (exportReadFails) throw IOException("simulated unreadable preferences file")
            return lastExport
        }

        override fun lastExportAtChanges(): Flow<Long> = exportChanges

        override suspend fun reminderDismissedAt() = dismissedAt

        override suspend fun recordReminderDismissed(at: Long) {
            if (dismissalFails) throw IOException("simulated preferences file that will not write")
            dismissedAt = at
        }

        override fun damagedUpdates(): Flow<Boolean> = damaged

        override suspend fun onboards(granted: Boolean): Boolean {
            events += "onboards:$granted"
            return !granted
        }

        override fun now() = time

        override suspend fun sleep(millis: Long) {
            assertEquals("The heard line is measured each minute", MINUTE, millis)
            minute.await()
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val UNCATEGORIZED = 1L
    }
}
