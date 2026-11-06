package my.pinged.ledger.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the inbox opens when it is handed a card to focus (#114). On the
 * device rather than with `ReviewSwipeScreenTest` on the JVM: which card is
 * on screen is decided by the cards' measured heights, and those follow the
 * font, which Robolectric does not measure as the device does.
 */
@RunWith(AndroidJUnit4::class)
class ReviewFocusScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aFocusedCardOpensAtTheTopWithAcceptAllAboveTheCards() {
        val cards = (1L..20L).map { card(it, PendingReason.RULE_REVIEW, amountSen = 45_000L) }
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

    private fun card(id: Long, reason: PendingReason, amountSen: Long = 65_000L) = ReviewCard(
        txn = ledgerTxn(amountSen = amountSen, occurredAt = 1_000L + id, state = TxnState.PENDING, pendingReason = reason, id = id),
        title = "Notification $id",
        body = "Body $id",
    )

    private companion object {
        const val FOCUSED = 15L
    }
}
