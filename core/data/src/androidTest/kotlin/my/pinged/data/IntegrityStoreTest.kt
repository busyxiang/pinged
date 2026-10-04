package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The verdict's durability, and what a delete owes it. */
@RunWith(AndroidJUnit4::class)
class IntegrityStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun forgetEverything() = runBlocking { IntegrityStore.forget(context) }

    /** Never checked is not "checked and clean", and the screen says so. */
    @Test fun neverCheckedReadsAsZeroAndNotDamaged() = runBlocking {
        assertEquals(0L, IntegrityStore.lastCheckAt(context))
        assertFalse(IntegrityStore.damaged(context))
    }

    /**
     * The verdict outlives the process, and has to. The check does not run at
     * open (design 2.1), so without a durable answer every cold start would
     * present a damaged database as healthy until something happened to look.
     */
    @Test fun aFailedCheckIsRememberedUntilOneSucceeds() = runBlocking {
        IntegrityStore.record(context, 1L, ok = false)
        assertTrue(IntegrityStore.damaged(context))

        IntegrityStore.record(context, 2L, ok = true)
        assertFalse("a clean check did not clear the damaged flag", IntegrityStore.damaged(context))
    }

    /**
     * A verdict DataStore will not take is logged, not thrown: every caller
     * records it beside something worth more -- a capture its retry may yet
     * store, an export's salvage wording -- that an `IOException` from here
     * would replace. A read-only store directory is the refusal: DataStore
     * cannot create its temporary file there.
     */
    @Test fun aRefusedDamageRecordDoesNotThrow() = runBlocking {
        IntegrityStore.record(context, 1L, ok = true)
        val store = File(context.filesDir, "datastore")
        assertTrue("no store directory to make read-only", store.isDirectory)
        assertTrue(store.setWritable(false, false))
        try {
            IntegrityStore.recordDamage(context)
        } finally {
            store.setWritable(true, true)
        }
        assertFalse(
            "the record landed, so nothing was refused and this proves nothing",
            IntegrityStore.damaged(context),
        )
    }

    /**
     * Spec 11.3: the record describes a database file that no longer exists, so
     * a wipe that left it behind would have the screen reporting damage in a
     * ledger nobody can open because nobody has one.
     */
    @Test fun aWipeForgetsTheVerdict() = runBlocking {
        IntegrityStore.record(context, 1L, ok = false)
        assertEquals(
            "the wipe reported a verdict it could not clear, so the assertions " +
                "below are about a forget that never ran",
            Wipe.Leftover.NONE,
            Wipe.everything(context),
        )
        assertFalse("a wiped device still claims to be damaged", IntegrityStore.damaged(context))
        assertEquals(0L, IntegrityStore.lastCheckAt(context))
    }
}
