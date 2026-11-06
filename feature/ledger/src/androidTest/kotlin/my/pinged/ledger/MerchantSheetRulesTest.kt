package my.pinged.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
import my.pinged.ledger.home.readMerchantSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #51: the merchant sheet's read of the rules a Separate is about to
 * give back. What the sheet says of them is `RowHostTest`'s, on the JVM; what
 * a merge says is the Card band's,
 * `MerchantSheetArtboardTest.theBandNamesTheRuleThatFilesTheMergedShop`.
 */
@RunWith(AndroidJUnit4::class)
class MerchantSheetRulesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    private val own = "MR DIY KLCC"

    /** The header's month, which nothing here reads. */
    private val ANY_DAY = LocalDates.of(0L)
    private val other = "MR D.I.Y."

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

        val sheet = readMerchantSheet(dao, Databases.txnDao(context), own, ANY_DAY)!!

        assertEquals("Shopping", sheet.ruleCategory)
        assertEquals(
            mapOf(other to "Food & Drinks", "THIRD" to null),
            sheet.others.associate { it.identityKey to it.ruleCategory },
        )

        dao.merge(own, other, "Mr D.I.Y.")
        val merged = readMerchantSheet(dao, Databases.txnDao(context), own, ANY_DAY)!!
        assertEquals("Food & Drinks", merged.ruleCategory)
        assertEquals(false, merged.noOwnRule)
        assertEquals(listOf(false), readMerchantSheet(dao, Databases.txnDao(context), other, ANY_DAY)!!.members.map { it.noOwnRule })
    }

    @Test fun aSourceWithNoRuleReadsAsHavingNoneOfItsOwnAfterAMerge() {
        context.freshLedger()
        pay(own, 1_000L); pay(other, 2_000L)
        teach(other, "Food & Drinks")
        val dao = Databases.merchantIdentityDao(context)
        dao.merge(own, other, "Mr D.I.Y.")

        assertEquals(true, readMerchantSheet(dao, Databases.txnDao(context), own, ANY_DAY)!!.noOwnRule)
        assertEquals(listOf(true), readMerchantSheet(dao, Databases.txnDao(context), other, ANY_DAY)!!.members.map { it.noOwnRule })
    }
}
