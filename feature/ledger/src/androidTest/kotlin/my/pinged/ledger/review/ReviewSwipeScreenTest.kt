package my.pinged.ledger.review

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.2's two fast ways to accept (#114), through [ReviewInboxScreenContent]
 * with no database: a swipe on a plain card, and the Accept all button with
 * its label. Transfer and duplicate cards take neither.
 */
@RunWith(AndroidJUnit4::class)
class ReviewSwipeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val answers = mutableListOf<Pair<Long, ReviewAnswer>>()
    private var acceptAlls = 0

    /**
     * The duplicate here has no pair, so it is drawn as a plain card with
     * Accept on it (#116); it is still not plain to a swipe. A paired one is
     * `ReviewDuplicateScreenTest.aDuplicateCardCannotBeSwiped`'s.
     */
    @Test fun aSwipeAcceptsAPlainCardAndDoesNothingOnATransferOrAnUnpairedDuplicateCard() {
        content(listOf(card(PLAIN, PendingReason.OVER_THRESHOLD), card(TRANSFER, PendingReason.TRANSFER_SUSPECT), card(DUPLICATE, PendingReason.DUPLICATE_SUSPECT)))
        assertEquals("precondition: the pairless duplicate is not drawn as a plain card", CardKind.PLAIN, card(DUPLICATE, PendingReason.DUPLICATE_SUSPECT).kind)

        listOf(TRANSFER, DUPLICATE, PLAIN).forEach { id ->
            compose.onNodeWithTag(reviewCardTag(id)).performScrollTo().performTouchInput { swipeRight() }
            compose.waitForIdle()
        }

        assertEquals(
            "A swipe did not accept the plain card alone: a transfer or a duplicate card, paired or not, must never be accepted unseen",
            listOf(PLAIN to ReviewAnswer.ACCEPT),
            answers,
        )
    }

    @Test fun aSwipeDoesNothingWhileARestoreRuns() {
        content(listOf(card(PLAIN, PendingReason.OVER_THRESHOLD)), busy = Operation.RESTORE)

        compose.onNodeWithTag(reviewCardTag(PLAIN)).performScrollTo().performTouchInput { swipeRight() }
        compose.waitForIdle()

        assertEquals("A swipe answered while the ledger was being replaced", emptyList<Any>(), answers)
    }

    @Test fun acceptAllNamesTheCountAndTheRinggitNetAndReportsATap() {
        content(
            listOf(
                card(PLAIN, PendingReason.OVER_THRESHOLD, amountSen = 65_000L),
                card(REFUND, PendingReason.RULE_REVIEW, amountSen = 5_000L).let { it.copy(txn = it.txn.copy(direction = Direction.REFUND)) },
                card(TRANSFER, PendingReason.TRANSFER_SUSPECT, amountSen = 10_000L),
            ),
        )

        compose.onNodeWithText("Accept 2 · RM600.00").performScrollTo().performClick()

        assertEquals("Accept all was not reported once for one tap", 1, acceptAlls)
        assertEquals("Accept all reported as a card's own answer", emptyList<Any>(), answers)
    }

    @Test fun acceptAllDropsTheSumWhenAPlainCardIsNotInRinggit() {
        content(
            listOf(
                card(PLAIN, PendingReason.OVER_THRESHOLD, amountSen = 65_000L),
                card(REFUND, PendingReason.MERCHANT_MISSING, amountSen = 1_200L).let { it.copy(txn = it.txn.copy(currency = "USD")) },
            ),
        )

        compose.onNodeWithText("Accept 2").performScrollTo().assertIsEnabled()
    }

    /** The duplicate is pairless, so drawn with Accept (#116), and still not plain to Accept all. */
    @Test fun acceptAllIsAbsentWithNoPlainCardsAndDisabledWhileADeleteRuns() {
        val cards = mutableStateOf(listOf(card(TRANSFER, PendingReason.TRANSFER_SUSPECT), card(DUPLICATE, PendingReason.DUPLICATE_SUSPECT)))
        val busy = mutableStateOf<Operation?>(null)
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = cards.value,
                    storageUnavailable = false,
                    busy = busy.value,
                    onBack = {},
                    onAnswer = { card, answer -> answers += card.txn.id to answer },
                    onAcceptAll = { acceptAlls++ },
                )
            }
        }

        assertEquals(
            "Accept all was offered over a transfer and an unpaired duplicate card alone",
            0,
            compose.onAllNodesWithText("Accept ", substring = true).fetchSemanticsNodes().size,
        )

        cards.value = cards.value + card(PLAIN, PendingReason.OVER_THRESHOLD)
        busy.value = Operation.DELETE
        compose.onNodeWithText("Accept 1 · RM650.00").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals("Accept all was tapped through while the ledger was being replaced", 0, acceptAlls)
    }

    @Test fun aFocusedCardOpensAtTheTopWithAcceptAllAboveTheCards() {
        val cards = (1L..20L).map { card(it, PendingReason.OVER_THRESHOLD) }
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = cards,
                    storageUnavailable = false,
                    busy = null,
                    onBack = {},
                    onAnswer = { _, _ -> },
                    focus = FOCUSED,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(reviewCardTag(FOCUSED)).assertIsDisplayed()
        val above = compose.onAllNodesWithTag(reviewCardTag(FOCUSED - 1)).fetchSemanticsNodes().isNotEmpty() &&
            compose.onNodeWithTag(reviewCardTag(FOCUSED - 1)).isDisplayed()
        assertFalse(
            "The inbox opened on the card above the focused one: the Accept all row was not counted among the items above the cards",
            above,
        )
    }

    private fun content(cards: List<ReviewCard>, busy: Operation? = null) {
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = cards,
                    storageUnavailable = false,
                    busy = busy,
                    onBack = {},
                    onAnswer = { card, answer -> answers += card.txn.id to answer },
                    onAcceptAll = { acceptAlls++ },
                )
            }
        }
    }

    private fun card(id: Long, reason: PendingReason, amountSen: Long = 65_000L) = ReviewCard(
        txn = ledgerTxn(amountSen = amountSen, occurredAt = 1_000L + id, state = TxnState.PENDING, pendingReason = reason, id = id),
        title = "Notification $id",
        body = "Body $id",
    )

    private companion object {
        const val PLAIN = 41L
        const val TRANSFER = 42L
        const val DUPLICATE = 43L
        const val REFUND = 44L
        const val FOCUSED = 15L
    }
}
