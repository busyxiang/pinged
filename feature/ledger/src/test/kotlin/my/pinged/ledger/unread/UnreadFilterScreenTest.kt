package my.pinged.ledger.unread

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.NamedSource
import my.pinged.data.dao.UnreadCapture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Could not read filtered to one app (#368) as drawn: the filter names its
 * app and offers every app back, and its empty state is that app's. Which
 * cards a filter reads is `UnreadFilterReadTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class UnreadFilterScreenTest {
    @get:Rule val compose = createComposeRule()

    private val grab = NamedSource("com.grabtaxi.passenger", "GRABPAY")

    private val card = UnreadCapture(
        id = 1L,
        sourcePackage = grab.pkg,
        postedAt = 1_790_730_840_000L,
        title = null,
        text = "Paid RM12.00 to a merchant",
        bigText = null,
        label = "GrabPay",
    )

    private fun draw(state: UnreadState, source: NamedSource?, onShowEveryApp: () -> Unit = {}) = compose.setContent {
        UnreadContent(
            state,
            onBack = {},
            onNeverATransaction = {},
            onCopy = {},
            onEnd = {},
            source = source,
            onShowEveryApp = onShowEveryApp,
        )
    }

    @Test fun theFilterNamesItsAppAndShowEveryAppClearsIt() {
        var cleared = 0
        draw(UnreadState(loaded = true, items = listOf(card)), grab, onShowEveryApp = { cleared++ })

        compose.onNodeWithText(filterLine(grab)).assertExists()
        compose.onNode(hasText(SHOW_EVERY_APP) and hasClickAction()).performClick()

        assertEquals("Show every app did not clear the filter", 1, cleared)
    }

    @Test fun theListUnfilteredNamesNoAppAndOffersNoClear() {
        draw(UnreadState(loaded = true, items = listOf(card)), source = null)

        compose.onNodeWithText(filterLine(grab)).assertDoesNotExist()
        compose.onNodeWithText(SHOW_EVERY_APP).assertDoesNotExist()
    }

    @Test fun anEmptyFilteredListSaysSoForItsApp() {
        draw(UnreadState(loaded = true), grab)

        compose.onNodeWithText(nothingUnreadFrom(grab)).assertExists()
        compose.onNodeWithText(NOTHING_UNREAD).assertDoesNotExist()
        // Still offered, so the user can see every app's from here.
        compose.onNode(hasText(SHOW_EVERY_APP) and hasClickAction()).assertExists()
    }
}
