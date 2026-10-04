package my.pinged

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import my.pinged.capture.CaptureCaches
import my.pinged.capture.ListenerStatus
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [readExportOverdue] does when it cannot read what it needs.
 *
 * Its caller is `MainActivity`'s `lifecycleScope`, which carries no
 * `CoroutineExceptionHandler`: a throw from here does not fail the nudge, it
 * reaches the thread's default handler and kills the process -- on the home
 * screen, on every foreground, for as long as the condition lasts. So the
 * subject of both tests is that nothing escapes, and the answer it gives
 * instead is the silent one.
 *
 * **The failures are handed in rather than provoked on disk.** The `transfer`
 * store is one DataStore for the process and caches its first successful read,
 * so a preferences file corrupted from here is never read again and the nudge
 * would answer from the cache -- a test passing for a reason that has nothing
 * to do with the guard. `WipeTest`'s directory-in-the-scratch-path trick
 * provokes a DataStore *write*; this guard is around a read.
 */
@RunWith(AndroidJUnit4::class)
class ExportNudgeGuardTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aStoreThatWillNotReadSilencesTheNudgeInsteadOfKillingTheProcess() = runBlocking {
        val outcome = runCatching {
            readExportOverdue(
                context,
                countTxns = { 12 },
                // What DataStore raises on a truncated preferences file
                // (`CorruptionException`) or a disk that will not read: both
                // are `IOException`, and neither is a
                // `DatabaseUnavailableException` or an `SQLiteException`, so
                // neither guard around this one would catch it.
                lastExportAt = { throw IOException("simulated unreadable preferences file") },
            )
        }

        assertTrue(
            "Reading the last export threw out of the nudge computation: " +
                "${outcome.exceptionOrNull()}. Nothing above it catches this, " +
                "so the home screen crashes on every resume until the file is " +
                "readable again",
            outcome.isSuccess,
        )
        assertFalse(
            "The nudge answered \"overdue\" for a store it could not read. It " +
                "cannot know that: the user may have exported this morning",
            outcome.getOrThrow(),
        )
    }

    @Test fun aLedgerThatWillNotCountSilencesTheNudgeInsteadOfKillingTheProcess() = runBlocking {
        val outcome = runCatching {
            readExportOverdue(
                context,
                countTxns = { throw SQLiteException("simulated scan that aborts mid-count") },
                lastExportAt = { 0L },
            )
        }

        assertTrue(
            "Counting the ledger threw out of the nudge computation: " +
                "${outcome.exceptionOrNull()}. `CaptureStorage.guarded` catches " +
                "`DatabaseUnavailableException` and nothing else, and the " +
                "caller has no handler",
            outcome.isSuccess,
        )
        assertFalse(
            "The nudge answered \"overdue\" over a database it could not " +
                "count, so a user with an empty ledger is told they have " +
                "something to lose",
            outcome.getOrThrow(),
        )
    }

    /**
     * **A connection closed under the count is `android.database.SQLException`,
     * which `SQLiteException` extends rather than the other way round.** A
     * delete or a restore resetting the database between `guarded`'s open and
     * the count leaves the count holding a closed instance -- measured on
     * emulator-5554, it throws "Error code: 21, message: connection is
     * closed" as the superclass, which a `catch (SQLiteException)` never sees.
     *
     * The real exception, from the real count: the instance is closed while
     * `Databases` still holds it, which is the state a reset landing between
     * those two lines leaves behind.
     */
    @Test fun aConnectionClosedUnderTheCountSilencesTheNudgeInsteadOfKillingTheProcess() = runBlocking {
        Databases.reset()
        Databases.shared(context).close()
        val outcome = try {
            runCatching { readExportOverdue(context, lastExportAt = { 0L }) }
        } finally {
            Databases.reset()
        }

        assertTrue(
            "Counting the ledger on a closed connection threw out of the nudge " +
                "computation: ${outcome.exceptionOrNull()}",
            outcome.isSuccess,
        )
        assertFalse("The nudge answered \"overdue\" over a count that never ran", outcome.getOrThrow())
    }

    /**
     * **Damage under the count silences the nudge and says nothing about
     * capture.** The count meets code 11 on the first connection and on the
     * one that replaces it, so the read is refused; raising the storage flag
     * for that would put "nothing new is being recorded" above the home
     * screen on every foreground while capture works.
     */
    @Test fun aDamagedCountSilencesTheNudgeAndLeavesTheCaptureBannerDown() = runBlocking {
        CaptureCaches.clear(context)
        try {
            val overdue = readExportOverdue(
                context,
                countTxns = { throw SQLiteDatabaseCorruptException("simulated damage under the count") },
                lastExportAt = { 0L },
            )

            assertFalse("the nudge answered overdue over a count it could not read", overdue)
            assertFalse(
                "damage under the nudge's count put the capture banner up",
                ListenerStatus.report(context).storageUnavailable,
            )
        } finally {
            CaptureCaches.clear(context)
            IntegrityStore.forget(context)
        }
    }
}
