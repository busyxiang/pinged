package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.Integrity
import my.pinged.data.IntegrityStore
import my.pinged.data.PingedDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 121ms per 10,000 captures (design 2.1) is nothing weekly and a great deal per
 * notification. This is the throttle that keeps it the former, and the record
 * that makes the result reach a screen.
 */
@RunWith(AndroidJUnit4::class)
class PeriodicIntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startClean() = runBlocking {
        Graph.reset()
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
        CaptureCaches.clear(context)
        IntegrityStore.forget(context)
    }

    @Test fun theFirstRunChecks() = runBlocking {
        val db = DatabaseFactory.build(context)
        assertTrue(PeriodicIntegrity.checkIfDue(context, now = 1_000L))
        assertEquals(1_000L, IntegrityStore.lastCheckAt(context))
        db.close()
    }

    @Test fun theNextNotificationDoesNotCheckAgain() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(context, now = 1_000L)
        assertFalse(
            "a check ran twice in a row, which is 121ms per notification burst",
            PeriodicIntegrity.checkIfDue(context, now = 2_000L),
        )
        db.close()
    }

    @Test fun aWeekLaterItChecksAgain() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(context, now = 1_000L)
        val aWeekOn = 1_000L + PeriodicIntegrity.EVERY_MILLIS + 1
        assertTrue(PeriodicIntegrity.checkIfDue(context, now = aWeekOn))
        db.close()
    }

    /**
     * A clock moved backwards -- a timezone-naive user correction, or a restored
     * system image -- must not mute the check until wall time catches up. Ahead
     * of the throttle, because `now - last` is negative and compares less than
     * any positive interval.
     */
    @Test fun aClockMovedBackwardsStillChecks() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(context, now = 10_000L)
        assertTrue(PeriodicIntegrity.checkIfDue(context, now = 5_000L))
        db.close()
    }

    /**
     * The point of the whole task: a verdict the settings screen can read.
     * [Integrity.check] is injected so the boolean can be driven from both
     * sides without a file to match.
     *
     * A stub proves nothing about the seam's own default, and neither does
     * [theFirstRunChecks] -- both still pass with the default replaced by
     * `{ Integrity.Result.Ok }`, because neither looks at a verdict the real
     * pragma had to produce. [theDefaultCheckIsTheRealPragma] is the one that
     * does, and it is what [damagePage] below buys.
     */
    @Test fun damageIsRecordedWhereTheScreenReadsIt() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(
            context,
            now = 1_000L,
            check = { Integrity.Result.Damaged("row 4 missing from index") },
        )
        assertTrue(IntegrityStore.damaged(context))
        db.close()
    }

    /** A later clean check clears it, or the screen never recovers. */
    @Test fun aCleanCheckClearsTheVerdict() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(
            context, now = 1_000L,
            check = { Integrity.Result.Damaged("row 4 missing from index") },
        )
        PeriodicIntegrity.checkIfDue(context, now = 1_000L + PeriodicIntegrity.EVERY_MILLIS + 1)
        assertFalse(IntegrityStore.damaged(context))
        db.close()
    }

    /**
     * **A file removed while this waited on DataStore is not checked, and
     * nothing is recorded.** This suspends reading the throttle, and a
     * delete's wipe can land in between: a check that opened anyway would
     * recreate the file the wipe removed, and record a verdict about it. The
     * removal lands inside [check], after the read.
     */
    @Test fun aFileRemovedAfterTheThrottleIsReadIsSkippedNotRecorded() = runBlocking {
        Databases.shared(context)
        val ran = PeriodicIntegrity.checkIfDue(context, now = 1_000L, check = { ctx ->
            Databases.reset()
            ctx.deleteDatabase(DatabaseFactory.NAME)
            Integrity.check(ctx)
        })
        val reopened = context.getDatabasePath(DatabaseFactory.NAME).exists()

        assertFalse("the check recreated the ledger the wipe removed", reopened)
        assertFalse("a check of a removed file was reported as run", ran)
        assertEquals(
            "a check of a removed file recorded a verdict, so the next one is " +
                "not due for a week",
            0L,
            IntegrityStore.lastCheckAt(context),
        )
    }

    /** The same refusal when the file was already gone on arrival. */
    @Test fun aFileRemovedBeforeTheCallIsSkipped() = runBlocking {
        Databases.shared(context)
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)

        val ran = PeriodicIntegrity.checkIfDue(context, now = 1_000L)

        assertFalse(
            "the check recreated the ledger the wipe removed",
            context.getDatabasePath(DatabaseFactory.NAME).exists(),
        )
        assertFalse("a check of a removed file was reported as run", ran)
        assertEquals(0L, IntegrityStore.lastCheckAt(context))
    }

    /**
     * The seam's default has to be the real pragma, and only a damaged file
     * says so: with `check` defaulted to `{ Integrity.Result.Ok }` every other
     * test in this class passes, because each either supplies its own `check`
     * or asserts on the timestamp, which a stub writes just as happily.
     *
     * **The cleanup covers both of the things this test leaves behind, and it
     * starts before the damage rather than after it.** Everything else in this
     * APK shares `pinged.db` and [ParseFixtures] deliberately does not recreate
     * it per test, so a damaged file left here surfaces as a failure in some
     * other class entirely -- which is what a throw from [damagePage] or
     * from the [DatabaseFactory.build] that follows it would do with either
     * outside the `try`. The verdict is the second: `integrity` is one store
     * for the process, so a `damaged = true` left here is read by every class
     * that runs after this one. The close is wrapped the way
     * [my.pinged.data.Databases.reset] wraps its own, because it is the first
     * statement of a `finally` whose later lines are the ones that matter.
     */
    @Test fun theDefaultCheckIsTheRealPragma() = runBlocking {
        val page = DatabaseFactory.build(context).let { db ->
            // The flipped byte has to be in the file, not in a `-wal` the
            // check reads through.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            db.openHelper.readableDatabase.query("SELECT pageno FROM dbstat WHERE name = '$UNREAD_AT_OPEN'").use {
                it.moveToFirst()
                it.getInt(0)
            }.also { db.close() }
        }

        var db: PingedDatabase? = null
        try {
            damagePage(context.getDatabasePath(DatabaseFactory.NAME), page)
            db = DatabaseFactory.build(context)
            PeriodicIntegrity.checkIfDue(context, now = 1_000L)
            assertTrue(
                "the default check did not run the pragma, so nothing in production does",
                IntegrityStore.damaged(context),
            )
        } finally {
            runCatching { db?.close() }
            Graph.reset()
            Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
            IntegrityStore.forget(context)
        }
    }
}

/**
 * A table nothing reads while the database opens and seeds, so a page of it
 * damaged leaves a database that opens and then fails the pragma. Not the
 * middle of the file: on this test's near-empty database that is a page the
 * open reads, measured on emulator-5554 at schema v3, and the open then throws
 * `DatabaseUnreadableException` before any check runs. Page 1 is no better --
 * it holds the salt SQLCipher needs to open at all.
 */
private const val UNREAD_AT_OPEN = "merchant_alias"

/**
 * Flip one byte inside page [page] of a database file. SQLCipher's page is
 * 4,096 bytes, and a flip anywhere in one fails its HMAC.
 */
private fun damagePage(file: java.io.File, page: Int) {
    java.io.RandomAccessFile(file, "rw").use { raf ->
        val at = (page - 1).toLong() * 4096 + 1000
        raf.seek(at)
        val original = raf.readByte()
        raf.seek(at)
        raf.writeByte(original.toInt() xor 0xFF)
    }
}
