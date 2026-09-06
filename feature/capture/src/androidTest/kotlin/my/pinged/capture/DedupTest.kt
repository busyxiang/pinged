package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.2's two duplicate layers, against the real database.
 *
 * The point of every test here is which of three things happened: no
 * transaction, a transaction in the ledger, or a transaction in the review
 * inbox. Conflating the first with the third is how money disappears; the
 * third with the second is how it gets counted twice.
 */
@RunWith(AndroidJUnit4::class)
class DedupTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)

    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
    }

    private fun runPass() = ParseFixtures.pass(context).run()

    // ---- layer 1, rule 1 -------------------------------------------------

    /**
     * The refresh case: one notification redrawn, in the same slot, with the
     * same text. `UPDATE_OF`, and no second transaction.
     */
    @Test
    fun theSameSlotAndContentInsideTenMinutesIsAnUpdateNotASecondTransaction() {
        val text = "Payment of RM4.44 to REFRESH SHOP successful"
        val first = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-slot", postedAt = base - 3_600_000L,
        )
        val second = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-slot", postedAt = base - 3_600_000L + 60_000L,
        )

        runPass()

        assertEquals(ParseStatus.MATCHED, captures.byId(first).parseStatus)
        assertNotNull(whyNoTransaction(first), ParseFixtures.txnForCapture(context, first))

        assertEquals(ParseStatus.UPDATE_OF, captures.byId(second).parseStatus)
        assertEquals(first, captures.byId(second).duplicateOfId)
        assertNull(
            "A refresh of one notification is not a second payment",
            ParseFixtures.txnForCapture(context, second),
        )
    }

    /**
     * Beyond ten minutes, the identical pair is **not** dropped: it becomes a
     * transaction flagged `DUPLICATE_SUSPECT`, in the review inbox, offering Merge
     * or Keep both.
     *
     * `content_hash` carries no timestamp and normalization preserves digits, so
     * two genuinely separate identical payments are identical by construction --
     * RM4.55 on Monday and a byte-identical RM4.55 on Wednesday through one reused
     * wallet slot. Treating that as a refresh deletes the second payment with no
     * error, no review item and no way to notice short of a bank statement.
     */
    @Test
    fun theSameSlotAndContentBeyondTenMinutesIsAReviewItemNotASilentDrop() {
        val text = "Payment of RM4.55 to WINDOW SHOP successful"
        val first = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-window", postedAt = base - 7_200_000L,
        )
        val second = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-window", postedAt = base - 7_200_000L + 20 * 60_000L,
        )

        runPass()

        val firstTxn = ParseFixtures.txnForCapture(context, first)
        val secondTxn = ParseFixtures.txnForCapture(context, second)
        assertNotNull(firstTxn)
        assertNotNull(
            "Beyond the window the second payment must still reach the ledger",
            secondTxn,
        )
        requireNotNull(firstTxn)
        requireNotNull(secondTxn)

        assertEquals(ParseStatus.MATCHED, captures.byId(second).parseStatus)
        assertEquals(first, captures.byId(second).duplicateOfId)
        assertEquals(TxnState.COMMITTED, firstTxn.state)
        assertEquals(TxnState.PENDING, secondTxn.state)
        assertEquals(PendingReason.DUPLICATE_SUSPECT, secondTxn.pendingReason)
        assertEquals(
            "Both halves of the money must be recorded, not one",
            455L + 455L,
            firstTxn.amountSen + secondTxn.amountSen,
        )
    }

    /**
     * A live notification re-delivered on rebind is a refresh, and it does not
     * need an exemption to be one: `getActiveNotifications()` hands back the
     * original `postTime`, so the elapsed time against its own earlier row is
     * zero and the window says `UPDATE_OF` on its own.
     */
    @Test
    fun aCatchUpRedeliveryOfTheSamePostingIsAnUpdate() {
        val text = "Payment of RM4.66 to CATCHUP SHOP successful"
        val postedAt = base - 10_800_000L
        val first = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-catchup", postedAt = postedAt,
        )
        val second = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-catchup", postedAt = postedAt,
            arrival = Arrival.CATCHUP,
        )

        runPass()

        assertNotNull(whyNoTransaction(first), ParseFixtures.txnForCapture(context, first))
        assertEquals(ParseStatus.UPDATE_OF, captures.byId(second).parseStatus)
        assertNull(ParseFixtures.txnForCapture(context, second))
    }

    /**
     * **A second posting reusing the slot is money, whichever way it arrives.**
     *
     * `CATCHUP` used to skip rule 1's window entirely, so a byte-identical payment
     * an hour later -- or two days later, since `content_hash` excludes time and
     * captures are never deleted -- was matched against the older row and recorded
     * `UPDATE_OF`, with no transaction and no review item. The same pair delivered
     * as `POSTED` became a duplicate suspect, so the arrival path alone decided
     * between the review inbox and silently dropping the money.
     */
    @Test
    fun aCatchUpOfASecondPostingOutsideTheWindowIsASuspectNotAnUpdate() {
        val text = "Payment of RM4.66 to CATCHUP SHOP successful"
        val first = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-catchup", postedAt = base - 10_800_000L,
        )
        val second = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-catchup", postedAt = base - 10_800_000L + 3_600_000L,
            arrival = Arrival.CATCHUP,
        )

        runPass()

        assertNotNull(whyNoTransaction(first), ParseFixtures.txnForCapture(context, first))
        assertEquals(
            "A genuine second payment swept in by a rebind was recorded as a refresh " +
                "of the first, so it produced no transaction at all",
            ParseStatus.MATCHED,
            captures.byId(second).parseStatus,
        )
        val secondTxn = ParseFixtures.txnForCapture(context, second)
        assertNotNull("spec 7.2: duplicates are never dropped automatically", secondTxn)
        assertEquals(
            "It has to reach the review inbox rather than the ledger unremarked",
            PendingReason.DUPLICATE_SUSPECT,
            secondTxn!!.pendingReason,
        )
    }

    // ---- layer 1, rule 2 -------------------------------------------------

    /** Same content, different slot, inside a minute: `DUPLICATE_OF`, no transaction. */
    @Test
    fun theSameContentFromADifferentSlotInsideAMinuteIsADuplicateWithNoTransaction() {
        val text = "Payment of RM4.77 to TWO SLOTS successful"
        val first = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-slot-a", postedAt = base - 1_800_000L,
        )
        val second = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-slot-b", postedAt = base - 1_800_000L + 5_000L,
        )

        runPass()

        assertNotNull(whyNoTransaction(first), ParseFixtures.txnForCapture(context, first))
        assertEquals(ParseStatus.DUPLICATE_OF, captures.byId(second).parseStatus)
        assertEquals(first, captures.byId(second).duplicateOfId)
        assertNull(ParseFixtures.txnForCapture(context, second))
    }

    /**
     * Two captures with the same content, different slots, and **the same
     * `posted_at`** must still produce one transaction.
     *
     * This is the wrong-money edge of rule 2, and it eats the money rather than
     * duplicating it. "Is there an earlier row with this hash and a different slot"
     * is asked by both members of the pair, and on a `posted_at` tie each sees the
     * other as earlier, each is marked `DUPLICATE_OF` the other, and **neither**
     * produces a transaction. `raw_capture.id` breaks the tie.
     */
    @Test
    fun twoIdenticalPostsInTheSameMillisecondThroughDifferentSlotsStillProduceOneTransaction() {
        val text = "Payment of RM4.88 to SAME MILLISECOND successful"
        val postedAt = base - 2_400_000L
        val first = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-tie-a", postedAt = postedAt,
        )
        val second = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-tie-b", postedAt = postedAt,
        )

        runPass()

        val txns = listOfNotNull(
            ParseFixtures.txnForCapture(context, first),
            ParseFixtures.txnForCapture(context, second),
        )
        assertEquals(
            "Exactly one of an identical same-millisecond pair is the duplicate; " +
                "zero transactions means the payment vanished.\n  " +
                whyNoTransaction(first) + "\n  " + whyNoTransaction(second),
            1,
            txns.size,
        )
        assertEquals(488L, txns.single().amountSen)
        assertEquals(ParseStatus.MATCHED, captures.byId(first).parseStatus)
        assertEquals(ParseStatus.DUPLICATE_OF, captures.byId(second).parseStatus)
        assertEquals(first, captures.byId(second).duplicateOfId)
    }

    /**
     * A capture whose slot-mate was posted *later* still produces a
     * transaction.
     *
     * `slotRefreshOutcome` read a negative elapsed as inside the ten-minute
     * window -- `-2592000000 <= 600000` is true -- so a payment matched against
     * a byte-identical notification posted a month afterwards was recorded
     * `UPDATE_OF`: no transaction, no review item, the money gone. `id <
     * :selfId` was the stated defence, and it is the one thing that cannot
     * defend it, because a negative elapsed only arises when insertion order
     * and `posted_at` order disagree.
     *
     * The rows are inserted in the order that produces it -- the later posting
     * first -- which an import from an edited file is the shortest route to,
     * since it preserves both columns.
     */
    @Test
    fun aSlotMatePostedLaterDoesNotSwallowTheCapture() {
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val postedAt = base - 16_200_000L
        val text = "Payment of $rm to KEDAI RUNCIT successful"

        // Inserted first, posted a month later: the pair the ordering defence
        // assumed could not exist.
        ParseFixtures.insertCapture(
            context, text = text, title = marker, pkg = ParseFixtures.TNG,
            sbnKey = "$marker-oo", postedAt = postedAt + 30L * 24 * 60 * 60 * 1000,
        )
        val earlier = ParseFixtures.insertCapture(
            context, text = text, title = marker, pkg = ParseFixtures.TNG,
            sbnKey = "$marker-oo", postedAt = postedAt,
        )

        runPass()

        val txn = ParseFixtures.txnForCapture(context, earlier)
        assertNotNull(
            "The capture was recorded as a refresh of a notification posted a " +
                "month after it, so the payment is not in the ledger at all",
            txn,
        )
        assertEquals(sen, txn!!.amountSen)
        assertEquals(
            "Out of order means unknown, and unknown goes to the inbox",
            PendingReason.DUPLICATE_SUSPECT,
            txn.pendingReason,
        )
    }

    /**
     * What the capture actually became, for an assertion that would otherwise
     * fail with a bare `AssertionError` and no way to tell why.
     *
     * This exists because of a real failure nobody could diagnose.
     * `aCatchUpRedeliveryOfTheSamePostingIsAnUpdate` failed once on CI --
     * Android 16, where this suite had never run before -- and passed on the
     * re-run. It has not reproduced since: not on twelve consecutive local
     * runs, not on a cleared database, and not with the execution order
     * forced. All the report carried was `assertNotNull` at a line number.
     *
     * Every other assertion in this file names what it means. These four did
     * not, and they are the ones that failed, so the next occurrence produces a
     * diagnosis instead of another dead end: whether the capture was matched,
     * rejected, deduplicated and against what, or never parsed at all.
     *
     * The flake is **not** understood and is not claimed to be fixed.
     */
    private fun whyNoTransaction(captureId: Long): String {
        val row = captures.byId(captureId)
        return "No transaction for capture $captureId: parse_status=${row.parseStatus}, " +
            "duplicate_of_id=${row.duplicateOfId}, matched_rule_id=${row.matchedRuleId}, " +
            "rejected_by_rule_id=${row.rejectedByRuleId}, sbn_key=${row.sbnKey}, " +
            "posted_at=${row.postedAt}, content_hash=${row.contentHash}"
    }

    // ---- layer 2 ---------------------------------------------------------

    /**
     * One purchase, two apps. Layer 2 keeps **both** transactions and sends the
     * newer one to the review inbox with Merge / Keep both -- it does not drop
     * either, because two genuine RM5.00 parking payments in one afternoon are
     * entirely normal.
     */
    @Test
    fun layerTwoFlagsTheNewerTransactionAcrossPackagesAndKeepsBoth() {
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val postedAt = base - 5_400_000L

        val wallet = ParseFixtures.insertCapture(
            context,
            text = "Payment of $rm to STARBUCKS KLCC successful",
            title = marker, pkg = ParseFixtures.TNG,
            sbnKey = "$marker-l2-wallet", postedAt = postedAt,
        )
        val bank = ParseFixtures.insertCapture(
            context,
            text = "You have paid $rm to STARBUCKS KLCC",
            title = marker, pkg = ParseFixtures.MAE,
            sbnKey = "$marker-l2-bank", postedAt = postedAt + 120_000L,
        )

        runPass()

        val walletTxn = requireNotNull(ParseFixtures.txnForCapture(context, wallet))
        val bankTxn = requireNotNull(ParseFixtures.txnForCapture(context, bank))

        assertEquals(sen, walletTxn.amountSen)
        assertEquals(sen, bankTxn.amountSen)
        assertEquals(TxnState.COMMITTED, walletTxn.state)
        assertEquals(
            "The newer transaction of a layer-2 pair goes to the review inbox",
            TxnState.PENDING,
            bankTxn.state,
        )
        assertEquals(PendingReason.DUPLICATE_SUSPECT, bankTxn.pendingReason)
        // Layer 2 is a real second transaction, so the capture is MATCHED and
        // not DUPLICATE_OF. That difference is the whole of spec 7.2's
        // "duplicates are never dropped automatically".
        assertEquals(ParseStatus.MATCHED, captures.byId(bank).parseStatus)
        assertEquals(wallet, captures.byId(bank).duplicateOfId)
    }

    /**
     * A refund is not the same purchase seen twice, and merging it into the
     * expense it reverses would erase the reversal.
     *
     * The pair here satisfies every clause spec 7.2 actually writes down: the
     * reversal is for the amount charged, the wallet and the bank are different
     * packages, two minutes is inside ten, and the merchant is the same shop.
     * Only `direction` separates them, so with that clause removed the refund
     * lands in the inbox offering Merge -- and taking that offer leaves the
     * user's total claiming money they were given back.
     */
    @Test
    fun layerTwoDoesNotFlagARefundAgainstTheExpenseItReverses() {
        val (_, rm) = ParseFixtures.uniqueAmount()
        // Clear of both layer-2 cases above, so a repeated unique amount
        // cannot make this pass or fail for the wrong reason.
        val postedAt = base - 12_600_000L

        val wallet = ParseFixtures.insertCapture(
            context,
            text = "Payment of $rm to STARBUCKS KLCC successful",
            title = marker, pkg = ParseFixtures.TNG,
            sbnKey = "$marker-l2r-wallet", postedAt = postedAt,
        )
        val bank = ParseFixtures.insertCapture(
            context,
            text = "Bayaran balik $rm daripada STARBUCKS KLCC telah diterima",
            title = marker, pkg = ParseFixtures.MAE,
            sbnKey = "$marker-l2r-bank", postedAt = postedAt + 120_000L,
        )

        runPass()

        val walletTxn = requireNotNull(ParseFixtures.txnForCapture(context, wallet))
        val bankTxn = requireNotNull(ParseFixtures.txnForCapture(context, bank))
        assertEquals(Direction.EXPENSE, walletTxn.direction)
        assertEquals(
            "The refund rule has to be what matched, or this proves nothing",
            Direction.REFUND,
            bankTxn.direction,
        )
        assertEquals(TxnState.COMMITTED, walletTxn.state)
        assertEquals(
            "A refund must not be offered for merge with the expense it reverses",
            TxnState.COMMITTED,
            bankTxn.state,
        )
        assertNull(bankTxn.pendingReason)
        assertNull(captures.byId(bank).duplicateOfId)
    }

    /**
     * Layer 2's merchant clause is a clause and not decoration: the same amount
     * from two packages within ten minutes at two different merchants is two
     * purchases, and flagging it would train the user to dismiss the review
     * inbox.
     */
    @Test
    fun layerTwoDoesNotFlagTwoDifferentMerchantsAtTheSameAmount() {
        val (_, rm) = ParseFixtures.uniqueAmount()
        // An hour clear of the positive layer-2 test above, so that even if
        // both draw the same unique amount neither can be the other's suspect.
        val postedAt = base - 9_000_000L

        val wallet = ParseFixtures.insertCapture(
            context,
            text = "Payment of $rm to 99 SPEEDMART successful",
            title = marker, pkg = ParseFixtures.TNG,
            sbnKey = "$marker-l2n-wallet", postedAt = postedAt,
        )
        val bank = ParseFixtures.insertCapture(
            context,
            text = "You have paid $rm to GUARDIAN PHARMACY",
            title = marker, pkg = ParseFixtures.MAE,
            sbnKey = "$marker-l2n-bank", postedAt = postedAt + 120_000L,
        )

        runPass()

        assertEquals(
            TxnState.COMMITTED,
            requireNotNull(ParseFixtures.txnForCapture(context, wallet)).state,
        )
        assertEquals(
            TxnState.COMMITTED,
            requireNotNull(ParseFixtures.txnForCapture(context, bank)).state,
        )
    }
}
