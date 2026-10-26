package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.ParseStatus
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
 * `CaptureRecord.read` against the real encrypted database: which days are
 * **captured**, and where the **history start** is (#81, Capture evidence;
 * #64, #65, #75, #305). The rules over that evidence are `CaptureRecordTest`'s.
 *
 * Fixtures are September 2026 throughout, read as of 10 October. Each date a
 * test expects is written as a literal `yyyymmdd`, so a wrong packing cannot
 * cancel against itself.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRecordReadTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun record(zone: ZoneId = KL) = CaptureRecord.read(db, OCT_10, CaptureRecord.EVERY_DAY, collecting = true, zone = zone)

    /** The days of September that are captured, as `yyyymmdd`. */
    private fun capturedInSeptember(): Set<Int> {
        val record = record()
        return (1..30).map { java.time.LocalDate.of(2026, 9, it) }
            .filter { record.day(it) == DayStatus.CAPTURED }
            .map { it.year * 10_000 + it.monthValue * 100 + it.dayOfMonth }
            .toSet()
    }

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

    private fun kl(day: Int, hour: Int = 12) =
        ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, KL).toInstant().toEpochMilli()

    private fun captureAt(postedAt: Long, status: ParseStatus = ParseStatus.NEW): Long =
        db.rawCaptureDao().insert(
            sampleCapture(hash = "h$postedAt$status", sbnKey = "k$postedAt$status", postedAt = postedAt, status = status),
        )

    // ---- captured days ---------------------------------------------------

    @Test fun aBoundDayIsCaptured() {
        bind(20260903)
        bind(20260831)

        assertEquals(
            "A day the listener was bound on is not captured, or a day with no " +
                "evidence was let in, so a month borrows its neighbours' coverage",
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
        // A capture's transaction has its capture beside it, so a history start;
        // the fixture's transactions point at captures that were never written.
        bind(20260901)
        txnOn(20260910)
        txnOn(20260911, state = TxnState.PENDING)
        txnOn(20260912, isExcluded = true)
        txnOn(20260913, direction = Direction.REFUND)
        txnOn(20260914)

        assertEquals(
            "A day holding a transaction captured from a notification is not " +
                "captured. The money is real and the grid draws it, so the " +
                "header would count a not-captured day the grid does not show",
            setOf(20260901, 20260910, 20260911, 20260912, 20260913, 20260914),
            capturedInSeptember(),
        )
    }

    @Test fun aHandEnteredTransactionIsNotEvidence() {
        bind(20260901)
        txnOn(20260914, handEntered = true)

        assertEquals(
            "A hand-entered transaction made its day captured. Nothing about it " +
                "says Pinged was watching",
            DayStatus.NOT_CAPTURED,
            record().day(java.time.LocalDate.of(2026, 9, 14)),
        )
    }

    /**
     * The salvage case (#305): a restore that could not read `capture_day`,
     * leaving one capture no rule matched on the day the history starts. That
     * day is the history start, so it must not also be a not-captured day.
     */
    @Test fun anUnmatchedCaptureAloneMakesItsDayCapturedAndNotMissing() {
        captureAt(kl(day = 8), ParseStatus.UNMATCHED)

        assertEquals(
            "The history-start day, whose only evidence is an unmatched capture, " +
                "was not captured: the record treated the notification that " +
                "starts it as no evidence of the day",
            setOf(20260908),
            capturedInSeptember(),
        )
        assertEquals(java.time.LocalDate.of(2026, 9, 8), record().historyStart)
    }

    @Test fun everyKeptNotificationWithNoTransactionIsEvidenceWhateverBecameOfIt() {
        val statuses = CaptureRecord.WITHOUT_TXN
        statuses.forEachIndexed { i, status -> captureAt(kl(day = 10 + i), status) }

        assertEquals(
            "A status that leaves no transaction (${statuses.joinToString()}) " +
                "did not vouch for its day, or a day was read with the wrong status",
            statuses.indices.map { 20260910 + it }.toSet(),
            capturedInSeptember(),
        )
    }

    @Test fun aMatchedCapturesDayIsItsTransactionsNotItsPostedAt() {
        // Posted late on the 11th in Kuala Lumpur, parsed into the 10th's ledger.
        bind(20260901)
        val id = captureAt(kl(day = 11, hour = 23), ParseStatus.MATCHED)
        db.txnDao().insert(
            sampleTxn(
                rawCaptureId = id,
                localDate = LocalDate(20260910),
                categoryId = db.categoryDao().requireUncategorizedId(),
            ),
        )

        assertEquals(
            "A matched capture vouched for its posted day as well as its " +
                "transaction's. Its transaction already carries the day",
            setOf(20260901, 20260910),
            capturedInSeptember(),
        )
    }

    @Test fun aKeptNotificationsDayIsLocalNotUtc() {
        // 23:30 UTC on the 12th is the 13th in Kuala Lumpur.
        captureAt(ZonedDateTime.of(2026, 9, 12, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), ParseStatus.REJECTED)

        assertEquals("The capture was dated in UTC", setOf(20260913), capturedInSeptember())
    }

    @Test fun aNotificationOnTodayIsNotACapturedDay() {
        bind(20260901)
        captureAt(ZonedDateTime.of(2026, 10, 10, 9, 0, 0, 0, KL).toInstant().toEpochMilli(), ParseStatus.UNMATCHED)

        val record = record()
        assertEquals(DayStatus.NOT_ENDED, record.day(OCT_10))
        assertEquals("Today counted toward collecting; only the 1st is a captured day", 1, record.collecting.days)
    }

    @Test fun aRecordReadOverASpanAnswersAsTheWholeHistoryDoes() {
        bind(20260901)
        bind(20260903)
        captureAt(kl(day = 4), ParseStatus.UNMATCHED)
        txnOn(20260905)
        val span = java.time.LocalDate.of(2026, 9, 3)..java.time.LocalDate.of(2026, 9, 6)

        val narrow = CaptureRecord.read(db, OCT_10, span, zone = KL)
        val whole = record()

        assertEquals(
            "A record read over days 3 to 6 answered differently from the whole history",
            span.start.datesUntil(span.endInclusive.plusDays(1)).toList().map(whole::day),
            span.start.datesUntil(span.endInclusive.plusDays(1)).toList().map(narrow::day),
        )
        assertEquals(
            listOf(DayStatus.CAPTURED, DayStatus.CAPTURED, DayStatus.CAPTURED, DayStatus.NOT_CAPTURED),
            span.start.datesUntil(span.endInclusive.plusDays(1)).toList().map(narrow::day),
        )
    }

    @Test fun aRecordReadWithoutCollectingRefusesTheCount() {
        bind(20260901)

        val refusal = runCatching { CaptureRecord.read(db, OCT_10, CaptureRecord.EVERY_DAY, zone = KL).collecting }
        assertEquals(
            "A record that fetched only its span answered a count it did not read",
            IllegalStateException::class,
            refusal.exceptionOrNull()?.let { it::class },
        )
    }

    @Test fun theCollectingCountTakesInNotificationsToo() {
        captureAt(kl(day = 1), ParseStatus.UNMATCHED)
        captureAt(kl(day = 2), ParseStatus.NO_EXTRAS)
        bind(20260903)
        txnOn(20260904)

        assertEquals(4, record().collecting.days)
    }

    // ---- historyStart ----------------------------------------------------

    private fun historyStart(zone: ZoneId = KL) = record(zone).historyStart

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
        val OCT_10: java.time.LocalDate = java.time.LocalDate.of(2026, 10, 10)
    }
}
