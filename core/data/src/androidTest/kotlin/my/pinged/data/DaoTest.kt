package my.pinged.data

import my.pinged.data.LocalDate
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.DuplicateWindows
import my.pinged.data.Seed
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import java.time.YearMonth
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import my.pinged.parse.Direction

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase()
    }

    // Without this every test in this class leaves an open SQLCipher handle
    // on a file the next test's deleteDatabase() is about to unlink. `useDb {}`
    // closes for the tests that open their own; a class-level `db` needs this.
    @After fun tearDown() {
        db.close()
    }

    // ---- seed ----------------------------------------------------------

    @Test fun seedsFourteenCategoriesWithUncategorizedProtected() {
        val all = db.categoryDao().all()
        assertEquals(Seed.categories().size, all.size)
        val uncategorized = all.single { it.name == "Uncategorized" }
        assertTrue(uncategorized.isProtected)
        // Ordered by sort_order, and only Uncategorized is protected.
        assertEquals((0..13).toList(), all.map { it.sortOrder })
        assertEquals(listOf("Uncategorized"), all.filter { it.isProtected }.map { it.name })
        // sort_order 0 was renamed from "Makan" to "Food & Drinks"; the icon
        // key and the position did not move with it.
        assertEquals("Food & Drinks", all.first().name)
        assertEquals("utensils", all.first().iconKey)
        assertEquals(0, all.first().sortOrder)
    }

    @Test fun uncategorizedResolvesToASeededId() {
        val id = db.categoryDao().requireUncategorizedId()
        val row = db.categoryDao().all().single { it.id == id }
        assertEquals("Uncategorized", row.name)
    }

    // ---- stage two work queue ------------------------------------------

    @Test fun claimNextReturnsOnlyNewRowsInArrivalOrder() {
        val dao = db.rawCaptureDao()
        dao.insert(sampleCapture(hash = "a", postedAt = 200))
        dao.insert(sampleCapture(hash = "b", postedAt = 100))
        dao.insert(sampleCapture(hash = "c", postedAt = 300, status = ParseStatus.MATCHED))

        val claimed = dao.claimNext(10)
        assertEquals(listOf("b", "a"), claimed.map { it.contentHash })
    }

    @Test fun claimNextHonoursItsLimit() {
        val dao = db.rawCaptureDao()
        repeat(5) { dao.insert(sampleCapture(hash = "h$it", postedAt = 100L + it)) }
        assertEquals(listOf("h0", "h1"), dao.claimNext(2).map { it.contentHash })
    }

    @Test fun claimNextCanBeAskedForAnotherStatus() {
        val dao = db.rawCaptureDao()
        dao.insert(sampleCapture(hash = "new"))
        dao.insert(sampleCapture(hash = "old", status = ParseStatus.UNMATCHED))
        assertEquals(
            listOf("old"),
            dao.claimNext(10, ParseStatus.UNMATCHED).map { it.contentHash },
        )
    }

    @Test fun markOutcomeMovesARowOffTheQueue() {
        val dao = db.rawCaptureDao()
        val id = dao.insert(sampleCapture(hash = "q"))
        val rows = dao.markOutcome(
            id = id,
            status = ParseStatus.MATCHED,
            ruleId = "tng.payment.v1",
            rejectId = null,
            collisionId = null,
            duplicateOf = null,
            packVersion = 3,
        )
        assertEquals("markOutcome did not update the row", 1, rows)
        assertEquals(0, dao.claimNext(10).size)
        val row = dao.byId(id)
        assertEquals(ParseStatus.MATCHED, row.parseStatus)
        assertEquals("tng.payment.v1", row.matchedRuleId)
        assertEquals(3, row.packVersion)
    }

    // ---- duplicates, layer 1: the same notification again ---------------
    // Spec 7.2 layer 1. Matches on content_hash inside 60 seconds and
    // produces NO transaction.

    @Test fun findByContentHashRespectsTheWindow() {
        val dao = db.rawCaptureDao()
        dao.insert(sampleCapture(hash = "dup", postedAt = 1_000_000))
        assertEquals(1, dao.contentHashWindow("dup", 1_000_000).size)
        assertEquals(0, dao.contentHashWindow("dup", 1_000_000 + DuplicateWindows.CONTENT_HASH_MILLIS + 1).size)
    }

    /**
     * The window boundary from the row side, in both directions: one capture
     * exactly 60 seconds old is inside, one 60,001 ms old is outside, and the
     * query separates them. Varying `sinceMillis` alone (above) does not prove
     * this.
     */
    @Test fun layerOneWindowIncludesSixtySecondsAndExcludesSixtyOne() {
        val dao = db.rawCaptureDao()
        val now = 1_000_000L
        dao.insert(sampleCapture(hash = "dup", postedAt = now, sbnKey = "slot-now"))
        dao.insert(sampleCapture(hash = "dup", postedAt = now - DuplicateWindows.CONTENT_HASH_MILLIS, sbnKey = "slot-edge"))
        dao.insert(sampleCapture(hash = "dup", postedAt = now - DuplicateWindows.CONTENT_HASH_MILLIS - 1, sbnKey = "slot-stale"))

        val hits = dao.contentHashWindow("dup", now).map { it.sbnKey }.sorted()
        assertEquals(listOf("slot-edge", "slot-now"), hits)
    }

    /**
     * The guard that would have been silently disabled. A capture posted two
     * seconds ago is still `NEW`, because the stage-two worker has not run
     * yet -- that is the ordinary case, not the exception. A `parse_status`
     * filter on this query means the second notification is written as a
     * fresh capture and becomes a second transaction for money spent once.
     */
    @Test fun layerOneCatchesADuplicateWhoseOriginalIsStillNew() {
        val dao = db.rawCaptureDao()
        val first = dao.insert(sampleCapture(hash = "dup", postedAt = 1_000_000, sbnKey = "slot-1"))
        val original = dao.byId(first)
        assertEquals("The original must still be NEW for this test to mean anything",
            ParseStatus.NEW, original.parseStatus)

        // The second notification arrives two seconds later in a different slot.
        val secondPostedAt = 1_002_000L
        val candidates = dao
            .contentHashWindow("dup", secondPostedAt)
            .filter { it.sbnKey != "slot-2" }
        assertEquals(
            "A duplicate whose original is still NEW was not detected",
            listOf(first), candidates.map { it.id },
        )
    }

    /**
     * The unwindowed form of rule 1 still finds an hour-old match. That is
     * what the catch-up path uses, where `getActiveNotifications()` has
     * already proved the notification is live; `DuplicateLayerOneTest` covers
     * the windowed `POSTED` path and the arrival branch.
     */
    @Test fun sameSlotAndContentIsFoundRegardlessOfElapsedTime() {
        val dao = db.rawCaptureDao()
        val anHour = 60 * 60 * 1000L
        val earlier = dao.insert(sampleCapture(hash = "refresh", postedAt = 1_000_000, sbnKey = "slot-x"))
        val now = 1_000_000L + anHour
        val self = dao.insert(
            sampleCapture(hash = "refresh", postedAt = now, sbnKey = "slot-x", arrival = Arrival.CATCHUP),
        )

        assertEquals(
            "Same sbn_key and content_hash must match with no time limit",
            earlier,
            dao.findEarlierInSlot("slot-x", "refresh", self)?.id,
        )
        // ...whereas the 60-second rule has long since expired for it. Rule 2
        // matches on content alone, so the capture being processed is in its
        // result set and the caller filters its own row out (see the DAO doc);
        // `earlier` is the row that must not be there.
        assertEquals(
            listOf(self),
            dao.contentHashWindow("refresh", now).map { it.id },
        )
    }

    @Test fun sameSlotWithDifferentContentIsNotAnUpdate() {
        val dao = db.rawCaptureDao()
        dao.insert(sampleCapture(hash = "one", sbnKey = "slot-y"))
        val self = dao.insert(sampleCapture(hash = "two", sbnKey = "slot-y"))
        assertNull(dao.findEarlierInSlot("slot-y", "two", self))
    }

    // ---- duplicates, layer 2: the same purchase seen twice --------------
    // Spec 7.2 layer 2. Identical amount, 10 minutes, DIFFERENT package.
    // Produces a real transaction flagged DUPLICATE_SUSPECT for review;
    // duplicates are never dropped automatically.

    @Test fun duplicateSuspectsExcludeTheSamePackage() {
        val txnDao = db.txnDao()
        txnDao.insert(sampleTxn(amountSen = 12_000, occurredAt = 5_000, pkg = "com.a"))
        assertEquals(1, txnDao.findDuplicateSuspects(12_000, 0, 10_000, Direction.EXPENSE, "com.b").size)
        assertEquals(0, txnDao.findDuplicateSuspects(12_000, 0, 10_000, Direction.EXPENSE, "com.a").size)
    }

    /**
     * The ten-minute boundary in both directions. `BETWEEN` is inclusive, so
     * a transaction exactly ten minutes either side of the new one is a
     * suspect and one millisecond further out is not.
     */
    @Test fun layerTwoWindowIncludesTenMinutesAndExcludesTenMinutesAndOne() {
        val txnDao = db.txnDao()
        val at = 5_000_000L
        val w = DuplicateWindows.LAYER_TWO_MILLIS
        txnDao.insert(sampleTxn(amountSen = 12_000, occurredAt = at - w - 1, pkg = "com.a"))
        txnDao.insert(sampleTxn(amountSen = 12_000, occurredAt = at - w, pkg = "com.a"))
        txnDao.insert(sampleTxn(amountSen = 12_000, occurredAt = at + w, pkg = "com.a"))
        txnDao.insert(sampleTxn(amountSen = 12_000, occurredAt = at + w + 1, pkg = "com.a"))

        val hits = txnDao.findDuplicateSuspects(12_000, at - w, at + w, Direction.EXPENSE, "com.b")
        assertEquals(listOf(at - w, at + w), hits.map { it.occurredAt }.sorted())
    }

    @Test fun layerTwoNeedsTheAmountToBeIdentical() {
        val txnDao = db.txnDao()
        txnDao.insert(sampleTxn(amountSen = 12_001, occurredAt = 5_000, pkg = "com.a"))
        assertEquals(0, txnDao.findDuplicateSuspects(12_000, 0, 10_000, Direction.EXPENSE, "com.b").size)
    }

    @Test fun layerTwoIgnoresRejectedTransactions() {
        val txnDao = db.txnDao()
        txnDao.insert(
            sampleTxn(amountSen = 12_000, occurredAt = 5_000, pkg = "com.a", state = TxnState.REJECTED),
        )
        assertEquals(0, txnDao.findDuplicateSuspects(12_000, 0, 10_000, Direction.EXPENSE, "com.b").size)
    }

    @Test fun layerTwoKeepsPendingTransactionsAsCandidates() {
        val txnDao = db.txnDao()
        txnDao.insert(
            sampleTxn(amountSen = 12_000, occurredAt = 5_000, pkg = "com.a", state = TxnState.PENDING),
        )
        assertEquals(1, txnDao.findDuplicateSuspects(12_000, 0, 10_000, Direction.EXPENSE, "com.b").size)
    }

    /**
     * A refund is not a duplicate of the payment it reverses.
     *
     * Every clause spec 7.2 writes down is satisfied here -- same amount (a
     * reversal is for the amount charged), inside ten minutes, different
     * package, same merchant -- so without the `direction` clause this returns
     * the expense and the inbox offers Merge, which would erase the reversal
     * and leave the user's ledger claiming money they got back.
     */
    @Test fun layerTwoDoesNotPairARefundWithTheExpenseItReverses() {
        val txnDao = db.txnDao()
        txnDao.insert(sampleTxn(amountSen = 12_000, occurredAt = 5_000, pkg = "com.a"))
        assertEquals(
            0,
            txnDao.findDuplicateSuspects(12_000, 0, 10_000, Direction.REFUND, "com.b").size,
        )
    }

    /** The same refund seen twice still is one, which is what layer 2 is for. */
    @Test fun layerTwoStillPairsTwoCopiesOfTheSameRefund() {
        val txnDao = db.txnDao()
        txnDao.insert(
            sampleTxn(amountSen = 12_000, occurredAt = 5_000, pkg = "com.a", direction = Direction.REFUND),
        )
        assertEquals(
            1,
            txnDao.findDuplicateSuspects(12_000, 0, 10_000, Direction.REFUND, "com.b").size,
        )
    }

    /**
     * The two layers must not be conflated: they have different windows and
     * different consequences. Five minutes is outside layer 1 (so no
     * `DUPLICATE_OF`, and the capture parses normally) and inside layer 2 (so
     * the resulting transaction is a `DUPLICATE_SUSPECT` for the inbox). A
     * layer-2 case handled as layer 1 would drop a real transaction, which
     * spec 7.2 forbids.
     */
    @Test fun theTwoLayersHaveDifferentWindows() {
        val fiveMinutes = 5 * 60 * 1000L
        val at = 5_000_000L

        db.rawCaptureDao().insert(sampleCapture(hash = "dup", postedAt = at - fiveMinutes, sbnKey = "s1"))
        assertEquals(
            "Five minutes must be outside the 60-second layer-1 window",
            0, db.rawCaptureDao().contentHashWindow("dup", at).size,
        )

        db.txnDao().insert(sampleTxn(amountSen = 12_000, occurredAt = at - fiveMinutes, pkg = "com.a"))
        assertEquals(
            "Five minutes must be inside the 10-minute layer-2 window",
            1,
            db.txnDao()
                .findDuplicateSuspects(12_000, at - DuplicateWindows.LAYER_TWO_MILLIS, at + DuplicateWindows.LAYER_TWO_MILLIS, Direction.EXPENSE, "com.b")
                .size,
        )
    }

    // ---- idempotence ---------------------------------------------------

    @Test fun oneCaptureCannotProduceTwoTransactions() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "once"))
        db.txnDao().insert(sampleTxn(rawCaptureId = capture))
        val second = runCatching { db.txnDao().insert(sampleTxn(rawCaptureId = capture)) }
        assertTrue("The unique index on raw_capture_id did not hold", second.isFailure)
        assertEquals("A second transaction was written for one capture", 1, db.txnDao().countAll())
    }

    @Test fun manualTransactionsAreNotConstrainedByTheUniqueIndex() {
        // SQLite treats NULLs as distinct in a unique index, which is what
        // lets manual entries (raw_capture_id null, spec 4) coexist.
        db.txnDao().insert(sampleTxn(rawCaptureId = null, pkg = null))
        db.txnDao().insert(sampleTxn(rawCaptureId = null, pkg = null))
        assertEquals(2, db.txnDao().countAll())
    }

    // ---- allow-list ----------------------------------------------------

    @Test fun onlyEnabledSourcesAreReturned() {
        val dao = db.captureSourceDao()
        dao.insertIfNew(enabledSource("com.yes"))
        dao.insertIfNew(disabledSource("com.no"))
        assertEquals(listOf("com.yes"), dao.enabled().map { it.pkg })
        assertEquals(2, dao.all().size)
        assertEquals("com.no", dao.byPackage("com.no")?.pkg)
    }

    /**
     * Discovery re-seeing a package it already knows must not create a second
     * row and must not touch the one that is there. `AllowListConsentTest`
     * covers what the old `@Insert(REPLACE)` upsert did to `enabled`.
     */
    @Test fun rediscoveringAPackageLeavesTheExistingRowAlone() {
        val dao = db.captureSourceDao()
        dao.insertIfNew(disabledSource("com.dup"))
        dao.setEnabled("com.dup", true)
        assertEquals(-1L, dao.insertIfNew(disabledSource("com.dup")))
        assertEquals(1, dao.countAll())
        assertEquals(true, dao.byPackage("com.dup")?.enabled)
    }

    // ---- local dates ---------------------------------------------------
    // Zone-explicit on purpose. Asserting day arithmetic against
    // ZoneId.systemDefault() makes the result depend on the device the test
    // runs on: the plan's original version added eight hours to an instant
    // that is 22:13 in Asia/Kuala_Lumpur, so it crossed local midnight and
    // failed on a Malaysia-set device -- the app's entire target market.

    @Test fun localDateIsStableForTheSameDay() {
        val startOfDay = 1_789_920_000_000L // 2026-09-21 08:00 in KL
        val laterSameDay = startOfDay + 8 * 60 * 60 * 1000
        assertEquals(LocalDate(20260921), LocalDates.of(startOfDay, KL))
        assertEquals(LocalDates.of(startOfDay, KL), LocalDates.of(laterSameDay, KL))
    }

    @Test fun localDateRollsOverAtLocalMidnight() {
        val lastMilliOfTheDay = 1_789_919_999_999L // 2026-09-21 07:59:59.999 in KL
        assertEquals(LocalDate(20260920), LocalDates.of(lastMilliOfTheDay, KL))
        assertEquals(LocalDate(20260921), LocalDates.of(lastMilliOfTheDay + 1, KL))
    }

    @Test fun localDateUsesTheZoneItIsGivenAndNotUtc() {
        // 2026-09-20 17:00 UTC is already 2026-09-21 in Kuala Lumpur. If the
        // day were derived at query time from the process zone instead of
        // stored, this row would change months when the user travels.
        val evening = 1_789_930_800_000L
        assertEquals(LocalDate(20260921), LocalDates.of(evening, KL))
        assertEquals(LocalDate(20260920), LocalDates.of(evening, ZoneId.of("UTC")))
    }

    @Test fun aStoredTransactionCarriesItsOwnLocalDate() {
        val id = db.txnDao().insert(sampleTxn(occurredAt = 1_789_920_000_000L))
        val row = db.txnDao().recent(10).single { it.id == id }
        assertEquals(LocalDates.of(1_789_920_000_000L), row.localDate)
    }

    /**
     * Every bound is a literal `yyyymmdd` decimal, never a round trip through
     * [LocalDates.calendarDay].
     *
     * A packing and an unpacking that are wrong the same way agree with each
     * other, so a test built from both passes while every stored day is
     * misfiled -- which is the shape of the bug a caller reaches for when it
     * writes the arithmetic out again instead of calling this.
     */
    @Test fun aMonthRangeIsThatMonthsOwnFirstAndLastDay() {
        val sept = LocalDates.monthRange(YearMonth.of(2026, 9))
        assertEquals(
            "A month does not start on its own first day, so every aggregate " +
                "bounded by this range asks about days that are not the month",
            LocalDate(20260901), sept.start,
        )
        assertEquals(
            "A 30-day month does not end on its 30th: a range one day long " +
                "either way moves a day's money into the neighbouring month",
            LocalDate(20260930), sept.endInclusive,
        )

        val jan = LocalDates.monthRange(YearMonth.of(2026, 1))
        assertEquals(
            "A single-digit month is not zero-padded, so the packing stops " +
                "ordering days the way the calendar does and BETWEEN stops meaning anything",
            LocalDate(20260101), jan.start,
        )
        assertEquals("...and the same at the end of January", LocalDate(20260131), jan.endInclusive)

        assertEquals(
            "February 2024 has 29 days and the range ends before the last of " +
                "them, so a leap day's spending belongs to no month at all",
            LocalDate(20240229), LocalDates.monthRange(YearMonth.of(2024, 2)).endInclusive,
        )
        assertEquals(
            "February 2025 has 28 days and the range reaches past them",
            LocalDate(20250228), LocalDates.monthRange(YearMonth.of(2025, 2)).endInclusive,
        )
    }

    /** The range is closed at both ends, which is what `BETWEEN` is. */
    @Test fun aMonthRangeHoldsItsOwnEndsAndNeitherNeighbour() {
        val feb = LocalDates.monthRange(YearMonth.of(2024, 2))
        assertTrue("The first of the month is outside its own range", feb.contains(LocalDate(20240201)))
        assertTrue("The last of the month is outside its own range", feb.contains(LocalDate(20240229)))
        assertFalse(
            "The last day of January is inside February's range",
            feb.contains(LocalDate(20240131)),
        )
        assertFalse(
            "The first day of March is inside February's range",
            feb.contains(LocalDate(20240301)),
        )
    }

    /**
     * Pinned against `java.time` rather than against a value this file packed,
     * for [aMonthRangeIsThatMonthsOwnFirstAndLastDay]'s reason.
     */
    @Test fun aStoredDayDecodesToTheCalendarDayItNames() {
        assertEquals(
            "A stored day decodes to some other date, so a day heading names a " +
                "day the rows under it did not happen on",
            java.time.LocalDate.of(2026, 9, 30), LocalDates.calendarDay(LocalDate(20260930)),
        )
        assertEquals(
            "A single-digit month and day decode wrongly, which is the half of " +
                "the year a test on 20260930 alone cannot see",
            java.time.LocalDate.of(2026, 1, 5), LocalDates.calendarDay(LocalDate(20260105)),
        )
    }

    /**
     * The range against the column, rather than against another copy of the
     * arithmetic: the rows are written with literal `local_date` decimals and
     * SQLite decides what the bounds select.
     */
    @Test fun aMonthRangeBoundsAnAggregateOnTheDaysTheColumnHolds() {
        listOf(20240131, 20240201, 20240229, 20240301).forEach {
            db.txnDao().insert(sampleTxn(amountSen = 1_000L, localDate = LocalDate(it)))
        }

        val feb = LocalDates.monthRange(YearMonth.of(2024, 2))
        val counted = db.txnDao().dayTotals(feb.start, feb.endInclusive)
            .map { it.localDate.yyyymmdd }
            .sorted()

        assertEquals(
            "The bounds monthRange built did not select exactly the rows whose " +
                "stored local_date names that month -- so the month total is " +
                "some other month's money",
            listOf(20240201, 20240229), counted,
        )
    }

    private companion object {
        val KL: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    }
}
