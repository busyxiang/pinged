package my.pinged.ledger.sources

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.NOT_READ_YET
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #237: the header's count is of two lists that are empty until the first
 * read, and empty lists are not "0 ENABLED". Nor is a ledger that could not
 * be opened.
 */
@RunWith(AndroidJUnit4::class)
class SourcesHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun beforeTheReadLandsTheCountIsADash() {
        compose.setContent { SourcesScreenContent(state = SourcesState(), onToggle = { _, _ -> }, onBack = {}) }
        compose.onNodeWithText("0 ENABLED", substring = true).assertDoesNotExist()
        compose.onNodeWithText("$NOT_READ_YET ENABLED", substring = true).assertExists()
    }

    @Test fun anUnreadableLedgerClaimsNoCount() {
        compose.setContent {
            SourcesScreenContent(state = SourcesState(loaded = true, storageUnavailable = true), onToggle = { _, _ -> }, onBack = {})
        }
        compose.onNodeWithText("0 ENABLED", substring = true).assertDoesNotExist()
    }

    @Test fun aReadCountIsDrawn() {
        compose.setContent {
            SourcesScreenContent(
                state = SourcesState(suggested = listOf(SourceRow("a.b", "A", 1, true)), loaded = true),
                onToggle = { _, _ -> },
                onBack = {},
            )
        }
        compose.onNodeWithText("1 ENABLED", substring = true).assertExists()
    }
}
