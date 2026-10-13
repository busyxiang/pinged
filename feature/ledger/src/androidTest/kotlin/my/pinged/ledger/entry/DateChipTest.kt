package my.pinged.ledger.entry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The date chip beside the amount (#103, spec 9.4): it reads `Today`, its
 * picker offers today and every past day but never a future one, and it
 * then reads the day chosen. Drawn from [ManualEntryScreenContent] with the
 * state held here the way `ManualEntryViewModel` holds it, on a fixed today.
 */
@RunWith(AndroidJUnit4::class)
class DateChipTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(ManualEntryState(today = MID_AUGUST))
    private val picked = mutableListOf<LocalDate>()

    private fun draw() {
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state,
                    onAmount = {},
                    onDay = { day ->
                        picked += day
                        state = state.copy(draft = state.draft.dated(day, state.today))
                    },
                    onMerchant = {},
                    onNote = {},
                    onSave = {},
                    onBack = {},
                    onSaved = {},
                )
            }
        }
    }

    private fun chip() = compose.onNodeWithTag(DATE_CHIP)

    private fun day(date: LocalDate) = compose.onNodeWithTag(dayTag(date))

    @Test fun theChipReadsTodayAndThenTheDayChosen() {
        draw()
        chip().assertTextEquals(TODAY)

        chip().performScrollTo().performClick()
        day(MID_AUGUST.minusDays(3)).performScrollTo().assertIsEnabled().performClick()

        chip().assertTextEquals("12 August")
        assertEquals("The draft is not dated 12 August", MID_AUGUST.minusDays(3), state.draft.day)
    }

    @Test fun thePickerCannotChooseTomorrow() {
        draw()
        chip().performScrollTo().performClick()

        day(MID_AUGUST).assertIsEnabled()
        day(MID_AUGUST.plusDays(1)).performScrollTo().assertIsNotEnabled().performClick()

        assertEquals("Tomorrow reached the draft", emptyList<LocalDate>(), picked)
        assertNull("The draft is dated", state.draft.day)
        compose.onNodeWithContentDescription(NEXT_MONTH).assertIsNotEnabled()
    }

    @Test fun onAMonthsLastDayTheNextMonthIsOutOfReach() {
        state = ManualEntryState(today = LocalDate.of(2026, 8, 31))
        draw()
        chip().performScrollTo().performClick()

        compose.onNodeWithContentDescription(NEXT_MONTH).performScrollTo().assertIsNotEnabled().performClick()

        day(LocalDate.of(2026, 9, 1)).assertDoesNotExist()
    }

    @Test fun aDayInAnEarlierYearCanBeChosen() {
        draw()
        chip().performScrollTo().performClick()
        repeat(12) { compose.onNodeWithContentDescription(PREVIOUS_MONTH).performScrollTo().performClick() }

        day(LocalDate.of(2025, 8, 20)).performScrollTo().performClick()

        chip().assertTextEquals("20 August 2025")
    }

    private companion object {
        /** Mid-month, so tomorrow is drawn in the month the picker opens on. */
        val MID_AUGUST: LocalDate = LocalDate.of(2026, 8, 15)
    }
}
