package my.pinged.ledger.review

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.SOURCE_LABEL
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a card of spec 9.2's inbox draws and what its buttons report, through
 * [ReviewInboxScreenContent] with no database: each kind of card, the inbox
 * that empties while open, and the line that disables every answer while a
 * restore or a delete runs.
 */
@RunWith(AndroidJUnit4::class)
class ReviewInboxScreenTest {
    @get:Rule val compose = createComposeRule()

    private val answers = mutableListOf<Pair<Long, ReviewAnswer>>()
    private var backs = 0
    private var undos = 0

    @Test fun aPlainCardShowsTheNotificationVerbatimTheReadingTheReasonAndTwoAnswers() {
        content(listOf(rent))

        compose.onNodeWithText(TITLE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BODY).performScrollTo().assertIsDisplayed()
        listOf("RM650.00", MERCHANT, "Rent", SOURCE_LABEL, "15 August 2026, 12:00").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Over RM500.00.").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(ReviewAnswer.ACCEPT.label).performScrollTo().performClick()
        compose.onNodeWithText(ReviewAnswer.NOT_A_PAYMENT.label).performScrollTo().performClick()
        assertEquals(
            "The plain card's two buttons did not report their own answers for their own card",
            listOf(RENT_ID to ReviewAnswer.ACCEPT, RENT_ID to ReviewAnswer.NOT_A_PAYMENT),
            answers,
        )
    }

    @Test fun aTransferCardAsksLeaveOutOfSpendingWithLeaveOutAsTheDefaultAndNoSwipe() {
        val reload = rent.copy(
            txn = rent.txn.copy(amountSen = 10_000L, pendingReason = PendingReason.TRANSFER_SUSPECT),
            merchant = null,
        )
        val cardBill = rent.copy(
            txn = rent.txn.copy(id = RENT_ID + 1, amountSen = 120_000L, pendingReason = PendingReason.TRANSFER_SUSPECT),
            merchant = "Maybank Card",
        )
        content(listOf(reload, cardBill))

        // The merchant when Pinged read one, else the source. Each card is
        // brought into the lazy list by key first: on CI's short screen the
        // second card is not composed until scrolled to, so performScrollTo
        // alone finds no node.
        val list = compose.onNode(hasScrollToKeyAction())
        list.performScrollToKey("review-${RENT_ID + 1}")
        compose.onNodeWithText("Maybank Card RM1,200.00: leave out of spending?").performScrollTo().assertIsDisplayed()
        list.performScrollToKey("review-$RENT_ID")
        compose.onNodeWithText("$SOURCE_LABEL RM100.00: leave out of spending?").performScrollTo().assertIsDisplayed()

        // Reading order on the first card -- by line, then left to right --
        // read in one scroll position.
        compose.onAllNodesWithText(ReviewAnswer.NOT_A_PAYMENT.label)[0].performScrollTo()
        val drawn = listOf(ReviewAnswer.KEEP_AS_EXPENSE, ReviewAnswer.NOT_A_PAYMENT, ReviewAnswer.LEAVE_OUT)
            .sortedWith(
                compareBy(
                    { compose.onAllNodesWithText(it.label)[0].fetchSemanticsNode().boundsInRoot.top.toInt() },
                    { compose.onAllNodesWithText(it.label)[0].fetchSemanticsNode().boundsInRoot.left },
                ),
            )
        assertEquals(
            "A transfer card's answers are not Leave out, Keep as expense and Not a payment, in that order",
            listOf(ReviewAnswer.LEAVE_OUT, ReviewAnswer.KEEP_AS_EXPENSE, ReviewAnswer.NOT_A_PAYMENT),
            drawn,
        )
        compose.onAllNodesWithText(ReviewAnswer.LEAVE_OUT.label)[0].performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, DEFAULT_ANSWER))
        listOf(ReviewAnswer.KEEP_AS_EXPENSE, ReviewAnswer.NOT_A_PAYMENT).forEach {
            compose.onAllNodesWithText(it.label)[0].performScrollTo()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        }
        assertEquals(
            "A transfer card offers Accept, which would count a reload as spending",
            0,
            compose.onAllNodesWithText(ReviewAnswer.ACCEPT.label).fetchSemanticsNodes().size,
        )

        // No swipe answers a transfer card (spec 9.2): the user always sees
        // which of the three they gave.
        compose.onNodeWithText("$SOURCE_LABEL RM100.00: leave out of spending?").performScrollTo()
            .performTouchInput { swipeRight() }
        compose.onNodeWithText("$SOURCE_LABEL RM100.00: leave out of spending?").performScrollTo()
            .performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("A swipe answered a transfer card", emptyList<Any>(), answers)

        val three = listOf(ReviewAnswer.LEAVE_OUT, ReviewAnswer.KEEP_AS_EXPENSE, ReviewAnswer.NOT_A_PAYMENT)
        three.forEach { compose.onAllNodesWithText(it.label)[0].performScrollTo().performClick() }
        assertEquals(
            "The transfer card's three buttons did not report their own answers for their own card",
            three.map { RENT_ID to it },
            answers,
        )
    }

    @Test fun anInboxThatEmptiesWhileOpenSaysSoAndStaysOpen() {
        val cards = mutableStateOf(listOf(rent))
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = cards.value,
                    storageUnavailable = false,
                    busy = null,
                    onBack = { backs++ },
                    onAnswer = { card, _ -> cards.value = cards.value - card },
                )
            }
        }

        compose.onNodeWithText(ReviewAnswer.ACCEPT.label).performScrollTo().performClick()

        compose.onNodeWithText(NOTHING_AWAITING_REVIEW).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Awaiting review").assertIsDisplayed()
        assertEquals("The emptied inbox closed itself", 0, backs)
    }

    @Test fun aRestoreOrADeleteDisablesEveryAnswerAndUndoWithOneLineSayingWhich() {
        val busy = mutableStateOf<Operation?>(Operation.RESTORE)
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = listOf(rent),
                    storageUnavailable = false,
                    busy = busy.value,
                    onBack = {},
                    onAnswer = { card, answer -> answers += card.txn.id to answer },
                    undoable = Undoable("ACCEPTED · RM650.00", rows = emptyList()),
                    onUndo = { undos++ },
                )
            }
        }

        listOf(Operation.RESTORE, Operation.DELETE).forEach { operation ->
            busy.value = operation
            compose.onNodeWithText(busyLine(operation)).performScrollTo().assertIsDisplayed()
            rent.kind.answers.forEach { answer ->
                compose.onNodeWithText(answer.label).performScrollTo().assertIsNotEnabled().performClick()
            }
            // Pinned outside the scroll; see ReviewUndoTest.
            compose.onNodeWithText(UNDO).assertIsNotEnabled().performClick()
        }
        assertEquals("A button answered while the ledger was being replaced", emptyList<Any>(), answers)
        assertEquals("Undo was tapped through while the ledger was being replaced", 0, undos)

        busy.value = null
        compose.onNodeWithText(ReviewAnswer.ACCEPT.label).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(UNDO).assertIsEnabled()
        assertEquals(
            "The busy line stayed up after the operation ended",
            0,
            compose.onAllNodesWithText(busyLine(Operation.RESTORE)).fetchSemanticsNodes().size,
        )
    }

    private fun content(cards: List<ReviewCard>) {
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = cards,
                    storageUnavailable = false,
                    busy = null,
                    onBack = { backs++ },
                    onAnswer = { card, answer -> answers += card.txn.id to answer },
                )
            }
        }
    }

    private companion object {
        const val RENT_ID = 41L
        const val TITLE = "Maybank2u"
        const val BODY = "You've paid RM650.00 to RUMAH SEWA\nRef: 000000"

        val rent = ReviewCard(
            txn = ledgerTxn(
                amountSen = 65_000L,
                occurredAt = NOON,
                state = TxnState.PENDING,
                pendingReason = PendingReason.OVER_THRESHOLD,
                id = RENT_ID,
            ),
            category = "Rent",
            title = TITLE,
            body = BODY,
        )
    }
}
