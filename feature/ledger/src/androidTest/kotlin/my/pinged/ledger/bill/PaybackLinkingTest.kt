package my.pinged.ledger.bill

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.detail.DetailViewModel
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.entry.ManualDraft
import my.pinged.ledger.entry.ManualEntries
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.refreshed
import my.pinged.ledger.review.card
import my.pinged.ledger.review.inbox
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.4's linking screens against the real encrypted database (#392,
 * step 4), driven through the holders the screens draw -- the inbox, the
 * detail screen's view model and manual entry -- so what is asserted is what
 * a tap does: the link written or refused, and the screen's read after it.
 */
@RunWith(AndroidJUnit4::class)
class PaybackLinkingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    // ---- the review inbox -------------------------------------------------

    @Test fun aCardsPickLinksTheRowAndTheCardNamesTheBillWithoutBeingAnswered() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 9_600L, occurredAt = SEP_18_NIGHT))
        val held = dao.insert(heldRefund())
        val inbox = inbox()

        val offer = inbox.bills(card(inbox, held), "") as BillOffer.Recent
        assertEquals("the dinner for three did not rank first", listOf(bill), offer.multiples.map { it.txn.id })

        inbox.pickBill(card(inbox, held), bill).join()

        assertEquals(bill, dao.byId(held)!!.billTxnId)
        assertEquals("the pick answered the card", TxnState.PENDING, dao.byId(held)!!.state)
        assertEquals("the card did not read the bill back", bill, card(inbox, held).bill?.txn?.id)
        assertNull("a pick was offered for Undo", inbox.undoable.value)

        inbox.accept(card(inbox, held)).join()
        assertEquals("Accept dropped the bill", bill, dao.byId(held)!!.billTxnId)
        assertEquals("the share is not the dinner less the payback", 6_400L, dao.billChoice(bill)!!.shareSen)
    }

    @Test fun acceptAllCommitsACardWithNoBillAsPlainMoneyReceived() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 9_600L, occurredAt = SEP_18_NIGHT))
        val linked = dao.insert(heldRefund())
        val plain = dao.insert(heldRefund())
        val inbox = inbox()
        inbox.pickBill(card(inbox, linked), bill).join()

        inbox.acceptAll().join()

        assertEquals(listOf(TxnState.COMMITTED, TxnState.COMMITTED), listOf(linked, plain).map { dao.byId(it)!!.state })
        assertEquals("Accept all dropped a pick", bill, dao.byId(linked)!!.billTxnId)
        assertNull("Accept all gave a card a bill", dao.byId(plain)!!.billTxnId)
        assertEquals("the share holds anything but the linked payback", 6_400L, dao.billChoice(bill)!!.shareSen)
    }

    @Test fun noBillOnACardTakesThePickOff() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 9_600L, occurredAt = SEP_18_NIGHT))
        val held = dao.insert(heldRefund())
        val inbox = inbox()
        inbox.pickBill(card(inbox, held), bill).join()

        inbox.pickBill(card(inbox, held), null).join()

        assertNull(dao.byId(held)!!.billTxnId)
        assertNull(card(inbox, held).bill)
    }

    @Test fun aPickOnACardThatMovedWritesNothingAndTheCardReadsAgain() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 9_600L, occurredAt = SEP_18_NIGHT))
        val held = dao.insert(heldRefund())
        val inbox = inbox()
        val drawn = card(inbox, held)
        Databases.shared(context).openHelper.writableDatabase.execSQL("UPDATE txn SET pending_reason = 'OVER_THRESHOLD' WHERE id = $held")

        inbox.pickBill(drawn, bill).join()

        assertNull(dao.byId(held)!!.billTxnId)
        assertEquals("the card was not read again", PendingReason.OVER_THRESHOLD, card(inbox, held).txn.pendingReason)
    }

    // ---- the detail screen ------------------------------------------------

    @Test fun notAPaybackUnlinksAndItsUndoLinksAgain() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 9_600L, occurredAt = SEP_18_NIGHT))
        val payback = dao.insert(ledgerTxn(amountSen = 3_200L, occurredAt = SEP_20_NOON).copy(direction = Direction.REFUND))
        assertEquals(1, dao.setBill(payback, bill, SEP_20_NOON))
        val detail = DetailViewModel(context.applicationContext as Application, payback, dao.byId(payback)!!.createdAt)
        withTimeout(TIMEOUT) { detail.read.refreshed() }
        assertEquals(bill, detail.read.state.value.answer?.bill?.txn?.id)

        detail.notAPayback().join()

        assertNull(dao.byId(payback)!!.billTxnId)
        assertNull("the screen still names the bill", detail.read.state.value.answer?.bill)
        assertEquals("no Undo was offered", bill, detail.unlinked.value)

        detail.undo().join()

        assertEquals(bill, dao.byId(payback)!!.billTxnId)
        assertNull("Undo stayed on offer once used", detail.unlinked.value)
    }

    @Test fun theDetailsPickIsHeldToTheBillsGuards() = runBlocking {
        val dao = context.freshLedger()
        val later = dao.insert(ledgerTxn(amountSen = 9_600L, occurredAt = SEP_20_NOON + DAY))
        val refund = dao.insert(ledgerTxn(amountSen = 3_200L, occurredAt = SEP_20_NOON).copy(direction = Direction.REFUND))
        val detail = DetailViewModel(context.applicationContext as Application, refund, dao.byId(refund)!!.createdAt)
        withTimeout(TIMEOUT) { detail.read.refreshed() }

        detail.pickBill(later).join()

        assertNull(dao.byId(refund)!!.billTxnId)
    }

    // ---- manual entry -----------------------------------------------------

    @Test fun moneyReceivedSavedWithABillIsAPaybackOfIt() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 6_000L, occurredAt = SEP_18_NIGHT))

        val id = entries().save(ManualDraft(amountSen = 2_000L, direction = Direction.REFUND, bill = bill)).await()

        assertEquals(bill, dao.byId(id!!)!!.billTxnId)
    }

    @Test fun aPaymentSavedWithAHiddenPickIsNoPayback() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 6_000L, occurredAt = SEP_18_NIGHT))

        val id = entries().save(ManualDraft(amountSen = 2_000L, direction = Direction.EXPENSE, bill = bill)).await()

        assertNull(dao.byId(id!!)!!.billTxnId)
    }

    @Test fun moneyReceivedDatedBeforeItsBillIsNotSaved() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 6_000L, occurredAt = SEP_20_NOON + DAY))
        val before = dao.countAll()

        assertNull(entries().save(ManualDraft(amountSen = 2_000L, direction = Direction.REFUND, bill = bill)).await())
        assertEquals(before, dao.countAll())
    }

    @Test fun aTypedPaybackSwitchedToPaidLosesItsBillAndOpensWithIt() = runBlocking {
        val dao = context.freshLedger()
        val bill = dao.insert(ledgerTxn(amountSen = 6_000L, occurredAt = SEP_18_NIGHT))
        val entries = entries()
        val id = entries.save(ManualDraft(amountSen = 2_000L, direction = Direction.REFUND, bill = bill)).await()!!
        val target = EditTarget(id, dao.byId(id)!!.createdAt)
        val opened = entries.load(id).await() as my.pinged.ledger.entry.Loaded.Entry
        assertEquals("a typed payback opened without its bill", bill, opened.draft.bill)

        assertTrue(entries.update(target, opened.draft.copy(direction = Direction.EXPENSE)).await())

        val stored = dao.byId(id)!!
        assertEquals(Direction.EXPENSE, stored.direction)
        assertNull(stored.billTxnId)
    }

    @Test fun aTypedBillOpensWithItsEarliestPaybacksDay() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()
        val bill = entries.save(ManualDraft(amountSen = 6_000L, day = SEP_18)).await()!!
        entries.save(ManualDraft(amountSen = 2_000L, direction = Direction.REFUND, bill = bill)).await()

        val opened = entries.load(bill).await() as my.pinged.ledger.entry.Loaded.Entry

        assertNotNull(dao.byId(bill))
        assertEquals(SEP_20, opened.earliestPayback)
    }

    private fun entries() = ManualEntries(context = { context }, scope = scope, clock = { SEP_20_NOON })

    private fun heldRefund() = ledgerTxn(
        amountSen = 3_200L,
        occurredAt = SEP_20_NOON,
        state = TxnState.PENDING,
        pendingReason = PendingReason.RULE_REVIEW,
    ).copy(direction = Direction.REFUND)

    private companion object {
        val SEP_18: LocalDate = LocalDate.of(2026, 9, 18)
        val SEP_20: LocalDate = LocalDate.of(2026, 9, 20)
        val SEP_18_NIGHT: Long = SEP_18.atTime(19, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val SEP_20_NOON: Long = SEP_20.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        const val DAY = 86_400_000L
    }
}
