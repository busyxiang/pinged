package my.pinged.ledger.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.transfer.ImportJson
import my.pinged.ledger.transfer.exportBytes
import my.pinged.ledger.transfer.freshDatabase
import my.pinged.parse.Confidence
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.3's transfer card answered from the review inbox (#115), against the
 * real encrypted database: Leave out writes the exclusion reason the pack's
 * rule suggests, re-derived from the capture because parsing never stored it;
 * Keep as expense counts the row; both are undone in full; a left-out row is
 * out of every total, on the transfers line, and survives an export and an
 * import.
 *
 * Driven through [ReviewViewModel.answer], the dispatch a card's button
 * calls, and its [ReviewInbox].
 */
@RunWith(AndroidJUnit4::class)
class LeaveOutTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun leaveOutWritesTheReasonThePackRuleSuggestsAndTransferWhenTheRuleIsGone() = runBlocking {
        val dao = context.freshLedger()
        val reload = dao.insert(transfer(capturedBy(TNG, "tng-reload-v1", "r1"), at = NOON))
        val cardBill = dao.insert(transfer(capturedBy(MAE, "mae-card-bill-v1", "r2"), at = NOON + 1))
        // A Maybank capture, so a fallback read off the package rather than
        // off the missing rule would say CARD_PAYMENT.
        val gone = dao.insert(transfer(capturedBy(MAE, "mae-card-bill-v0", "r3"), at = NOON + 2))
        val before = dao.byId(reload)!!
        val inbox = inbox()

        for (id in listOf(reload, cardBill, gone)) answer(inbox, id, ReviewAnswer.LEAVE_OUT).join()

        assertEquals(
            "Leave out on a tng-reload-v1 capture did not write the rule's TRANSFER",
            ExclusionReason.TRANSFER,
            dao.byId(reload)!!.exclusionReason,
        )
        assertEquals(
            "Leave out on a mae-card-bill-v1 capture did not write the rule's CARD_PAYMENT",
            ExclusionReason.CARD_PAYMENT,
            dao.byId(cardBill)!!.exclusionReason,
        )
        assertEquals(
            "Leave out on a capture whose rule is no longer in the pack did not fall back to TRANSFER",
            ExclusionReason.TRANSFER,
            dao.byId(gone)!!.exclusionReason,
        )
        val after = dao.byId(reload)!!
        assertEquals(
            "Leave out wrote something other than COMMITTED, no reason, excluded with its reason (and updated_at)",
            before.copy(
                state = TxnState.COMMITTED,
                pendingReason = null,
                isExcluded = true,
                exclusionReason = ExclusionReason.TRANSFER,
                updatedAt = after.updatedAt,
            ),
            after,
        )
        assertEquals("A left-out card is still in the inbox", emptyList<ReviewCard>(), inbox.cards.value)
    }

    @Test fun aLeftOutRowIsOutOfEveryTotalAndOnTheTransfersLineAndKeepAsExpenseCountsIt() = runBlocking {
        val dao = context.freshLedger()
        val committed = dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = NOON))
        val leftOut = dao.insert(transfer(capturedBy(TNG, "tng-reload-v1", "r1"), at = NOON + 1, amountSen = 10_000L))
        val kept = dao.insert(transfer(capturedBy(TNG, "tng-reload-v1", "r2"), at = NOON + 2, amountSen = 2_000L))
        val inbox = inbox()

        answer(inbox, leftOut, ReviewAnswer.LEAVE_OUT).join()
        answer(inbox, kept, ReviewAnswer.KEEP_AS_EXPENSE).join()

        val keptRow = dao.byId(kept)!!
        assertEquals(
            "Keep as expense did not count the row as it stands",
            Triple(TxnState.COMMITTED, null, false),
            Triple(keptRow.state, keptRow.pendingReason, keptRow.isExcluded),
        )
        // RM50.00 committed plus the RM20.00 kept; the RM100.00 left out in none.
        assertEquals("The day total", listOf(7_000L), dao.dayTotals(DAY, DAY).map { it.netSen })
        assertEquals("The month total", listOf(7_000L), dao.monthTotals(DAY, DAY).map { it.netSen })
        assertEquals("The monthly totals", listOf(7_000L), dao.monthlyTotals(DAY, DAY).map { it.netSen })
        assertEquals(
            "The category bars",
            7_000L,
            dao.monthByCategory(DAY, DAY, limit = 10).sumOf { it.netSen },
        )
        assertEquals(
            "The top merchants",
            7_000L,
            dao.merchantTotals(DAY, DAY, limit = 10).sumOf { it.netSen },
        )
        assertEquals(
            "The left-out RM100.00 is not on Charts' transfers line, or something else is",
            listOf(NotInTotalLine.TRANSFERS to 10_000L),
            dao.notInTotal(DAY, DAY).map { it.line to it.netSen },
        )
        assertEquals(
            "The left-out row is not drawn in the day's feed, greyed",
            listOf(kept to false, leftOut to true),
            dao.dayRows(DAY).filter { it.txn.id != committed }
                .map { it.txn.id to it.txn.isExcluded },
        )
    }

    @Test fun undoAfterLeaveOutOrKeepAsExpenseRestoresTheRowWithBothExclusionColumnsCleared() = runBlocking {
        val dao = context.freshLedger()
        val leftOut = dao.insert(transfer(capturedBy(MAE, "mae-card-bill-v1", "r1"), at = NOON))
        val kept = dao.insert(transfer(capturedBy(TNG, "tng-reload-v1", "r2"), at = NOON + 1))
        val beforeLeftOut = dao.byId(leftOut)!!
        val beforeKept = dao.byId(kept)!!
        val inbox = inbox()

        answer(inbox, leftOut, ReviewAnswer.LEAVE_OUT).join()
        assertEquals("precondition: Leave out did not exclude the row", true, dao.byId(leftOut)!!.isExcluded)
        inbox.undo().join()

        assertEquals(
            "Undo after Leave out did not put the row back awaiting review with is_excluded and exclusion_reason cleared",
            beforeLeftOut,
            dao.byId(leftOut)!!.copy(updatedAt = beforeLeftOut.updatedAt),
        )

        answer(inbox, kept, ReviewAnswer.KEEP_AS_EXPENSE).join()
        assertEquals("precondition: Keep as expense did not count the row", TxnState.COMMITTED, dao.byId(kept)!!.state)
        inbox.undo().join()

        assertEquals(
            "Undo after Keep as expense did not put the row back awaiting review",
            beforeKept,
            dao.byId(kept)!!.copy(updatedAt = beforeKept.updatedAt),
        )
    }

    @Test fun leaveOutAgainstARowThatMovedUnderneathWritesNothing() = runBlocking {
        val dao = context.freshLedger()
        val moved = dao.insert(transfer(capturedBy(TNG, "tng-reload-v1", "r1"), at = NOON))
        val inbox = inbox()
        val drawn = card(inbox, moved)

        // What a Corrections re-read does between the card being drawn and the tap.
        sql("UPDATE txn SET pending_reason = 'OVER_THRESHOLD' WHERE id = $moved")
        inbox.leaveOut(drawn).join()

        val row = dao.byId(moved)!!
        assertEquals(
            "Leave out wrote over a row whose reason changed after the card was drawn",
            Triple(TxnState.PENDING, PendingReason.OVER_THRESHOLD, false),
            Triple(row.state, row.pendingReason, row.isExcluded),
        )
        assertEquals("A Leave out that wrote nothing is on offer for Undo", null, inbox.undoable.value)
    }

    @Test fun aLeftOutRowSurvivesAnExportAndAnImport() = runBlocking {
        val dao = context.freshLedger()
        val leftOut = dao.insert(transfer(capturedBy(MAE, "mae-card-bill-v1", "r1"), at = NOON))
        val inbox = inbox()
        answer(inbox, leftOut, ReviewAnswer.LEAVE_OUT).join()
        val answered = dao.byId(leftOut)!!
        assertFalse("precondition: Leave out did not settle the row", answered.state == TxnState.PENDING)

        Databases.reset()
        val bytes = DatabaseFactory.build(context).let { db -> try { exportBytes(db) } finally { db.close() } }
        val restored = freshDatabase(context)
        try {
            ImportJson.read(restored, ByteArrayInputStream(bytes))
            val row = restored.txnDao().pageFrom(0L, 10).single { it.id == leftOut }
            assertEquals(
                "A left-out row did not survive the round trip",
                listOf(TxnState.COMMITTED, null, true, ExclusionReason.CARD_PAYMENT),
                listOf(row.state, row.pendingReason, row.isExcluded, row.exclusionReason),
            )
        } finally {
            restored.close()
        }
    }

    // ---- fixtures ------------------------------------------------------

    private lateinit var viewModel: ReviewViewModel

    /** [settledReview]'s holder, its view model kept for [answer]. */
    private suspend fun inbox(): ReviewInbox {
        viewModel = settledReview()
        return viewModel.inbox
    }

    /** What a tap on [answer]'s button on row [id]'s card does. */
    private fun answer(inbox: ReviewInbox, id: Long, answer: ReviewAnswer) = viewModel.answer(card(inbox, id), answer)

    /** A row the gate held as a transfer, of [capture]'s package, as parsing writes one: not excluded. */
    private fun transfer(capture: RawCapture, at: Long, amountSen: Long = 10_000L): Txn =
        ledgerTxn(
            amountSen = amountSen,
            occurredAt = at,
            state = TxnState.PENDING,
            pendingReason = PendingReason.TRANSFER_SUSPECT,
            categoryId = uncategorized(),
        ).copy(
            rawCaptureId = Databases.rawCaptureDao(context).insert(capture),
            sourcePackage = capture.sourcePackage,
            confidence = Confidence.HIGH,
        )

    /** A capture of [pkg] that parsing matched with [ruleId]. Text is irrelevant: nothing re-parses it. */
    private fun capturedBy(pkg: String, ruleId: String, hash: String) = RawCapture(
        sourcePackage = pkg,
        postedAt = NOON,
        whenMillis = null,
        capturedAt = NOON,
        sbnKey = "0|$pkg|1|null|0|$hash",
        notifId = 1,
        notifTag = null,
        userHandle = 0,
        channelId = "txn",
        flags = 0,
        arrival = Arrival.POSTED,
        title = "Constructed",
        text = "RM100.00",
        bigText = null,
        subText = null,
        extrasJson = null,
        contentHash = hash,
        parseStatus = ParseStatus.MATCHED,
        matchedRuleId = ruleId,
    )

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
    }
}
