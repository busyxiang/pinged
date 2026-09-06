package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.Arrival
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.2 duplicate layer 1, as rewritten: rule 1's ten-minute window on the
 * `POSTED` path, its absence on the `CATCHUP` path, and -- first of all -- the
 * fact that rule 1 can now express "an earlier row" at all.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateLayerOneTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase()
    }

    @After fun tearDown() = db.close()

    private val dao get() = db.rawCaptureDao()

    // ---- rule 1 could not express "an earlier row" ---------------------

    /**
     * **The one test the 54 green tests did not contain, and the reason they were
     * green.**
     *
     * Stage one inserts the capture, then stage two claims it out of the queue, so
     * by the time rule 1 runs the row it is asking about is already on disk -- and
     * the old query (`sbn_key = ? AND content_hash = ? LIMIT 1`, no
     * self-exclusion, no `ORDER BY`) matched it. Every capture was its own
     * `UPDATE_OF` and the ledger stayed permanently empty while the app reported
     * that it was working.
     *
     * Every pre-existing test queried for a row that was *not* the one being
     * processed -- an arrangement production never produces. This one inserts the
     * row first, the way the listener does.
     */
    @Test fun ruleOneDoesNotReturnTheRowBeingProcessed() {
        val self = dao.insert(
            sampleCapture(hash = "solo", postedAt = 1_000_000, sbnKey = "slot-a"),
        )
        assertEquals("Fixture is wrong: the row under test is not on disk", 1, dao.countAll())

        assertNull(
            "Rule 1 returned the capture being processed as its own earlier " +
                "duplicate. Every capture is then an UPDATE_OF and no " +
                "transaction is ever created.",
            dao.findEarlierInSlot("slot-a", "solo", self),
        )
        assertNull(
            "The windowed form has the same hole if it lacks id < :selfId",
            dao.findEarlierInSlotSince(
                "slot-a",
                "solo",
                self,
                1_000_000 - DuplicateWindows.SLOT_REFRESH_MILLIS,
                1_000_000,
            ),
        )
    }

    /** ...while a genuinely earlier row in the same slot is still found. */
    @Test fun ruleOneStillFindsAGenuinelyEarlierRow() {
        val earlier = dao.insert(sampleCapture(hash = "r", postedAt = 1_000_000, sbnKey = "slot-b"))
        val self = dao.insert(sampleCapture(hash = "r", postedAt = 1_060_000, sbnKey = "slot-b"))

        assertEquals(earlier, dao.findEarlierInSlot("slot-b", "r", self)?.id)
    }

    /**
     * `LIMIT 1` with no `ORDER BY` is unordered by definition, so which row a
     * chain of refreshes resolved to was a property of the query plan. `ORDER
     * BY id ASC` makes it the first one, which is the original purchase --
     * resolving to the latest instead would make `duplicate_of_id` a linked
     * list through the chain rather than a pointer at the thing that happened.
     */
    @Test fun ruleOneResolvesToTheFirstRowInTheChainNotTheLatest() {
        val first = dao.insert(sampleCapture(hash = "c", postedAt = 1_000_000, sbnKey = "slot-c"))
        dao.insert(sampleCapture(hash = "c", postedAt = 1_000_100, sbnKey = "slot-c"))
        dao.insert(sampleCapture(hash = "c", postedAt = 1_000_200, sbnKey = "slot-c"))
        val self = dao.insert(sampleCapture(hash = "c", postedAt = 1_000_300, sbnKey = "slot-c"))

        assertEquals(first, dao.findEarlierInSlot("slot-c", "c", self)?.id)
    }

    // ---- rule 1's window, spec 7.2 as rewritten ------------------------

    private fun earlierInSlotWithin(key: String, hash: String, self: Long, postedAt: Long) =
        dao.findEarlierInSlotSince(
            key = key,
            hash = hash,
            selfId = self,
            sinceMillis = postedAt - DuplicateWindows.SLOT_REFRESH_MILLIS,
            untilMillis = postedAt,
        )

    /**
     * Inside ten minutes a slot-and-content match is a refresh. This is the
     * only branch that drops a capture silently, which is why spec 7.2 keeps
     * the window as short as tolerable rather than as long as plausible.
     */
    @Test fun aRefreshInsideTenMinutesIsFound() {
        val at = 5_000_000L
        val earlier = dao.insert(sampleCapture(hash = "w", postedAt = at, sbnKey = "slot-w"))
        val self = dao.insert(
            sampleCapture(hash = "w", postedAt = at + 60_000, sbnKey = "slot-w"),
        )
        assertEquals(earlier, earlierInSlotWithin("slot-w", "w", self, at + 60_000)?.id)
    }

    /**
     * The boundary, inclusive, matching layer 2's `BETWEEN`.
     *
     * Two separate slots, because the two halves cannot share one: a chain of
     * three rows in one slot would let the middle row satisfy the query for
     * the third and the assertion would be measuring the wrong gap.
     */
    @Test fun theWindowIncludesTenMinutesAndExcludesTenMinutesAndOne() {
        val at = 5_000_000L
        val w = DuplicateWindows.SLOT_REFRESH_MILLIS

        val insideOriginal = dao.insert(sampleCapture(hash = "b", postedAt = at, sbnKey = "slot-in"))
        val onTheEdge = dao.insert(sampleCapture(hash = "b", postedAt = at + w, sbnKey = "slot-in"))
        assertEquals(
            "Exactly ten minutes must still be a refresh",
            insideOriginal,
            earlierInSlotWithin("slot-in", "b", onTheEdge, at + w)?.id,
        )

        dao.insert(sampleCapture(hash = "b", postedAt = at, sbnKey = "slot-out"))
        val justOutside = dao.insert(
            sampleCapture(hash = "b", postedAt = at + w + 1, sbnKey = "slot-out"),
        )
        assertNull(
            "One millisecond past ten minutes must fall out of the window",
            earlierInSlotWithin("slot-out", "b", justOutside, at + w + 1),
        )
    }

    /**
     * **The wrong-money bug the window exists to fix.** `content_hash` carries no
     * timestamp (spec 4) and normalization preserves digits, so a wallet posting
     * through one reused slot makes two genuinely separate identical payments
     * byte-identical *and* same-slot. Monday's RM10 and Wednesday's RM10 were one
     * transaction, with no error and no review item.
     *
     * Two days apart the windowed query finds nothing, so the money is not dropped,
     * while the unwindowed query still sees the pair -- which is what lets the
     * caller raise a duplicate suspect rather than treating Wednesday as unrelated.
     */
    @Test fun twoIdenticalPaymentsTwoDaysApartInOneSlotAreNotSilentlyDropped() {
        val monday = 5_000_000L
        val wednesday = monday + 2 * 24 * 60 * 60 * 1000L
        val first = dao.insert(
            sampleCapture(hash = "rm10", postedAt = monday, sbnKey = "tng-reload"),
        )
        val second = dao.insert(
            sampleCapture(hash = "rm10", postedAt = wednesday, sbnKey = "tng-reload"),
        )

        assertNull(
            "Wednesday's RM10 was dropped as a refresh of Monday's",
            earlierInSlotWithin("tng-reload", "rm10", second, wednesday),
        )
        assertEquals(
            "The pair must still be visible, so it can be flagged for review",
            first,
            dao.findEarlierInSlot("tng-reload", "rm10", second)?.id,
        )
        assertEquals(
            SlotRefreshOutcome.DUPLICATE_SUSPECT,
            slotRefreshOutcome(wednesday - monday),
        )
    }

    /**
     * **The catch-up exemption is gone, and this test used to require it.**
     *
     * The argument was that `getActiveNotifications()` returns only posts that are
     * still live. That is true of the *new* capture and says nothing about the
     * earlier row it is matched against -- and `content_hash` excludes time, so a
     * byte-identical payment days later has the same identity by construction.
     *
     * No exemption is needed: a live notification re-delivered on rebind keeps its
     * original `postTime`, so elapsed time against its own earlier row is zero and
     * the window returns `UPDATE_OF` without one.
     */
    @Test fun aCatchUpRedeliveryIsARefreshAndASecondPostingIsNot() {
        val monday = 5_000_000L
        val muchLater = monday + 3 * 24 * 60 * 60 * 1000L
        val first = dao.insert(sampleCapture(hash = "live", postedAt = monday, sbnKey = "slot-live"))
        val self = dao.insert(
            sampleCapture(
                hash = "live",
                postedAt = muchLater,
                sbnKey = "slot-live",
                arrival = Arrival.CATCHUP,
            ),
        )

        assertEquals(
            "The unwindowed query is still the fallback, so an older match is found " +
                "rather than missed -- being found is what makes it a review item",
            first,
            dao.findEarlierInSlot("slot-live", "live", self)?.id,
        )
        assertEquals(
            "The same posting re-delivered has zero elapsed time",
            SlotRefreshOutcome.UPDATE_OF,
            slotRefreshOutcome(0L),
        )
        assertEquals(
            "A second posting three days later is money, not a refresh",
            SlotRefreshOutcome.DUPLICATE_SUSPECT,
            slotRefreshOutcome(muchLater - monday),
        )
    }

    /** `arrival` survives the round trip, since nothing can reconstruct it. */
    @Test fun arrivalIsStoredAndReadBack() {
        val posted = dao.insert(sampleCapture(hash = "p", sbnKey = "sp", arrival = Arrival.POSTED))
        val caught = dao.insert(sampleCapture(hash = "c", sbnKey = "sc", arrival = Arrival.CATCHUP))
        assertEquals(Arrival.POSTED, dao.byId(posted).arrival)
        assertEquals(Arrival.CATCHUP, dao.byId(caught).arrival)
    }

    // ---- the decision function ------------------------------------------

    /**
     * A row posted *after* the capture is not something the capture refreshed.
     *
     * `slotRefreshOutcome` used to read a negative elapsed as inside the window and
     * justify it by rule 1's `id < :selfId` ordering. That argument defeats itself:
     * a negative elapsed can only arise when insertion order and `posted_at` order
     * disagree, which is exactly when `id <` is not evidence of "earlier". The cost
     * was a payment recorded as a refresh of a notification posted a month later.
     */
    @Test fun aPriorRowPostedAfterTheCaptureIsNotARefresh() {
        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        assertEquals(
            SlotRefreshOutcome.DUPLICATE_SUSPECT,
            slotRefreshOutcome(-thirtyDays),
        )
        assertEquals(
            "one millisecond out of order is still out of order",
            SlotRefreshOutcome.DUPLICATE_SUSPECT,
            slotRefreshOutcome(-1),
        )
        // And the boundary the other side of it is unchanged.
        assertEquals(SlotRefreshOutcome.UPDATE_OF, slotRefreshOutcome(0))
    }

    /**
     * The windowed query will not return it either, which is the same bug at
     * the other layer: `posted_at >= :sinceMillis` alone ran open to infinity,
     * so "within ten minutes before me" also meant "and everything after me
     * forever".
     */
    @Test fun theSlotWindowExcludesARowPostedAfterTheCapture() {
        val dao = db.rawCaptureDao()
        val at = 5_000_000L
        val later = dao.insert(sampleCapture(hash = "future", postedAt = at + 30_000, sbnKey = "slot"))
        val self = dao.insert(sampleCapture(hash = "future", postedAt = at, sbnKey = "slot"))
        assertTrue("precondition: the later row was inserted first", later < self)

        assertNull(
            "A row posted after the capture was returned as the match it refreshed",
            dao.findEarlierInSlotSince(
                key = "slot",
                hash = "future",
                selfId = self,
                sinceMillis = at - DuplicateWindows.SLOT_REFRESH_MILLIS,
                untilMillis = at,
            ),
        )
        assertEquals(
            "the unwindowed fallback still finds it, so the pair reaches the inbox",
            later,
            dao.findEarlierInSlot(key = "slot", hash = "future", selfId = self)?.id,
        )
    }

    @Test fun slotRefreshOutcomeBranchesOnlyOnTheWindow() {
        val w = DuplicateWindows.SLOT_REFRESH_MILLIS
        for (arrival in Arrival.entries) {
            assertEquals(arrival.name, SlotRefreshOutcome.UPDATE_OF, slotRefreshOutcome(0))
            assertEquals(arrival.name, SlotRefreshOutcome.UPDATE_OF, slotRefreshOutcome(w))
            assertEquals(
                "$arrival: a posting past the window is money either way",
                SlotRefreshOutcome.DUPLICATE_SUSPECT,
                slotRefreshOutcome(w + 1),
            )
        }
    }

    /** Spec 7.2's ten minutes on rule 1 is deliberately layer 2's number. */
    @Test fun theTenMinuteWindowIsSharedWithLayerTwoRatherThanDuplicated() {
        assertEquals(DuplicateWindows.LAYER_TWO_MILLIS, DuplicateWindows.SLOT_REFRESH_MILLIS)
        assertEquals(600_000L, DuplicateWindows.SLOT_REFRESH_MILLIS)
        assertEquals(60_000L, DuplicateWindows.CONTENT_HASH_MILLIS)
    }

    // ---- rule 2's upper bound -------------------------------------------

    /**
     * **Harmless live, wrong-money under re-parse.** Rule 2's predicate was
     * `posted_at >= :sinceMillis` and nothing more. Live that never shows, because
     * the capture being processed is the newest row -- but spec 5.5's re-parse
     * walks history, and there the same query returns captures posted *later*, so a
     * March capture is marked a duplicate of a separate identical purchase in
     * September and March's transaction disappears.
     */
    @Test fun ruleTwoDoesNotMatchCapturesPostedAfterTheOneBeingProcessed() {
        val march = 1_740_000_000_000L
        val september = march + 180L * 24 * 60 * 60 * 1000
        dao.insert(sampleCapture(hash = "rm25", postedAt = march, sbnKey = "slot-mar"))
        dao.insert(sampleCapture(hash = "rm25", postedAt = september, sbnKey = "slot-sep"))

        val candidates = dao.contentHashWindow("rm25", march).map { it.postedAt }
        assertEquals(
            "A capture six months later came back as a candidate duplicate of " +
                "an older one; under re-parse that deletes the older transaction",
            listOf(march),
            candidates,
        )
    }

    @Test fun ruleTwoStillMatchesInsideItsSixtySeconds() {
        val at = 1_740_000_000_000L
        dao.insert(sampleCapture(hash = "pair", postedAt = at, sbnKey = "slot-1"))
        dao.insert(sampleCapture(hash = "pair", postedAt = at + 2_000, sbnKey = "slot-2"))

        val hits = dao.contentHashWindow("pair", at + 2_000).map { it.sbnKey }.sorted()
        assertEquals(listOf("slot-1", "slot-2"), hits)
    }

    /**
     * The upper bound is inclusive, so the capture's own `posted_at` is inside
     * it -- otherwise a second notification posted in the same millisecond
     * would fall out of the window it is supposed to be caught by.
     */
    @Test fun ruleTwoIncludesARowPostedInTheSameMillisecond() {
        val at = 1_740_000_000_000L
        dao.insert(sampleCapture(hash = "same", postedAt = at, sbnKey = "slot-x"))
        dao.insert(sampleCapture(hash = "same", postedAt = at, sbnKey = "slot-y"))
        assertNotNull(dao.contentHashWindow("same", at).singleOrNull { it.sbnKey == "slot-x" })
        assertEquals(2, dao.contentHashWindow("same", at).size)
    }
}
