package my.pinged.ledger.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Design 9.2: the footer names the build and the parser pack beneath the
 * privacy line, so a user reporting a bad parse can say which pack read it.
 */
@RunWith(AndroidJUnit4::class)
class FooterVersionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun footerShowsBuildAndPackVersionBeneathPrivacyLine() {
        compose.setContent { PingedTheme { Footer(buildVersion = "1.2.3", packVersion = 14) } }

        compose.onNodeWithText("NO INTERNET PERMISSION", substring = true).assertIsDisplayed()
        compose.onNodeWithText("BUILD 1.2.3", substring = true).assertIsDisplayed()
        compose.onNodeWithText("PACK 14", substring = true).assertIsDisplayed()

        val privacyTop = compose.onNodeWithText("NO INTERNET PERMISSION", substring = true)
            .fetchSemanticsNode().boundsInRoot.top
        val versionTop = compose.onNodeWithText("BUILD 1.2.3", substring = true)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("version line at $versionTop is not beneath the privacy line at $privacyTop", versionTop > privacyTop)
    }
}
