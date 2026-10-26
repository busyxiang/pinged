package my.pinged.ledger.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.Seed
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A money-back card's category line and the sheet behind it (§9.2), through
 * [ReviewInboxScreenContent] with no database: which cards ask, what the line
 * says, and that a pick is reported as a filing and never as an answer.
 */
@RunWith(AndroidJUnit4::class)
class MoneyBackCategoryTest {
    @get:Rule val compose = createComposeRule()

    private val answers = mutableListOf<Pair<Long, ReviewAnswer>>()
    private val filed = mutableListOf<Pair<Long, Long>>()
    private var choicesRead = 0
    private var cards by mutableStateOf(listOf(moneyBack))

    private fun content(choices: List<Category>? = categories) {
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = cards,
                    storageUnavailable = false,
                    busy = null,
                    onBack = {},
                    onAnswer = { card, answer -> answers += card.txn.id to answer },
                    choices = {
                        choicesRead++
                        choices
                    },
                    onFile = { card, categoryId -> filed += card.txn.id to categoryId },
                )
            }
        }
    }

    @Test fun onlyAPlainCardOfMoneyComingBackAsksWhichCategory() {
        val payment = moneyBack.copy(txn = moneyBack.txn.copy(id = 2L, direction = Direction.EXPENSE))
        val transfer = moneyBack.copy(txn = moneyBack.txn.copy(id = 3L, pendingReason = PendingReason.TRANSFER_SUSPECT))
        assertEquals(listOf(true, false, false), listOf(moneyBack, payment, transfer).map { it.asksCategory })

        cards = listOf(payment, transfer)
        content()

        compose.onAllNodesWithTag(CATEGORY_LINE).assertCountEquals(0)
    }

    @Test fun anUnsortedMoneyBackCardSaysToChooseAndASortedOneNamesItsCategory() {
        content()
        compose.onNodeWithText(COUNTS_AGAINST).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CHOOSE_A_CATEGORY).assertIsDisplayed()

        cards = listOf(moneyBack.copy(category = "Food & Drinks"))
        compose.onNodeWithText("Food & Drinks").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(CHOOSE_A_CATEGORY).assertCountEquals(0)
    }

    @Test fun aPickIsReportedAsAFilingOfThatCardAndIsNotAnAnswer() {
        content()
        compose.onNodeWithTag(CATEGORY_LINE).performScrollTo().performClick()

        compose.onNodeWithText("Food & Drinks").performClick()
        compose.onNodeWithText(SAVE_LABEL).performClick()

        assertEquals(1, choicesRead)
        assertEquals("The pick did not reach onFile for its own card and category", listOf(MONEY_BACK_ID to FOOD), filed)
        assertEquals("A filing was reported as an answer, which would settle the card", emptyList<Any>(), answers)
        compose.onAllNodesWithText(SAVE_LABEL).assertCountEquals(0)
    }

    @Test fun categoriesThatCannotBeReadOpenNoSheet() {
        content(choices = null)
        compose.onNodeWithTag(CATEGORY_LINE).performScrollTo().performClick()

        compose.waitForIdle()
        compose.onAllNodesWithText(SAVE_LABEL).assertCountEquals(0)
        assertEquals(emptyList<Any>(), filed)
    }

    @Test fun aSheetOpenOverACardThatLeftTheInboxIsWithdrawnAndDoesNotComeBackWithIt() {
        content()
        compose.onNodeWithTag(CATEGORY_LINE).performScrollTo().performClick()
        compose.onNodeWithText(SAVE_LABEL).assertIsDisplayed()

        cards = emptyList()
        compose.waitForIdle()
        compose.onAllNodesWithText(SAVE_LABEL).assertCountEquals(0)

        // Undo, or a re-read, can put the same row back: its sheet was
        // closed with it, and a pick made later would answer a question
        // the user no longer had open.
        cards = listOf(moneyBack)
        compose.waitForIdle()
        compose.onAllNodesWithText(SAVE_LABEL).assertCountEquals(0)
    }

    private companion object {
        const val MONEY_BACK_ID = 41L
        const val FOOD = 11L

        val categories = listOf(
            Category(id = FOOD, name = "Food & Drinks", iconKey = "utensils", sortOrder = 1),
            Category(id = 12L, name = "Transport", iconKey = "car", sortOrder = 2),
        )

        val moneyBack = ReviewCard(
            txn = ledgerTxn(
                amountSen = 2_795L,
                occurredAt = NOON,
                state = TxnState.PENDING,
                pendingReason = PendingReason.RULE_REVIEW,
                id = MONEY_BACK_ID,
            ).copy(direction = Direction.REFUND, merchantRaw = "SAMPLE PAYER NAME", merchantDisplay = "Sample Payer Name"),
            category = Seed.UNCATEGORIZED,
            title = "Maybank2u: Scan & Pay",
            body = "You've received RM 27.95 from SAMPLE PAYER NAME. REF: 000000000Q.",
        )
    }
}
