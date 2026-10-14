package my.pinged.ledger.day

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import my.pinged.data.Databases
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.home.REMOVE_CONFIRM
import my.pinged.ledger.home.REMOVE_HEADING
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a payment on a captured row already counted (#132), through the real
 * `LedgerRow` on the `Day` screen, which the Ledger shares.
 */
@RunWith(AndroidJUnit4::class)
class RemoveCapturedRowTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun confirmingRejectsTheRowAndTheListDropsIt() {
        val dao = context.freshLedger()
        val id = dao.insert(captured())
        screen()

        waitFor(MERCHANT)
        compose.onNodeWithText(MERCHANT).performScrollTo().performClick()
        waitFor(REMOVE_HEADING)
        assertEquals("The tap wrote before it was confirmed", TxnState.COMMITTED, dao.byId(id)?.state)

        compose.onNodeWithText(REMOVE_CONFIRM).performClick()

        val gone = runCatching {
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(MERCHANT)).fetchSemanticsNodes().isEmpty() }
        }.isSuccess
        assertTrue("The removed payment is still on the list", gone)
        assertEquals(TxnState.REJECTED, dao.byId(id)?.state)
        assertEquals("The capture is the evidence and must survive", true, dao.byId(id)?.rawCaptureId != null)
    }

    @Test fun aRowStillAwaitingReviewOffersNoRemoval() {
        val dao = context.freshLedger()
        dao.insert(captured(state = TxnState.PENDING, reason = PendingReason.OVER_THRESHOLD))
        screen()

        waitFor(MERCHANT)
        compose.onNodeWithText(MERCHANT).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText(REMOVE_HEADING).assertCountEquals(0)
    }

    private fun captured(state: TxnState = TxnState.COMMITTED, reason: PendingReason? = null): Txn =
        ledgerTxn(
            amountSen = 1_250L,
            occurredAt = NOON,
            categoryId = Databases.categoryDao(context).requireUncategorizedId(),
            state = state,
            pendingReason = reason,
        ).copy(merchantRaw = MERCHANT, merchantDisplay = MERCHANT, merchantKey = MERCHANT.uppercase())

    private fun screen() {
        val viewModel = DayViewModel(context.applicationContext as Application, DAY)
        compose.setContent { PingedTheme { DayScreen(viewModel, onBack = {}) } }
    }

    private fun waitFor(text: String) {
        val appeared = runCatching { compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() } }.isSuccess
        assertTrue("Waited ${TIMEOUT}ms for '$text'", appeared)
    }

    private companion object {
        val DAY: LocalDate = LocalDate.now().minusDays(3)
        val NOON: Long = DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        const val MERCHANT = "Kopi Pagi"
    }
}
