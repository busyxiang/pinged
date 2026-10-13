package my.pinged

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.review.REVIEW_BACK
import my.pinged.ledger.review.ReviewAnswer
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Spending list's pinned `N AWAITING REVIEW` row opens the review inbox
 * on Spending's own stack, and back -- system or chevron -- returns to the
 * list (#112).
 *
 * `createEmptyComposeRule`, for the reason `DayRouteTest` gives: the row has
 * to be in the database before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class ReviewRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    @Before fun seed() {
        discard()
        Databases.shared(context).txnDao().insert(held())
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun thePinnedRowOpensTheInboxAndSystemBackReturnsToTheList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheInbox()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            awaitTheList("System back from the inbox did not return to the Spending list")
        }
    }

    @Test fun theChevronReturnsToTheList() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openTheInbox()

            compose.onNodeWithContentDescription(REVIEW_BACK).performScrollTo().performClick()

            awaitTheList("The inbox's chevron did not return to the Spending list")
        }
    }

    private fun openTheInbox() {
        awaitTheList("The Spending list never said one payment awaits review")
        compose.onNodeWithText(PINNED).performScrollTo().performClick()
        assertTrue(
            "The pinned row did not open the inbox on the held payment's card",
            compose.waitFor {
                compose.countOf(TITLE) == 1 && compose.countOf(ReviewAnswer.ACCEPT.label) == 1 &&
                    compose.countOf("RM650.00") == 1
            },
        )
        assertTrue("The tab bar is drawn over the inbox", compose.countOf("SPENDING") == 0)
    }

    private fun awaitTheList(why: String) {
        assertTrue(
            why,
            compose.waitFor {
                compose.countOf(PINNED) == 1 && compose.countOf(TITLE) == 0 &&
                    compose.onAllNodesWithContentDescription(REVIEW_BACK).fetchSemanticsNodes().isEmpty()
            },
        )
    }

    private fun held() = Txn(
        // Captured, as its source says: a null id is a manual entry (spec 4).
        // The id names no capture row; the column has no foreign key.
        rawCaptureId = captureIds++,
        amountSen = 65_000L,
        direction = Direction.EXPENSE,
        occurredAt = System.currentTimeMillis(),
        localDate = LocalDates.of(System.currentTimeMillis()),
        merchantRaw = "RUMAH SEWA",
        merchantDisplay = "Rumah Sewa",
        merchantKey = "RUMAH SEWA",
        categoryId = Databases.categoryDao(context).requireUncategorizedId(),
        sourcePackage = "com.maybank2u.life",
        sourceLabel = "MAE",
        confidence = Confidence.HIGH,
        state = TxnState.PENDING,
        pendingReason = PendingReason.OVER_THRESHOLD,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun discard() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        const val PINNED = "1 AWAITING REVIEW"

        /** The inbox's title. */
        const val TITLE = "Awaiting review"
    }
}
