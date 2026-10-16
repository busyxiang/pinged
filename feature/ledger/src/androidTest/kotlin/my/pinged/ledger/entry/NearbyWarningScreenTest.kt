package my.pinged.ledger.entry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Save's warning when Pinged already has the payment (#104, spec 9.4), drawn
 * from [ManualEntryScreenContent] with the state held here the way
 * `ManualEntryViewModel` holds it: Cancel withdraws the warning and nothing
 * else. What the holder does with each answer is `NearbyWarningViewModelTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class NearbyWarningScreenTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(ManualEntryState())
    private var saves = 0
    private var addsAnyway = 0

    private fun draw(nearby: NearbyCapture) {
        state = ManualEntryState(draft = ManualDraft(amountSen = 1_250L), nearby = nearby)
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state,
                    onAmount = { sen -> state = state.copy(draft = state.draft.copy(amountSen = sen)) },
                    onMerchant = {},
                    onNote = {},
                    onSave = { saves++ },
                    onBack = {},
                    onSaved = {},
                    onAddAnyway = { addsAnyway++ },
                    onKeepEditing = { state = state.copy(nearby = null) },
                )
            }
        }
    }

    @Test fun theWarningNamesTheCapturesSourceAndTime() {
        draw(captured(awaitingReview = false))
        compose.onNodeWithText(LINE).performScrollTo().assertIsDisplayed()
    }

    @Test fun aCaptureAwaitingReviewIsSaidToBe() {
        draw(captured(awaitingReview = true))
        compose.onNodeWithText("$LINE, awaiting review").performScrollTo().assertIsDisplayed()
    }

    @Test fun addAnywaySavesAndSaveWaitsForAnAnswer() {
        draw(captured(awaitingReview = false))
        compose.onNodeWithText(SAVE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(ADD_ANYWAY).performScrollTo().assertIsEnabled().performClick()
        assertEquals("Add anyway did not reach the holder", 1, addsAnyway)
        assertEquals("Save was asked again instead", 0, saves)
    }

    @Test fun cancelLeavesTheDraftFilledInAndSaveable() {
        draw(captured(awaitingReview = false))
        compose.onNodeWithText(NEARBY_CANCEL).performScrollTo().performClick()
        compose.onNodeWithText(LINE).assertDoesNotExist()
        compose.onNodeWithTag(AMOUNT_FIELD).assertTextEquals("RM12.50")
        compose.onNodeWithText(SAVE).performScrollTo().assertIsEnabled()
        assertEquals("Cancel saved the entry", 0, addsAnyway + saves)
    }

    private fun captured(awaitingReview: Boolean) = NearbyCapture(
        amountSen = 1_250L,
        source = "Touch 'n Go eWallet",
        occurredAt = LocalDate.of(2026, 9, 2).atTime(12, 41, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        awaitingReview = awaitingReview,
    )

    private companion object {
        const val LINE = "Pinged already has RM12.50 from Touch 'n Go eWallet at 12:41"
    }
}
