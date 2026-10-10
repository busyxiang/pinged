package my.pinged.ledger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOf
import my.pinged.data.Databases
import my.pinged.data.dao.MerchantChoice
import my.pinged.data.dao.MerchantMember
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.MerchantActions
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.SEPARATE_NO_RULE
import my.pinged.ledger.home.bothHaveRules
import my.pinged.ledger.home.readMerchantSheet
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #51: what the merchant sheet says, before the tap, about the rules a
 * Merge or a Separate is about to combine or give back.
 */
@RunWith(AndroidJUnit4::class)
class MerchantSheetRulesTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    private val own = "MR DIY KLCC"
    private val other = "MR D.I.Y."

    private fun sheet(
        ruleCategory: String? = null,
        otherRule: String? = null,
        mergedInto: String? = null,
        noOwnRule: Boolean = false,
        members: List<MerchantMember> = emptyList(),
    ) = MerchantSheetState(
        ownKey = own,
        identityKey = if (mergedInto != null) other else own,
        name = "Mr DIY",
        derivedName = "Mr DIY",
        txnCount = 2,
        mergedInto = mergedInto,
        members = members,
        others = listOf(MerchantChoice(other, "Mr D.I.Y.", "Mr D.I.Y.", 3, ruleCategory = otherRule)),
        suggested = emptySet(),
        ruleCategory = ruleCategory,
        noOwnRule = noOwnRule,
    )

    private fun show(sheet: MerchantSheetState) {
        val flow = flowOf(
            PagingData.from(
                emptyList<my.pinged.ledger.home.LedgerItem>(),
                LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true)),
            ),
        )
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = flow.collectAsLazyPagingItems(),
                    read = LedgerRead(),
                    storageUnavailable = false,
                    onAssign = { _, _, _ -> },
                    onOpenSettings = {},
                    merchantSheet = sheet,
                    merchantActions = MerchantActions.None,
                )
            }
        }
        // The sheet's own list is up once its heading is.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Mr D.I.Y.").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test fun whenBothHalvesHaveRulesTheRowNamesTheCategoryTheMergedShopWillBeFiledUnder() {
        show(sheet(ruleCategory = "Shopping", otherRule = "Food & Drinks"))
        assertEquals("The target's category, not the source's", 1, count(bothHaveRules("Food & Drinks")))
        assertEquals(0, count(bothHaveRules("Shopping")))
    }

    @Test fun theRowSaysNothingWhenOnlyOneHalfHasARule() {
        show(sheet(ruleCategory = "Shopping", otherRule = null))
        assertEquals(0, count(bothHaveRules("Shopping")))
        compose.waitForIdle()
        assertEquals("No both-have-rules line of any category", 0, compose.onAllNodesWithText("Both have a rule", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun theRowSaysNothingWhenTheTargetAloneHasARule() {
        show(sheet(ruleCategory = null, otherRule = "Food & Drinks"))
        assertEquals(0, compose.onAllNodesWithText("Both have a rule", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun separateSaysASourceWithNoRuleOfItsOwnGoesBackToUncategorized() {
        show(sheet(mergedInto = "Mr D.I.Y.", ruleCategory = "Food & Drinks", noOwnRule = true))
        assertEquals(1, count(SEPARATE_NO_RULE))
    }

    @Test fun separateSaysNothingOfTheKindWhenTheSourceHasItsOwnRule() {
        show(sheet(mergedInto = "Mr D.I.Y.", ruleCategory = "Food & Drinks", noOwnRule = false))
        assertEquals(0, count(SEPARATE_NO_RULE))
    }

    @Test fun separateSaysNothingWhenTheMergedShopHasNoRuleToLose() {
        show(sheet(mergedInto = "Mr D.I.Y.", ruleCategory = null, noOwnRule = true))
        assertEquals(0, count(SEPARATE_NO_RULE))
    }

    @Test fun eachMemberWithNoRuleOfItsOwnCarriesTheSameWarning() {
        show(
            sheet(
                ruleCategory = "Food & Drinks",
                members = listOf(
                    MerchantMember("A KEY", "A key", noOwnRule = true),
                    MerchantMember("B KEY", "B key", noOwnRule = false),
                ),
            ),
        )
        assertEquals("Only the member without a rule of its own", 1, count(SEPARATE_NO_RULE))
    }

    @Test fun aMemberWithNoRuleIsNotWarnedAboutWhenTheMergedShopHasNoRuleToLose() {
        show(sheet(ruleCategory = null, members = listOf(MerchantMember("A KEY", "A key", noOwnRule = true))))
        assertEquals(0, count(SEPARATE_NO_RULE))
    }

    // ---- read from the real database ----------------------------------------

    private fun pay(key: String, at: Long) = Databases.txnDao(context).insert(
        ledgerTxn(amountSen = 100L, occurredAt = at, categoryId = Databases.categoryDao(context).requireUncategorizedId())
            .copy(merchantKey = key, merchantRaw = key, merchantDisplay = key),
    )

    private fun teach(key: String, category: String) {
        val categories = Databases.categoryDao(context)
        val id = categories.all().single { it.name == category }.id
        Databases.merchantRuleDao(context).insert(
            MerchantRule(
                matchType = MatchType.EXACT,
                pattern = key,
                merchantDisplay = key,
                categoryId = id,
                origin = RuleOrigin.LEARNED,
                priority = MerchantRuleDao.LEARNED_PRIORITY,
            ),
        )
    }

    @Test fun theSheetReadsEachChoicesRuleAndTheOwnRulesPresence() {
        context.freshLedger()
        pay(own, 1_000L); pay(other, 2_000L); pay("THIRD", 3_000L)
        teach(own, "Shopping"); teach(other, "Food & Drinks")
        val dao = Databases.merchantIdentityDao(context)

        val sheet = readMerchantSheet(dao, own)!!

        assertEquals("Shopping", sheet.ruleCategory)
        assertEquals(
            mapOf(other to "Food & Drinks", "THIRD" to null),
            sheet.others.associate { it.identityKey to it.ruleCategory },
        )

        dao.merge(own, other, "Mr D.I.Y.")
        val merged = readMerchantSheet(dao, own)!!
        assertEquals("Food & Drinks", merged.ruleCategory)
        assertEquals(false, merged.noOwnRule)
        assertEquals(listOf(false), readMerchantSheet(dao, other)!!.members.map { it.noOwnRule })
    }

    @Test fun aSourceWithNoRuleReadsAsHavingNoneOfItsOwnAfterAMerge() {
        context.freshLedger()
        pay(own, 1_000L); pay(other, 2_000L)
        teach(other, "Food & Drinks")
        val dao = Databases.merchantIdentityDao(context)
        dao.merge(own, other, "Mr D.I.Y.")

        assertEquals(true, readMerchantSheet(dao, own)!!.noOwnRule)
        assertEquals(listOf(true), readMerchantSheet(dao, other)!!.members.map { it.noOwnRule })
    }
}
