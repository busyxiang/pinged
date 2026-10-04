package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **[CaptureStorage.guarded] can throw after its block has returned, and a
 * caller's guard has to sit outside the call rather than inside the block.**
 *
 * The success path clears the storage-unavailable flag once the block is done,
 * so that a restored key makes the banner go away. That clear is a DataStore
 * write, and a DataStore write can fail -- and when it does, the exception is
 * an `IOException`, which `guarded` does not catch and which arrives with the
 * block's answer already computed and thrown away.
 *
 * `MainActivity.readExportOverdue` is the caller this is about: it catches
 * `IOException` around the whole `guarded` call rather than inside the block,
 * and the reason it must is exactly this. Nothing pinned that until here.
 * `ExportNudgeGuardTest` pins the other half -- that the catch, given an
 * `IOException`, answers false instead of killing the process.
 */
@RunWith(AndroidJUnit4::class)
class GuardedWriteFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The scratch path, on the module's own store.
     *
     * The name is DataStore's own: the store's name plus `.preferences_pb`,
     * plus the `.tmp` it writes through before renaming.
     */
    private val scratch get() = File(context.filesDir, "datastore/capture_health.preferences_pb.tmp")

    @After fun leaveTheStoreWritable() {
        scratch.deleteRecursively()
        CaptureHealth.forgetProcessMemo()
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }
    }

    /**
     * **The flag has to be set first, and that is not setup.** DataStore
     * 1.2.1's `transformAndWrite` compares the transformed `Preferences` with
     * the current one and neither writes nor emits when they are equal
     * (measured on emulator-5554; `TransferStore.forgotten` records the same
     * probe from the other module). Clearing a flag that is not there writes
     * nothing, so there would be nothing to fail, and the test would pass over
     * a write that never happened.
     *
     * [CaptureHealth.forgetProcessMemo] for the same reason from the other
     * direction: [CaptureHealth.clearStorageUnavailable] short-circuits on an
     * in-process memo saying the flag is already clear, which every earlier
     * test in this APK that opened the database will have left set. Forgetting
     * it is what lets the clear reach DataStore at all.
     *
     * A directory standing where DataStore wants its scratch file is the
     * cheapest full disk on offer -- the trick and the measurement are
     * `WipeTest.aForgetThatFailsIsAnUnfinishedDelete`'s.
     */
    @Test fun aWriteThatFailsAfterTheBlockThrowsOutOfGuarded() = runBlocking {
        CaptureHealth.forgetProcessMemo()
        CaptureHealth.recordStorageUnavailable(context)
        CaptureHealth.forgetProcessMemo()
        assertTrue("the scratch path was already taken", scratch.mkdirs())

        val outcome = runCatching {
            CaptureStorage.guarded(
                context,
                what = "The ledger cannot be read",
                unavailable = { "the database would not open" },
            ) {
                "the block returned"
            }
        }

        assertTrue(
            "`guarded` returned ${outcome.getOrNull()}, so the clear after the " +
                "block did not fail and this case no longer provokes the write " +
                "it is named for",
            outcome.isFailure,
        )
        assertTrue(
            "`guarded` threw ${outcome.exceptionOrNull()}, which is not what a " +
                "failing DataStore write raises -- a caller catching " +
                "`IOException` around it would not catch this",
            outcome.exceptionOrNull() is IOException,
        )
    }

    /**
     * The same write, with the caller's guard where `readExportOverdue` puts
     * it: outside the call. Inside the block it would never run, because the
     * block has already returned by the time the write happens.
     */
    @Test fun aCallerCatchingOutsideTheCallStillGetsAnAnswer() = runBlocking {
        CaptureHealth.forgetProcessMemo()
        CaptureHealth.recordStorageUnavailable(context)
        CaptureHealth.forgetProcessMemo()
        assertTrue("the scratch path was already taken", scratch.mkdirs())

        val answer = try {
            CaptureStorage.guarded(
                context,
                what = "The ledger cannot be read",
                unavailable = { "the database would not open" },
            ) {
                // A guard here sees nothing: the write is after this returns.
                try {
                    "the block returned"
                } catch (thrown: IOException) {
                    "caught inside the block"
                }
            }
        } catch (thrown: IOException) {
            "caught outside the call"
        }

        assertEquals(
            "The failing write was answered from somewhere other than a catch " +
                "around the whole call",
            "caught outside the call",
            answer,
        )
    }
}
