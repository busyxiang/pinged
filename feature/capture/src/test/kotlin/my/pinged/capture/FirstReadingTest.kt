package my.pinged.capture

import my.pinged.capture.ReadingFixtures.AT
import my.pinged.capture.ReadingFixtures.MAE
import my.pinged.capture.ReadingFixtures.MINUTE
import my.pinged.capture.ReadingFixtures.SYNTHETIC
import my.pinged.capture.ReadingFixtures.UNASKED
import my.pinged.capture.ReadingFixtures.capture
import my.pinged.capture.ReadingFixtures.category
import my.pinged.capture.ReadingFixtures.corpus
import my.pinged.capture.ReadingFixtures.firstReading
import my.pinged.capture.ReadingFixtures.inLedger
import my.pinged.capture.ReadingFixtures.matched
import my.pinged.capture.ReadingFixtures.reading
import my.pinged.capture.ReadingFixtures.uncategorizedId
import my.pinged.capture.ReadingFixtures.verdict
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.ExclusionReason
import my.pinged.parse.LearnedRules
import my.pinged.parse.MatchMode
import my.pinged.parse.TaughtTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stage two's first reading of a capture a rule matched: the row
 * `commitCapture` writes and the capture it may duplicate, through the pack
 * that ships and the in-memory ports.
 *
 * The device keeps what only the database or the worker can show -- the queue,
 * layer one, damage, the stored columns -- and one end-to-end case for each
 * rule here (`PendingReasonRecordedTest`, `StandingAnswerStageTwoTest`,
 * `DedupTest`, `ParseWorkerTest`, `TaughtStageTwoTest`).
 */
class FirstReadingTest {

    private fun read(capture: RawCapture, reading: Reading = reading(), pairs: PairCandidates = ReadingFixtures.NOTHING): Txn =
        firstReading(capture, reading, pairs = pairs).txn

    /** [text] read by [ReadingFixtures.syntheticMatcher], whose rules each fire one condition. */
    private fun synthetic(text: String): Txn {
        val synthetic = ReadingFixtures.syntheticMatcher()
        val read = capture(text, pkg = SYNTHETIC)
        return firstReading(read, reading(synthetic), outcome = matched(read, synthetic)).txn
    }

    private fun held(reason: PendingReason) = listOf(TxnState.PENDING, reason, false, null)
    private fun leftOut(reason: ExclusionReason) = listOf(TxnState.COMMITTED, null, true, reason)
    private val counts = listOf(TxnState.COMMITTED, null, false, null)

    // ---- spec 7.1's gate, one condition at a time ------------------------

    @Test fun ruleReviewIsRecordedForAReviewConfidenceRule() {
        val txn = synthetic("Maybe spent RM7.10 at SOME STALL today")

        assertEquals(710L, txn.amountSen)
        assertEquals(held(PendingReason.RULE_REVIEW), verdict(txn))
    }

    @Test fun transferSuspectIsRecordedForATransferSuspectRule() {
        val txn = synthetic("Moved RM7.20 to SAVINGS account")

        // Spec 7.3: the exclusion is the user's answer, not the parser's.
        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(txn))
    }

    @Test fun merchantMissingIsRecordedWhenTheRuleExtractsNoMerchant() {
        val txn = synthetic("Charge of RM7.30 applied")

        assertEquals(730L, txn.amountSen)
        assertEquals(held(PendingReason.MERCHANT_MISSING), verdict(txn))
        // Null and not "", so `merchant_raw IS NULL OR merchant_raw = ''` recomputes the reason.
        assertEquals(listOf(null, null, null), listOf(txn.merchantRaw, txn.merchantDisplay, txn.merchantKey))
    }

    /** `TNG*` cleans away to nothing: kept as raw, the gate would see a merchant and the row commit. */
    @Test fun aMerchantThatIsOnlyPaymentRailIsMissingRatherThanKept() {
        val txn = read(capture("Payment of RM12.34 to TNG* successful"))

        assertEquals(1_234L, txn.amountSen)
        assertNull("An acquirer prefix on its own was kept as the merchant", txn.merchantRaw)
        assertNull(txn.merchantKey)
        assertEquals(held(PendingReason.MERCHANT_MISSING), verdict(txn))
    }

    @Test fun overThresholdIsRecordedForAnAmountAboveTheDefaultThreshold() {
        val txn = read(capture("Payment of RM600.00 to BIG TICKET successful"))

        assertEquals(60_000L, txn.amountSen)
        assertEquals(held(PendingReason.OVER_THRESHOLD), verdict(txn))
    }

    /** Spec 7.1 says "exceeds", so the limit itself counts. */
    @Test fun theThresholdBoundaryIsExclusive() {
        val txn = read(capture("Payment of RM500.00 to ON THE LINE successful"))

        assertEquals(50_000L, txn.amountSen)
        assertEquals(counts, verdict(txn))
    }

    /** #362: at a saved RM1,000, RM800 counts, RM1,200 waits, and RM1,000.00 exactly counts. */
    @Test fun aFirstReadingHoldsByTheCheckLimitItIsGiven() {
        val atRm1000 = reading(checkLimitSen = 100_000L)
        fun stored(rm: String) = read(capture("Payment of $rm to LIMIT successful"), atRm1000).let { it.amountSen to verdict(it) }

        assertEquals(80_000L to counts, stored("RM800.00"))
        assertEquals(120_000L to held(PendingReason.OVER_THRESHOLD), stored("RM1,200.00"))
        assertEquals("The boundary is not strict", 100_000L to counts, stored("RM1,000.00"))
    }

    /** `tng-reload-v1` fires three conditions; the transfer question is the one stored. */
    @Test fun transferSuspectOutranksTheWeakerReasonsThatFireWithIt() {
        val reload = capture("Reload of RM100.00 was successful")
        val txn = read(reload)

        assertEquals("tng-reload-v1", matched(reload).ruleId)
        assertEquals(10_000L, txn.amountSen)
        assertNull("The reload rule extracts no merchant", txn.merchantRaw)
        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(txn))
    }

    // ---- the row's own columns --------------------------------------------

    /** Spec 5.4 title-cases only an all-uppercase merchant; `clean` uppercases, so display is not built from it. */
    @Test fun aMerchantThatCapitalisedItselfKeepsItsCapitalisation() {
        val txn = read(capture("Payment of RM12.34 to foodpanda KLCC successful"))

        assertEquals("foodpanda KLCC", txn.merchantRaw)
        assertEquals("The ledger shows a name the notification never said", "foodpanda KLCC", txn.merchantDisplay)
        assertEquals("FOODPANDA KLCC", txn.merchantKey)
    }

    /** `when` displaces `postTime` only when it is at most a day earlier: never later, never days earlier. */
    @Test fun aFutureWhenIsIgnoredAndAModestlyEarlierOneIsTrusted() {
        fun occurredAt(whenMillis: Long?) =
            read(capture("Payment of RM3.33 to WHEN successful", postedAt = AT, whenMillis = whenMillis)).occurredAt
        val hour = 60 * MINUTE

        assertEquals(AT - 2 * hour, occurredAt(AT - 2 * hour))
        assertEquals("A `when` in the future dated the transaction", AT, occurredAt(AT + 2 * hour))
        assertEquals("A `when` days earlier dated the transaction", AT, occurredAt(AT - 5 * 24 * hour))
        assertEquals("No `when` at all", AT, occurredAt(null))
    }

    @Test fun theRowCarriesItsCaptureItsSourceAndTheClock() {
        val payment = capture("Payment of RM12.34 to KEDAI successful")
        val txn = read(payment)

        assertEquals(
            listOf<Any?>(payment.id, payment.sourcePackage, "Touch 'n Go eWallet", AT + MINUTE, AT + MINUTE, Direction.EXPENSE),
            listOf<Any?>(txn.rawCaptureId, txn.sourcePackage, txn.sourceLabel, txn.createdAt, txn.updatedAt, txn.direction),
        )
    }

    // ---- filing -----------------------------------------------------------

    /** The bundled dictionary files `MY50` under Transport. */
    @Test fun aCaptureFromADictionaryBrandIsFiledByTheDictionary() {
        val txn = read(my50())

        assertEquals("MY50 PASS", txn.merchantKey)
        assertEquals(category("Transport"), txn.categoryId)
    }

    @Test fun aCaptureWithNoDictionaryHitIsFiledUncategorized() {
        assertEquals(uncategorizedId, read(capture("Payment of RM12.34 to foodpanda KLCC successful")).categoryId)
    }

    /** #48: the learned rule outranks the dictionary. */
    @Test fun aLearnedRuleBeatsTheDictionary() {
        val learned = LearnedRules.Snapshot(rules = mapOf("MY50 PASS" to category("Shopping")))

        assertEquals(category("Shopping"), read(my50(), reading(learned = learned)).categoryId)
    }

    /** A rule answers by `category.id`, so a snapshot of names that lacks its category does not let the dictionary answer. */
    @Test fun aLearnedRuleWhoseCategoryIsMissingFromThePassSnapshotStillBeatsTheDictionary() {
        val reading = reading(
            learned = LearnedRules.Snapshot(rules = mapOf("ZZSNAP" to category("Health"))),
            dictionary = listOf(DictionaryEntry("ZZSNAP", "Shopping", MatchMode.EXACT)),
            categoryIds = ReadingFixtures.categoryIds - "Health",
        )

        assertEquals(category("Health"), read(capture("Payment of RM12.34 to ZZSNAP successful"), reading).categoryId)
    }

    /** #48: a rule on a canonical key files a key merged into it, which keeps its own key. */
    @Test fun aRuleOnAnAliasResolvedIdentityFilesTheMergedKeysCapture() {
        val reading = reading(
            learned = LearnedRules.Snapshot(rules = mapOf("ZZCANON" to category("Health")), aliases = mapOf("ZZMERGED" to "ZZCANON")),
        )
        val txn = read(capture("Payment of RM12.34 to ZZMERGED successful"), reading)

        assertEquals("ZZMERGED", txn.merchantKey)
        assertEquals(category("Health"), txn.categoryId)
    }

    /** #46: an entry whose category is gone is dropped, so its merchant falls through to Uncategorized. */
    @Test fun anEntryWhoseCategoryIsGoneFallsThroughToUncategorized() {
        val reading = reading(
            dictionary = listOf(
                DictionaryEntry("ZZKEPT", "Shopping", MatchMode.EXACT),
                DictionaryEntry("ZZGONE", "Deleted", MatchMode.EXACT),
            ),
        )

        assertEquals("the control entry did not file", category("Shopping"), read(capture("Payment of RM1.00 to ZZKEPT successful"), reading).categoryId)
        assertEquals(uncategorizedId, read(capture("Payment of RM1.00 to ZZGONE successful"), reading).categoryId)
    }

    // ---- spec 7.3's standing answers --------------------------------------

    /** What the inbox's Always stores for [card], a row read with no answer: its rule, app and merchant key. */
    private fun always(card: RawCapture) = InMemoryStandingAnswers.Answer(matched(card).ruleId, card.sourcePackage, read(card).merchantKey)

    private fun reload(rm: String = "RM28.77") = capture("Reload of $rm was successful")
    private fun cardBill() = capture("Successful payment of RM 28.77 to Maybank Master Card. REF:000000000M", pkg = MAE)
    private fun transferTo(name: String) = capture("You've transferred RM 28.77 to $name. REF: 000000000Q.", pkg = MAE)

    @Test fun aReloadsAnswerLeavesOutTheNextReloadAsATransfer() {
        val answered = reading(answers = setOf(always(reload())))

        assertEquals(leftOut(ExclusionReason.TRANSFER), verdict(read(reload("RM31.00"), answered)))
    }

    @Test fun aCardBillsAnswerLeavesOutTheNextBillAsACardPayment() {
        val answered = reading(answers = setOf(always(cardBill())))

        assertEquals(leftOut(ExclusionReason.CARD_PAYMENT), verdict(read(cardBill(), answered)))
    }

    @Test fun aTransfersAnswerLeavesOutTheSameCounterpartyAndAsksAboutAnother() {
        val answered = reading(answers = setOf(always(transferTo("RAY TAN"))))

        assertEquals(leftOut(ExclusionReason.TRANSFER), verdict(read(transferTo("RAY TAN"), answered)))
        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(read(transferTo("ALI BAKAR"), answered)))
    }

    /** "None" matches only "none": an answer with a counterparty does not cover a reload. */
    @Test fun anAnswerForACounterpartyDoesNotAnswerAReloadWhichHasNone() {
        val answered = reading(answers = setOf(always(transferTo("RAY TAN"))))

        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(read(reload(), answered)))
    }

    @Test fun anAnswerForOneRuleDoesNotAnswerAnotherOfTheSameApp() {
        val answered = reading(answers = setOf(always(cardBill())))

        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(read(transferTo("RAY TAN"), answered)))
    }

    /** Layer two's pair is a fact the user never saw when they answered. */
    @Test fun aMatchingReloadThatIsAlsoADuplicateSuspectIsStillHeld() {
        val answered = reading(answers = setOf(always(reload())))
        val manual = InMemoryPairCandidates.Row(read(capture("Payment of RM28.77 to KEDAI successful", postedAt = AT - MINUTE)).copy(id = 1, rawCaptureId = null, sourcePackage = null), ruleId = null)

        val first = firstReading(reload(), answered, pairs = InMemoryPairCandidates(listOf(manual)))

        assertEquals(held(PendingReason.DUPLICATE_SUSPECT), verdict(first.txn))
        assertNull("A manual entry has no capture to name", first.duplicateOf)
    }

    /** Stored as it is with no answer: the transfer question outranks the limit. */
    @Test fun aMatchingReloadOverTheCheckLimitIsStillHeld() {
        val answered = reading(answers = setOf(always(reload())))

        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(read(reload("RM600.00"), answered)))
    }

    /** Spec 5.5's re-parse hands a capture an older pack read back to `NEW`: not a first read, so not settled. */
    @Test fun aCaptureAPackHasReadBeforeIsNotSettledByAnAnswer() {
        val answered = reading(answers = setOf(always(reload())))

        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(read(capture("Reload of RM28.77 was successful", packVersion = 17), answered)))
    }

    // ---- spec 7.2's layer two -------------------------------------------

    private val wallet = capture("Payment of RM12.34 to STARBUCKS KLCC successful", postedAt = AT)

    /** One purchase, two apps: the newer waits as the older's duplicate, and neither is dropped. */
    @Test fun layerTwoHoldsTheNewerOfAPairAcrossAppsAndNamesTheOlder() {
        val inLedger = inLedger(wallet)
        val bank = capture("You have paid RM12.34 to STARBUCKS KLCC", pkg = MAE, postedAt = AT + 2 * MINUTE)

        val first = firstReading(bank, pairs = InMemoryPairCandidates(listOf(inLedger)))

        assertEquals(held(PendingReason.DUPLICATE_SUSPECT), verdict(first.txn))
        assertEquals(wallet.id, first.duplicateOf)
    }

    /** The merchant clause is a clause: two shops at one amount are two purchases. */
    @Test fun layerTwoDoesNotPairTwoDifferentMerchantsAtTheSameAmount() {
        val speedmart = inLedger(capture("Payment of RM12.34 to 99 SPEEDMART successful", postedAt = AT))
        val pharmacy = capture("You have paid RM12.34 to GUARDIAN PHARMACY", pkg = MAE, postedAt = AT + 2 * MINUTE)

        val first = firstReading(pharmacy, pairs = InMemoryPairCandidates(listOf(speedmart)))

        assertEquals(counts, verdict(first.txn))
        assertNull(first.duplicateOf)
    }

    /** A refund reversing the expense is not the purchase seen twice: Merge would erase the reversal. */
    @Test fun layerTwoDoesNotPairARefundWithTheExpenseItReverses() {
        val refund = capture("Bayaran balik RM12.34 daripada STARBUCKS KLCC telah diterima", pkg = MAE, postedAt = AT + 2 * MINUTE)

        val first = firstReading(refund, pairs = InMemoryPairCandidates(listOf(inLedger(wallet))))

        assertEquals("The refund rule has to be what matched", Direction.REFUND, first.txn.direction)
        assertEquals(counts, verdict(first.txn))
        assertNull(first.duplicateOf)
    }

    /** #255: MAE's card spend at the FPX gateway, then the FPX notice naming the payee, is one payment. */
    @Test fun layerTwoPairsMaesCardSpendAtTheFpxGatewayWithTheFpxPayment() {
        val card = corpus("mae-card-spend-fpx-credit-card-rps.txt", postedAt = AT)
        val fpx = corpus("mae-fpx-card-funded-ibilik.txt", postedAt = AT + 6_000L)

        val first = firstReading(fpx, pairs = InMemoryPairCandidates(listOf(inLedger(card))))

        assertEquals(held(PendingReason.DUPLICATE_SUSPECT), verdict(first.txn))
        assertEquals(card.id, first.duplicateOf)
    }

    /** One rule read both in one app: two payments, even though a gateway merchant agrees with anything. */
    @Test fun layerTwoDoesNotPairTwoCapturesTheSameRuleReadInOneApp() {
        val earlier = corpus("mae-card-spend-fpx-credit-card-rps.txt", postedAt = AT)
        val later = corpus("mae-card-spend-fpx-credit-card-rps.txt", postedAt = AT + 2 * MINUTE)

        val first = firstReading(later, pairs = InMemoryPairCandidates(listOf(inLedger(earlier))))

        assertEquals(TxnState.COMMITTED, first.txn.state)
        assertNull(first.duplicateOf)
    }

    /** Paid from the account: nothing beside it, so it counts rather than wait every month. */
    @Test fun anFpxPaymentWithNoCardSpendBesideItCommits() {
        assertEquals(counts, verdict(read(corpus("mae-fpx-from-account-digi.txt"))))
    }

    @Test fun anFpxPaymentOverTheThresholdStillWaitsForReview() {
        val over = corpus("mae-fpx-from-account-digi.txt") { body ->
            body.replace("RM30.00", "RM650.00").also { check(it != body) { "the Digi fixture no longer says RM30.00" } }
        }

        assertEquals(held(PendingReason.OVER_THRESHOLD), verdict(read(over)))
    }

    // ---- what layer one, or damage, already said -------------------------

    /** Layer two's lookup met damage: a suspect naming no pair, and the port is not asked again. */
    @Test fun aPairUnknownIsASuspectNamingNoPair() {
        val first = firstReading(capture("Payment of RM12.34 to KEDAI successful"), prior = Reading.Prior.PairUnknown, pairs = UNASKED)

        assertEquals(held(PendingReason.DUPLICATE_SUSPECT), verdict(first.txn))
        assertNull(first.duplicateOf)
    }

    /** Layer one's suspect is the one named, over a layer-two pair the lookup also finds. */
    @Test fun aSlotSuspectNamesItsPriorOverALayerTwoPair() {
        val bank = capture("You have paid RM12.34 to STARBUCKS KLCC", pkg = MAE, postedAt = AT + 2 * MINUTE)

        val first = firstReading(bank, prior = Reading.Prior.SlotSuspect(42), pairs = InMemoryPairCandidates(listOf(inLedger(wallet))))

        assertEquals(held(PendingReason.DUPLICATE_SUSPECT), verdict(first.txn))
        assertEquals(42L, first.duplicateOf)
    }

    // ---- spec 5.8's taught rules --------------------------------------------

    private fun taught(text: String, amount: Int, merchant: IntRange?, matchCount: Int): Pair<RawCapture, Txn> {
        val title = "Kiosk"
        val derived = requireNotNull(TaughtTemplate.derive(TaughtTemplate.words(title, text, null), amount, merchant))
        val rules = TaughtTemplate.matcher(listOf(TaughtTemplate.Stored(7, ReadingFixtures.TNG, derived.pattern, Direction.EXPENSE, matchCount)))
        val read = capture(text.replace("RM1.00", "RM12.34"), title = title)
        return read to firstReading(read, outcome = matched(read, rules)).txn
    }

    /** Under three matches a taught rule's reads wait for review. */
    @Test fun aTaughtRulesEarlyReadsWaitForReview() {
        val (_, txn) = taught("debit RM1.00 at KEDAI AH SENG", amount = 2, merchant = 4..6, matchCount = 0)

        assertEquals("KEDAI AH SENG", txn.merchantRaw)
        assertEquals(held(PendingReason.RULE_REVIEW), verdict(txn))
    }

    /** No merchant tapped is spec 7.1's missing-merchant review, not a new state. */
    @Test fun aTaughtRuleWithNoMerchantLandsInReviewAsMissingMerchant() {
        val (_, txn) = taught("debit RM1.00 from account 1234", amount = 2, merchant = null, matchCount = 3)

        assertEquals(1_234L, txn.amountSen)
        assertEquals(held(PendingReason.MERCHANT_MISSING), verdict(txn))
    }

    private fun my50() = capture(
        "Travel Pass: You have paid RM12.34 for your My50 Pass. View your updated pass details now.",
        title = "Payment successful",
    )
}
