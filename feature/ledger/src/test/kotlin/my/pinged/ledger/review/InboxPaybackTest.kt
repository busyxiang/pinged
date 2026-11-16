package my.pinged.ledger.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.Seed
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.bill.BillOffer
import my.pinged.ledger.bill.NO_BILL_PICKED
import my.pinged.ledger.bill.PAYS_BACK
import my.pinged.ledger.bill.SEP_18
import my.pinged.ledger.bill.WHICH_BILL
import my.pinged.ledger.bill.billChoice
import my.pinged.ledger.bill.billRowTag
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import my.pinged.read.LedgerAccess
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A received-money card's `PAYS BACK` (spec 9.2, `design/InboxPayback.dc.html`)
 * through [ReviewInboxScreenContent] with no database: which cards offer a
 * bill, the line before and after a pick, and that a pick is reported as a
 * pick and never as an answer.
 */
@RunWith(AndroidJUnit4::class)
class InboxPaybackTest {
    @get:Rule val compose = createComposeRule()

    private val answers = mutableListOf<ReviewAnswer>()
    private val picks = mutableListOf<Pair<Long, Long?>>()
    private var cards by mutableStateOf(listOf(arjun))

    private fun content() {
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = cards,
                    ledger = LedgerAccess.Readable,
                    onBack = {},
                    onAnswer = { _, answer -> answers += answer },
                    bills = { _, _ -> BillOffer.Recent(multiples = listOf(yuenKee), rest = emptyList()) },
                    onPickBill = { card, bill -> picks += card.txn.id to bill },
                )
            }
        }
    }

    @Test fun onlyAReceivedMoneyCardOffersABill() {
        val merchantRefund = arjun.copy(txn = arjun.txn.copy(id = 2L), matchedRuleId = "mae-refund-my-v1")
        val payment = arjun.copy(txn = arjun.txn.copy(id = 3L, direction = Direction.EXPENSE))
        assertEquals(listOf(true, false, false), listOf(arjun, merchantRefund, payment).map { it.offersBill })

        cards = listOf(merchantRefund, payment)
        content()

        compose.onAllNodesWithTag(PAYS_BACK_LINE).assertCountEquals(0)
        compose.onNodeWithText(COUNTS_AGAINST).performScrollTo()
    }

    @Test fun noBillReadsNoneInInkAboveTheCategoryLine() {
        content()

        compose.onNodeWithText(PAYS_BACK).performScrollTo()
        compose.onNodeWithText(NO_BILL_PICKED).assertExists()
        compose.onNodeWithText(COUNTS_AGAINST).assertExists()
        compose.onNodeWithText(CHOOSE_A_CATEGORY).assertExists()
        compose.onAllNodesWithTag(BILL_LINE).assertCountEquals(0)
    }

    @Test fun aCountedBillTakesTheCategoryLinesPlaceWithOneLine() {
        cards = listOf(arjun.copy(bill = yuenKee))
        content()

        compose.onNodeWithTag(BILL_LINE).performScrollTo()
        compose.onNodeWithText("Counts against Food & Drinks on 18 Sep. Your share of Yuen Kee becomes RM32.00.").assertExists()
        compose.onAllNodesWithText(COUNTS_AGAINST).assertCountEquals(0)
        compose.onAllNodesWithText(NO_BILL_PICKED).assertCountEquals(0)
    }

    @Test fun aBillThatStoppedCountingAsksTheCategoryAgain() {
        cards = listOf(arjun.copy(bill = billChoice(7L, "Yuen Kee", 9_600L, SEP_18, excluded = true)))
        content()

        compose.onNodeWithText(COUNTS_AGAINST).performScrollTo()
        compose.onAllNodesWithTag(BILL_LINE).assertCountEquals(0)
    }

    @Test fun aPickIsReportedAndIsNotAnAnswer() {
        content()

        compose.onNodeWithTag(PAYS_BACK_LINE).performScrollTo().performClick()
        compose.onNodeWithText(WHICH_BILL).assertExists()
        compose.onNodeWithTag(billRowTag(yuenKee.txn.id)).performClick()

        assertEquals(listOf(ARJUN to yuenKee.txn.id), picks)
        assertTrue("the pick answered the card: $answers", answers.isEmpty())
        compose.onAllNodesWithText(WHICH_BILL).assertCountEquals(0)
    }

    @Test fun picksTheSameBillAgainWritesNothing() {
        cards = listOf(arjun.copy(bill = yuenKee))
        content()

        compose.onNodeWithTag(PAYS_BACK_LINE).performScrollTo().performClick()
        compose.onNodeWithTag(billRowTag(yuenKee.txn.id)).performClick()

        assertTrue(picks.isEmpty())
    }

    private companion object {
        const val ARJUN = 52L

        val yuenKee = billChoice(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 3_200L)

        val arjun = ReviewCard(
            txn = ledgerTxn(
                amountSen = 3_200L,
                occurredAt = NOON,
                state = TxnState.PENDING,
                pendingReason = PendingReason.RULE_REVIEW,
                id = ARJUN,
            ).copy(direction = Direction.REFUND, merchantRaw = "SAMPLE PAYER NAME", merchantDisplay = "Sample Payer Name"),
            category = Seed.UNCATEGORIZED,
            title = "Touch 'n Go eWallet",
            body = "RM 32.00 received from SAMPLE PAYER NAME for SAMPLE USER.",
            matchedRuleId = "tng-received-v1",
        )
    }
}
