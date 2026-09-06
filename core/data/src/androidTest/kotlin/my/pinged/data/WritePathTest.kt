package my.pinged.data

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.Arrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Durability of a committed capture, and what it costs.
 *
 * Room enables WAL, and AOSP's SQLite pairs WAL with `synchronous = NORMAL`,
 * which does not fsync at commit -- durable against process death and **not**
 * against the machine stopping. A battery pull discards the un-checkpointed
 * tail of the WAL, which is the most recent captures, and spec 11.2 points out
 * that Android will not replay the past.
 */
@RunWith(AndroidJUnit4::class)
class WritePathTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromAnEmptyDatabase() {
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    /**
     * **The pragma has to be read on the connection writes actually use, and an
     * ordinary query is not that connection.**
     *
     * SQLCipher runs an AOSP-style pool behind one `SQLiteDatabase` and routes by
     * statement kind: `execSQL` gets primary affinity, a `query` gets read-only
     * affinity and can be served by a secondary. `synchronous` is per-connection
     * with no pool-wide setter, so the secondaries stay at the framework default --
     * harmlessly, since a read never fsyncs.
     *
     * Inside a transaction the session holds the primary connection, which makes
     * this read deterministic. Through a plain query it was not: the same assertion
     * returned 2 while the pool had only its primary and 1 once it had opened a
     * secondary, so it passed or failed on how many reads had happened first.
     */
    private fun readPragmaOnTheWriteConnection(db: SupportSQLiteDatabase): Int {
        db.beginTransaction()
        return try {
            db.pragma("synchronous").toInt()
        } finally {
            db.endTransaction()
        }
    }

    /** 2 is `FULL`, 1 is `NORMAL`. */
    @Test fun theWriteConnectionIsSynchronousFull() {
        DatabaseFactory.build(context).useDb { db ->
            db.rawCaptureDao().insert(sampleCapture(hash = "durable"))
            val onWrites = readPragmaOnTheWriteConnection(db.openHelper.writableDatabase)
            val onReads = db.openHelper.writableDatabase.pragma("synchronous").toInt()
            Log.i(
                OpenTest.REPORT_TAG,
                "PRAGMA synchronous: $onWrites on the write connection, " +
                    "$onReads through a plain read",
            )
            assertEquals(
                "PRAGMA synchronous is not FULL on the write path, so a committed " +
                    "capture is not fsynced and a battery pull loses it",
                2,
                onWrites,
            )
        }
    }

    /** It survives a close and reopen, i.e. the factory sets it and not a test. */
    @Test fun itIsStillFullAfterReopening() {
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().countAll() }
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(2, readPragmaOnTheWriteConnection(db.openHelper.writableDatabase))
        }
    }

    /**
     * A control, so the assertion above is known to be measuring something.
     * With the pragma explicitly put back to `NORMAL` on the write connection
     * the same read returns 1, which rules out "the read always says 2".
     */
    @Test fun theSameReadReportsNormalWhenItIsNormal() {
        DatabaseFactory.build(context).useDb { db ->
            db.openHelper.writableDatabase.execSQL("PRAGMA synchronous = NORMAL")
            assertEquals(1, readPragmaOnTheWriteConnection(db.openHelper.writableDatabase))
        }
    }

    /**
     * The cost, logged rather than asserted against a threshold -- the number is
     * the evidence, and a timing bound on an emulator is noise. Spec 15 puts
     * capture volume at ~28 a day, so the whole daily cost is the per-insert delta
     * times 28.
     *
     * The mode is set with `execSQL` and verified inside a transaction for the
     * reason in [readPragmaOnTheWriteConnection]: set through a plain query, the
     * NORMAL arm could land on a read connection and quietly time FULL against
     * FULL.
     */
    @Test fun theCostOfFsyncingEveryCommitIsMeasured() {
        val perInsert = mutableMapOf<String, Double>()
        for (mode in listOf("NORMAL", "FULL")) {
            context.deleteDatabase(DatabaseFactory.NAME)
            DatabaseFactory.build(context).useDb { db ->
                val write = db.openHelper.writableDatabase
                write.execSQL("PRAGMA synchronous = $mode")
                assertEquals(
                    "The fixture did not reach $mode on the write connection, so " +
                        "this measurement would be timing the wrong thing",
                    if (mode == "NORMAL") 1 else 2,
                    readPragmaOnTheWriteConnection(write),
                )
                repeat(20) {
                    db.rawCaptureDao().insert(sampleCapture(hash = "warm$it", sbnKey = "w$it"))
                }
                val n = 200
                val started = System.nanoTime()
                repeat(n) {
                    db.rawCaptureDao().insert(sampleCapture(hash = "m$it", sbnKey = "m$it"))
                }
                perInsert[mode] = (System.nanoTime() - started) / 1000.0 / n
            }
        }
        val normal = perInsert.getValue("NORMAL")
        val full = perInsert.getValue("FULL")
        Log.i(
            OpenTest.REPORT_TAG,
            "insert cost: synchronous=NORMAL %.3fus, synchronous=FULL %.3fus, delta %.3fus; "
                .format(normal, full, full - normal) +
                "at 28 captures/day that is %.2fms/day".format((full - normal) * 28 / 1000.0),
        )
        assertTrue("Measurement failed to produce a number", normal > 0 && full > 0)
    }

    /**
     * A capture read back through a *fresh* database handle, i.e. off disk
     * rather than out of the page cache of the connection that wrote it. This
     * does not prove an fsync happened -- only a power cut does that, and this
     * harness cannot cause one -- and it is the strongest thing available
     * here, so it is stated for what it is.
     */
    @Test fun aCommittedCaptureIsReadableFromANewHandle() {
        DatabaseFactory.build(context).useDb { db ->
            db.rawCaptureDao().insert(sampleCapture(hash = "persisted", arrival = Arrival.POSTED))
        }
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(1, db.rawCaptureDao().countAll())
            assertEquals("persisted", db.rawCaptureDao().pageFrom(0, 1).single().contentHash)
        }
    }
}
