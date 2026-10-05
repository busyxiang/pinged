package my.pinged.ledger.corrections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import my.pinged.capture.Corrections
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.theme.CANNOT_READ_YOUR_DATA
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Spec 5.5's list draws both values of what changed, and an answer reaches
 * the correction it was drawn beside.
 */
class CorrectionsScreenTest {
    @get:Rule val compose = createComposeRule()

    /** Issue #20's card bill, as pack 12 offers it. */
    private val cardBill = ledgerTxn(amountSen = 226_276L, id = 7L).copy(merchantDisplay = "Maybank Master Card").let { before ->
        Corrections.Correction(
            captureId = 70L,
            fromPackVersion = 11,
            toPackVersion = 12,
            previousRuleId = "mae-scan-pay-v1",
            ruleId = "mae-card-bill-v1",
            before = before,
            after = before.copy(state = TxnState.PENDING, pendingReason = PendingReason.TRANSFER_SUSPECT),
            changed = setOf(Corrections.Field.STATUS),
        )
    }

    @Test fun aCorrectionDrawsItsOldAndNewValuesAndOnlyWhatChanged() {
        compose.setContent {
            CorrectionsContent(
                CorrectionsState(loaded = true, items = listOf(cardBill), packVersion = 12),
                onBack = {}, onAccept = {}, onDecline = {},
            )
        }
        compose.onNodeWithText("Maybank Master Card · RM2,262.76").assertIsDisplayed()
        compose.onNodeWithText("COUNTED").assertIsDisplayed()
        compose.onNodeWithText("→ PENDING · TRANSFER SUSPECT").assertIsDisplayed()
        compose.onNodeWithText("AMOUNT").assertDoesNotExist()
    }

    @Test fun eachAnswerReachesItsOwnCorrection() {
        val accepted = mutableListOf<Long>()
        val declined = mutableListOf<Long>()
        compose.setContent {
            CorrectionsContent(
                CorrectionsState(loaded = true, items = listOf(cardBill), packVersion = 12),
                onBack = {},
                onAccept = { accepted += it.captureId },
                onDecline = { declined += it.captureId },
            )
        }
        compose.onNodeWithText(ACCEPT).performClick()
        compose.onNodeWithText(KEEP).performClick()
        assertEquals(listOf(70L), accepted)
        assertEquals(listOf(70L), declined)
    }

    /** "Nothing to review" before the sweep has finished would be a claim about unread history. */
    @Test fun anUnfinishedSweepIsNotNothingToReview() {
        compose.setContent {
            CorrectionsContent(
                CorrectionsState(loaded = true, checking = true, packVersion = 12),
                onBack = {}, onAccept = {}, onDecline = {},
            )
        }
        compose.onNodeWithText("STILL CHECKING YOUR HISTORY AGAINST PACK 12").assertIsDisplayed()
        compose.onNodeWithText(NOTHING_TO_REVIEW).assertDoesNotExist()
    }

    @Test fun anUnreadableDatabaseIsNotNothingToReview() {
        compose.setContent {
            CorrectionsContent(
                CorrectionsState(loaded = true, storageUnavailable = true),
                onBack = {}, onAccept = {}, onDecline = {},
            )
        }
        compose.onNodeWithText(CANNOT_READ_YOUR_DATA).assertIsDisplayed()
        compose.onNodeWithText(NOTHING_TO_REVIEW).assertDoesNotExist()
    }
}
