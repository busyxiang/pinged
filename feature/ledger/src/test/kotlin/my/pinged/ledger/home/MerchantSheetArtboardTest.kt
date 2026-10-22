package my.pinged.ledger.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.MerchantChoice
import my.pinged.data.dao.MerchantMember
import my.pinged.data.dao.MerchantTotal
import my.pinged.ui.theme.CLOSE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * `design/MerchantSheet.dc.html` (#231), drawn with the board's own figures:
 * the shared sheet chrome, the NAME half's reach, and a merge that is chosen
 * first and committed second.
 */
@RunWith(AndroidJUnit4::class)
class MerchantSheetArtboardTest {
    @get:Rule val compose = createComposeRule()

    private val merged = mutableListOf<String>()
    private var closed = 0

    private val sheet = MerchantSheetState(
        ownKey = "TNG*99SPEEDMART",
        identityKey = "TNG*99SPEEDMART",
        name = "99 Speedmart",
        derivedName = "99 Speedmart",
        txnCount = 11,
        mergedInto = null,
        members = listOf(MerchantMember("99 SPEEDMART", "99 SPEEDMART", txnCount = 2)),
        others = listOf(
            MerchantChoice("TNG*SPEEDMART99", "Speedmart 99 Kepong", "Speedmart 99 Kepong", 2),
            MerchantChoice("99 SPEEDMART SDN BHD", "99 Speedmart Sdn Bhd", "99 Speedmart Sdn Bhd", 1),
        ),
        suggested = setOf("TNG*SPEEDMART99", "99 SPEEDMART SDN BHD"),
        month = YearMonth.of(2026, 9),
        monthTotals = listOf(MerchantTotal("TNG*99SPEEDMART", "99 Speedmart", "MYR", 26_815L, 11)),
    )

    private fun draw(shown: MerchantSheetState = sheet) {
        val actions = MerchantActions({}, {}, { merged += it }, {}, { closed++ })
        compose.setContent { MerchantSheet(shown, actions) }
    }

    private val kepong = sheet.others.first()

    /** Scrolls the sheet's lazy list to [text], which `performScrollTo` cannot reach before it is composed. */
    private fun scrollTo(text: String) =
        compose.onNodeWithTag(MERCHANT_LIST_TAG).performScrollToNode(hasText(text)).let { compose.onNodeWithText(text) }

    @Test fun theSheetIsTheSharedChromeWithATitleAndAClose() {
        draw()
        assertEquals("No drag handle", 0, compose.onAllNodesWithContentDescription("Drag handle").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription(CLOSE).performClick()
        assertEquals(1, closed)
    }

    @Test fun theHeaderCountsTheRowsMonth() {
        draw()
        assertEquals("The title, and the NAME field it fills", 2, compose.onAllNodesWithText("99 Speedmart").fetchSemanticsNodes().size)
        compose.onNodeWithText("11 PAYMENTS · RM268.15 IN SEPTEMBER").assertExists()
    }

    @Test fun aMonthWithNothingCountedSaysSoRatherThanZero() {
        assertEquals("NO PAYMENTS COUNTED IN AUGUST", monthLine(YearMonth.of(2026, 8), emptyList()))
        assertEquals(
            "1 PAYMENT · RM4.20 IN AUGUST",
            monthLine(YearMonth.of(2026, 8), listOf(MerchantTotal("K", null, "MYR", 420L, 1))),
        )
    }

    @Test fun alsoPaidAsListsEachKeyWithItsOwnCount() {
        draw()
        scrollTo(ALSO_PAID_AS)
        scrollTo("TNG*99SPEEDMART · 9")
        scrollTo("99 SPEEDMART · 2")
    }

    @Test fun eachCandidateShowsItsCountAndRawKey() {
        draw()
        scrollTo("2 PAYMENTS · TNG*SPEEDMART99")
    }

    @Test fun theBandSaysWhatMovesAndWhoseNameWins() {
        draw()
        scrollTo("Speedmart 99 Kepong").performClick()
        scrollTo("11 PAYMENTS FROM 99 SPEEDMART WILL COUNT AS SPEEDMART 99 KEPONG")
        scrollTo("ITS NAME GIVES WAY TO SPEEDMART 99 KEPONG'S")
    }

    @Test fun theBandNamesTheRuleThatFilesTheMergedShop() {
        val both = mergeBand(sheet.copy(ruleCategory = "Shopping"), kepong.copy(ruleCategory = "Groceries"))
        assertEquals("ITS NAME AND RULE GIVE WAY TO SPEEDMART 99 KEPONG'S", both[1])
        assertEquals("The target's rule files both", "NEW PAYMENTS WILL BE FILED UNDER GROCERIES", both[2])

        val carried = mergeBand(sheet.copy(ruleCategory = "Shopping"), kepong)
        assertEquals("ITS NAME GIVES WAY TO SPEEDMART 99 KEPONG'S", carried[1])
        assertEquals("A rule only this one has is carried", "NEW PAYMENTS WILL BE FILED UNDER SHOPPING", carried[2])

        assertEquals("Neither has a rule: no filing line", 2, mergeBand(sheet, kepong).size)
    }

    @Test fun withNothingSuggestedTheBlankSearchListsTheMostRecent() {
        val others = (1..8).map { MerchantChoice("K$it", "Shop $it", "Shop $it", 1) }
        val blank = candidates(others, suggested = emptySet(), filter = " ")
        assertEquals(RECENT, blank.heading)
        assertEquals(others.take(RECENT_COUNT), blank.choices)

        val typed = candidates(others, suggested = setOf("K1"), filter = "shop 7")
        assertEquals(null, typed.heading)
        assertEquals(listOf(others[6]), typed.choices)
    }

    @Test fun theNameHalfStatesItsReachBeforeAnythingIsTyped() {
        draw()
        scrollTo("NAME")
        scrollTo("11 TRANSACTIONS WILL SHOW A NEW NAME")
        scrollTo(SAVE_NAME).assertIsNotEnabled()
    }

    @Test fun choosingACandidateMergesNothingUntilTheCommitButton() {
        draw()
        scrollTo("Speedmart 99 Kepong").performClick()
        assertEquals("A tap on a candidate only chooses it", emptyList<String>(), merged)

        scrollTo("Count it as Speedmart 99 Kepong").assertIsEnabled().performClick()
        assertEquals(listOf("TNG*SPEEDMART99"), merged)
    }

    @Test fun suggestionsSitUnderTheirOwnHeading() {
        draw()
        scrollTo(SUGGESTED)
        assertEquals(1, compose.onAllNodesWithText(SUGGESTED, substring = true).fetchSemanticsNodes().size)
    }
}
