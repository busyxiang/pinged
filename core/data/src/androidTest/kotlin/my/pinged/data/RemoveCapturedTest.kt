package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TxnDao.removeCaptured` (#132): Not a payment, on a row already counted.
 * It is a state change, never a `DELETE`, so the capture it came from keeps
 * its evidence and Corrections and dedup still find it.
 */
@RunWith(AndroidJUnit4::class)
class RemoveCapturedTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L

    @Before fun setUp() {
        db = freshDatabase()
        uncategorized = db.categoryDao().requireUncategorizedId()
    }

    @After fun tearDown() = db.close()

    @Test fun aCommittedCaptureIsRejectedAndItsRowAndCaptureSurvive() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "a"))
        val id = db.txnDao().insert(sampleTxn(rawCaptureId = capture, categoryId = uncategorized))

        assertEquals(1, db.txnDao().removeCaptured(id, NOW))

        assertEquals(TxnState.REJECTED, db.txnDao().byId(id)?.state)
        assertEquals("The capture is the evidence and must stay", capture, db.txnDao().byId(id)?.rawCaptureId)
        assertEquals("A removed payment still counted", 0, db.txnDao().countUnrejected())
    }

    @Test fun aManualEntryIsNotTouched() {
        val id = db.txnDao().insert(sampleTxn(rawCaptureId = null, pkg = null, categoryId = uncategorized))

        assertEquals("A manual entry is deleted, not rejected", 0, db.txnDao().removeCaptured(id, NOW))
        assertEquals(TxnState.COMMITTED, db.txnDao().byId(id)?.state)
    }

    @Test fun aRowStillAwaitingReviewIsNotTouched() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "b"))
        val id = db.txnDao().insert(
            sampleTxn(
                rawCaptureId = capture,
                categoryId = uncategorized,
                state = TxnState.PENDING,
                pendingReason = PendingReason.OVER_THRESHOLD,
            ),
        )

        assertEquals("The inbox answers a held row, with its own guard", 0, db.txnDao().removeCaptured(id, NOW))
        assertEquals(TxnState.PENDING, db.txnDao().byId(id)?.state)
    }

    @Test fun aRowAlreadyRejectedWritesNothing() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "c"))
        val id = db.txnDao().insert(sampleTxn(rawCaptureId = capture, categoryId = uncategorized, state = TxnState.REJECTED))
        val before = db.txnDao().byId(id)

        assertEquals(0, db.txnDao().removeCaptured(id, NOW))
        assertEquals(before, db.txnDao().byId(id))
    }

    private companion object {
        const val NOW = 9_000L
    }
}
