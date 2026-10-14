package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A guarded call that meets the gate a delete or a restore holds.
 *
 * Nothing is damaged, so a screen's read or write refused there must not put
 * the storage banner up: it would stay up, saying capture stopped, until the
 * next guarded call that succeeds. A capture refused there is different --
 * the notification is lost -- and still raises it (`DatabaseBeingDeletedException`).
 */
@RunWith(AndroidJUnit4::class)
class GateRefusalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun healthyStorage() {
        Graph.reset()
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
        CaptureHealth.forgetProcessMemo()
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }
    }

    @After fun clearTheBanner() {
        Graph.reset()
        CaptureHealth.forgetProcessMemo()
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }
    }

    @Test(timeout = 60_000)
    fun aScreensCallRefusedByTheGateRaisesNoBanner() {
        val answer = underTheGate {
            CaptureStorage.guarded(context, "a screen's write, for the test", { "refused" }, damageStopsCapture = false) {
                "written"
            }
        }

        assertEquals("precondition: the gate did not refuse the call", "refused", answer)
        assertEquals(
            "a screen's call refused by a delete's gate raised the storage-unavailable banner",
            false,
            runBlocking { CaptureHealth.storageUnavailable(context) },
        )
    }

    @Test(timeout = 60_000)
    fun aCaptureRefusedByTheGateStillRaisesTheBanner() {
        val answer = underTheGate {
            CaptureStorage.guarded(context, "a capture, for the test", { "refused" }) { "stored" }
        }

        assertEquals("precondition: the gate did not refuse the capture", "refused", answer)
        assertEquals(
            "a capture the gate dropped left no storage-unavailable record, so the drop is silent",
            true,
            runBlocking { CaptureHealth.storageUnavailable(context) },
        )
    }

    /** [call], made from this thread while another holds `Databases.whileDeleting`. */
    private fun <T> underTheGate(call: suspend () -> T): T {
        val up = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = thread {
            Databases.whileDeleting { up.countDown(); release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
        try {
            check(up.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the gate never rose" }
            return runBlocking { call() }
        } finally {
            release.countDown()
            gate.join()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
