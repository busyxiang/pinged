package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Databases.holder` for the integrity check (#279). The export's half is
 * `ExportHolderTest` in `:feature:ledger`, where the writers live.
 *
 * The pragma has no seam to stop it mid-walk, so the check is held where it
 * is seen first: queued for the monitor behind a [Databases.whileLive] block
 * the test owns. The value is set before the monitor is asked for, so a read
 * started then is waiting behind the check as much as one started during the
 * pragma.
 */
@RunWith(AndroidJUnit4::class)
class HolderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @Before fun startFromAnEmptyDatabase() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
        db = DatabaseFactory.build(context).also { it.rawCaptureDao().insert(sampleCapture()) }
    }

    @After fun close() {
        db?.close()
        db = null
    }

    @Test fun aReadDuringAnIntegrityCheckSeesItUntilItEnds() {
        val release = CountDownLatch(1)
        val blocker = holdTheMonitor(heldAs = null, release)

        val verdict = AtomicReference<Result<Integrity.Result>>()
        val checker = thread { verdict.set(runCatching { Integrity.check(context) }) }
        awaitHolder(Databases.Holder.IntegrityCheck)

        val seenByRead = AtomicReference<Databases.Holder?>()
        val reader = thread {
            seenByRead.set(Databases.holder.value)
            Databases.inOneTransaction(context) { it.categoryDao().countAll() }
        }
        reader.join(200)
        assertTrue("the read did not wait behind the check", reader.isAlive)
        assertEquals(Databases.Holder.IntegrityCheck, Databases.holder.value)

        release.countDown()
        listOf(blocker, checker, reader).forEach { it.join(10_000) }
        assertEquals(Integrity.Result.Ok, verdict.get().getOrThrow())
        assertEquals(Databases.Holder.IntegrityCheck, seenByRead.get())
        assertNull(
            "The check returned and the read behind it finished, but holder still " +
                "names ${Databases.holder.value}: a screen would say it is waiting forever",
            Databases.holder.value,
        )
    }

    @Test fun aCheckThatThrowsLeavesNothingHolding() {
        db?.close()
        db = null
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }

        val outcome = runCatching { Integrity.check(context) }

        assertTrue("expected the check to refuse a missing file, got $outcome", outcome.exceptionOrNull() is DatabaseReplacedException)
        assertNull("A check that threw left holder at ${Databases.holder.value}", Databases.holder.value)
    }

    /**
     * **One holder ending does not clear another still up**, in either
     * order: the first to begin is shown throughout, and `null` only once
     * both are done. Nested on one thread, which the monitor's reentrancy
     * allows, so both are certainly up at once.
     */
    @Test fun oneHolderEndingLeavesTheOtherShown() {
        val live = db!!
        Databases.whileLive(live, Databases.Holder.Export) {
            Databases.aside(context, Databases.Holder.IntegrityCheck) {
                assertEquals(Databases.Holder.Export, Databases.holder.value)
            }
            assertEquals(
                "The check inside the export ended and took the export's holder with it",
                Databases.Holder.Export,
                Databases.holder.value,
            )
        }
        assertNull(Databases.holder.value)

        Databases.aside(context, Databases.Holder.IntegrityCheck) {
            Databases.whileLive(live, Databases.Holder.Export) {
                assertEquals(Databases.Holder.IntegrityCheck, Databases.holder.value)
            }
            assertEquals(
                "The export inside the check ended and took the check's holder with it",
                Databases.Holder.IntegrityCheck,
                Databases.holder.value,
            )
        }
        assertNull(Databases.holder.value)
    }

    /** A thread inside [Databases.whileLive] until [release]; returns once it is in. */
    private fun holdTheMonitor(heldAs: Databases.Holder?, release: CountDownLatch): Thread {
        val entered = CountDownLatch(1)
        val holding = thread {
            Databases.whileLive(db!!, heldAs) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue("never entered whileLive", entered.await(10, TimeUnit.SECONDS))
        return holding
    }

    private fun awaitHolder(expected: Databases.Holder) = runBlocking {
        withTimeout(10_000) { Databases.holder.first { it == expected } }
    }
}
