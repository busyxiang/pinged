package my.pinged.ledger.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ledger.search.PICK
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.5's check limit row and its sheet (#362): the row names the step
 * now, the sheet lists the six with that one checked, and a pick saves it
 * and closes the sheet with no Save button.
 */
@RunWith(AndroidJUnit4::class)
class CheckLimitSettingTest {
    @get:Rule val compose = createComposeRule()

    private val steps = listOf("RM100", "RM200", "RM500", "RM1,000", "RM2,000", "RM5,000")

    @Test fun theRowNamesTheStepNowAndOpensTheSheet() {
        var opened = false
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = SettingsState(storage = Storage.HEALTHY, checkLimitSen = 100_000L, loaded = true),
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                    onCheckLimit = { opened = true },
                )
            }
        }

        compose.onNode(hasText(CHECK_LIMIT_ROW) and hasText("RM1,000")).performScrollTo().performClick()

        assertTrue("The row did not open the sheet", opened)
    }

    @Test fun theSheetListsTheSixStepsWithTheOneNowChecked() {
        compose.setContent { PingedTheme { CheckLimitSheet(selectedSen = 50_000L, onPick = {}, onDismiss = {}) } }

        steps.forEach { compose.onNodeWithTag(PICK + it).assertExists() }
        assertEquals(
            "The sheet offers a step the store would refuse",
            steps.size,
            compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag(PICK + "RM500").assertIsSelected()
        (steps - "RM500").forEach { compose.onNodeWithTag(PICK + it).assertIsNotSelected() }
        compose.onNodeWithText(CHECK_LIMIT_NOTE).assertExists()
    }

    /** The screen's own wiring: a pick hands the step to the holder and the sheet goes. */
    @Test fun aPickSavesTheStepAndClosesTheSheet() {
        var saved: Long? = null
        compose.setContent {
            PingedTheme {
                CheckLimitChooser(selectedSen = 50_000L, onSave = { saved = it }) { open -> LaunchedEffect(Unit) { open() } }
            }
        }
        compose.onNodeWithTag(PICK + "RM2,000").performClick()

        assertEquals(200_000L, saved)
        compose.onNodeWithTag(PICK + "RM500").assertDoesNotExist()
    }

    @Test fun nothingIsSavedUntilAStepIsPicked() {
        var saved: Long? = null
        compose.setContent {
            PingedTheme {
                CheckLimitChooser(selectedSen = 50_000L, onSave = { saved = it }) { open -> LaunchedEffect(Unit) { open() } }
            }
        }

        compose.onNodeWithTag(PICK + "RM500").assertExists()
        assertNull(saved)
        assertTrue("The sheet draws a Save button", compose.onAllNodes(hasText("Save")).fetchSemanticsNodes().isEmpty())
    }
}
