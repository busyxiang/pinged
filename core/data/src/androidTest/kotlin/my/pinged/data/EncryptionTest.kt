package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one failure mode that is otherwise invisible: SQLCipher silently
 * bypassed, leaving a plaintext database that every other test in this module
 * would still pass against.
 *
 * An unencrypted SQLite file begins with the exact 16 bytes
 * "SQLite format 3\u0000". An encrypted one begins with ciphertext, because
 * SQLCipher encrypts page 1 header included and puts its salt there instead.
 */
@RunWith(AndroidJUnit4::class)
class EncryptionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromAnEmptyDatabase() {
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun theFileOnDiskIsNotAPlaintextSqliteDatabase() {
        DatabaseFactory.build(context).useDb { db ->
            db.rawCaptureDao().insert(sampleCapture(hash = "encryption-probe"))
        }

        val file: File = context.getDatabasePath(DatabaseFactory.NAME)
        file.assertNotPlaintextSqlite("The database")
    }

    @Test fun aPlainSqliteOpenOfTheFileFails() {
        DatabaseFactory.build(context).useDb { db ->
            db.rawCaptureDao().insert(sampleCapture(hash = "plain-open-probe"))
        }
        val path = context.getDatabasePath(DatabaseFactory.NAME).absolutePath
        // The platform's own unencrypted SQLite must refuse this file. If it
        // succeeds, the bytes are readable without the key.
        val opened = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { it.rawQuery("SELECT count(*) FROM sqlite_master", null).use { c -> c.moveToFirst() } }
            true
        } catch (e: Exception) {
            Log.i(OpenTest.REPORT_TAG, "plain SQLite refused the file: ${e.javaClass.simpleName}")
            false
        }
        assertTrue("Plain, keyless SQLite opened the database. It is not encrypted.", !opened)
    }
}
