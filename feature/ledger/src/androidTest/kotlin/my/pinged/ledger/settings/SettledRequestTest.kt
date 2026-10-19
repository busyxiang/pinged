package my.pinged.ledger.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A request whose answer is already in by the time it is awaited, made from
 * the main thread -- a restore whose document will not open, picked from
 * `MainActivity`'s `restoreLauncher` callback, can settle that fast.
 *
 * `request` starts undispatched, so it runs on the caller's thread until it
 * first suspends, and an `await` on a completed `Deferred` does not suspend.
 * Without a dispatch of its own the re-read that follows ran on the main
 * thread, and Room threw "Cannot access database on the main thread" out of
 * `viewModelScope`, where nothing catches it (CI run 39250612727,
 * `RestorePickTest`). Timing reached it there once; this hands `request` the
 * completed answer, so it reaches the path every run.
 *
 * The throw arrives at the default uncaught handler, replaced here, for the
 * reason `MainThreadRefreshTest`'s KDoc measured; asserting on the [Job] alone
 * would pass, because `join` does not rethrow.
 */
@RunWith(AndroidJUnit4::class)
class SettledRequestTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val crashed = AtomicReference<Throwable?>(null)

    private var systemHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun captureWhatWouldKillTheProcess() {
        Transfers.forgetOutcome()
        // The open is memoized for the process; a fresh one is what the
        // re-read has to reach, as a cold restore's would.
        Databases.reset()
        systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown -> crashed.compareAndSet(null, thrown) }
    }

    @After fun restoreTheSystemHandler() {
        Thread.setDefaultUncaughtExceptionHandler(systemHandler)
    }

    @Test fun anAnswerAlreadyInIsSettledOffTheMainThread() {
        val outcome = Outcome(
            serial = Long.MAX_VALUE,
            operation = Operation.RESTORE,
            job = TransferJob.Failed("Pinged could not open that file. Nothing on this phone was changed."),
        )
        val made = AtomicReference<Job?>(null)
        val model = AtomicReference<SettingsViewModel?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val settings = SettingsViewModel(app)
            model.set(settings)
            made.set(settings.request { CompletableDeferred(Transfers.Answer.Settled(outcome)) })
        }

        runBlocking { withTimeout(TIMEOUT_MILLIS) { made.get()!!.join() } }

        assertNull(
            "Settling an answer already in threw out of viewModelScope -- the re-read ran on the main thread",
            crashed.get(),
        )
        assertTrue(
            "The request did not re-read the ledger after settling, so it did not reach the path at all",
            model.get()!!.state.value.loaded,
        )
    }

    private companion object {
        /** Covers a cold SQLCipher open on a loaded emulator; `OpenTest` is what measures it. */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
