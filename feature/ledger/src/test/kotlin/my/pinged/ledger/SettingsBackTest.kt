package my.pinged.ledger

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.LearnedMerchant
import my.pinged.ledger.corrections.CorrectionsContent
import my.pinged.ledger.corrections.CorrectionsState
import my.pinged.ledger.learned.LearnedContent
import my.pinged.ledger.learned.LearnedState
import my.pinged.ledger.licences.LicencesContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #220: every screen pushed from Settings goes back by the 20dp chevron
 * `design/Rules.dc.html` draws, as Categories does, and none by the word
 * "Back" beside its title.
 */
@RunWith(AndroidJUnit4::class)
class SettingsBackTest {
    @get:Rule val compose = createComposeRule()

    private var backs = 0

    @Test fun correctionsGoesBackByTheChevron() = assertChevron {
        CorrectionsContent(CorrectionsState(loaded = true, packVersion = 12), onBack = { backs++ }, onAccept = {}, onDecline = {})
    }

    @Test fun learnedGoesBackByTheChevron() = assertChevron {
        LearnedContent(
            LearnedState(loaded = true, items = listOf(LearnedMerchant("MR DIY", "Hardware shop", 4L, "Shopping", payments = 3))),
            onBack = { backs++ },
            onDelete = {},
        )
    }

    @Test fun licencesGoesBackByTheChevron() = assertChevron { LicencesContent(emptyList(), onBack = { backs++ }) }

    private fun assertChevron(content: @Composable () -> Unit) {
        compose.setContent(content)
        assertTrue("the header still draws the word Back", texts().none { it == "Back" })
        compose.onNodeWithContentDescription(SETTINGS_BACK).performClick()
        assertEquals(1, backs)
    }

    /** Every text in the unmerged tree. */
    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }
}
