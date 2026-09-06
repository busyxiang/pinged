package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What happens to `capture_day` when something deletes the row capture has
 * already written for today.
 *
 * A restore does exactly that, and capture writes that row once per calendar
 * day, remembering in process that it has. The memo is a cache of a row
 * somebody else can delete, and a stale one is not a missed optimisation: the
 * rhythm grid hatches today as *not captured* while capture runs normally.
 */
@RunWith(AndroidJUnit4::class)
class CoverageMemoTest {
    private lateinit var db: PingedDatabase
    private val days get() = db.captureDayDao()

    @Before fun setUp() {
        db = freshDatabase()
        CaptureDays.forgetProcessMemo()
    }

    /** A date far outside any real one, so the shared emulator cannot supply it. */
    private val day = LocalDate(19_950_617)

    /**
     * A row vanishing without the DAO's knowledge, which is the state the memo
     * has to survive. `deleteAllForImport` is the real caller and has its own
     * test below; this is the same deletion with the invalidation left out, so
     * that the two halves can be measured separately.
     */
    private fun deleteTheRow() {
        db.openHelper.writableDatabase
            .execSQL("DELETE FROM capture_day WHERE local_date = ${day.yyyymmdd}")
    }

    @After fun tearDown() {
        // The memo is process-scoped and this suite is one process, so a day
        // left in it would gate the next test's writer.
        CaptureDays.forgetProcessMemo()
        db.close()
    }

    /**
     * The memo suppresses the writer, which is its whole purpose -- so a
     * deleted row does not come back on the next notification by itself. This
     * is the state the fix has to be measured against; without this half, the
     * test below could pass because the memo never mattered.
     */
    @Test fun theMemoIsWhatKeepsADeletedDayDeleted() {
        run {
            CaptureDays.forgetProcessMemo()
            CaptureDays.markNotificationSeen(day) { days }
            assertNotNull("precondition: the day was written", days.byDate(day))

            deleteTheRow()

            CaptureDays.markNotificationSeen(day) { days }
            assertNull(
                "The memo is not gating the writer, so this test proves nothing",
                days.byDate(day),
            )
        }
    }

    /**
     * And emptying the table through the DAO is what lets the next
     * notification write the row back -- the recovery
     * `CaptureDayDao.deleteAllForImport` promises, now kept by the same call
     * that breaks it rather than by a caller two modules away remembering to.
     */
    @Test fun emptyingTheTableThroughTheDaoRestoresTheNextWrite() {
        run {
            CaptureDays.forgetProcessMemo()
            CaptureDays.markNotificationSeen(day) { days }
            days.deleteAllForImport()

            CaptureDays.markNotificationSeen(day) { days }
            assertNotNull(
                "After a restore the day capture is actually watching must come " +
                    "back, or the grid reports the app blind on a day it was not",
                days.byDate(day),
            )
        }
    }

    /**
     * A write that fails is retried; the memo does not claim what did not happen.
     *
     * `markListenerBound` set its memo *before* the write while its sibling set it
     * after. Every reason that write can throw is a transient storage failure the
     * process outlives, and the only caller is `onListenerConnected`, which fires
     * once and then not again while the listener stays bound -- so one failure at
     * boot lost `listener_bound` for the rest of the day.
     */
    @Test fun aListenerBindThatFailsToWriteIsWrittenOnTheNextAttempt() {
        run {
            CaptureDays.forgetProcessMemo()

            val thrown = runCatching {
                CaptureDays.markListenerBound(day) { error("storage is full") }
            }
            assertTrue("precondition: the write failed", thrown.isFailure)
            assertNull("precondition: nothing was written", days.byDate(day))

            CaptureDays.markListenerBound(day) { days }
            assertNotNull(
                "The failed write poisoned the memo, so the day capture was " +
                    "watching is recorded as a day it was blind",
                days.byDate(day),
            )
        }
    }

    /**
     * The memo does not outlive the process, so a restart recovers too.
     *
     * This is why the durable copy of it in DataStore is gone: that copy made
     * the stale state survive every restart, and nothing could invalidate it.
     * [CaptureHealth.forgetProcessMemo] is what a fresh process looks like from
     * inside one test process.
     */
    @Test fun aFreshProcessWritesTheDayAgain() {
        run {
            CaptureDays.forgetProcessMemo()
            CaptureDays.markNotificationSeen(day) { days }
            deleteTheRow()

            CaptureDays.forgetProcessMemo()

            CaptureDays.markNotificationSeen(day) { days }
            assertNotNull(days.byDate(day))
        }
    }
}
