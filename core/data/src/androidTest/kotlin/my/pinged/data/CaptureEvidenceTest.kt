package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The two reads behind `notCapturedDays`, against the real encrypted
 * database: which days are **captured**, and where the **history start** is
 * (#81, Capture evidence; #64, #65, #75).
 *
 * Fixtures are September 2026 throughout. Each date a test expects is written
 * as a literal `yyyymmdd`, so a wrong packing cannot cancel against itself.
 */
@RunWith(AndroidJUnit4::class)
class CaptureEvidenceTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun capturedInSeptember(): Set<Int> =
        db.captureDayDao().capturedDates(LocalDate(20260901), LocalDate(20260930))
            .map { it.yyyymmdd }.toSet()

    private fun bind(yyyymmdd: Int, bound: Boolean = true) =
        db.captureDayDao().recordListenerBound(LocalDate(yyyymmdd), bound)

    private var nextCaptureId = 1L

    /** A transaction on [yyyymmdd], captured from a notification unless [handEntered]. */
    private fun txnOn(
        yyyymmdd: Int,
        handEntered: Boolean = false,
        state: TxnState = TxnState.COMMITTED,
        isExcluded: Boolean = false,
        direction: Direction = Direction.EXPENSE,
    ) {
        db.txnDao().insert(
            sampleTxn(
                rawCaptureId = if (handEntered) null else nextCaptureId++,
                localDate = LocalDate(yyyymmdd),
                categoryId = db.categoryDao().requireUncategorizedId(),
                state = state,
                isExcluded = isExcluded,
                direction = direction,
            ),
        )
    }

    // ---- capturedDates ---------------------------------------------------

    @Test fun aBoundDayIsCaptured() {
        bind(20260903)
        bind(20260831)
        bind(20261001)

        assertEquals(
            "A day the listener was bound on is not captured, or a day outside " +
                "the range was let in, so a month borrows its neighbours' coverage",
            setOf(20260903),
            capturedInSeptember(),
        )
    }

    @Test fun aDayRecordedAsUnboundIsNotCaptured() {
        bind(20260903)
        bind(20260904, bound = false)

        assertEquals(
            "A day recorded as unbound counted as captured. Only an import " +
                "stores one, and it is the one day the record says Pinged was " +
                "not watching",
            setOf(20260903),
            capturedInSeptember(),
        )
    }

    @Test fun aDayWithOnlyANotificationSeenRowIsCapturedOnItsBoundFlagAlone() {
        // The import's shape: bound, and no notification that day.
        db.captureDayDao().insertForImport(
            CaptureDay(LocalDate(20260905), listenerBound = true, sawAnyNotification = false),
        )
        db.captureDayDao().insertForImport(
            CaptureDay(LocalDate(20260906), listenerBound = false, sawAnyNotification = true),
        )

        assertEquals(
            "saw_any_notification decided the answer. A quiet phone posts " +
                "nothing on a day it is watching, and a notification on an " +
                "unbound row says nothing the bound flag does not",
            setOf(20260905),
            capturedInSeptember(),
        )
    }

    @Test fun aCapturedTransactionWithNoRowIsCapturedWhateverItsState() {
        txnOn(20260910)
        txnOn(20260911, state = TxnState.PENDING)
        txnOn(20260912, isExcluded = true)
        txnOn(20260913, direction = Direction.REFUND)
        txnOn(20260913)

        assertEquals(
            "A day holding a transaction captured from a notification is not " +
                "captured. The money is real and the grid draws it, so the " +
                "header would count a not-captured day the grid does not show",
            setOf(20260910, 20260911, 20260912, 20260913),
            capturedInSeptember(),
        )
    }

    @Test fun aHandEnteredTransactionIsNotEvidence() {
        txnOn(20260914, handEntered = true)

        assertEquals(
            "A hand-entered transaction made its day captured. Nothing about it " +
                "says Pinged was watching",
            emptySet<Int>(),
            capturedInSeptember(),
        )
    }

    @Test fun aDayWithBothKindsOfEvidenceIsReturnedOnce() {
        bind(20260915)
        txnOn(20260915)
        txnOn(20260915)

        assertEquals(
            "A day came back more than once",
            listOf(20260915),
            db.captureDayDao().capturedDates(LocalDate(20260901), LocalDate(20260930))
                .map { it.yyyymmdd },
        )
    }

    // ---- historyStart ----------------------------------------------------

    private fun historyStart(zone: ZoneId = KL) =
        CaptureEvidence.historyStart(db.captureDayDao(), db.rawCaptureDao(), zone)

    private fun captureAt(postedAt: Long) {
        db.rawCaptureDao().insert(
            sampleCapture(hash = "h$postedAt", sbnKey = "k$postedAt", postedAt = postedAt),
        )
    }

    @Test fun theHistoryStartIsTheFirstCaptureDayWhenThatIsAllThereIs() {
        bind(20260909)
        bind(20260907)

        assertEquals(
            "The first capture_day row did not set the history start, so the " +
                "days between the first bind and the first capture are drawn blank",
            java.time.LocalDate.of(2026, 9, 7),
            historyStart(),
        )
    }

    /**
     * A salvage restore that lost `capture_day`. 23:30 UTC on the 7th is the
     * 8th in Kuala Lumpur, so the expected date also pins the conversion of
     * spec 15.7: a raw UTC day would be off by one.
     */
    @Test fun theHistoryStartIsTheFirstCaptureInTheLocalZoneWhenThatIsAllThereIs() {
        captureAt(ZonedDateTime.of(2026, 9, 10, 9, 0, 0, 0, KL).toInstant().toEpochMilli())
        captureAt(ZonedDateTime.of(2026, 9, 7, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli())

        assertEquals(
            "The earliest capture was not converted to the device's local day",
            java.time.LocalDate.of(2026, 9, 8),
            historyStart(),
        )
    }

    @Test fun theEarlierOfTheTwoWinsEitherWay() {
        bind(20260905)
        captureAt(ZonedDateTime.of(2026, 9, 7, 12, 0, 0, 0, KL).toInstant().toEpochMilli())
        assertEquals(
            "A bind before the first capture did not set the history start; the " +
                "days between them would be drawn blank",
            java.time.LocalDate.of(2026, 9, 5),
            historyStart(),
        )

        captureAt(ZonedDateTime.of(2026, 9, 2, 12, 0, 0, 0, KL).toInstant().toEpochMilli())
        assertEquals(
            "A capture before the first capture_day row did not set the history " +
                "start",
            java.time.LocalDate.of(2026, 9, 2),
            historyStart(),
        )
    }

    @Test fun neitherGivesNoHistoryStart() {
        txnOn(20260910, handEntered = true)

        assertNull(
            "A database with no capture evidence, only a hand-entered " +
                "transaction, has a history start",
            historyStart(),
        )
    }

    /**
     * A restore from an older phone: the file's rows go in as an import does,
     * then this phone binds. The old phone's first day is where history
     * starts, not this phone's.
     */
    @Test fun anImportedOlderHistoryKeepsItsStart() {
        db.captureDayDao().insertAllForImport(
            (1..20).map {
                CaptureDay(LocalDate(20250300 + it), listenerBound = true, sawAnyNotification = true)
            },
        )
        db.rawCaptureDao().insertAll(
            (3..20).map {
                val at = ZonedDateTime.of(2025, 3, it, 12, 0, 0, 0, KL).toInstant().toEpochMilli()
                sampleCapture(hash = "old$it", sbnKey = "old$it", postedAt = at)
            },
        )
        bind(20260910)

        assertEquals(
            "A restore lost the old phone's history start, so months of real " +
                "history would be drawn blank",
            java.time.LocalDate.of(2025, 3, 1),
            historyStart(),
        )
    }

    private companion object {
        val KL: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    }
}
