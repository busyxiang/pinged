package my.pinged.ledger.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen's Paid / Received control (#355): what it reads under
 * each direction, and that a tap on a segment reports its direction.
 */
@RunWith(AndroidJUnit4::class)
class MoneyReceivedScreenTest {
    @get:Rule val compose = createComposeRule()

    private val picked = mutableListOf<Direction>()

    private fun draw(state: ManualEntryState) {
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state,
                    onAmount = {}, onMerchant = {}, onNote = {}, onSave = {}, onBack = {}, onSaved = {},
                    onDirection = { picked += it },
                )
            }
        }
    }

    @Test fun paidIsTheDefaultAndTitlesAddPayment() {
        draw(ManualEntryState())

        compose.onNodeWithTag(PAID_SEGMENT).assertIsSelected()
        compose.onNodeWithText("Add payment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("COUNTS AGAINST").assertDoesNotExist()
    }

    @Test fun receivedTitlesAndLabelsTheChips() {
        draw(ManualEntryState(draft = ManualDraft(direction = Direction.REFUND)))

        compose.onNodeWithTag(RECEIVED_SEGMENT).assertIsSelected()
        compose.onNodeWithText("Add money received").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from Add money received").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("COUNTS AGAINST").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add payment").assertDoesNotExist()
    }

    @Test fun aCorrectionOfMoneyReceivedIsEditMoneyReceived() {
        val stored = ManualDraft(amountSen = 1_250L, direction = Direction.REFUND)
        draw(ManualEntryState(draft = stored, editing = 7L, loaded = stored))

        compose.onNodeWithText("Edit money received").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from Edit money received").performScrollTo().assertIsDisplayed()
    }

    @Test fun aTapOnASegmentReportsItsDirection() {
        draw(ManualEntryState())

        compose.onNodeWithTag(RECEIVED_SEGMENT).performScrollTo().performClick()
        compose.onNodeWithTag(PAID_SEGMENT).performScrollTo().performClick()

        assertEquals(listOf(Direction.REFUND, Direction.EXPENSE), picked)
    }
}
