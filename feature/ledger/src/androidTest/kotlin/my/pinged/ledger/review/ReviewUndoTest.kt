package my.pinged.ledger.review

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.2's Undo as the user meets it (#113): the snackbar after an answer,
 * replaced by the next answer's, and its Undo bringing the card back. Drawn
 * by [ReviewScreen] over a real [ReviewViewModel] and database, so the card
 * that returns is one the database gave back.
 *
 * The snackbar is pinned to the bottom of the screen, outside the cards'
 * scroll, so its Undo is tapped without `performScrollTo` -- there is no
 * scroll to bring it into, as with the tab bar.
 */
@RunWith(AndroidJUnit4::class)
class ReviewUndoTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun theSnackbarFollowsEachAnswerAndItsUndoBringsTheCardBack() {
        val dao = context.freshLedger()
        dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON + 1, amountSen = 65_000L))
        val rule = dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON, amountSen = 1_200L))
        val viewModel = ReviewViewModel(context.applicationContext as Application)
        compose.setContent { PingedTheme { ReviewScreen(viewModel, onBack = {}) } }
        waitForText(OVER)

        // Newest first: the RM650.00 card's Accept is the first.
        compose.onAllNodesWithText(ReviewAnswer.ACCEPT.label)[0].performScrollTo().performClick()
        waitForText("ACCEPTED · RM650.00")

        compose.onNodeWithText(ReviewAnswer.NOT_A_PAYMENT.label).performScrollTo().performClick()
        waitForText("NOT A PAYMENT · RM12.00")
        assertEquals(
            "The first answer's snackbar is still up after the second answer replaced it",
            0,
            compose.onAllNodesWithText("ACCEPTED · RM650.00").fetchSemanticsNodes().size,
        )
        waitForText(NOTHING_AWAITING_REVIEW)

        compose.onNodeWithText(UNDO).performClick()

        waitForText(RULE)
        compose.onNodeWithText(RULE).performScrollTo().assertIsDisplayed()
        compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(UNDO).fetchSemanticsNodes().isEmpty() }
        assertEquals(
            "Undo brought the card back but the row is not awaiting review for its reason",
            TxnState.PENDING to PendingReason.RULE_REVIEW,
            dao.byId(rule)!!.let { it.state to it.pendingReason },
        )
        assertEquals(
            "Undo reached past the last answer and brought back the accepted card too",
            0,
            compose.onAllNodesWithText(OVER).fetchSemanticsNodes().size,
        )
    }

    private fun waitForText(text: String) =
        compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private companion object {
        val OVER = reasonSentence(PendingReason.OVER_THRESHOLD)
        val RULE = reasonSentence(PendingReason.RULE_REVIEW)
    }
}
