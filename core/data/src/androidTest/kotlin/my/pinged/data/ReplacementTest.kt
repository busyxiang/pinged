package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Databases.generation`: when a replacement of the shared database is
 * announced, and what the one holder in this module does with it.
 *
 * The timing is the contract. Holders rebind on the announcement by opening
 * the database again, so an announcement made while the file is gated or half
 * written sends them into it.
 */
@RunWith(AndroidJUnit4::class)
class ReplacementTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A date far outside any real one, so the shared emulator cannot supply it. */
    private val day = LocalDate(19_950_618)

    @Before fun startFromAnEmptyDatabase() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        CaptureDays.forgetProcessMemo()
    }

    @After fun leaveAnOpenableDatabase() {
        CaptureDays.forgetProcessMemo()
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun aResetIsAnnouncedOnce() {
        val before = Databases.generation.value
        Databases.reset()
        assertEquals(before + 1, Databases.generation.value)
    }

    /**
     * Not when the gate goes up and closes the handle: a holder reopening
     * then would meet `DatabaseBeingDeletedException` and draw an unreadable
     * ledger over a delete that is about to succeed.
     */
    @Test fun aDeleteIsAnnouncedOnceTheGateHasLifted() {
        val before = Databases.generation.value
        val inside = Databases.whileDeleting { Databases.generation.value }
        assertEquals(
            "The delete announced a replacement while its gate was still up",
            before,
            inside,
        )
        assertEquals(before + 1, Databases.generation.value)
    }

    /** A delete that throws has still closed the handle every holder is bound to. */
    @Test fun aDeleteThatFailsIsAnnouncedToo() {
        val before = Databases.generation.value
        runCatching { Databases.whileDeleting { error("delete blew up") } }
        assertEquals(before + 1, Databases.generation.value)
    }

    /**
     * A restore holds the gate across its wipe, which raises its own, and its
     * import, which resets again -- and must announce once, after both. At
     * either inner one, every rebinding holder would open the file before the
     * import had written it.
     */
    @Test fun aRestoreIsAnnouncedOnceAfterItsImport() {
        val before = Databases.generation.value
        val inside = Databases.whileDeleting {
            Databases.whileDeleting { }
            Databases.reset()
            Databases.generation.value
        }
        assertEquals(
            "A restore announced its wipe or its reset before the import had " +
                "finished, so a holder rebinding on it opens a ledger half written",
            before,
            inside,
        )
        assertEquals(before + 1, Databases.generation.value)
    }

    /**
     * `CaptureDays`' memo names a row in one database. A delete that throws
     * after unlinking the file never reaches `CaptureCaches.clear`, so the
     * memo has to notice the replacement for itself, or today is missing from
     * the new table until midnight -- spec 4's grid hatching a day capture
     * watched as a day it was blind.
     */
    @Test fun aReplacedDatabaseGetsTheDayWrittenAgain() {
        CaptureDays.markNotificationSeen(day) { Databases.captureDayDao(context) }
        CaptureDays.markListenerBound(day) { Databases.captureDayDao(context) }
        assertNotNull(
            "precondition: the day was written",
            Databases.captureDayDao(context).byDate(day),
        )

        // The file goes and nothing clears the memo, which is what a delete
        // whose `KeyStore.deleteEntry` throws leaves behind.
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }

        CaptureDays.markNotificationSeen(day) { Databases.captureDayDao(context) }
        CaptureDays.markListenerBound(day) { Databases.captureDayDao(context) }
        val row = Databases.captureDayDao(context).byDate(day)
        assertNotNull(
            "The database was replaced and the memo still believed today was " +
                "recorded in it, so neither writer wrote the new table's row",
            row,
        )
        assertTrue(
            "The notification writer reached the new table and the bind " +
                "writer did not: its memo is still the replaced database's",
            row!!.listenerBound,
        )
    }
}
