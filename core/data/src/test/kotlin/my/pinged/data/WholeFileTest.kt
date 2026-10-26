package my.pinged.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases.Holder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [WholeFile]'s contract (#302, decision 9) over [Databases]' real state, on
 * the JVM: what it refuses, what [Databases.holder] shows for how long, which
 * monitor it holds, and how its gate nests. What needs SQLCipher and a file
 * stays on a device -- `ExportHolderTest`, `IntegrityTest`, `SalvageTest`,
 * `RestoreTest`, `DeleteGateTest`.
 *
 * **No block here reads a table.** The instances are in-memory Room databases
 * on Robolectric's SQLite that [Databases] never served, which is all
 * `whileLive` asks of one: that it is neither closed nor retired.
 *
 * Every wait is bounded, so a revert that deadlocks fails at its own
 * assertion rather than hanging the run.
 */
@RunWith(RobolectricTestRunner::class)
class WholeFileTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val built = ArrayList<PingedDatabase>()

    /** Every value [Databases.holder] took from the test's start, in order. */
    private val shown = CopyOnWriteArrayList<Holder?>()
    private val watcher = CoroutineScope(Dispatchers.Unconfined)

    @Before fun watchHolder() {
        Databases.holder.onEach { shown += it }.launchIn(watcher)
    }

    @After fun cleanUp() {
        watcher.cancel()
        built.forEach { runCatching { it.close() } }
        assertFalse("a test left a gate up", Databases.gated)
    }

    private fun instance(): PingedDatabase =
        Room.inMemoryDatabaseBuilder(context, PingedDatabase::class.java).allowMainThreadQueries().build()
            .also { built += it }

    @Test fun exportShowsTheExportHolderForTheBlockAndNothingBeforeOrAfter() {
        var inside: Holder? = null

        val answer = WholeFile.export(instance()) { inside = Databases.holder.value; 7 }

        assertEquals(7, answer)
        assertEquals(Holder.Export, inside)
        assertEquals(listOf<Holder?>(null, Holder.Export, null), shown)
    }

    @Test fun exportClearsTheHolderWhenItsBlockThrows() {
        try {
            WholeFile.export(instance()) { throw IllegalStateException("the document failed") }
            fail("the block's throw was swallowed")
        } catch (thrown: IllegalStateException) {
            assertEquals("the document failed", thrown.message)
        }

        assertNull(Databases.holder.value)
    }

    @Test fun exportRefusesAnInstanceSetAsideWithoutRunningItsBlock() {
        val db = instance().also { it.retire() }
        var ran = false

        try {
            WholeFile.export(db) { ran = true }
            fail("a retired instance was exported")
        } catch (_: DatabaseReplacedException) {
        }

        assertFalse("the block ran on an instance no longer served", ran)
        assertNull("the refusal left the holder up", Databases.holder.value)
    }

    @Test fun exportRefusesAClosedInstanceWithoutRunningItsBlock() {
        val db = instance().also { it.close() }
        var ran = false

        try {
            WholeFile.export(db) { ran = true }
            fail("a closed instance was exported")
        } catch (_: DatabaseReplacedException) {
        }

        assertFalse(ran)
    }

    @Test fun probeRefusesAReplacedInstanceToo() {
        val db = instance().also { it.retire() }

        var ran = false

        try {
            WholeFile.probe(db) { ran = true }
            fail("a retired instance was probed")
        } catch (_: DatabaseReplacedException) {
        }

        assertFalse("the probe ran on a retired instance", ran)
    }

    @Test fun probeShowsNothingOnTheHolder() {
        WholeFile.probe(instance()) { assertNull(Databases.holder.value) }

        assertEquals("a probe is not a long holder", listOf<Holder?>(null), shown)
    }

    @Test fun exportHoldsTheMonitorSoAResetWaitsForItsBlock() {
        val inBlock = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val resetDone = CountDownLatch(1)
        val exporting = thread {
            WholeFile.export(instance()) {
                inBlock.countDown()
                letGo.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue("the export never began", inBlock.await(10, TimeUnit.SECONDS))

        val resetting = thread {
            WholeFile.dropInstance()
            resetDone.countDown()
        }

        assertFalse(
            "a reset ran while an export held the file still",
            resetDone.await(300, TimeUnit.MILLISECONDS),
        )
        letGo.countDown()
        assertTrue("the reset never finished once the export did", resetDone.await(10, TimeUnit.SECONDS))
        exporting.join(10_000)
        resetting.join(10_000)
    }

    @Test fun checkIsRefusedUnderAGateBeforeItsBlockRuns() {
        var ran = false

        runBlocking {
            WholeFile.replaceAll {
                try {
                    WholeFile.check(context) { ran = true }
                    fail("a check ran while a gate was up")
                } catch (_: DatabaseBeingDeletedException) {
                }
            }
        }

        assertFalse(ran)
    }

    @Test fun checkWithNoKeyIsRefusedAndShowsTheIntegrityHolderOnlyWhileItAsked() {
        var ran = false

        try {
            WholeFile.check(context) { ran = true }
            fail("a check with no key answered")
        } catch (_: DatabaseKeyUnavailableException) {
        }

        assertFalse("the check ran with no key to open the file by", ran)
        assertEquals(listOf<Holder?>(null, Holder.IntegrityCheck, null), shown)
    }

    @Test fun salvageWithNoKeyIsRefusedBeforeItsBlockRuns() {
        var ran = false

        try {
            WholeFile.salvage(context) { ran = true }
            fail("a salvage with no key answered")
        } catch (_: DatabaseKeyUnavailableException) {
        }

        assertFalse("salvage ran with no key to open the file by", ran)
    }

    @Test fun replaceAllHoldsTheGateForItsBlockAndLowersItAfter() {
        var inside = false
        var openRefused = false

        runBlocking {
            WholeFile.replaceAll {
                inside = Databases.gated
                openRefused = try {
                    Databases.shared(context)
                    false
                } catch (_: DatabaseBeingDeletedException) {
                    true
                }
            }
        }

        assertTrue("no gate was up inside the block", inside)
        assertTrue("the gate did not refuse an open", openRefused)
        assertFalse("the gate was left up", Databases.gated)
    }

    @Test fun replaceAllLowersTheGateWhenItsBlockThrows() {
        try {
            runBlocking { WholeFile.replaceAll { throw IllegalStateException("the import failed") } }
            fail("the block's throw was swallowed")
        } catch (thrown: IllegalStateException) {
            assertEquals("the import failed", thrown.message)
        }

        assertFalse("a throwing block left the gate up", Databases.gated)
    }

    @Test fun nestedReplaceAllsKeepTheOuterGateUpAndAnnounceOnceWhenTheLastLifts() {
        val before = Databases.generation.value
        var outerStillUp = false
        var movedInside = true

        runBlocking {
            WholeFile.replaceAll {
                WholeFile.replaceAll { }
                outerStillUp = Databases.gated
                WholeFile.dropInstance()
                movedInside = Databases.generation.value != before
            }
        }

        assertTrue("the inner gate lowering took the outer one with it", outerStillUp)
        assertFalse("generation moved inside a gate, which a holder must not see", movedInside)
        assertEquals("one announcement for the whole replacement", before + 1, Databases.generation.value)
    }

    @Test fun dropInstanceOutsideAGateAnnouncesOnce() {
        val before = Databases.generation.value

        WholeFile.dropInstance()

        assertEquals(before + 1, Databases.generation.value)
    }
}
