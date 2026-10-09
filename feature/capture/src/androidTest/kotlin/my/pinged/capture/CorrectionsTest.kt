package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.dao.MerchantSql
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
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

    private val merchants = Databases.merchantIdentityDao(context)

    /** A row's merchant identity and name, through spec 6.4's own resolution SQL. */
    private fun resolved(captureId: Long): Pair<String?, String?> =
        Databases.shared(context).openHelper.readableDatabase.query(
            "SELECT ${MerchantSql.IDENTITY}, ${MerchantSql.NAME} FROM txn ${MerchantSql.JOINS} WHERE txn.raw_capture_id = ?",
            arrayOf<Any>(captureId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0) to cursor.getString(1)
        }

    /**
     * A KEDAI payment the older pack keyed [olderKey], which the current pack
     * keys differently but displays the same: a [Corrections.sweep] applies it
     * unasked. [amount] differing too makes it a correction to accept instead.
     */
    private fun rekeyed(n: Int, olderKey: String, at: Long, amount: Boolean = false): Long =
        readByAnOlderPack(
            text = "Payment of RM9$n.00 to KEDAI $n $marker successful",
            title = "Touch 'n Go $marker",
            at = at,
            oldRule = "tng-payment-v1",
            olderRead = "merchant_key = '$olderKey'" + if (amount) ", amount_sen = 9$n" else "",
        )

    /** A shop paid once under the current pack, to merge into. */
    private fun warung(at: Long): Pair<Long, String> {
        val id = ParseFixtures.insertCapture(
            context = context,
            text = "Payment of RM12.00 to WARUNG $marker successful",
            title = "Touch 'n Go $marker",
            pkg = ParseFixtures.TNG,
            sbnKey = "$marker-$at",
            postedAt = at,
        )
        ParseFixtures.pass(context).run()
        return id to txn(id).merchantKey!!
    }

    /**
     * Spec 5.5: a re-key "carries the user's merchant decisions with it". A
     * row merged into another shop at `K` stays in that shop at `K'`.
     */
    @Test
    fun aSilentReKeyKeepsAMergedRowInItsMerge() {
        val (_, target) = warung(base - 2_900L)
        val olderKey = "KEDAI 1 $marker SDN BHD"
        val id = rekeyed(1, olderKey, base - 2_800L)
        assertTrue(merchants.merge(source = olderKey, target = target, targetName = "Warung Mak Su"))
        assertEquals("The fixture is not the shop it was merged into", target to "Warung Mak Su", resolved(id))

        assertEquals("A key-only difference was put to the user", 0, sweep().offered)
        assertTrue("The fixture was not re-keyed", txn(id).merchantKey != olderKey)
        assertEquals("The re-keyed row dropped out of the merge", target to "Warung Mak Su", resolved(id))
    }

    /** The same through [Corrections.accept], whose diff can move the key too. */
    @Test
    fun anAcceptedReKeyKeepsAMergedRowInItsMerge() {
        val (_, target) = warung(base - 2_700L)
        val olderKey = "KEDAI 2 $marker SDN BHD"
        val id = rekeyed(2, olderKey, base - 2_600L, amount = true)
        assertTrue(merchants.merge(source = olderKey, target = target, targetName = "Warung Mak Su"))

        sweep()
        assertTrue(Corrections.accept(captures, offered(id)!!))
        assertTrue("The fixture was not re-keyed", txn(id).merchantKey != olderKey)
        assertEquals("The accepted row dropped out of the merge", target to "Warung Mak Su", resolved(id))
    }

    /**
     * A renamed, unmerged `K` that no row keeps any more: the name moves to
     * `K'`, which is canonical in its place.
     */
    @Test
    fun aRenamedMerchantKeepsItsNameWhenEveryRowIsReKeyed() {
        val olderKey = "KEDAI 3 $marker SDN BHD"
        val id = rekeyed(3, olderKey, base - 2_500L)
        merchants.rename(olderKey, "Kedai Kak Ton", derivedName = txn(id).merchantDisplay)
        assertEquals(olderKey to "Kedai Kak Ton", resolved(id))

        sweep()
        val newKey = txn(id).merchantKey!!
        assertTrue("The fixture was not re-keyed", newKey != olderKey)
        assertEquals("The re-keyed row lost its name", newKey to "Kedai Kak Ton", resolved(id))
        assertNull("The name was copied rather than moved", merchants.nameOf(olderKey))
    }

    /**
     * A renamed `K` that an edited row -- never re-read -- still keeps: the
     * name stays for that row, and `K'` is aliased to `K` to share it.
     */
    @Test
    fun aRenamedMerchantAnotherRowKeepsIsSharedByTheReKeyedRow() {
        val olderKey = "KEDAI 4 $marker SDN BHD"
        val kept = rekeyed(4, olderKey, base - 2_400L)
        val moved = rekeyed(5, olderKey, base - 2_300L)
        txn(kept).let { Databases.txnDao(context).setCategory(it.id, it.categoryId, System.currentTimeMillis()) }
        merchants.rename(olderKey, "Kedai Kak Ton", derivedName = txn(moved).merchantDisplay)

        sweep()
        assertEquals("The edited row was re-keyed", olderKey, txn(kept).merchantKey)
        assertTrue("The fixture was not re-keyed", txn(moved).merchantKey != olderKey)
        assertEquals(olderKey to "Kedai Kak Ton", resolved(kept))
        assertEquals("The re-keyed row lost the name its shop still has", olderKey to "Kedai Kak Ton", resolved(moved))
    }

    /**
     * A canonical `K` with another key merged into it, and no row left at `K`:
     * `K'` takes its place, so the member and the re-keyed row stay one shop.
     */
    @Test
    fun aMergeWhoseCanonicalKeyIsReKeyedStaysOneShop() {
        val (member, memberKey) = warung(base - 2_200L)
        val olderKey = "KEDAI 6 $marker SDN BHD"
        val id = rekeyed(6, olderKey, base - 2_100L)
        assertTrue(merchants.merge(source = memberKey, target = olderKey, targetName = "Kedai Enam"))

        sweep()
        val newKey = txn(id).merchantKey!!
        assertTrue("The fixture was not re-keyed", newKey != olderKey)
        assertEquals("The re-keyed row left its shop", newKey to "Kedai Enam", resolved(id))
        assertEquals("The member was left in a shop with no rows", newKey to "Kedai Enam", resolved(member))
    }

    private fun categoryId(name: String): Long =
        Databases.categoryDao(context).all().first { it.name == name }.id

    /** A learned rule as the teaching save writes it: `EXACT`, `LEARNED`, unscoped, on [identity]. */
    private fun learn(identity: String, category: String) {
        Databases.merchantRuleDao(context).insert(
            MerchantRule(
                matchType = MatchType.EXACT,
                pattern = identity,
                merchantDisplay = identity,
                categoryId = categoryId(category),
                origin = RuleOrigin.LEARNED,
                priority = MerchantRuleDao.LEARNED_PRIORITY,
            ),
        )
    }

    /** The category of the rule whose pattern is exactly [pattern], or null; no alias resolution. */
    private fun ruleOn(pattern: String): Long? =
        Databases.shared(context).openHelper.readableDatabase.query(
            "SELECT category_id FROM merchant_rule WHERE pattern = ?",
            arrayOf<Any>(pattern),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    /** A later payment to KEDAI [n], read by the current pack and filed by stage two; its capture id. */
    private fun followingPaymentId(n: Int, at: Long): Long {
        val id = ParseFixtures.insertCapture(
            context = context,
            text = "Payment of RM7$n.${50 + (at / 100L) % 40} to KEDAI $n $marker successful",
            title = "Touch 'n Go $marker",
            pkg = ParseFixtures.TNG,
            sbnKey = "$marker-follow-$at",
            postedAt = at,
        )
        ParseFixtures.pass(context).run()
        return id
    }

    /** The category stage two filed [followingPaymentId]'s capture under. */
    private fun followingPayment(n: Int, at: Long): Long = txn(followingPaymentId(n, at)).categoryId

    /**
     * Issue #52, `K` still kept by a row: `K'` is aliased to `K`, so the rule
     * stays on `K` and reaches `K'` through the alias.
     */
    @Test
    fun aLearnedRuleReachesTheReKeyedMerchantThroughAnAliasWhileARowKeepsTheOldKey() {
        val olderKey = "KEDAI 7 $marker SDN BHD"
        val kept = rekeyed(7, olderKey, base - 2_000L)
        val moved = rekeyed(8, olderKey, base - 1_900L)
        txn(kept).let { Databases.txnDao(context).setCategory(it.id, it.categoryId, System.currentTimeMillis()) }
        learn(olderKey, "Food & Drinks")

        sweep()
        val newKey = txn(moved).merchantKey!!
        assertTrue("The fixture was not re-keyed", newKey != olderKey)
        assertEquals("The rule's pattern moved while a row still has the key", categoryId("Food & Drinks"), ruleOn(olderKey))
        assertNull("A second rule was written for the new key", ruleOn(newKey))
        assertEquals(olderKey, resolved(moved).first)
        assertEquals(
            "A following capture at the new key missed the rule",
            categoryId("Food & Drinks"),
            followingPayment(8, base - 1_800L),
        )
    }

    /** Issue #52, no row keeps `K`: `K'` replaces it and the pattern moves in the same write. */
    @Test
    fun aLearnedRuleMovesToTheNewKeyWhenNoRowKeepsTheOldOne() {
        val olderKey = "KEDAI 9 $marker SDN BHD"
        val id = rekeyed(9, olderKey, base - 1_700L)
        val filedBefore = txn(id).categoryId
        learn(olderKey, "Food & Drinks")

        sweep()
        val newKey = txn(id).merchantKey!!
        assertTrue("The fixture was not re-keyed", newKey != olderKey)
        assertEquals("The pattern did not move to the new key", categoryId("Food & Drinks"), ruleOn(newKey))
        assertNull("The rule was copied rather than moved", ruleOn(olderKey))
        assertEquals("Carrying a rule re-filed a row", filedBefore, txn(id).categoryId)
        assertEquals(
            "A following capture at the new key missed the rule",
            categoryId("Food & Drinks"),
            followingPayment(9, base - 1_600L),
        )
    }

    /** Issue #52, `K` merged into `c`: `K'` joins `c` and `c`'s rule carries with no extra step. */
    @Test
    fun aLearnedRuleOnTheMergeTargetReachesTheReKeyedMerchant() {
        val (_, target) = warung(base - 1_500L)
        val olderKey = "KEDAI 10 $marker SDN BHD"
        val id = rekeyed(10, olderKey, base - 1_400L)
        assertTrue(merchants.merge(source = olderKey, target = target, targetName = "Warung Mak Su"))
        learn(target, "Food & Drinks")

        sweep()
        val newKey = txn(id).merchantKey!!
        assertTrue("The fixture was not re-keyed", newKey != olderKey)
        assertEquals(target, resolved(id).first)
        assertEquals("The target's rule moved", categoryId("Food & Drinks"), ruleOn(target))
        assertNull("A rule was written for the new key", ruleOn(newKey))
        assertEquals(
            "A following capture at the new key missed the target's rule",
            categoryId("Food & Drinks"),
            followingPayment(10, base - 1_300L),
        )
    }

    /**
     * Issue #52, `K'` already has a rule in another category: nothing is
     * carried, no stored `category_id` changes, and `K`'s orphaned rule stays.
     */
    @Test
    fun aLearnedRuleIsNotCarriedOntoAKeyThatAlreadyHasAnotherCategory() {
        val olderKey = "KEDAI 11 $marker SDN BHD"
        val id = rekeyed(11, olderKey, base - 1_200L)
        val newKey = txn(followingPaymentId(11, base - 1_100L)).merchantKey!!
        learn(olderKey, "Food & Drinks")
        learn(newKey, "Shopping")
        val filedBefore = txn(id).categoryId

        sweep()
        assertEquals("The fixture was not re-keyed", newKey, txn(id).merchantKey)
        assertEquals("The old key's rule changed", categoryId("Food & Drinks"), ruleOn(olderKey))
        assertEquals("The new key's own rule changed", categoryId("Shopping"), ruleOn(newKey))
        assertEquals("A stored category changed", filedBefore, txn(id).categoryId)
        assertEquals(newKey, resolved(id).first)
        assertEquals(
            "A following capture was not filed under the new key's own rule",
            categoryId("Shopping"),
            followingPayment(11, base - 1_000L),
        )
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
