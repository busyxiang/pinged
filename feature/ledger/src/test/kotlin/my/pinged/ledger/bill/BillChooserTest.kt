package my.pinged.ledger.bill

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.4's bill chooser with no database (`design/BillChooser.dc.html`,
 * `BillChooserSearch`, `BillChooserEmpty`): No bill first, the ranked bills
 * before the rest, the pick ticked, a tap that picks and closes, and a
 * search that asks for what was typed and says so when it finds nothing.
 */
@RunWith(AndroidJUnit4::class)
class BillChooserTest {
    @get:Rule val compose = createComposeRule()

    private val yuenKee = billChoice(1L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 3_200L)
    private val foodpanda = billChoice(2L, "foodpanda", 6_400L, SEP_18.minusDays(4))
    private val tealive = billChoice(3L, "Tealive", 890L, SEP_20)
    private val arjun = BillFor("Arjun Nair", 3_200L, "MYR", SEP_20)

    private val asked = mutableListOf<String>()
    private val picked = mutableListOf<Long?>()
    private var dismissed = 0
    private var open by mutableStateOf(true)

    private fun draw(current: Long? = null, offer: (String) -> BillOffer? = { recent() }) {
        compose.setContent {
            PingedTheme {
                if (open) {
                    BillChooser(
                        payback = arjun,
                        current = current,
                        offer = { query ->
                            asked += query
                            offer(query)
                        },
                        onPick = {
                            picked += it
                            open = false
                        },
                        onDismiss = {
                            dismissed++
                            open = false
                        },
                    )
                }
            }
        }
    }

    private fun recent() = BillOffer.Recent(multiples = listOf(yuenKee, foodpanda), rest = listOf(tealive))

    @Test fun theRankedBillsComeBeforeTheRestEachUnderItsHeading() {
        assertEquals(
            listOf(
                ChooserLine.Heading(multiplesHeading(3_200L, "MYR"), first = true),
                ChooserLine.Bill(yuenKee),
                ChooserLine.Bill(foodpanda),
                ChooserLine.Heading(LAST_60_DAYS, first = false),
                ChooserLine.Bill(tealive),
            ),
            chooserLines(recent(), arjun),
        )
        assertEquals(
            "a group with no bills kept its heading",
            listOf(ChooserLine.Heading(LAST_60_DAYS, first = true), ChooserLine.Bill(tealive)),
            chooserLines(BillOffer.Recent(emptyList(), listOf(tealive)), arjun),
        )
    }

    @Test fun noBillComesFirstAndTheUnsearchedListIsAskedFor() {
        draw()
        compose.waitUntil { asked.isNotEmpty() }

        assertEquals(listOf(""), asked)
        compose.onNodeWithText(NO_BILL).assertExists()
        compose.onNodeWithText(multiplesHeading(3_200L, "MYR")).assertExists()
        compose.onNodeWithText("32.00 PAID BACK · YOUR SHARE 64.00").assertExists()
        compose.onNodeWithText(chooserFootnote(SEP_20, searched = false)).assertExists()
        compose.onNodeWithTag(BILL_LIST).performScrollToNode(hasTestTag(billRowTag(3L)))
    }

    @Test fun theCurrentPickIsTickedAndNoBillWhenThereIsNone() {
        draw(current = 1L)
        compose.onNodeWithTag(billRowTag(1L)).assertIsSelected()
        compose.onNodeWithTag(BILL_LIST).performScrollToNode(hasTestTag(billRowTag(3L)))
        compose.onNodeWithTag(billRowTag(3L)).assertIsNotSelected()
        compose.onNodeWithTag(NO_BILL_ROW).assertIsNotSelected()
    }

    @Test fun noBillIsTickedWhileNothingIsPicked() {
        draw(current = null)
        compose.onNodeWithTag(NO_BILL_ROW).assertIsSelected()
        compose.onNodeWithTag(billRowTag(1L)).assertIsNotSelected()
    }

    @Test fun aTapPicksAndClosesWithNoSave() {
        draw()
        compose.onNodeWithTag(BILL_LIST).performScrollToNode(hasTestTag(billRowTag(3L)))
        compose.onNodeWithTag(billRowTag(3L)).performClick()

        assertEquals(listOf<Long?>(3L), picked)
        assertEquals("a pick was a dismissal", 0, dismissed)
        compose.onAllNodesWithText(WHICH_BILL).assertCountEquals(0)
    }

    @Test fun noBillPicksNothing() {
        draw(current = 1L)
        compose.onNodeWithTag(NO_BILL_ROW).performClick()

        assertEquals(listOf<Long?>(null), picked)
    }

    @Test fun aSearchAsksForWhatWasTypedAndListsItAsOneList() {
        draw { query -> if (query.isEmpty()) recent() else BillOffer.Found(query, listOf(foodpanda, tealive), capped = false) }
        compose.onNodeWithTag(BILL_SEARCH).performTextInput("64")
        compose.waitUntil { "64" in asked }

        compose.onNodeWithText("2 BILLS, ALL HISTORY").assertExists()
        compose.onAllNodesWithText(LAST_60_DAYS).assertCountEquals(0)
        compose.onAllNodesWithText("Yuen Kee").assertCountEquals(0)
        compose.onNodeWithText(chooserFootnote(SEP_20, searched = true)).assertExists()
        compose.onNodeWithText(NO_BILL).assertExists()
    }

    @Test fun anEmptySearchSaysWhatItSearchedAndHowFar() {
        draw { query -> if (query.isEmpty()) recent() else BillOffer.Found(query, emptyList(), capped = false) }
        compose.onNodeWithTag(BILL_SEARCH).performTextInput("kfc")
        compose.waitUntil { "kfc" in asked }

        compose.onNodeWithText(nothingLine(SEP_20, "kfc")).assertExists()
        compose.onNodeWithText(EMPTY_FOOTNOTE).assertExists()
        compose.onNodeWithText(NO_BILL).assertExists()
    }

    @Test fun nothingInTheLastSixtyDaysSaysSo() {
        draw { BillOffer.Recent(emptyList(), emptyList()) }
        compose.waitUntil { asked.isNotEmpty() }
        compose.onNodeWithText(nothingLine(SEP_20, query = null)).assertExists()
    }

    @Test fun aReadTheLedgerRefusesClosesTheSheet() {
        draw { null }
        compose.waitUntil { dismissed == 1 }
        assertTrue(picked.isEmpty())
    }
}
