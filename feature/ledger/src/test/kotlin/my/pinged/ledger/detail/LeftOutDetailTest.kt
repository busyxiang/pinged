package my.pinged.ledger.detail

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.ExclusionReason
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Muted
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

/**
 * `design/DetailExcluded.dc.html` (#229): Detail for a row the user marked
 * Not spending says it is not counted, in the eyebrow, the badge, the
 * Category row and the small print, greys its hero, and offers only Count as
 * spending and Delete.
 */
@RunWith(AndroidJUnit4::class)
class LeftOutDetailTest {
    @get:Rule val compose = createComposeRule()

    private fun draw(reason: ExclusionReason?) {
        val txn = ledgerTxn(amountSen = 5_000L, isExcluded = reason != null, exclusionReason = reason, id = 9L)
        val row = DetailRow(
            row = FeedRow(txn, identityKey = txn.merchantKey, displayName = MERCHANT),
            categoryName = GROCERIES,
            captureTitle = "DuitNow Payment",
            captureText = "You have paid RM50.00 to TAN WEI MING",
            captureBigText = null,
            matchedRuleId = "DUITNOW-PAID-V1",
            packVersion = 7,
        )
        val chooser = ChooserRead(categories = listOf(Category(id = 1L, name = GROCERIES, iconKey = "cart", sortOrder = 0)))
        compose.setContent {
            DetailScreenContent(
                DetailRead(row, chooser),
                storageUnavailable = false,
                onBack = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                zone = ZoneOffset.UTC,
            )
        }
    }

    @Test fun aLeftOutRowSaysItIsNotCounted() {
        draw(ExclusionReason.USER)
        compose.onNodeWithText("PAID · NOT COUNTED").performScrollTo()
        compose.onNodeWithText("NOT SPENDING · YOU LEFT IT OUT").performScrollTo()
        compose.onNodeWithText("Not counted while left out").performScrollTo()
        compose.onAllNodesWithText(GROCERIES).assertCountEquals(0)
        compose.onNodeWithText("KEPT IN THE LIST, GREYED · LEFT OUT OF EVERY TOTAL AND CHART").performScrollTo()
    }

    @Test fun aLeftOutRowOffersOnlyCountAsSpendingAndDelete() {
        draw(ExclusionReason.USER)
        compose.onAllNodesWithText(CHANGE_CATEGORY).assertCountEquals(0)
        compose.onAllNodesWithText(NOT_SPENDING).assertCountEquals(0)
        compose.onNodeWithText(COUNT_AS_SPENDING).performScrollTo()
        compose.onNodeWithText(DELETE).performScrollTo()
    }

    /** The board's hero: the ringgit in Muted and the sen in Faint, where a counted row's are Ink and Muted. */
    @Test fun aLeftOutRowsHeroIsGreyed() {
        draw(ExclusionReason.USER)
        assertEquals(listOf(Muted, Faint), heroColours())
    }

    @Test fun aCountedRowKeepsItsCategoryAndInkHero() {
        draw(null)
        compose.onNodeWithText(PAID).performScrollTo()
        compose.onNodeWithText(GROCERIES).performScrollTo()
        compose.onAllNodesWithText("NOT SPENDING", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("KEPT IN THE LIST", substring = true).assertCountEquals(0)
        assertEquals(listOf(Ink, Muted), heroColours())
    }

    /** The colour each part of the hero is drawn in: the whole figure's, then the sen's. */
    private fun heroColours(): List<Any?> {
        val text = compose.onNodeWithTag(AMOUNT).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)!!.single()
        val whole = text.spanStyles.singleOrNull { it.start == 0 && it.end == text.length }?.item?.color
        val sen = text.spanStyles.single { it.start == text.lastIndexOf('.') }.item.color
        return listOf(whole, sen)
    }

    private companion object {
        const val GROCERIES = "Groceries"
    }
}
