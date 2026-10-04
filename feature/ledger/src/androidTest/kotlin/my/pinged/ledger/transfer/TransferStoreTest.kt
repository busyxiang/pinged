package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun forgetTheExport() = runBlocking { TransferStore.forget(context) }

    @Test fun neverExportedReadsAsZero() = runBlocking {
        assertEquals(0L, TransferStore.lastExportAt(context))
    }

    @Test fun anExportIsRemembered() = runBlocking {
        TransferStore.recordExport(context, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, TransferStore.lastExportAt(context))
    }

    /**
     * **A forget on a store that is already empty writes nothing, and a
     * watcher still has to hear it.**
     *
     * DataStore 1.2.1 compares the transformed `Preferences` with the current
     * one and neither writes nor emits when they are equal (measured on
     * emulator-5554; `TransferStore.forgotten` records the probe), so this is
     * the one change to `lastExportAt` that the store itself cannot announce --
     * and `distinctUntilChanged` would collapse it in any case, both values
     * being `0L`.
     *
     * It is not a corner: `Restore.replaceEverything` forgets an export that a
     * never-exported install never recorded, in the same call that gives the
     * ledger a file's worth of rows, and nothing resumes `MainActivity` between
     * the picker returning and the import finishing. A watcher that hears
     * nothing leaves the home screen with no backup nudge over a restored
     * ledger that has no copy anywhere.
     *
     * **The forget is repeated rather than timed.** It leaves the same state
     * however many times it runs, so repeating it costs nothing and removes the
     * race between it and the collector subscribing -- a tick emitted before
     * anyone is listening is gone, and a test that slept instead would be
     * asserting on the sleep.
     */
    @Test fun aForgetThatChangesNothingStoredStillReachesItsWatcher() = runBlocking {
        val heard = CompletableDeferred<Long>()
        val watcher = launch(Dispatchers.IO) {
            TransferStore.lastExportAtChanges(context).collect { heard.complete(it) }
        }

        try {
            val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
            while (!heard.isCompleted && System.currentTimeMillis() < deadline) {
                TransferStore.forget(context)
                delay(50)
            }

            assertTrue(
                "A forget over an already-empty store told its watcher nothing, " +
                    "so a restore onto a never-exported install leaves the nudge " +
                    "down over a ledger with no copy anywhere",
                heard.isCompleted,
            )
            assertEquals(
                "The watcher heard something other than \"never exported\"",
                0L,
                // Complete, because the assertion above it did not fire.
                heard.await(),
            )
        } finally {
            watcher.cancel()
        }
    }

    private companion object {
        /** Long enough for a DataStore read off a loaded emulator. */
        const val TIMEOUT_MILLIS = 5_000L
    }
}
