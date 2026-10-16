package my.pinged.ledger.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import my.pinged.ui.theme.CLOSE
import my.pinged.ui.theme.Separator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** `design/Onboarding.dc.html` (#156): what it claims, and where its two controls go. */
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theThreeClaimsAreMadeAndTheButtonAsksForAccess() {
        var asked = 0
        compose.setContent { OnboardingScreen(onAllowAccess = { asked++ }) }

        for (text in listOf(
            ONBOARDING_HEADLINE,
            "STEP 1 OF 2",
            "STAYS ON YOUR PHONE",
            "ONLY THE APPS YOU PICK",
            "STARTS FROM TODAY",
        )) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        // Not scrolled to: the action band is fixed below the claims, so the
        // button is on screen at CI's 640dp without the user finding it.
        compose.onNodeWithText(ALLOW_ACCESS).assertIsDisplayed().performClick()
        compose.onNodeWithText("ANDROID ASKS NEXT" + Separator + "PINGED NEVER SEES A PASSWORD").assertIsDisplayed()

        assertEquals("The button did not ask for notification access", 1, asked)
    }

    @Test fun theLinkSaysWhatIsStoredAndCloses() {
        compose.setContent { OnboardingScreen(onAllowAccess = {}) }

        // No scroll first, as for the button: the link is in the same fixed band.
        compose.onNodeWithText(WHAT_IS_STORED).assertIsDisplayed().performClick()
        for (label in listOf("FROM THE APPS YOU PICK", "FROM EVERY OTHER APP", "FROM ALL APPS TOGETHER")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }

        compose.onNodeWithContentDescription(CLOSE).performClick()
        compose.waitForIdle()
        assertEquals(
            "The sheet stayed up after its close was tapped",
            0,
            compose.onAllNodesWithText("FROM EVERY OTHER APP").fetchSemanticsNodes().size,
        )
    }
}
