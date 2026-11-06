package my.pinged.ledger.sources

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.parse.PackagePack
import my.pinged.parse.ParsePack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #366: the screen says which apps Pinged reads whether or not any of them is
 * installed, since the sentence is about the pack and not the phone.
 */
@RunWith(AndroidJUnit4::class)
class SourcesReadsLineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSentenceShowsWithNoneOfThePacksAppsInstalled() {
        val pack = ParsePack(
            packVersion = 1,
            packages = listOf(PackagePack("a.tng", "Touch 'n Go"), PackagePack("a.mae", "MAE"), PackagePack("a.boost", "Boost")),
        )
        compose.setContent {
            SourcesScreenContent(state = SourcesState(loaded = true), onToggle = { _, _ -> }, onBack = {}, pack = pack)
        }
        // The state under test: Suggested is empty because nothing is installed.
        compose.onNodeWithText("NO KNOWN SOURCE IS INSTALLED ON THIS DEVICE").assertExists()
        compose.onNodeWithText("Pinged reads Touch 'n Go, MAE and Boost. Add payments from other apps by hand.").assertExists()
    }
}
