package my.pinged.ledger.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.SOURCE_LABEL
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A duplicate card (§9.2, #116) through [ReviewInboxScreenContent] with no
 * database: both rows side by side, each with its own source, time and
 * amount; Merge and Keep both; and no swipe, so a batch-clearing thumb can
 * never count a payment twice.
 */
@RunWith(AndroidJUnit4::class)
class ReviewDuplicateScreenTest {
    @get:Rule val compose = createComposeRule()

    private val answers = mutableListOf<Pair<Long, ReviewAnswer>>()

    @Test fun aDuplicateCardShowsBothRowsSideBySideAndOffersMergeAndKeepBoth() {
        content()

        compose.onNodeWithText("MAYBE THE SAME PURCHASE TWICE").performScrollTo().assertIsDisplayed()
        listOf(
            DUPLICATE_HELD to listOf(BANK, "15 August 2026, 12:02", "RM12.34"),
            DUPLICATE_PAIR to listOf(SOURCE_LABEL, "15 August 2026, 12:00", "RM12.30"),
        ).forEach { (side, lines) ->
            lines.forEach { line ->
                compose.onNode(hasText(line) and hasAnyAncestor(hasTestTag(side)))
                    .performScrollTo()
                    .assertIsDisplayed()
            }
        }

        compose.onNodeWithText(ReviewAnswer.MERGE.label).performScrollTo().performClick()
        compose.onNodeWithText(ReviewAnswer.KEEP_BOTH.label).performScrollTo().performClick()
        assertEquals(
            "The duplicate card's two buttons did not report their own answers for the row awaiting review",
            listOf(HELD_ID to ReviewAnswer.MERGE, HELD_ID to ReviewAnswer.KEEP_BOTH),
            answers,
        )
        listOf(ReviewAnswer.ACCEPT, ReviewAnswer.NOT_A_PAYMENT).forEach {
            assertEquals(
                "A duplicate card offers ${it.label} beside Merge and Keep both",
                0,
                compose.onAllNodesWithText(it.label).fetchSemanticsNodes().size,
            )
        }
    }

    @Test fun aDuplicateCardCannotBeSwiped() {
        content()

        compose.onNodeWithText("MAYBE THE SAME PURCHASE TWICE").performScrollTo()
            .performTouchInput { swipeRight() }
        compose.onNodeWithText("MAYBE THE SAME PURCHASE TWICE").performScrollTo()
            .performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals("A swipe answered a duplicate card", emptyList<Any>(), answers)
    }

    private fun content() {
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = listOf(duplicate),
                    storageUnavailable = false,
                    busy = null,
                    onBack = {},
                    onAnswer = { card, answer -> answers += card.txn.id to answer },
                )
            }
        }
    }

    private companion object {
        const val HELD_ID = 52L
        const val BANK = "Maybank MAE"

        // Different amounts only so each side's own amount is told apart; a
        // real pair shares one.
        val duplicate = ReviewCard(
            txn = ledgerTxn(
                amountSen = 1_234L,
                occurredAt = NOON + 2 * 60_000L,
                state = TxnState.PENDING,
                pendingReason = PendingReason.DUPLICATE_SUSPECT,
                id = HELD_ID,
            ).copy(sourceLabel = BANK, sourcePackage = "com.maybank2u.life"),
            category = "Food & Drinks",
            title = "MAE",
            body = "You have paid RM12.34 to KEDAI",
            pair = ledgerTxn(amountSen = 1_230L, occurredAt = NOON, id = 51L),
        )
    }
}
