package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.ProviderException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database-without-key state, which spec 11.1 says "must be handled, not
 * crashed" -- and which nothing tested, in either direction.
 *
 * `DatabaseKeyUnavailableException` existed and one of its two paths was
 * reachable, so the type read as covered. No test ever constructed the state,
 * so nothing noticed that the *other* path -- `rawKeyPassphrase` finding no key
 * file -- did not throw it at all: it called `create()` and minted fresh key
 * material without asking whether a database the old key had been opening
 * existed.
 *
 * The wrapped key lives in `getNoBackupFilesDir()` precisely so no backup or
 * transfer path can carry it, while `databases/` is an ordinary app directory,
 * so "key gone, database present" is what a partial restore leaves behind.
 * Fresh key material then makes SQLCipher fail to decrypt page 1, which is
 * byte-for-byte how a corrupt file fails -- and spec 11.1 needs the two told
 * apart, because corruption offers "export whatever still reads" and a missing
 * key offers "start fresh or restore".
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile get() = File(context.noBackupFilesDir, "db.key")
    private val dbFile get() = context.getDatabasePath(DatabaseFactory.NAME)

    @Before fun startClean() {
        context.deleteDatabase(DatabaseFactory.NAME)
        keyFile.delete()
    }

    /**
     * Leaves the fixture in a state the *next* test class can open. Without
     * it, a class that ran after the corrupted-key test would inherit a key
     * file full of garbage and fail for a reason that has nothing to do with
     * it.
     */
    @After fun leaveAWorkingKeyBehind() {
        DatabaseKey.afterKeyFileOpened = {}
        DatabaseKey.beforeKeyMinted = {}
        context.deleteDatabase(DatabaseFactory.NAME)
        keyFile.delete()
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() }
    }

    // ---- the case that was silently mishandled -------------------------

    @Test fun aMissingKeyBesideAnExistingDatabaseThrowsInsteadOfMintingANewKey() {
        DatabaseFactory.build(context).useDb { db ->
            db.rawCaptureDao().insert(sampleCapture(hash = "irreplaceable"))
        }
        val keyBefore = keyFile.readBytes()
        assertTrue("No database file to lose", dbFile.isFile)

        // What a partial restore leaves: no_backup/ gone, databases/ intact.
        assertTrue("Could not delete the key file", keyFile.delete())

        val thrown = assertThrows(DatabaseKeyUnavailableException::class.java) {
            DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() }
        }
        Log.i(OpenTest.REPORT_TAG, "key-missing-db-present: ${thrown.message}")
        // The message has to name the situation, because the caller has to
        // choose between two different offers on the strength of it.
        assertTrue(
            "The exception does not say what happened: ${thrown.message}",
            thrown.message!!.contains("key missing but database present"),
        )
        // And the crucial part: it did NOT write a new key over the gap.
        assertTrue(
            "A new key was minted anyway; the database is now unopenable forever",
            !keyFile.exists(),
        )
        assertTrue("The database was destroyed", dbFile.isFile)

        // Proof the refusal preserved something real: put the original key
        // back and the database opens, with its row still in it.
        keyFile.writeBytes(keyBefore)
        DatabaseFactory.build(context).useDb {
            assertEquals(
                "The database was recoverable all along",
                1,
                it.rawCaptureDao().countAll(),
            )
        }
    }

    // ---- the neighbouring cases, so the guard is not just "always throw" --

    @Test fun aFirstEverLaunchStillMintsAKey() {
        assertTrue("Fixture is wrong: a key already exists", !keyFile.exists())
        assertTrue("Fixture is wrong: a database already exists", !dbFile.exists())

        DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() }

        assertTrue("No key was created on a fresh install", keyFile.isFile)
        assertTrue("No database was created on a fresh install", dbFile.isFile)
    }

    /**
     * Key file gone *and* database gone is an uninstall or a completed "delete
     * all data", and minting is correct there. This is the case the old code
     * was written for; it is the only one it was right about.
     */
    @Test fun aMissingKeyWithNoDatabaseIsAFreshStart() {
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().insert(sampleCapture()) }
        context.deleteDatabase(DatabaseFactory.NAME)
        keyFile.delete()

        DatabaseFactory.build(context).useDb {
            assertEquals(0, it.rawCaptureDao().countAll())
        }
        assertTrue(keyFile.isFile)
    }

    /**
     * The path that was already reachable: a key file that is there but cannot
     * be unwrapped. Spec 11.1 lists a Keystore entry invalidated by an OTA and
     * a device-to-device transfer; a corrupted blob exercises the same
     * `catch`. It must be the same exception type, because the app offers the
     * user the same two choices.
     */
    @Test fun anUnwrappableKeyThrowsTheSameException() {
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().insert(sampleCapture()) }
        val blob = keyFile.readBytes()
        // Flip a bit inside the ciphertext body, past the 12-byte IV, so
        // AES-GCM's tag check fails rather than the read.
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        keyFile.writeBytes(blob)

        val thrown = assertThrows(DatabaseKeyUnavailableException::class.java) {
            DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() }
        }
        Log.i(OpenTest.REPORT_TAG, "key-unwrappable: ${thrown.message}")
        assertTrue(
            "The unwrap failure should say it could not unwrap: ${thrown.message}",
            thrown.message!!.contains("could not be unwrapped"),
        )
    }

    /**
     * **A key file cut short is the key gone, not a crash.** `unwrap` split
     * the blob before its `try`, so a file shorter than the 12-byte IV threw
     * `IndexOutOfBoundsException` -- outside the
     * `DatabaseUnavailableException` family, so past `CaptureStorage.guarded`
     * and `SettingsViewModel.opened` both. Zero bytes is what a key write
     * cut off after its open leaves; 11 is short of the IV, 27 short of the
     * IV and the GCM tag. Both readers, the minting one and `aside`'s.
     */
    @Test fun aKeyFileCutShortIsTheKeyGoneRatherThanAnUnhandledException() {
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().insert(sampleCapture()) }
        val whole = keyFile.readBytes()
        for (length in listOf(0, 11, 27)) {
            keyFile.writeBytes(whole.copyOf(length))
            for ((reader, read) in listOf<Pair<String, () -> Unit>>(
                "build" to { DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() } },
                "existingRawKeyPassphrase" to { DatabaseKey.existingRawKeyPassphrase(context) },
            )) {
                val thrown = runCatching(read).exceptionOrNull()
                assertTrue(
                    "$reader on a $length-byte key file threw $thrown, not DatabaseKeyUnavailableException",
                    thrown is DatabaseKeyUnavailableException,
                )
            }
        }
        keyFile.writeBytes(whole)
        DatabaseFactory.build(context).useDb {
            assertEquals("the database was intact behind the short key", 1, it.rawCaptureDao().countAll())
        }
    }

    /**
     * **A process killed while the key is being written leaves no key
     * file.** Written in place, a death between the open, which truncates,
     * and the write left a zero-byte `db.key`, and every launch after that
     * read it as a key gone -- over a database that, after power lost before
     * writeback, could be intact. Simulated by an `Error` thrown once the
     * file is open, which nothing on the write path catches, so no cleanup
     * runs: the open file is all a death leaves. Written to a temporary and
     * renamed, `db.key` is never there short, and the next launch mints.
     */
    @Test fun aProcessKilledMidKeyWriteLeavesNoKeyFileBehind() {
        DatabaseKey.afterKeyFileOpened = { throw SimulatedDeath() }
        assertThrows(SimulatedDeath::class.java) { DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() } }
        assertFalse(
            "a key write cut off after its open left a ${keyFile.length()}-byte db.key, which every later open reads as the key gone",
            keyFile.exists(),
        )

        DatabaseKey.afterKeyFileOpened = {}
        DatabaseFactory.build(context).useDb { assertEquals(0, it.rawCaptureDao().countAll()) }
        assertTrue("the next launch did not mint a key", keyFile.isFile)
        assertFalse("the temporary outlived the write that replaced it", File(context.noBackupFilesDir, "db.key.new").exists())
    }

    /**
     * **A key write that fails -- a full disk -- is the
     * `DatabaseUnavailableException` family's, and leaves nothing.** An
     * `IOException` escaping `DatabaseFactory.build` would pass
     * `CaptureStorage.guarded`, as a short key would. Also where the cost of
     * minting, fsynced twice, is measured.
     */
    @Test fun aKeyWriteThatFailsIsTheUnreadableStateAndLeavesNothing() {
        DatabaseKey.afterKeyFileOpened = { throw IOException("No space left on device (simulated)") }
        val thrown = runCatching { DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() } }.exceptionOrNull()
        assertTrue("a failed key write threw $thrown", thrown is DatabaseUnreadableException)
        assertFalse("a failed key write left a ${keyFile.length()}-byte db.key", keyFile.exists())
        assertFalse("a failed key write left its temporary behind", File(context.noBackupFilesDir, "db.key.new").exists())

        DatabaseKey.afterKeyFileOpened = {}
        val minted = (1..5).map {
            keyFile.delete()
            val start = System.nanoTime()
            DatabaseKey.rawKeyPassphrase(context)
            (System.nanoTime() - start) / 1_000L
        }
        Log.i(OpenTest.REPORT_TAG, "key minted, fsynced and renamed in $minted us")
    }

    /**
     * **A Keystore that will not mint the wrapping key is the unreadable
     * state too, and the next open mints.** `generateKey` raises
     * `ProviderException`, which let through would pass every guard, and the
     * first open after a delete everything -- `MainActivity`'s probe, the
     * settings screen's read -- would kill the app. Thrown here as AndroidKeyStore throws
     * it, where `DatabaseKey.create` asks for the key.
     */
    @Test fun aKeystoreThatWillNotMintAKeyIsTheUnreadableStateAndLeavesNothing() {
        DatabaseKey.beforeKeyMinted = { throw ProviderException("Failed to generate key (simulated)") }
        val thrown = runCatching { DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() } }.exceptionOrNull()
        assertTrue("a Keystore that would not mint threw $thrown", thrown is DatabaseUnreadableException)
        assertFalse("a key that was never made left a ${keyFile.length()}-byte db.key", keyFile.exists())

        DatabaseKey.beforeKeyMinted = {}
        DatabaseFactory.build(context).useDb { assertEquals(0, it.rawCaptureDao().countAll()) }
        assertTrue("the next open did not mint a key", keyFile.isFile)
    }

    /** What [aProcessKilledMidKeyWriteLeavesNoKeyFileBehind] throws: an `Error`, so no `catch` on the write path sees it. */
    private class SimulatedDeath : Error("the process died here (simulated)")

    // ---- destroy ------------------------------------------------------

    /**
     * `DatabaseKey.destroy` used to delete the key and leave the database,
     * which manufactured the state the test at the top of this class proves is
     * unrecoverable. It has no caller yet, so this was a loaded trap rather
     * than a live bug -- and a trap in the one method whose entire job (spec
     * 11.3, "delete all data") is to leave nothing behind.
     */
    @Test fun destroyLeavesNoDatabaseBehindToBeOrphaned() {
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().insert(sampleCapture()) }
        assertTrue(dbFile.isFile)
        assertTrue(keyFile.isFile)

        DatabaseKey.destroy(context)

        assertTrue("destroy left the key file", !keyFile.exists())
        assertTrue("destroy left an unopenable database behind", !dbFile.exists())
        assertTrue(
            "destroy left a WAL beside a deleted database",
            !File(dbFile.parentFile, "${DatabaseFactory.NAME}-wal").exists(),
        )
        assertTrue(
            "destroy left a SHM beside a deleted database",
            !File(dbFile.parentFile, "${DatabaseFactory.NAME}-shm").exists(),
        )
        // And the app can start again afterwards, which is the point of
        // "start fresh".
        DatabaseFactory.build(context).useDb {
            assertEquals(0, it.rawCaptureDao().countAll())
        }
    }
}
