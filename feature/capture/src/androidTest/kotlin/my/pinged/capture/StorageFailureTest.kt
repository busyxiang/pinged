package my.pinged.capture

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.entity.Arrival
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three ways storage can be unavailable, and the one answer the app owes
 * the user for all of them: say so, and keep saying it until it is not true.
 *
 * Every test here failed before the commit that added it.
 */
@RunWith(AndroidJUnit4::class)
class StorageFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile get() = File(context.noBackupFilesDir, "db.key")
    private val dbFile get() = context.getDatabasePath(DatabaseFactory.NAME)

    @Before fun freshDatabaseAndKey() {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        keyFile.delete()
        DatabaseFactory.build(context).close()
        runBlocking {
            CaptureHealth.forgetProcessMemo()
            CaptureHealth.clearStorageUnavailable(context)
        }
    }

    @After fun leaveItOpenable() {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        keyFile.delete()
        runBlocking {
            CaptureHealth.forgetProcessMemo()
            CaptureHealth.clearStorageUnavailable(context)
        }
    }

    /**
     * A corrupt file is not a missing key, and the guard used to catch only the
     * second. `SQLiteNotADatabaseException` extends the framework's
     * `SQLiteException` and reached nobody, so the report came back entirely
     * healthy -- no banner, `needsUserAction` false -- while every notification
     * was dropped into the listener's exception handler.
     */
    @Test fun anUnreadableDatabaseIsReportedLikeAMissingKey() {
        Graph.reset()
        RandomAccessFile(dbFile, "rw").use { file ->
            file.seek(0)
            file.write(ByteArray(4096) { 0x5A })
        }

        assertNull(
            "Nothing can be stored in a database that will not open",
            runBlocking {
                CaptureIngest.ingest(context, capture("corrupt"), Arrival.POSTED, System.currentTimeMillis())
            },
        )

        val after = runBlocking { ListenerStatus.report(context) }
        assertTrue(
            "The database cannot be read and the report cannot say so",
            after.storageUnavailable,
        )
        assertTrue("A phone storing nothing must not read as needing no action", after.needsUserAction)
    }

    /**
     * The flag is durable and its write-throttle is per-process, so the process
     * that meets a stale flag is by definition not the one that wrote it. While
     * the throttle was a plain `false`, the clear short-circuited before it
     * could ever reach DataStore, and the banner -- which is first in
     * `MainActivity`'s `when` and carries no action button -- stayed up for the
     * life of the install.
     */
    @Test fun aFlagWrittenByAnEarlierProcessIsCleared() {
        runBlocking {
            context.captureStore.edit { it[booleanPreferencesKey("storage_unavailable")] = true }
            assertTrue("precondition", CaptureHealth.storageUnavailable(context))

            // What a fresh process brings: a healthy open and no memory of why.
            CaptureHealth.forgetProcessMemo()
            CaptureHealth.clearStorageUnavailable(context)

            assertFalse(
                "A recovered database left the banner up, and nothing in the UI can dismiss it",
                CaptureHealth.storageUnavailable(context),
            )
        }
    }

    /**
     * `DatabaseUnavailableException` extends `IllegalStateException`, so a
     * generic `catch (Exception)` *inside* the guarded block caught it first.
     * That made `unavailable` unreachable -- the worker answered a permanently
     * dead database with `retry()` forever -- and, because the block then
     * returned normally, `guarded` cleared the flag the listener had just set.
     */
    @Test fun theWorkerGivesUpOnAMissingKeyAndLeavesTheBannerAlone() {
        runBlocking { CaptureHealth.recordStorageUnavailable(context) }
        Graph.reset()
        keyFile.delete()

        val worker = TestListenableWorkerBuilder<ParseWorker>(context).build()
        val result = runBlocking { worker.doWork() }

        assertEquals(
            "A key that is gone is not a transient fault; retrying it wakes the app " +
                "about 131 times a day for as long as the state lasts",
            ListenableWorker.Result.failure(),
            result,
        )
        assertTrue(
            "The worker cleared the storage banner while the database was still unopenable",
            runBlocking { CaptureHealth.storageUnavailable(context) },
        )
    }

    private fun capture(tag: String) = CaptureFixtures.posted(
        CaptureFixtures.notification(
            context,
            title = "Touch 'n Go",
            text = "Payment of RM12.00 to Kedai Ali successful",
        ),
        pkg = ParseFixtures.TNG,
        id = 1,
        tag = tag,
        postTime = System.currentTimeMillis(),
    )
}
