package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Spec 11.1's corruption detection. Scheduling reasoning: design 2.1. */
@RunWith(AndroidJUnit4::class)
class IntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromAnEmptyDatabase() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun aHealthyDatabaseIsOk() {
        DatabaseFactory.build(context).useDb { db ->
            db.rawCaptureDao().insert(sampleCapture(hash = "healthy"))
            assertEquals(Integrity.Result.Ok, Integrity.check(context))
        }
    }

    /**
     * The state the whole recovery path exists for. Page 1 is left alone:
     * damaging it is indistinguishable from a wrong key, and the database then
     * refuses to open at all -- a different state.
     */
    @Test fun aDamagedDatabaseIsNotOk() {
        DatabaseFactory.build(context).useDb { db ->
            db.runInTransaction {
                repeat(10) { i ->
                    db.rawCaptureDao().insertAll(
                        List(500) { sampleCapture(hash = "d-${i * 500 + it}", postedAt = 1000L + i * 500 + it) },
                    )
                }
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        Databases.reset()
        damageMidFile(context.getDatabasePath(DatabaseFactory.NAME))

        val result = Integrity.check(context)
        assertTrue(
            "A database with a flipped byte reported $result, so nothing can " +
                "ever reach the salvage path",
            result is Integrity.Result.Damaged,
        )
    }

    /**
     * **A check never recreates a file a delete has removed.** It opens an
     * instance of its own, and Room creates a missing file on open: measured
     * on emulator-5554 before the check had its own instance, a check through
     * a closed handle answered `Ok` and left `pinged.db` recreated where a
     * wipe had just removed it, and a restore's rebuild then found a database
     * beside no key and refused.
     */
    @Test fun aCheckAfterTheFileIsRemovedDoesNotRecreateIt() {
        Databases.shared(context)
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)

        val outcome = runCatching { Integrity.check(context) }
        val reopened = context.getDatabasePath(DatabaseFactory.NAME).exists()
        context.deleteDatabase(DatabaseFactory.NAME)

        assertFalse("Integrity.check recreated the ledger a delete removed, and answered $outcome", reopened)
        assertTrue(
            "a check with no file answered $outcome rather than refusing, so a caller " +
                "would record it as a verdict",
            outcome.exceptionOrNull() is DatabaseReplacedException,
        )
    }

    /**
     * **A check never mints a key.** `DatabaseKey.rawKeyPassphrase` creates
     * one when neither the key nor the database is there, and the check opens
     * beside `Databases.shared`, whose first open may be running through that
     * same unsynchronised check-then-create -- two keys, and the ledger under
     * the one that lost.
     */
    @Test fun aCheckWithNeitherKeyNorFileMintsNeither() {
        DatabaseFactory.build(context).close()
        DatabaseKey.destroy(context)
        val keyFile = File(context.noBackupFilesDir, "db.key")

        val outcome = runCatching { Integrity.check(context) }

        assertFalse("a check minted a key: $outcome", keyFile.exists())
        assertFalse("a check created a database: $outcome", context.getDatabasePath(DatabaseFactory.NAME).exists())
        assertTrue(
            "a check with no key answered $outcome",
            outcome.exceptionOrNull() is DatabaseKeyUnavailableException,
        )
    }
}
