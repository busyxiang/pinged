package my.pinged.ledger.learned

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.StandingAnswerRow
import my.pinged.parse.ExclusionReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.3's standing answers under Things you taught Pinged's LEFT OUT tab:
 * one line each, worded from the app, the rule's suggestion and the
 * counterparty, and a delete that reports its own row.
 */
@RunWith(AndroidJUnit4::class)
class LeftOutTabTest {
    @get:Rule val compose = createComposeRule()

    private fun answer(id: Long, rule: String, pkg: String, label: String?, counterparty: String?, exclusion: ExclusionReason) =
        LeftOut(StandingAnswerRow(id, rule, pkg, merchantKey = counterparty?.uppercase(), label = label, counterparty = counterparty), exclusion)

    private val reload = answer(1, "tng-reload-v1", TNG, "Touch 'n Go eWallet", null, ExclusionReason.TRANSFER)
    private val bill = answer(2, "mae-card-bill-v1", MAE, "Maybank MAE", "Maybank Master Card", ExclusionReason.CARD_PAYMENT)
    private val sent = answer(3, "mae-transferred-v1", MAE, "Maybank MAE", "RAY FOO", ExclusionReason.TRANSFER)

    @Test fun eachAnswerIsWordedFromItsAppItsRulesSuggestionAndItsCounterparty() {
        assertEquals("Touch 'n Go eWallet · Transfers", leftOutLabel(reload))
        assertEquals("Maybank MAE · Card payments to Maybank Master Card", leftOutLabel(bill))
        assertEquals("Maybank MAE · Transfers to RAY FOO", leftOutLabel(sent))
    }

    /** An app no longer on the allow-list has no label; its package names it. */
    @Test fun anAppWithNoLabelIsNamedByItsPackage() {
        assertEquals("$TNG · Transfers", leftOutLabel(answer(4, "tng-reload-v1", TNG, null, null, ExclusionReason.TRANSFER)))
    }

    @Test fun theTabCountsTheAnswersAndListsEachOnOneLine() {
        compose.setContent {
            LearnedContent(LearnedState(loaded = true, leftOut = listOf(reload, bill, sent)), onBack = {}, onDelete = {})
        }

        compose.onNodeWithText("LEFT OUT 3").performScrollTo().performClick()

        listOf(reload, bill, sent).forEach { compose.onNodeWithText(leftOutLabel(it)).performScrollTo().assertIsDisplayed() }
    }

    @Test fun anEmptyTabSaysHowToMakeOne() {
        compose.setContent { LearnedContent(LearnedState(loaded = true), onBack = {}, onDelete = {}, initialTab = TaughtTab.LEFT_OUT) }

        compose.onNodeWithText(NOTHING_LEFT_OUT).assertIsDisplayed()
    }

    @Test fun deleteReportsItsOwnRow() {
        val deleted = mutableListOf<Long>()
        compose.setContent {
            LearnedContent(
                LearnedState(loaded = true, leftOut = listOf(bill)),
                onBack = {},
                onDelete = {},
                initialTab = TaughtTab.LEFT_OUT,
                onDeleteLeftOut = { deleted += it.row.id },
            )
        }

        compose.onNodeWithContentDescription(DELETE).performScrollTo().performClick()

        assertEquals(listOf(2L), deleted)
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
    }
}
