package my.pinged

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #237: a count of what awaits review that is not read yet, or could not be
 * read, is not 0. No badge says nothing awaits review; the badge stays, with
 * the loading boards' dash.
 */
@RunWith(AndroidJUnit4::class)
class TabBarBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aCountNotKnownIsADashNotNoBadge() {
        compose.setContent { TabBar(selected = Tab.Charts, onSelect = {}, awaitingReview = null) }
        compose.onNodeWithContentDescription(spendingBadgeDescription(null)).assertExists()
    }

    @Test fun nothingAwaitingReviewIsNoBadge() {
        compose.setContent { TabBar(selected = Tab.Charts, onSelect = {}, awaitingReview = 0) }
        compose.onNodeWithContentDescription("awaiting review", substring = true).assertDoesNotExist()
    }
}
