package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.PingedDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the export's keyset cursor cannot see, pinned next to the check that
 * stops it existing.
 *
 * `ExportJson.writePaged` starts at 0 and every paging query says
 * `id > :after`, so a row numbered below one is skipped by every export this
 * device will ever write, with nothing reporting it: the app still shows the
 * row and the backup does not contain it.
 *
 * Pinged never creates such a row, so the only way one appears is a restore,
 * and `BackupRow.rowId` refuses it there. If the cursor is ever made total
 * (`Long.MIN_VALUE`, and the paging queries to match) this test is the one that
 * will fail -- delete it then, and relax the import check with it.
 */
@RunWith(AndroidJUnit4::class)
class ExportCursorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    @Test fun aRowNumberedBelowOneIsInvisibleToTheExport() {
        val target = freshDatabase(context).also { db = it }
        val seeded = seedOneOfEverything(target)
        val victim = seeded.txns.first()

        val before = String(exportBytes(target), Charsets.UTF_8)
        assertTrue(
            "precondition: the transaction is in the export while its id is positive",
            before.contains(""""id":$victim,"raw_capture_id""""),
        )
        val txnsBefore = target.txnDao().countAll()

        // Straight to SQL: no DAO renumbers a row, because nothing in the app
        // has any reason to.
        target.openHelper.writableDatabase
            .execSQL("UPDATE txn SET id = -1 WHERE id = $victim")

        assertEquals(
            "the row is still in the table, which is the whole problem",
            txnsBefore,
            target.txnDao().countAll(),
        )
        assertFalse(
            "The keyset cursor started below one, so this test no longer " +
                "describes the export and BackupRow.rowId may be relaxed",
            String(exportBytes(target), Charsets.UTF_8).contains(""""raw_capture_id":1,"amount_sen""""),
        )
    }
}
