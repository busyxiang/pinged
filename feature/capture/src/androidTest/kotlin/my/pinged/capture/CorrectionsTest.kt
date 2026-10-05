package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 5.5's third mode, against the real encrypted database and the pack
 * that ships.
 *
 * Each fixture is a capture the bundled pack reads, committed by stage two,
 * then put back the way an older pack left it: the capture stamped one
 * version back and the transaction rewritten to the older read. The four
 * shapes are the four in issue #20, from a device export at pack 11.
 */
@RunWith(AndroidJUnit4::class)
class CorrectionsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val matcher = Graph.ruleMatcher()
    private val packVersion = matcher.packVersion

    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    /**
     * Both sweeps are table-wide and the suite shares one database, so every
     * stale row an earlier test left -- here or anywhere -- is compared and
     * any difference declined first. Only this test's fixtures then qualify.
     */
    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
        ParseFixtures.pass(context).run()
        runBlocking {
            Corrections.forgetSweeps(context)
            while (sweep().more) Unit
            Corrections.pending(context, captures, matcher)!!.forEach { Corrections.decline(captures, it) }
            Corrections.forgetSweeps(context)
        }
    }

    /** The mark is durable and shared: left reset, the next suite's worker runs this sweep. */
    @After
    fun leaveTheSweepFinished() = runBlocking { Corrections.markSwept(context, packVersion) }

    private fun sweep(limit: Int = Corrections.MAX_COMPARED_PER_RUN): Corrections.Swept =
        runBlocking { Corrections.sweep(context, captures, matcher, limit = limit) }

    private fun pending(): List<Corrections.Correction>? =
        runBlocking { Corrections.pending(context, captures, matcher) }

    /**
     * A capture the current pack reads, committed, then restamped as pack
     * `N - 1`'s read under [oldRule] with [olderRead] applied to its
     * transaction in raw SQL -- no DAO writes those columns but this mode.
     */
    private fun readByAnOlderPack(
        text: String,
        title: String,
        pkg: String = ParseFixtures.TNG,
        at: Long,
        oldRule: String,
        olderRead: String,
    ): Long {
        val id = ParseFixtures.insertCapture(
            context = context,
            text = text,
            title = title,
            pkg = pkg,
            sbnKey = "$marker-$at",
            postedAt = at,
        )
        ParseFixtures.pass(context).run()
        assertEquals("The fixture was not read by the current pack", ParseStatus.MATCHED, captures.byId(id).parseStatus)
        val txn = ParseFixtures.txnForCapture(context, id)
        assertNotNull("The fixture wrote no transaction, so nothing below is about re-reading one", txn)

        assertEquals(
            1,
            captures.markOutcome(
                id = id,
                status = ParseStatus.MATCHED,
                ruleId = oldRule,
                packVersion = packVersion - 1,
                expected = ParseStatus.MATCHED,
            ),
        )
        Databases.shared(context).openHelper.writableDatabase
            .execSQL("UPDATE txn SET $olderRead WHERE id = ?", arrayOf(txn!!.id))
        return id
    }

    private fun txn(captureId: Long): Txn = ParseFixtures.txnForCapture(context, captureId)!!

    private fun offered(captureId: Long): Corrections.Correction? =
        pending()?.singleOrNull { it.captureId == captureId }

    /** The case spec 5.5 names: a wrong amount, offered with both values, applied only on accept. */
    @Test
    fun aWrongAmountIsOfferedWithBothValuesAndAppliedOnlyOnAccept() {
        val id = readByAnOlderPack(
            text = "Payment of RM32.00 to KEDAI $marker successful",
            title = "Touch 'n Go $marker",
            at = base - 9_000L,
            oldRule = "tng-payment-v1",
            olderRead = "amount_sen = 32",
        )

        assertEquals("The sweep did not find the difference", 1, sweep().offered)
        assertEquals("The sweep applied a visible difference unasked", 32L, txn(id).amountSen)
        assertEquals(packVersion - 1, captures.byId(id).packVersion)

        val correction = offered(id)
        assertNotNull("The difference was not offered for review", correction)
        assertEquals(32L, correction!!.before.amountSen)
        assertEquals(3_200L, correction.after.amountSen)
        assertEquals(setOf(Corrections.Field.AMOUNT), correction.changed)

        assertTrue(Corrections.accept(captures, correction))
        assertEquals(3_200L, txn(id).amountSen)
        assertEquals(packVersion, captures.byId(id).packVersion)
        assertNull("An accepted correction was offered again", offered(id))
    }

    /**
     * Issue #20's card bill. Accepted, it lands in review as a transfer
     * suspect (spec 7.3) and is **not** excluded: that is the user's answer
     * to give, as it is on a first read.
     */
    @Test
    fun anAcceptedCardBillLandsInReviewAndIsNotExcluded() {
        val id = readByAnOlderPack(
            text = "Successful payment of RM 2,262.76 to Maybank Master Card. REF:$marker",
            title = "Maybank2u: Payments",
            pkg = ParseFixtures.MAE,
            at = base - 8_000L,
            oldRule = "mae-scan-pay-v1",
            olderRead = "state = 'COMMITTED', pending_reason = NULL, confidence = 'HIGH'",
        )
        sweep()
        val correction = offered(id)
        assertNotNull("The card bill read as spending was not offered", correction)
        assertTrue("The re-read did not change the status", Corrections.Field.STATUS in correction!!.changed)

        assertTrue(Corrections.accept(captures, correction))
        val after = txn(id)
        assertEquals(TxnState.PENDING, after.state)
        assertEquals(PendingReason.TRANSFER_SUSPECT, after.pendingReason)
        assertFalse("Accepting excluded the row, which is spec 7.3's prompt to answer", after.isExcluded)
        assertEquals("mae-card-bill-v1", captures.byId(id).matchedRuleId)
    }

    /** Issue #20's My50 Pass: the whole sentence stored as the merchant. */
    @Test
    fun aMerchantReadAsTheWholeSentenceIsOffered() {
        val id = readByAnOlderPack(
            text = "Travel Pass: You have paid RM 50.00 for your My50 Pass. View your updated pass details now.",
            title = "Payment successful $marker",
            at = base - 7_500L,
            oldRule = "tng-paid-for-v1",
            olderRead = "merchant_raw = 'your My50 Pass. View your updated pass details now.', " +
                "merchant_display = 'your My50 Pass. View your updated pass details now.', " +
                "merchant_key = 'YOUR MY50 PASS. VIEW YOUR UPDATED PASS DETAILS NOW.'",
        )
        sweep()
        val correction = offered(id)
        assertNotNull(correction)
        assertEquals(setOf(Corrections.Field.MERCHANT), correction!!.changed)
        assertEquals("My50 Pass", correction.after.merchantDisplay)
    }

    /**
     * Issue #20's Spades Bakery: only `merchant_key` differs, which nothing
     * draws, so it is applied without a question.
     */
    @Test
    fun aKeyOnlyDifferenceIsAppliedWithoutAsking() {
        val id = readByAnOlderPack(
            text = "You have paid RM6.40 to SPADES BAKERY 3 SDN. BHD..",
            title = "DuitNow Payment $marker",
            at = base - 7_000L,
            oldRule = "duitnow-paid-v1",
            olderRead = "merchant_key = 'SPADES BAKERY 3 SDN. BHD'",
        )
        val before = txn(id)

        assertEquals("A key-only difference was put to the user", 0, sweep().offered)
        val after = txn(id)
        assertEquals("SPADES BAKERY 3", after.merchantKey)
        assertEquals(before.merchantDisplay, after.merchantDisplay)
        assertEquals(packVersion, captures.byId(id).packVersion)
        assertNull(offered(id))
    }

    /** Spec 5.5: "a human decision outranks any rule". */
    @Test
    fun anEditedRowIsNeverReadOrStamped() {
        val id = readByAnOlderPack(
            text = "Payment of RM41.00 to KEDAI $marker successful",
            title = "Touch 'n Go $marker",
            at = base - 6_500L,
            oldRule = "tng-payment-v1",
            olderRead = "amount_sen = 41",
        )
        val txn = txn(id)
        Databases.txnDao(context).setCategory(txn.id, txn.categoryId, System.currentTimeMillis())

        assertEquals(0, sweep().offered)
        assertEquals(41L, txn(id).amountSen)
        assertEquals("An edited row was stamped by a pack that did not decide it", packVersion - 1, captures.byId(id).packVersion)
        assertNull(offered(id))
    }

    @Test
    fun aDeclinedCorrectionKeepsTheRowAndDoesNotReturnUnderThisPack() {
        val id = readByAnOlderPack(
            text = "Payment of RM52.00 to KEDAI $marker successful",
            title = "Touch 'n Go $marker",
            at = base - 6_000L,
            oldRule = "tng-payment-v1-old",
            olderRead = "amount_sen = 52",
        )
        sweep()
        assertTrue(Corrections.decline(captures, offered(id)!!))

        assertEquals(52L, txn(id).amountSen)
        assertEquals(packVersion, captures.byId(id).packVersion)
        assertEquals("Declining replaced the rule that wrote the row", "tng-payment-v1-old", captures.byId(id).matchedRuleId)
        runBlocking { Corrections.forgetSweeps(context) }
        assertEquals("A declined correction came back under the same pack", 0, sweep().offered)
        assertNull(offered(id))
    }

    /** The correction drawn is not the row any more; the edit wins, and nothing is written. */
    @Test
    fun anEditBetweenOfferAndAcceptWins() {
        val id = readByAnOlderPack(
            text = "Payment of RM63.00 to KEDAI $marker successful",
            title = "Touch 'n Go $marker",
            at = base - 5_500L,
            oldRule = "tng-payment-v1",
            olderRead = "amount_sen = 63",
        )
        sweep()
        val correction = offered(id)!!
        Databases.txnDao(context).setCategory(correction.before.id, correction.before.categoryId, System.currentTimeMillis() + 1)

        assertFalse("A correction was applied over an edit made after it was drawn", Corrections.accept(captures, correction))
        assertEquals(63L, txn(id).amountSen)
        assertEquals(packVersion - 1, captures.byId(id).packVersion)
    }

    /** A decline and an accept of one correction: the first answer stands. */
    @Test
    fun aCorrectionAnsweredTwiceIsAnsweredOnce() {
        val id = readByAnOlderPack(
            text = "Payment of RM67.00 to KEDAI $marker successful",
            title = "Touch 'n Go $marker",
            at = base - 5_200L,
            oldRule = "tng-payment-v1",
            olderRead = "amount_sen = 67",
        )
        sweep()
        val correction = offered(id)!!
        assertTrue(Corrections.decline(captures, correction))

        assertFalse("A declined correction was then applied", Corrections.accept(captures, correction))
        assertEquals(67L, txn(id).amountSen)
    }

    /** Until the sweep reaches the end, the set is mostly unread rows: nothing is offered. */
    @Test
    fun nothingIsOfferedBeforeTheSweepFinishes() {
        readByAnOlderPack(
            text = "Payment of RM74.00 to KEDAI $marker successful",
            title = "Touch 'n Go $marker",
            at = base - 5_000L,
            oldRule = "tng-payment-v1",
            olderRead = "amount_sen = 74",
        )
        assertNull(pending())
    }

    /**
     * Offered rows stay in the set until answered, so a run that restarted
     * from the head would spend its whole bound re-reading them and never
     * finish. Three offers, a bound of two: the second run must reach the
     * third and finish.
     */
    @Test
    fun aBoundedSweepResumesPastTheRowsItOffered() {
        val ids = (0 until 3).map { n ->
            readByAnOlderPack(
                text = "Payment of RM8$n.00 to KEDAI $n $marker successful",
                title = "Touch 'n Go $marker",
                at = base - (4_000L - n * 100L),
                oldRule = "tng-payment-v1",
                olderRead = "amount_sen = 8$n",
            )
        }
        val first = sweep(limit = 2)
        assertEquals(2, first.compared)
        assertTrue(first.more)

        val second = sweep(limit = 2)
        assertEquals("The second run re-read what the first had offered", 1, second.compared)
        assertFalse(second.more)
        assertEquals(ids.toSet(), pending()!!.map { it.captureId }.filter { it in ids }.toSet())
    }

    /** Through the real worker, which is the only thing that runs the sweep in production. */
    @Test
    fun theWorkerComparesMatchedCaptures() {
        val id = readByAnOlderPack(
            text = "You have paid RM6.40 to SPADES BAKERY 3 SDN. BHD..",
            title = "DuitNow Payment $marker",
            at = base - 3_000L,
            oldRule = "duitnow-paid-v1",
            olderRead = "merchant_key = 'SPADES BAKERY 3 SDN. BHD'",
        )
        assertEquals(ListenableWorker.Result.success(), ParseFixtures.runWorker(context))
        assertEquals("The worker did not compare matched captures", "SPADES BAKERY 3", txn(id).merchantKey)
    }
}
