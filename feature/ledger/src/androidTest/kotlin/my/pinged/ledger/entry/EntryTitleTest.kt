package my.pinged.ledger.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen names what it does (#105): `Add payment` for a new entry,
 * `Edit payment` while one is corrected, in its title and in what TalkBack
 * reads for the back chevron.
 */
@RunWith(AndroidJUnit4::class)
class EntryTitleTest {
    @get:Rule val compose = createComposeRule()

    private fun draw(state: ManualEntryState) {
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(state = state, onAmount = {}, onMerchant = {}, onNote = {}, onSave = {}, onBack = {}, onSaved = {})
            }
        }
    }

    @Test fun aNewEntryIsAddPayment() {
        draw(ManualEntryState())

        compose.onNodeWithText("Add payment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from Add payment").performScrollTo().assertIsDisplayed()
    }

    @Test fun aCorrectionIsEditPayment() {
        val stored = ManualDraft(amountSen = 1_250L)
        draw(ManualEntryState(draft = stored, editing = 7L, loaded = stored))

        compose.onNodeWithText("Edit payment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from Edit payment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add payment").assertDoesNotExist()
    }
}
