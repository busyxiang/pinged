package my.pinged.ledger.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.TxnState
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.holdWhile
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.transfer.ImportJson
import my.pinged.ledger.transfer.exportBytes
import my.pinged.ledger.transfer.freshDatabase
import my.pinged.parse.Confidence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.2's review inbox against the real encrypted database (#112): what
 * each answer writes, what the totals do afterwards, the guard against a row
 * that moved underneath, the order and the count, the answers surviving an
 * export and an import, and nothing answered while a restore or a delete runs.
 *
 * Driven through [ReviewViewModel]'s [ReviewInbox], the holder the screen
 * draws, rather than the DAO: what is asserted is what a tap on a card does.
 */
@RunWith(AndroidJUnit4::class)
class ReviewInboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val releases = mutableListOf<CountDownLatch>()

    @After fun leaveNothingBehind() {
        releases.forEach { it.countDown() }
        runBlocking { withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } } }
        Transfers.forgetOutcome()
        context.discardTheDatabase()
    }

    @Test fun acceptWritesCommittedWithNoReasonAndTheTotalsCountIt() = runBlocking {
        val dao = context.freshLedger()
        val food = categoryId("Food & Drinks")
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = NOON, categoryId = food))
        val held = dao.insert(
            ledgerTxn(
                amountSen = 65_000L,
                occurredAt = NOON + 1,
                categoryId = food,
                state = TxnState.PENDING,
                pendingReason = PendingReason.OVER_THRESHOLD,
            ).copy(userEdited = true, confidence = Confidence.REVIEW),
        )
        val before = dao.byId(held)!!
        val inbox = inbox()

        inbox.accept(card(inbox, held)).join()

        val after = dao.byId(held)!!
        assertEquals("Accept did not count the payment", TxnState.COMMITTED, after.state)
        assertNull("Accept left a reason on a counted row", after.pendingReason)
        assertEquals(
            "Accept wrote more than state and reason: only those two (and updated_at) are the answer's",
            before.copy(state = TxnState.COMMITTED, pendingReason = null, updatedAt = after.updatedAt),
            after,
        )
        assertEquals(
            "The month total does not include the accepted RM650.00",
            listOf(70_000L),
            dao.monthTotals(DAY, DAY).map { it.netSen },
        )
        assertEquals(
            "The day total does not include the accepted RM650.00",
            listOf(70_000L),
            dao.dayTotals(DAY, DAY).map { it.netSen },
        )
        assertEquals("The accepted card is still in the inbox", emptyList<ReviewCard>(), inbox.cards)
    }

    @Test fun notAPaymentRejectsAndTheRowLeavesTheFeedTheTotalsAndTopMerchants() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = NOON))
        val held = dao.insert(
            ledgerTxn(
                amountSen = 7_000L,
                occurredAt = NOON + 1,
                state = TxnState.PENDING,
                pendingReason = PendingReason.RULE_REVIEW,
            ).copy(merchantKey = "KEDAI HELD", merchantRaw = "KEDAI HELD", merchantDisplay = "Kedai Held"),
        )
        assertEquals(
            "precondition: the held row is not on the awaiting-review line",
            listOf(NotInTotalLine.PENDING to 7_000L),
            dao.notInTotal(DAY, DAY).map { it.line to it.netSen },
        )
        val inbox = inbox()

        inbox.reject(card(inbox, held)).join()

        val after = dao.byId(held)!!
        assertEquals("Not a payment did not reject the row", TxnState.REJECTED, after.state)
        assertNull("Not a payment left a reason on the row", after.pendingReason)
        assertEquals(
            "The rejected row is still in the day's feed",
            listOf(5_000L),
            dao.dayRows(DAY).map { it.txn.amountSen },
        )
        assertEquals("The rejected row is still on a not-in-the-total line", emptyList<Any>(), dao.notInTotal(DAY, DAY))
        assertEquals("The rejected row is in the month total", listOf(5_000L), dao.monthTotals(DAY, DAY).map { it.netSen })
        assertEquals(
            "The rejected row's merchant is ranked among the top merchants",
            listOf("WARUNG PAK ALI"),
            dao.merchantTotals(DAY, DAY, limit = 10).map { it.identityKey },
        )
    }

    @Test fun anAnswerAgainstARowThatMovedUnderneathWritesNothing() = runBlocking {
        val dao = context.freshLedger()
        val reasonMoved = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val stateMoved = dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 1))
        val inbox = inbox()
        val drawnReason = card(inbox, reasonMoved)
        val drawnState = card(inbox, stateMoved)

        // What a Corrections re-read does between the card being drawn and the tap.
        sql("UPDATE txn SET pending_reason = 'MERCHANT_MISSING' WHERE id = $reasonMoved")
        // The state alone, the reason left as drawn, so only the guard's
        // `state` half can refuse it. No production write leaves a committed
        // row with a reason (requireStorable); this row exists to tell the
        // two halves of the guard apart.
        sql("UPDATE txn SET state = 'COMMITTED' WHERE id = $stateMoved")
        inbox.accept(drawnReason).join()
        inbox.reject(drawnState).join()

        val reason = dao.byId(reasonMoved)!!
        assertEquals(
            "Accept wrote over a row whose reason changed after the card was drawn",
            TxnState.PENDING to PendingReason.MERCHANT_MISSING,
            reason.state to reason.pendingReason,
        )
        assertEquals(
            "Not a payment wrote over a row that was committed after the card was drawn",
            TxnState.COMMITTED,
            dao.byId(stateMoved)!!.state,
        )
        assertEquals(
            "The missed answer did not re-read the card as the row now is",
            listOf(reasonMoved to PendingReason.MERCHANT_MISSING),
            inbox.cards?.map { it.txn.id to it.txn.pendingReason },
        )
    }

    @Test fun undoAfterAcceptOrNotAPaymentRestoresTheExactPriorStateAndReason() = runBlocking {
        val dao = context.freshLedger()
        val accepted = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val rejected = dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 1))
        val beforeAccept = dao.byId(accepted)!!
        val beforeReject = dao.byId(rejected)!!
        val inbox = inbox()

        inbox.accept(card(inbox, accepted)).join()
        assertEquals("precondition: Accept did not count the payment", TxnState.COMMITTED, dao.byId(accepted)!!.state)
        inbox.undo().join()

        assertEquals(
            "Undo after Accept did not put the row back exactly as it was awaiting review",
            beforeAccept,
            dao.byId(accepted)!!.copy(updatedAt = beforeAccept.updatedAt),
        )

        inbox.reject(card(inbox, rejected)).join()
        assertEquals("precondition: Not a payment did not reject the row", TxnState.REJECTED, dao.byId(rejected)!!.state)
        inbox.undo().join()

        assertEquals(
            "Undo after Not a payment did not put the row back exactly as it was awaiting review",
            beforeReject,
            dao.byId(rejected)!!.copy(updatedAt = beforeReject.updatedAt),
        )
        assertEquals(
            "The undone cards are not back in the inbox",
            listOf(rejected to PendingReason.RULE_REVIEW, accepted to PendingReason.OVER_THRESHOLD),
            inbox.cards?.map { it.txn.id to it.txn.pendingReason },
        )
        assertNull("Undo stayed on offer after it was used", inbox.undoable.value)
    }

    @Test fun undoAfterAnotherWriterChangedTheRowWritesNothing() = runBlocking {
        val dao = context.freshLedger()
        val restated = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val excluded = dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 1))
        val inbox = inbox()

        // Each answered, then changed by someone else before the Undo: a row
        // rejected after it was accepted, and a row the user excluded.
        inbox.accept(card(inbox, restated)).join()
        sql("UPDATE txn SET state = 'REJECTED' WHERE id = $restated")
        inbox.undo().join()
        inbox.accept(card(inbox, excluded)).join()
        sql("UPDATE txn SET is_excluded = 1, exclusion_reason = 'USER' WHERE id = $excluded")
        inbox.undo().join()

        assertEquals(
            "Undo wrote over a row whose state another writer changed after the answer",
            TxnState.REJECTED to null,
            dao.byId(restated)!!.let { it.state to it.pendingReason },
        )
        assertEquals(
            "Undo wrote over a row another writer excluded after the answer",
            Triple(TxnState.COMMITTED, null, true),
            dao.byId(excluded)!!.let { Triple(it.state, it.pendingReason, it.isExcluded) },
        )
        assertEquals("A refused Undo brought a card back", emptyList<ReviewCard>(), inbox.cards)
    }

    @Test fun anAnswerThatWritesNothingKeepsTheUndoOnOffer() = runBlocking {
        val dao = context.freshLedger()
        val answered = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val moved = dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 1))
        val inbox = inbox()
        val drawn = card(inbox, moved)
        inbox.accept(card(inbox, answered)).join()
        sql("UPDATE txn SET pending_reason = 'MERCHANT_MISSING' WHERE id = $moved")

        // Writes nothing: not a later answer, so it replaces nothing (§9.2).
        inbox.accept(drawn).join()
        inbox.undo().join()

        assertEquals(
            "An answer that wrote nothing took away the Undo of the answer before it",
            TxnState.PENDING to PendingReason.OVER_THRESHOLD,
            dao.byId(answered)!!.let { it.state to it.pendingReason },
        )
    }

    @Test fun theCardsAreNewestFirstWithTheirNotificationVerbatimAndCounted() = runBlocking {
        val dao = context.freshLedger()
        val captures = Databases.rawCaptureDao(context)
        val withBig = captures.insert(capture("h1", title = "Maybank2u", text = "short", bigText = "You've paid RM650.00\nto KEDAI"))
        val withoutBig = captures.insert(capture("h2", title = "MAE", text = "RM12.00 paid", bigText = null))
        val oldest = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON).copy(rawCaptureId = withBig))
        val newest = dao.insert(pending(PendingReason.MERCHANT_MISSING, at = NOON + 2).copy(rawCaptureId = withoutBig))
        val middle = dao.insert(pending(PendingReason.TRANSFER_SUSPECT, at = NOON + 1))
        dao.insert(ledgerTxn(amountSen = 100L, occurredAt = NOON + 3))

        val inbox = inbox()
        val cards = inbox.cards!!

        assertEquals("The cards are not newest first", listOf(newest, middle, oldest), cards.map { it.txn.id })
        assertEquals("The inbox counts something other than the rows awaiting review", 3, cards.size)
        assertEquals("The list's count disagrees with the inbox's", 3, dao.awaitingReviewCount())
        val big = cards.single { it.txn.id == oldest }
        assertEquals("The card's title is not the capture's", "Maybank2u", big.title)
        assertEquals("A card with big_text did not show it verbatim", "You've paid RM650.00\nto KEDAI", big.body)
        assertEquals("A card with no big_text did not show its text", "RM12.00 paid", cards.single { it.txn.id == newest }.body)
        assertEquals("The card's category is not its row's", "Uncategorized", cards.single { it.txn.id == middle }.category)
    }

    @Test fun answersSurviveAnExportAndAnImport() = runBlocking {
        val dao = context.freshLedger()
        // Real captures: the import refuses a transaction whose capture is
        // not in the file.
        val captures = Databases.rawCaptureDao(context)
        val accepted = dao.insert(
            pending(PendingReason.OVER_THRESHOLD, at = NOON)
                .copy(rawCaptureId = captures.insert(capture("a", title = "MAE", text = "RM650.00 paid", bigText = null))),
        )
        val rejected = dao.insert(
            pending(PendingReason.RULE_REVIEW, at = NOON + 1)
                .copy(rawCaptureId = captures.insert(capture("b", title = "MAE", text = "RM650.00 paid", bigText = null))),
        )
        val inbox = inbox()
        inbox.accept(card(inbox, accepted)).join()
        inbox.reject(card(inbox, rejected)).join()

        Databases.reset()
        val bytes = DatabaseFactory.build(context).let { db -> try { exportBytes(db) } finally { db.close() } }
        val restored = freshDatabase(context)
        try {
            ImportJson.read(restored, ByteArrayInputStream(bytes))
            val rows = restored.txnDao().pageFrom(0L, 10).associateBy { it.id }
            assertEquals(
                "An accepted answer did not survive the round trip",
                TxnState.COMMITTED to null,
                rows.getValue(accepted).let { it.state to it.pendingReason },
            )
            assertEquals(
                "A rejected answer did not survive the round trip",
                TxnState.REJECTED to null,
                rows.getValue(rejected).let { it.state to it.pendingReason },
            )
        } finally {
            restored.close()
        }
    }

    @Test fun nothingIsAnsweredDuringARestoreAndAnswersWorkAfterwards() = runBlocking {
        val dao = context.freshLedger()
        val held = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val inbox = inbox()
        val drawn = card(inbox, held)

        // Held before it touches anything; a document that will not open then
        // ends it with the ledger as it was.
        holdWhile(Operation.RESTORE, inbox) { requester, beforeStart ->
            Transfers.restoreFrom(context, open = { null }, requester = requester, beforeStart = beforeStart)
        }.use {
            withTimeout(ANSWER_BUDGET) { inbox.accept(drawn).join() }
        }

        assertEquals("An answer during a restore was written", TxnState.PENDING, dao.byId(held)!!.state)
        assertEquals("The inbox still says a restore is running after it ended", null, inbox.busy.value)
        inbox.accept(drawn).join()
        assertEquals("Accept did nothing once the restore had ended", TxnState.COMMITTED, dao.byId(held)!!.state)
    }

    @Test fun nothingIsAnsweredDuringADeleteAndAnswersWorkAfterwards() = runBlocking {
        val dao = context.freshLedger()
        val held = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val inbox = inbox()
        val drawn = card(inbox, held)

        // Held before it touches anything; the requester then goes away, so
        // the delete never starts.
        val requester = Job()
        holdWhile(Operation.DELETE, inbox, requester) { r, beforeStart ->
            Transfers.deleteEverything(context, requester = r, beforeStart = beforeStart)
        }.use {
            withTimeout(ANSWER_BUDGET) { inbox.reject(drawn).join() }
            requester.cancel()
        }

        assertEquals("An answer during a delete was written", TxnState.PENDING, dao.byId(held)!!.state)
        assertEquals("The inbox still says a delete is running after it ended", null, inbox.busy.value)
        inbox.reject(drawn).join()
        assertEquals("Not a payment did nothing once the delete had ended", TxnState.REJECTED, dao.byId(held)!!.state)
    }

    @Test fun aDeleteEverythingWithdrawsTheUndoAndItsRowIdNowNamesAnotherPayment() = runBlocking {
        val dao = context.freshLedger()
        val held = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val inbox = inbox()
        inbox.accept(card(inbox, held)).join()

        Transfers.deleteEverything(context, requester = Job()).await()
        // A new file restarts the ids, so this payment is the answered row's id.
        val other = Databases.txnDao(context).insert(
            ledgerTxn(amountSen = 1_234L, occurredAt = NOON, categoryId = uncategorized()),
        )
        assertEquals("precondition: the delete did not restart the ids", held, other)

        assertNull(
            "Undo was still offered after a delete-everything replaced the ledger it answered",
            withTimeoutOrNull(TIMEOUT) { inbox.undoable.first { it == null } }.let { inbox.undoable.value },
        )
        inbox.undo().join()
        assertEquals(
            "Undo after a delete-everything sent an unrelated payment with the answered row's id back to review",
            TxnState.COMMITTED to null,
            Databases.txnDao(context).byId(other)!!.let { it.state to it.pendingReason },
        )
    }

    @Test fun aDeleteStartingWithdrawsTheUndoEvenWhenItNeverRuns() = runBlocking {
        val dao = context.freshLedger()
        val held = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val inbox = inbox()
        inbox.accept(card(inbox, held)).join()

        // Held before it touches anything; the requester then goes away, so
        // the delete never starts and `Databases.rewrites` never moves.
        val rewrites = Databases.rewrites.value
        val requester = Job()
        holdWhile(Operation.DELETE, inbox, requester) { r, beforeStart ->
            Transfers.deleteEverything(context, requester = r, beforeStart = beforeStart)
        }.use {
            assertNull(
                "Undo was still offered while a delete-everything ran",
                withTimeoutOrNull(TIMEOUT) { inbox.undoable.first { it == null } }.let { inbox.undoable.value },
            )
            requester.cancel()
        }
        assertEquals("precondition: the held delete replaced the ledger", rewrites, Databases.rewrites.value)

        assertNull("Undo came back after the delete was called off", inbox.undoable.value)
        inbox.undo().join()
        assertEquals("An Undo withdrawn by a delete still wrote", TxnState.COMMITTED, dao.byId(held)!!.state)
    }

    @Test fun aReplacedLedgerWithdrawsTheUndo() = runBlocking {
        val dao = context.freshLedger()
        val held = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON))
        val inbox = inbox()
        inbox.accept(card(inbox, held)).join()

        // What a delete or a restore announces once done, with no transfer
        // running to say so beforehand.
        val rewrites = Databases.rewrites.value
        Databases.reset()
        assertTrue("precondition: the reset did not move rewrites", Databases.rewrites.value != rewrites)

        assertNull(
            "Undo was still offered after the ledger was replaced",
            withTimeoutOrNull(TIMEOUT) { inbox.undoable.first { it == null } }.let { inbox.undoable.value },
        )
        inbox.undo().join()
        assertEquals(
            "An Undo withdrawn by a replaced ledger still wrote",
            TxnState.COMMITTED,
            Databases.txnDao(context).byId(held)!!.state,
        )
    }

    // ---- fixtures ------------------------------------------------------

    /** [my.pinged.ledger.holdWhile] on the inbox's own busy flow. */
    private suspend fun holdWhile(
        operation: Operation,
        inbox: ReviewInbox,
        requester: Job = Job(),
        start: (Job, suspend () -> Unit) -> Unit,
    ): AutoCloseable = holdWhile(operation, inbox.busy, releases, requester, start)

    private fun categoryId(name: String): Long = Databases.categoryDao(context).all().single { it.name == name }.id

    private fun capture(hash: String, title: String, text: String, bigText: String?) = RawCapture(
        sourcePackage = "com.maybank2u.life",
        postedAt = NOON,
        whenMillis = null,
        capturedAt = NOON,
        sbnKey = "0|com.maybank2u.life|1|null|0|$hash",
        notifId = 1,
        notifTag = null,
        userHandle = 0,
        channelId = "txn",
        flags = 0,
        arrival = Arrival.POSTED,
        title = title,
        text = text,
        bigText = bigText,
        subText = null,
        extrasJson = null,
        contentHash = hash,
        parseStatus = ParseStatus.MATCHED,
    )

    private companion object {
        /** An answer refused rather than queued returns at once; a queued one waits for the hold. */
        const val ANSWER_BUDGET = 5_000L
    }
}
