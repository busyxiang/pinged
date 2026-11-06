package my.pinged.ledger.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Duplicate cards (§7.2, §9.2, #116) against the real encrypted database:
 * which row a card finds as its pair, the plain card it falls back to when it
 * finds none, and what Merge, Keep both and Undo write.
 *
 * The rows are seeded in the shapes stage two leaves them in -- a capture
 * whose `duplicate_of_id` names the earlier capture, each with its own
 * transaction -- rather than by running the parser: the lookup reads nothing
 * but those columns, and `:feature:capture`'s `DedupTest` is what pins that
 * layers 1 and 2 write them.
 */
@RunWith(AndroidJUnit4::class)
class ReviewDuplicateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    // ---- finding the pair ------------------------------------------------

    @Test fun aLayerOneSlotSuspectPairsWithTheTransactionOfTheCaptureItRepeats() = runBlocking {
        context.freshLedger()
        // One wallet slot, byte-identical text twenty minutes apart: rule 1's
        // window has passed, so the second is a transaction awaiting review.
        val (pair, held) = capturedPair(
            olderPackage = TNG, newerPackage = TNG, sameSlot = true, apart = 20 * MINUTE,
        )

        val card = card(inbox(), held)

        assertEquals("A layer-1 slot suspect is not drawn as a duplicate card", CardKind.DUPLICATE, card.kind)
        assertEquals("A layer-1 slot suspect did not find the transaction it repeats", pair, card.pair?.id)
    }

    @Test fun aLayerTwoSuspectPairsWithTheTransactionOfTheOtherAppsCapture() = runBlocking {
        context.freshLedger()
        val (pair, held) = capturedPair(olderPackage = TNG, newerPackage = MAE, sameSlot = false, apart = 2 * MINUTE)
        // A manual entry nearer in time, which must not win over the recorded pair.
        Databases.txnDao(context).insert(manual(amountSen = AMOUNT, occurredAt = NOON + 2 * MINUTE + 1))

        val card = card(inbox(), held)

        assertEquals("A layer-2 suspect is not drawn as a duplicate card", CardKind.DUPLICATE, card.kind)
        assertEquals("A layer-2 suspect did not find the other app's transaction", pair, card.pair?.id)
    }

    @Test fun withNoRecordedPairTheClosestManualEntryInTheWindowIsThePair() = runBlocking {
        val dao = context.freshLedger()
        val at = NOON + 30 * MINUTE
        val held = dao.insert(heldTxn(capture(TNG, "lone", duplicateOf = null, postedAt = at), at))
        val closest = dao.insert(manual(amountSen = AMOUNT, occurredAt = at + 3 * MINUTE))
        dao.insert(manual(amountSen = AMOUNT, occurredAt = at - 8 * MINUTE))
        // Each nearer than the closest, and each not a pair: dismissed, a
        // refund, another amount, a capture rather than a manual entry.
        dao.insert(manual(amountSen = AMOUNT, occurredAt = at + MINUTE).copy(state = TxnState.REJECTED))
        dao.insert(manual(amountSen = AMOUNT, occurredAt = at + MINUTE).copy(direction = Direction.REFUND))
        dao.insert(manual(amountSen = AMOUNT + 1, occurredAt = at + MINUTE))
        dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = at + 30_000L).copy(rawCaptureId = capture(MAE, "other", null, at)))
        // Outside the ten minutes, nearer than nothing.
        dao.insert(manual(amountSen = AMOUNT, occurredAt = at - 11 * MINUTE))

        val card = card(inbox(), held)

        assertEquals("A duplicate with a manual entry in the window is not a duplicate card", CardKind.DUPLICATE, card.kind)
        assertEquals("The manual-entry fallback did not pick the closest manual entry in time", closest, card.pair?.id)
    }

    @Test fun aDuplicateWithNoPairIsAPlainCard() = runBlocking {
        val dao = context.freshLedger()
        // The damage path's shape: flagged, nothing recorded, nothing in the window.
        val held = dao.insert(heldTxn(capture(TNG, "unpaired", duplicateOf = null, postedAt = NOON), NOON))
        dao.insert(manual(amountSen = AMOUNT, occurredAt = NOON + 11 * MINUTE))

        val card = card(inbox(), held)

        assertNull("A duplicate with no pair found one", card.pair)
        assertEquals("A duplicate with no pair is not a plain card", CardKind.PLAIN, card.kind)
        assertEquals(
            "A duplicate with no pair does not offer the plain answers",
            listOf(ReviewAnswer.ACCEPT, ReviewAnswer.NOT_A_PAYMENT),
            card.kind.answers,
        )
    }

    @Test fun aDuplicateWhosePairIsRejectedIsAPlainCard() = runBlocking {
        context.freshLedger()
        val (pair, held) = capturedPair(olderPackage = TNG, newerPackage = MAE, sameSlot = false, apart = 2 * MINUTE)
        sql("UPDATE txn SET state = 'REJECTED' WHERE id = $pair")

        val card = card(inbox(), held)

        assertNull("A duplicate whose pair was dismissed still offers it as its pair", card.pair)
        assertEquals("A duplicate whose pair was dismissed is not a plain card", CardKind.PLAIN, card.kind)
    }

    // ---- answering -----------------------------------------------------

    @Test fun mergeRejectsTheRowAwaitingReviewLeavesThePairAndCountsTheAmountOnce() = runBlocking {
        val dao = context.freshLedger()
        val (pair, held) = capturedPair(olderPackage = TNG, newerPackage = MAE, sameSlot = false, apart = 2 * MINUTE)
        val pairBefore = dao.byId(pair)!!
        val heldBefore = dao.byId(held)!!
        val inbox = inbox()

        inbox.merge(card(inbox, held)).join()

        val after = dao.byId(held)!!
        assertEquals(
            "Merge did not dismiss the row awaiting review, and only state and reason (and updated_at)",
            heldBefore.copy(state = TxnState.REJECTED, pendingReason = null, updatedAt = after.updatedAt),
            after,
        )
        assertEquals("Merge touched the pair it keeps", pairBefore, dao.byId(pair)!!)
        assertEquals(
            "After Merge the month total does not count the payment once",
            listOf(AMOUNT),
            dao.monthTotals(DAY, DAY).map { it.netSen },
        )
        assertEquals("The merged card is still in the inbox", emptyList<ReviewCard>(), inbox.cards)
        assertEquals("The snackbar does not name Merge", "MERGED · RM12.34", inbox.undoable.value?.said)
    }

    @Test fun keepBothCommitsTheRowAwaitingReviewAndCountsBoth() = runBlocking {
        val dao = context.freshLedger()
        val (pair, held) = capturedPair(olderPackage = TNG, newerPackage = TNG, sameSlot = true, apart = 20 * MINUTE)
        val pairBefore = dao.byId(pair)!!
        val heldBefore = dao.byId(held)!!
        val inbox = inbox()

        inbox.keepBoth(card(inbox, held)).join()

        val after = dao.byId(held)!!
        assertEquals(
            "Keep both did not count the row awaiting review, and only state and reason (and updated_at)",
            heldBefore.copy(state = TxnState.COMMITTED, pendingReason = null, updatedAt = after.updatedAt),
            after,
        )
        assertEquals("Keep both touched the pair", pairBefore, dao.byId(pair)!!)
        assertEquals(
            "After Keep both the month total does not count both payments",
            listOf(2 * AMOUNT),
            dao.monthTotals(DAY, DAY).map { it.netSen },
        )
        assertEquals("The snackbar does not name Keep both", "KEPT BOTH · RM12.34", inbox.undoable.value?.said)
    }

    @Test fun undoAfterMergeRestoresBothRowsPriorStates() = runBlocking {
        val dao = context.freshLedger()
        val (pair, held) = capturedPair(olderPackage = TNG, newerPackage = MAE, sameSlot = false, apart = 2 * MINUTE)
        val pairBefore = dao.byId(pair)!!
        val heldBefore = dao.byId(held)!!
        val inbox = inbox()
        inbox.merge(card(inbox, held)).join()
        assertEquals("precondition: Merge did not dismiss the row", TxnState.REJECTED, dao.byId(held)!!.state)

        inbox.undo().join()

        assertEquals(
            "Undo after Merge did not put the row back exactly as it was awaiting review",
            heldBefore,
            dao.byId(held)!!.let { it.copy(updatedAt = heldBefore.updatedAt) },
        )
        assertEquals("Undo after Merge changed the pair", pairBefore, dao.byId(pair)!!)
        assertEquals(
            "The undone duplicate is not back in the inbox beside its pair",
            listOf(held to pair),
            inbox.cards?.map { it.txn.id to it.pair?.id },
        )
    }

    /**
     * The pair dismissed between the card being drawn and the tap (another
     * card's Not a payment, a quick second tap): merging then would leave
     * neither row counted, so Merge writes nothing and the card re-reads as a
     * plain one.
     */
    @Test fun mergeWritesNothingWhenThePairWasDismissedUnderneath() = runBlocking {
        val dao = context.freshLedger()
        val (pair, held) = capturedPair(olderPackage = TNG, newerPackage = MAE, sameSlot = false, apart = 2 * MINUTE)
        val inbox = inbox()
        val drawn = card(inbox, held)
        sql("UPDATE txn SET state = 'REJECTED' WHERE id = $pair")

        inbox.merge(drawn).join()

        assertEquals(
            "Merge dismissed the row awaiting review after its pair had been dismissed, leaving neither counted",
            TxnState.PENDING to PendingReason.DUPLICATE_SUSPECT,
            dao.byId(held)!!.let { it.state to it.pendingReason },
        )
        assertNull("A Merge that wrote nothing is offered for Undo", inbox.undoable.value)
        assertEquals("The card did not re-read as a plain one", CardKind.PLAIN, card(inbox, held).kind)
    }

    // ---- Merge of a gateway pair (#255) ---------------------------------

    /**
     * The card spend at the FPX gateway, counted first, and the FPX notice
     * naming the payee held as its duplicate: Merge keeps the payee and
     * dismisses the committed gateway row, so the bill is counted once under
     * the name the user knows.
     */
    @Test fun mergeOfAGatewayPairKeepsThePayeeAndDismissesTheCommittedGatewayRow() = runBlocking {
        val dao = context.freshLedger()
        val (gateway, payee) = gatewayPair(gatewayHeld = false)
        val gatewayBefore = dao.byId(gateway)!!
        val payeeBefore = dao.byId(payee)!!
        val inbox = inbox()

        inbox.merge(card(inbox, payee)).join()

        assertEquals(
            "Merge did not dismiss the committed gateway row",
            gatewayBefore.copy(state = TxnState.REJECTED, pendingReason = null, updatedAt = dao.byId(gateway)!!.updatedAt),
            dao.byId(gateway)!!,
        )
        assertEquals(
            "Merge did not keep the row naming the payee",
            payeeBefore.copy(state = TxnState.COMMITTED, pendingReason = null, updatedAt = dao.byId(payee)!!.updatedAt),
            dao.byId(payee)!!,
        )
        assertEquals("After Merge the bill is not counted once", listOf(AMOUNT), dao.monthTotals(DAY, DAY).map { it.netSen })
        assertEquals("The merged card is still in the inbox", emptyList<ReviewCard>(), inbox.cards)
    }

    /** The gateway row arrived second: it is the held row, and Merge dismisses it as before. */
    @Test fun mergeOfAGatewayPairDismissesTheGatewayRowWhenItIsTheHeldOne() = runBlocking {
        val dao = context.freshLedger()
        val (gateway, payee) = gatewayPair(gatewayHeld = true)
        val payeeBefore = dao.byId(payee)!!
        val inbox = inbox()

        inbox.merge(card(inbox, gateway)).join()

        assertEquals(TxnState.REJECTED, dao.byId(gateway)!!.state)
        assertEquals("Merge touched the payee row it keeps", payeeBefore, dao.byId(payee)!!)
    }

    @Test fun mergeOfAGatewayPairCarriesTheDismissedRowsCategory() = runBlocking {
        val dao = context.freshLedger()
        val (bills, _) = twoCategories()
        val (gateway, payee) = gatewayPair(gatewayHeld = false, gatewayCategory = bills)
        val inbox = inbox()

        inbox.merge(card(inbox, payee)).join()

        assertEquals("The gateway row's category did not move to the payee row", bills, dao.byId(payee)!!.categoryId)
        assertEquals(TxnState.REJECTED, dao.byId(gateway)!!.state)
    }

    @Test fun mergeOfAGatewayPairCarriesTheHeldGatewayRowsCategoryToThePayee() = runBlocking {
        val dao = context.freshLedger()
        val (bills, _) = twoCategories()
        val (gateway, payee) = gatewayPair(gatewayHeld = true, gatewayCategory = bills)
        val inbox = inbox()

        inbox.merge(card(inbox, gateway)).join()

        assertEquals("The held gateway row's category did not move to the payee row", bills, dao.byId(payee)!!.categoryId)
    }

    @Test fun mergeOfAGatewayPairKeepsThePayeeRowsOwnCategory() = runBlocking {
        val dao = context.freshLedger()
        val (bills, rent) = twoCategories()
        val (_, payee) = gatewayPair(gatewayHeld = false, gatewayCategory = bills, payeeCategory = rent)
        val inbox = inbox()

        inbox.merge(card(inbox, payee)).join()

        assertEquals("Merge overwrote the payee row's own category", rent, dao.byId(payee)!!.categoryId)
        assertEquals(TxnState.COMMITTED, dao.byId(payee)!!.state)
    }

    /** A person edited the gateway row: Merge does what it does for any pair, and the edit stays counted. */
    @Test fun mergeOfAnEditedGatewayRowDismissesTheHeldRowInstead() = runBlocking {
        val dao = context.freshLedger()
        val (gateway, payee) = gatewayPair(gatewayHeld = false)
        sql("UPDATE txn SET user_edited = 1 WHERE id = $gateway")
        val gatewayBefore = dao.byId(gateway)!!
        val inbox = inbox()

        inbox.merge(card(inbox, payee)).join()

        assertEquals("Merge discarded the user's edited gateway row", gatewayBefore, dao.byId(gateway)!!)
        assertEquals(TxnState.REJECTED, dao.byId(payee)!!.state)
    }

    @Test fun undoAfterAGatewayMergeRestoresBothRowsExactly() = runBlocking {
        val dao = context.freshLedger()
        val (bills, _) = twoCategories()
        val (gateway, payee) = gatewayPair(gatewayHeld = false, gatewayCategory = bills)
        val gatewayBefore = dao.byId(gateway)!!
        val payeeBefore = dao.byId(payee)!!
        val inbox = inbox()
        inbox.merge(card(inbox, payee)).join()
        assertEquals("precondition: Merge did not dismiss the gateway row", TxnState.REJECTED, dao.byId(gateway)!!.state)

        inbox.undo().join()

        assertEquals("Undo did not restore the committed gateway row", gatewayBefore, dao.byId(gateway)!!.copy(updatedAt = gatewayBefore.updatedAt))
        assertEquals("Undo did not restore the payee row awaiting review", payeeBefore, dao.byId(payee)!!.copy(updatedAt = payeeBefore.updatedAt))
        assertEquals(listOf(payee to gateway), inbox.cards?.map { it.txn.id to it.pair?.id })
    }

    // ---- fixtures ------------------------------------------------------

    /**
     * An older committed capture-backed transaction and a newer one awaiting
     * review as its duplicate, its capture's `duplicate_of_id` naming the
     * older capture as stage two records it. Returns (pair, held).
     */
    private fun capturedPair(olderPackage: String, newerPackage: String, sameSlot: Boolean, apart: Long): Pair<Long, Long> {
        val dao = Databases.txnDao(context)
        val olderCapture = capture(olderPackage, if (sameSlot) "slot" else "older", duplicateOf = null, postedAt = NOON)
        val newerCapture = capture(newerPackage, if (sameSlot) "slot" else "newer", duplicateOf = olderCapture, postedAt = NOON + apart)
        val pair = dao.insert(
            ledgerTxn(amountSen = AMOUNT, occurredAt = NOON, categoryId = uncategorized())
                .copy(rawCaptureId = olderCapture, sourcePackage = olderPackage),
        )
        val held = dao.insert(heldTxn(newerCapture, NOON + apart).copy(sourcePackage = newerPackage))
        return pair to held
    }

    /**
     * A [capturedPair] from MAE alone, its merchants the fixtures' own: the
     * card spend at the FPX gateway and the FPX notice naming the payee.
     * [gatewayHeld] makes the gateway row the newer one awaiting review.
     * Returns (gateway, payee).
     */
    private fun gatewayPair(
        gatewayHeld: Boolean,
        gatewayCategory: Long = uncategorized(),
        payeeCategory: Long = uncategorized(),
    ): Pair<Long, Long> {
        val (pair, held) = capturedPair(olderPackage = MAE, newerPackage = MAE, sameSlot = false, apart = 6_000L)
        val (gateway, payee) = if (gatewayHeld) held to pair else pair to held
        sql("UPDATE txn SET merchant_raw = '$GATEWAY', category_id = $gatewayCategory WHERE id = $gateway")
        sql("UPDATE txn SET merchant_raw = '$PAYEE', category_id = $payeeCategory WHERE id = $payee")
        return gateway to payee
    }

    /** Two seeded categories other than Uncategorized. */
    private fun twoCategories(): Pair<Long, Long> =
        Databases.categoryDao(context).all().map { it.id }.filter { it != uncategorized() }.let { it[0] to it[1] }

    private fun heldTxn(captureId: Long, at: Long): Txn =
        ledgerTxn(
            amountSen = AMOUNT,
            occurredAt = at,
            categoryId = uncategorized(),
            state = TxnState.PENDING,
            pendingReason = PendingReason.DUPLICATE_SUSPECT,
        ).copy(rawCaptureId = captureId)

    private fun capture(pkg: String, slot: String, duplicateOf: Long?, postedAt: Long): Long =
        Databases.rawCaptureDao(context).insert(
            RawCapture(
                sourcePackage = pkg,
                postedAt = postedAt,
                whenMillis = null,
                capturedAt = postedAt,
                sbnKey = "0|$pkg|1|$slot|0",
                notifId = 1,
                notifTag = slot,
                userHandle = 0,
                channelId = "txn",
                flags = 0,
                arrival = Arrival.POSTED,
                title = pkg,
                text = "Payment of RM12.34 to KEDAI successful",
                bigText = null,
                subText = null,
                extrasJson = null,
                // Same slot, same text: the same hash, as layer 1 sees it.
                contentHash = "$pkg-$slot",
                parseStatus = ParseStatus.MATCHED,
                duplicateOfId = duplicateOf,
            ),
        )

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val AMOUNT = 1_234L
        const val MINUTE = 60_000L
        const val GATEWAY = "FPX-CREDIT CARD-RPS KUALA LUMPUR MYS"
        const val PAYEE = "IBILIK 02"
    }
}
