package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.StandingAnswer
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.3's standing answers as stage two applies them, through the bundled
 * pack's three rules that read transfers and the real encrypted database.
 *
 * Each test first reads one capture with no answer, as the card the user
 * answered was, then stores the answer the inbox's Always stores -- the
 * capture's rule, app and `txn.merchant_key` -- and reads the next.
 *
 * The suite shares one ledger, so every amount is [ParseFixtures.uniqueAmount]
 * or its own, every counterparty this run's [tag], and [forgetAnswers] removes
 * every answer: one left behind would leave out another class's reload.
 */
@RunWith(AndroidJUnit4::class)
class StandingAnswerStageTwoTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val answers = Databases.standingAnswerDao(context)

    private val marker = "ZZ" + System.nanoTime()

    /** Letters only: `Merchant.clean` is free to drop digits from a counterparty. */
    private val tag = System.nanoTime().toString().map { 'A' + (it - '0') }.joinToString("")
    private var at = System.currentTimeMillis() - 90_000_000L

    @Before fun prepare() = ParseFixtures.prepare(context)

    @After fun forgetAnswers() = answers.all().forEach { answers.delete(it.id) }

    /** "None" matches only "none": an answer with a counterparty does not cover a reload, and back. */
    @Test fun anAnswerForNoCounterpartyMatchesOnlyNone() {
        always(read(transferTo("RAY $tag")))

        assertHeld("a reload, which reads no counterparty", PendingReason.TRANSFER_SUSPECT, read(reload()))
    }

    /** The answer names the rule and the app: another rule of the same app is still asked about. */
    @Test fun anAnswerForOneRuleDoesNotAnswerAnotherOfTheSameApp() {
        always(read(cardBill()))

        assertHeld("a transfer read by mae-transferred-v1", PendingReason.TRANSFER_SUSPECT, read(transferTo("RAY $tag")))
    }

    @Test fun aCardAlreadyAwaitingReviewStaysWhenTheAnswerIsMade() {
        val waiting = read(reload())
        always(waiting)

        ParseFixtures.pass(context).run()

        assertEquals("a card already in the inbox was settled", waiting, Databases.txnDao(context).byId(waiting.id))
    }

    @Test fun deletingTheAnswerSendsTheNextCaptureBackToReviewAndChangesNoRow() {
        always(read(reload()))
        val leftOut = read(reload())
        assertLeftOut("precondition", ExclusionReason.TRANSFER, leftOut)

        answers.delete(answers.all().single().id)

        assertHeld("a reload after the answer was deleted", PendingReason.TRANSFER_SUSPECT, read(reload()))
        assertEquals("deleting the answer changed a row it wrote", leftOut, Databases.txnDao(context).byId(leftOut.id))
    }

    /** Spec 6.4's merge: the answer stays on the exact key it was made for. */
    @Test fun mergingTheCounterpartyIntoAnotherDoesNotMoveTheAnswer() {
        val answered = read(transferTo("RAY $tag"))
        always(answered)
        val other = read(transferTo("ALI $tag"))
        Databases.merchantIdentityDao(context).merge(source = answered.merchantKey!!, target = other.merchantKey!!, targetName = "Ali")

        assertEquals("the answer's key moved", answered.merchantKey, answers.all().single().merchantKey)
        assertHeld("a transfer to the merchant merged into", PendingReason.TRANSFER_SUSPECT, read(transferTo("ALI $tag")))
        assertLeftOut("a transfer to the counterparty answered", ExclusionReason.TRANSFER, read(transferTo("RAY $tag")))
    }

    @Test fun chartsTransfersLineCountsTheRowsAnAnswerWrote() {
        always(read(reload()))
        val day = LocalDates.of(at + STEP)
        val before = transfersOn(day)

        val leftOut = read(reload())

        assertEquals("precondition: the reload is that day's", day, leftOut.localDate)
        assertEquals("Charts' \"Transfers, left out\"", before + leftOut.amountSen, transfersOn(day))
    }

    // ---- fixtures ------------------------------------------------------

    private fun transfersOn(day: my.pinged.data.LocalDate): Long =
        Databases.txnDao(context).notInTotal(day, day)
            .filter { it.line == NotInTotalLine.TRANSFERS && it.currency == "MYR" }.sumOf { it.netSen }

    /** Inserts [capture], runs stage two, and returns the transaction it wrote. */
    private fun read(capture: Long): Txn {
        ParseFixtures.pass(context).run()
        return requireNotNull(ParseFixtures.txnForCapture(context, capture)) { "capture $capture wrote no transaction" }
    }

    /** What the inbox's Always stores for [card]'s row. */
    private fun always(card: Txn) {
        val capture = captures.byId(card.rawCaptureId!!)
        answers.answer(StandingAnswer(matchedRuleId = capture.matchedRuleId!!, sourcePackage = capture.sourcePackage, merchantKey = card.merchantKey, createdAt = at))
    }

    private fun reload(rm: String = ParseFixtures.uniqueAmount().second, postedAt: Long = next()): Long =
        ParseFixtures.insertCapture(context, text = "Reload of $rm was successful", title = marker, sbnKey = "$marker-$postedAt", postedAt = postedAt)

    private fun cardBill(): Long {
        val postedAt = next()
        return ParseFixtures.insertCapture(
            context,
            text = "Successful payment of ${spaced()} to Maybank Master Card. REF:000000000M",
            title = marker, pkg = ParseFixtures.MAE, sbnKey = "$marker-$postedAt", postedAt = postedAt,
        )
    }

    private fun transferTo(name: String): Long {
        val postedAt = next()
        return ParseFixtures.insertCapture(
            context,
            text = "You've transferred ${spaced()} to $name. REF: 000000000Q.",
            title = marker, pkg = ParseFixtures.MAE, sbnKey = "$marker-$postedAt", postedAt = postedAt,
        )
    }

    /** MAE's shape: `RM 28.77`. */
    private fun spaced() = ParseFixtures.uniqueAmount().second.replace("RM", "RM ")

    /** Each capture an hour after the last, outside both duplicate windows. */
    private fun next(): Long = (at + STEP).also { at = it }

    private fun assertLeftOut(what: String, reason: ExclusionReason, txn: Txn) = assertEquals(
        "$what was not written as Leave out writes it",
        listOf(TxnState.COMMITTED, null, true, reason),
        listOf(txn.state, txn.pendingReason, txn.isExcluded, txn.exclusionReason),
    )

    private fun assertHeld(what: String, reason: PendingReason, txn: Txn) = assertEquals(
        "$what was not held for review as it is with no answer",
        listOf(TxnState.PENDING, reason, false, null),
        listOf(txn.state, txn.pendingReason, txn.isExcluded, txn.exclusionReason),
    )

    private companion object {
        const val STEP = 60 * 60_000L
    }
}
