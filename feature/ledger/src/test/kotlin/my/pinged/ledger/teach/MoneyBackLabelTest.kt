package my.pinged.ledger.teach

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.UnreadCapture
import my.pinged.parse.TaughtTemplate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Teach screen's second direction choice. It saves `Direction.REFUND`, but
 * the notifications taught there include a friend paying a bill back, which is
 * not a refund, so the choice is worded for all of them.
 */
@RunWith(AndroidJUnit4::class)
class MoneyBackLabelTest {
    @get:Rule val compose = createComposeRule()

    private val capture = UnreadCapture(
        id = 9L,
        sourcePackage = "com.maybank2u.life",
        postedAt = 1790730840000L,
        title = "Maybank2u: Scan & Pay",
        text = "You've received RM 27.95 from SAMPLE PAYER NAME. REF: 000000000Q.",
        bigText = null,
        label = "Maybank MAE",
    )

    @Test fun theSecondChoiceIsMoneyBackAndNotARefund() {
        compose.setContent {
            TeachContent(
                state = TeachState(
                    loaded = true,
                    capture = capture,
                    selection = TeachSelection(TaughtTemplate.words(capture.title, capture.text, null)),
                ),
                onBack = {},
                onTap = {},
                onNoMerchant = {},
                onDirection = {},
                onSave = {},
            )
        }
        compose.onNodeWithText("Money back").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("A refund").assertCountEquals(0)
    }
}
