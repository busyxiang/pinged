package my.pinged.capture

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A raise and a clear of the storage flag that cross leave the next one able
 * to say so.
 *
 * Two `CaptureStorage.guarded` callers on the IO pool raise and clear the
 * flag at once whenever one is refused while another opens -- a capture
 * refused as a restore lifts its gate, a screen reading the ledger beside it.
 */
@RunWith(AndroidJUnit4::class)
class StorageFlagCrossingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveItClear() = runBlocking {
        CaptureHealth.forgetProcessMemo()
        CaptureHealth.clearStorageUnavailable(context)
    }

    @Test fun aRaiseAfterACrossingReachesTheStore() = afterEachCrossing { lost ->
        CaptureHealth.recordStorageUnavailable(context)
        if (!CaptureHealth.storageUnavailable(context)) lost()
    }

    @Test fun aClearAfterACrossingReachesTheStore() = afterEachCrossing { lost ->
        CaptureHealth.clearStorageUnavailable(context)
        if (CaptureHealth.storageUnavailable(context)) lost()
    }

    /**
     * A clear sent on seeing a raise land reaches the store.
     *
     * `MainActivity` samples on every change of the flag, and its sample's
     * probe opens the database, which clears it: that clear is what takes
     * down a banner raised inside a refusal's window. It can start as soon as
     * the raise's edit is visible in the store, before the raise has returned.
     *
     * Measured on emulator-5554 with `CaptureHealth`'s memo read as a fast
     * path outside its lock: 9 and 6 of 1000 clears lost, in two runs.
     */
    @Test fun aClearSentOnSeeingARaiseReachesTheStore() = runBlocking {
        var lost = 0
        repeat(CROSSINGS) {
            CaptureHealth.clearStorageUnavailable(context)
            // Subscribed and past its first value before the raise, as
            // `MainActivity`'s watcher is from STARTED: a collector that
            // subscribed while the raise was being written once waited out
            // its timeout with the store already raised.
            val watching = CompletableDeferred<Unit>()
            val cleared = async(Dispatchers.IO) {
                context.captureStore.data.first { prefs ->
                    watching.complete(Unit)
                    prefs[booleanPreferencesKey("storage_unavailable")] == true
                }
                CaptureHealth.clearStorageUnavailable(context)
            }
            watching.await()
            CaptureHealth.recordStorageUnavailable(context)
            withTimeout(10_000L) { cleared.await() }
            if (CaptureHealth.storageUnavailable(context)) lost++
        }
        assertEquals(
            "Of $CROSSINGS clears sent on seeing a raise, this many returned on the " +
                "memo the raise had not yet set, and left the flag up",
            0,
            lost,
        )
    }

    /**
     * [then] after each of [CROSSINGS] crossings of a raise and a clear, from
     * a forgotten memo, the state `CaptureCaches.clear` leaves after a
     * restore; [then] calls its argument when the store did not follow.
     *
     * Measured on emulator-5554 without `CaptureHealth`'s lock, 3 runs each:
     * a raise lost 240-265 times in 1000, a clear 18-22.
     */
    private fun afterEachCrossing(then: suspend (lost: () -> Unit) -> Unit) = runBlocking {
        var lost = 0
        repeat(CROSSINGS) {
            CaptureHealth.forgetProcessMemo()
            val start = CyclicBarrier(2)
            listOf<suspend () -> Unit>(
                { CaptureHealth.recordStorageUnavailable(context) },
                { CaptureHealth.clearStorageUnavailable(context) },
            ).map { write -> async(Dispatchers.IO) { start.await(); write() } }.awaitAll()
            then { lost++ }
        }
        assertEquals(
            "Of $CROSSINGS crossings of a raise and a clear, this many left the next " +
                "write short-circuiting on an in-process memo the store disagreed with",
            0,
            lost,
        )
    }

    private companion object {
        const val CROSSINGS = 1000
    }
}
