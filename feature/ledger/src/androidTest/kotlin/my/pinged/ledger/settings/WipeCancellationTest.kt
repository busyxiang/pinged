package my.pinged.ledger.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.IntegrityStore
import my.pinged.data.Wipe
import my.pinged.ledger.transfer.useDb
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Wipe.everything` under a cancellable caller: its own contract, which
 * neither production caller exercises -- `Transfers.deleteEverything` and
 * `Restore.replaceEverything` both run it to completion, and `RestoreTest`
 * pins that side.
 *
 * Here rather than in `:core:data`'s `WipeTest` because [HostileApp] is the
 * seam, and that source set cannot reach this one's.
 */
@RunWith(AndroidJUnit4::class)
class WipeCancellationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * **A cancellation arriving after the destroy has already failed is still
     * a cancellation, and the wipe's own report would bury it.** The
     * realistic pair is a Keystore alias invalidated by an OTA and a user who
     * leaves the screen: `DatabaseKey.destroy` throws, the
     * `IntegrityStore.forget` that runs anyway is cancelled, and it is the
     * cancellation that says what to do -- file nothing, at a holder whose
     * scope has gone.
     *
     * **The destroy's failure rides along as a suppressed exception, and that
     * is asserted.** It is the only record left of why the delete stopped, and
     * an empty `suppressed` is also how this test would look if the forget had
     * never been cancelled at all -- so the assertion doubles as the proof
     * that the branch under test ran.
     *
     * A verdict is recorded first because DataStore skips the file write when
     * `edit` changes nothing, and it is that write the cancellation has to
     * land in: measured, clearing an already-empty store returns before
     * suspending and the forget is never cancelled.
     */
    @Test fun aCancelledForgetAfterAFailedDestroyStillCancels() = runBlocking {
        DatabaseFactory.build(context).useDb { }
        IntegrityStore.record(context, at = 1L, ok = false)
        val caller = Job()
        val hostile = HostileApp(context.applicationContext as Application).apply {
            refusing = true
            refusalIs = {
                caller.cancel(CancellationException("the screen went away, for the test"))
                IllegalStateException("the Keystore alias is gone, for the test")
            }
        }
        var outcome: Result<Wipe.Leftover>? = null
        try {
            CoroutineScope(Dispatchers.Default + caller).launch {
                outcome = runCatching { Wipe.everything(hostile) }
            }.join()
        } finally {
            IntegrityStore.forget(context)
        }

        val thrown = outcome?.exceptionOrNull()
        assertTrue(
            "a cancelled cleanup was reported as the destroy's failure, at a " +
                "screen that is gone: $thrown",
            thrown is CancellationException,
        )
        assertTrue(
            "the destroy's own failure was dropped, so nothing says why the delete " +
                "stopped: ${thrown?.suppressed?.toList()}",
            thrown?.suppressed?.any { it is IllegalStateException } == true,
        )
    }
}
