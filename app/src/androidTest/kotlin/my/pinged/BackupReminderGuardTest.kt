package my.pinged

import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import my.pinged.capture.CaptureCaches
import my.pinged.capture.CaptureReport
import my.pinged.capture.GuardedRunner
import my.pinged.capture.ListenerStatus
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [AppStatus]'s backup reminder does when the real ledger will not count
 * under it, through `GuardedRunner` over the app's own database. The failures
 * a fake can hand in -- a store that will not read, a scan that aborts -- are
 * `AppStatusTest`'s; these two need the real connection and the real guard.
 *
 * In production [AppStatus]'s scope is `viewModelScope`, with no
 * `CoroutineExceptionHandler`: a throw there kills the process on the home
 * screen, on every foreground, for as long as the condition lasts. So the
 * subject is that nothing escapes, and that the reminder says nothing
 * instead. Each test first shows the reminder up over a healthy count, so
 * its going away is something the failure did.
 *
 * The capture report is handed in healthy, because the reminder never shows
 * beside a capture banner and this device's capture may not be healthy.
 */
@RunWith(AndroidJUnit4::class)
class BackupReminderGuardTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val escaped = CopyOnWriteArrayList<Throwable>()

    /**
     * **A connection closed under the count** -- a delete or a restore
     * resetting the database past its lease wait -- answers "Error code: 21,
     * message: connection is closed" as `android.database.SQLException`,
     * which `SQLiteException` extends rather than the other way round
     * (measured on emulator-5554). The real exception, from the real count:
     * the instance is closed while `Databases` still holds it.
     */
    @Test fun aConnectionClosedUnderTheCountSilencesTheReminderInsteadOfKillingTheProcess() {
        try {
            withTheReminderUp(GuardedRunner(context)) { status ->
                Databases.shared(context).close()

                onMain { status.settingsRead() }

                assertTrue(
                    "The reminder stayed up over a count on a closed connection, or the count " +
                        "threw out of it: $escaped",
                    awaitReminder(status, null),
                )
            }
        } finally {
            Databases.reset()
        }
    }

    /**
     * **Damage under the count silences the reminder and says nothing about
     * capture.** The count meets code 11 on the first connection and on the
     * one that replaces it, so the read is refused; raising the storage flag
     * for that would put "nothing new is being recorded" above the home
     * screen on every foreground while capture works.
     */
    @Test fun aDamagedCountSilencesTheReminderAndLeavesTheCaptureBannerDown() {
        runBlocking { CaptureCaches.clear(context) }
        val runner = DamagedCount(GuardedRunner(context))
        try {
            withTheReminderUp(runner) { status ->
                runner.damaged = true

                onMain { status.settingsRead() }

                assertTrue("The reminder stayed up over a count it could not read: $escaped", awaitReminder(status, null))
                assertFalse(
                    "Damage under the reminder's count put the capture banner up",
                    runBlocking { ListenerStatus.report(context) }.storageUnavailable,
                )
            }
        } finally {
            runBlocking { CaptureCaches.clear(context) }
            runBlocking { IntegrityStore.forget(context) }
        }
    }

    /** [block] with an [AppStatus] on screen over a ledger with a row and no export, its reminder up. */
    private fun withTheReminderUp(runner: ReadRunner, block: (AppStatus) -> Unit) {
        runBlocking { IntegrityStore.forget(context) }
        aRow()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, thrown -> escaped += thrown },
        )
        try {
            val status = AppStatus(scope, runner, HealthyCapture(StatusSources.OnDisk(context)))
            scope.launch { status.state.collect {} }
            onMain { status.settingsRead() }
            assertTrue(
                "precondition: no reminder over a ledger with a row and no export",
                awaitReminder(status, BackupReminderAction.Export),
            )
            block(status)
            assertEquals("Something escaped into the scope, which kills the process", emptyList<Throwable>(), escaped.toList())
        } finally {
            scope.cancel()
        }
    }

    /** [AppStatus]'s inputs are called from its scope's thread, which here is the main one. */
    private fun onMain(input: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(input)

    private fun awaitReminder(status: AppStatus, action: BackupReminderAction?): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            if (status.state.value.backupReminder == action) return true
            Thread.sleep(50)
        }
        return status.state.value.backupReminder == action
    }

    /** Something to lose. The clock, because the file outlives each test and the column is unique. */
    private fun aRow() {
        val now = System.currentTimeMillis()
        Databases.txnDao(context).insert(
            Txn(
                rawCaptureId = now * 1_000,
                amountSen = 1_250L,
                direction = Direction.EXPENSE,
                occurredAt = now,
                merchantRaw = "WARUNG PAK ALI",
                merchantDisplay = "Warung Pak Ali",
                merchantKey = "WARUNG PAK ALI",
                categoryId = 1L,
                sourcePackage = "my.com.tngdigital.ewallet",
                sourceLabel = "Touch 'n Go eWallet",
                confidence = Confidence.HIGH,
                state = TxnState.COMMITTED,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** The stores as they are, but capture healthy and no export or snooze on record. */
    private class HealthyCapture(stores: StatusSources) : StatusSources by stores {
        override suspend fun report() = CaptureReport(
            granted = true,
            lastNotificationAt = now() - 60_000L,
            staleForMillis = 60_000L,
        )

        override suspend fun lastExportAt() = 0L

        override suspend fun reminderDismissedAt() = 0L
    }

    /** [guarded], with the reminder's count meeting code 11 once [damaged] is set. */
    private class DamagedCount(private val guarded: ReadRunner) : ReadRunner by guarded {
        @Volatile var damaged = false

        override suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T> =
            guarded.read(what) { db ->
                if (damaged && what == AppStatus.COUNTING) throw SQLiteDatabaseCorruptException("simulated damage under the count")
                body(db)
            }
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
