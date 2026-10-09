package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.RuleOrigin
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #50: the settings list of learned merchants and its delete, against
 * the real encrypted database.
 */
@RunWith(AndroidJUnit4::class)
class LearnedMerchantsTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val rules get() = db.merchantRuleDao()
    private val txns get() = db.txnDao()
    private val identities get() = db.merchantIdentityDao()
    private val food get() = db.categoryId("Food & Drinks")
    private val shopping get() = db.categoryId("Shopping")

    private fun pay(
        key: String,
        at: Long = 1_000L,
        state: TxnState = TxnState.COMMITTED,
        excluded: Boolean = false,
    ): Long = txns.insert(
        sampleTxn(
            occurredAt = at,
            state = state,
            isExcluded = excluded,
            categoryId = db.categoryDao().requireUncategorizedId(),
        ).copy(merchantRaw = key, merchantDisplay = key, merchantKey = key),
    )

    private fun teach(key: String, category: Long, at: Long = 1_000L) = rules.teach(pay(key, at), category, 5_000L)

    private fun mergeYuenKee() =
        identities.merge("YUENKEEHOMETOWNCAFE", "RESTORAN YUEN KEE HOME TOWN CAFE", "Yuen Kee")

    @Test fun theListIsOrderedAToZByNameWhateverTheCase() {
        teach("ZUS COFFEE", food)
        teach("99 SPEEDMART", food)
        teach("mr diy", shopping)

        assertEquals(listOf("99 SPEEDMART", "mr diy", "ZUS COFFEE"), rules.learnedMerchants().map { it.name })
    }

    @Test fun aRowShowsTheUsersNameElseTheMerchantDisplay() {
        teach("MR DIY", shopping)
        teach("ZUS COFFEE", food)
        identities.rename("MR DIY", "Hardware shop", derivedName = "MR DIY")

        val rows = rules.learnedMerchants()

        assertEquals(listOf("Hardware shop", "ZUS COFFEE"), rows.map { it.name })
        assertEquals(listOf("Shopping", "Food & Drinks"), rows.map { it.categoryName })
    }

    /** A rule's own `merchant_display` is its text when the rule was written; nothing updates it. */
    @Test fun theFallbackNameFollowsTheIdentitysLatestPaymentNotTheDisplayFrozenAtTeaching() {
        teach("MR DIY", shopping)
        val later = pay("MR DIY", at = 2_000L)
        db.openHelper.writableDatabase.execSQL("UPDATE txn SET merchant_display = 'Mr DIY Cheras' WHERE id = $later")

        assertEquals("Mr DIY Cheras", rules.learnedMerchants().single().name)
    }

    @Test fun aRuleReKeyedByAPackUpgradeIsNamedByTheRowsUnderItsNewKey() {
        teach("MR DIY", shopping)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE txn SET merchant_key = 'MR DIY MY', merchant_display = 'Mr DIY Malaysia' WHERE merchant_key = 'MR DIY'",
        )
        identities.carry("MR DIY", "MR DIY MY")

        val row = rules.learnedMerchants().single()
        assertEquals("MR DIY MY", row.identity)
        assertEquals("Mr DIY Malaysia", row.name)
    }

    @Test fun aRuleWithNoPaymentsLeftKeepsTheDisplayItWasTaughtUnder() {
        teach("MR DIY", shopping)
        db.openHelper.writableDatabase.execSQL("DELETE FROM txn")

        assertEquals("MR DIY", rules.learnedMerchants().single().name)
    }

    @Test fun aRejectedRowDoesNotNameTheRule() {
        teach("MR DIY", shopping)
        val rejected = pay("MR DIY", at = 9_000L, state = TxnState.REJECTED)
        db.openHelper.writableDatabase.execSQL("UPDATE txn SET merchant_display = 'Not a payment' WHERE id = $rejected")

        assertEquals("MR DIY", rules.learnedMerchants().single().name)
    }

    @Test fun paymentsAreTheCommittedNonExcludedRowsOfTheIdentity() {
        teach("MR DIY", shopping)
        pay("MR DIY", at = 2_000L)
        pay("MR DIY", at = 3_000L, excluded = true)
        pay("MR DIY", at = 4_000L, state = TxnState.PENDING)
        pay("MR DIY", at = 5_000L, state = TxnState.REJECTED)
        pay("OTHER SHOP", at = 6_000L)

        assertEquals(2, rules.learnedMerchants().single().payments)
    }

    @Test fun paymentsAreLiveAndCountAMergedIdentityAcrossItsKeys() {
        val source = pay("YUENKEEHOMETOWNCAFE")
        pay("RESTORAN YUEN KEE HOME TOWN CAFE", at = 2_000L)
        pay("RESTORAN YUEN KEE HOME TOWN CAFE", at = 3_000L)
        mergeYuenKee()
        rules.teach(source, food, 5_000L)

        val row = rules.learnedMerchants().single()
        assertEquals("Yuen Kee", row.name)
        assertEquals("A merged identity's payments were not counted across its keys", 3, row.payments)

        pay("YUENKEEHOMETOWNCAFE", at = 7_000L)
        assertEquals("The count was not live", 4, rules.learnedMerchants().single().payments)
    }

    @Test fun aDormantSourceRuleIsNeverListedOrCounted() {
        val source = pay("YUENKEEHOMETOWNCAFE")
        val target = pay("RESTORAN YUEN KEE HOME TOWN CAFE", at = 2_000L)
        rules.teach(source, food, 5_000L)
        rules.teach(target, shopping, 5_000L)
        mergeYuenKee()

        assertEquals("The fixture's dormant rule is not stored", 2, rules.countAll())
        assertEquals(1, rules.learnedMerchants().size)
        assertEquals(1, rules.learnedCount())
    }

    @Test fun onlyExactLearnedUnscopedRulesAreListed() {
        teach("MR DIY", shopping)
        db.insertMerchantRule("GRAB", shopping, origin = RuleOrigin.BUNDLED)
        db.insertMerchantRule("DIGI", shopping, matchType = MatchType.CONTAINS)
        db.insertMerchantRule("TNG", shopping, scopedPackage = "my.bank.app")

        assertEquals(listOf("MR DIY"), rules.learnedMerchants().map { it.identity })
        assertEquals(1, rules.learnedCount())
    }

    @Test fun deleteRemovesTheRuleAndTheDormantSourceRuleAndNothingElse() {
        val source = pay("YUENKEEHOMETOWNCAFE")
        val target = pay("RESTORAN YUEN KEE HOME TOWN CAFE", at = 2_000L)
        rules.teach(source, food, 5_000L)
        mergeYuenKee()
        rules.teach(target, shopping, 6_000L)
        teach("MR DIY", shopping)
        assertEquals("The fixture should hold a dormant, a live and an unrelated rule", 3, rules.countAll())

        assertEquals(2, rules.deleteLearnedOfIdentity("RESTORAN YUEN KEE HOME TOWN CAFE"))

        assertEquals(listOf("MR DIY"), rules.learnedRules().map { it.pattern })
    }

    @Test fun deleteTouchesNoTransactionAndLeavesTheRowsItFiledUnconfirmed() {
        val id = pay("MR DIY")
        rules.teach(id, food, 5_000L)
        val before = txns.byId(id)

        rules.deleteLearnedOfIdentity("MR DIY")

        assertEquals("Delete changed a txn row", before, txns.byId(id))
        assertFalse("Delete confirmed history", requireNotNull(txns.byId(id)).userEdited)
    }

    @Test fun separateAfterDeleteRestoresNoRule() {
        val source = pay("YUENKEEHOMETOWNCAFE")
        pay("RESTORAN YUEN KEE HOME TOWN CAFE", at = 2_000L)
        rules.teach(source, food, 5_000L)
        mergeYuenKee()

        rules.deleteLearnedOfIdentity("RESTORAN YUEN KEE HOME TOWN CAFE")
        identities.separate("YUENKEEHOMETOWNCAFE")

        assertEquals(0, rules.countAll())
        assertNull(rules.learnedCategoryFor("YUENKEEHOMETOWNCAFE"))
        assertNull(rules.learnedCategoryFor("RESTORAN YUEN KEE HOME TOWN CAFE"))
    }

    @Test fun aDeletedRuleFilesNothingAndTeachingAgainWritesOneFresh() {
        val id = pay("MR DIY")
        rules.teach(id, food, 5_000L)
        rules.deleteLearnedOfIdentity("MR DIY")
        assertNull("A later capture would still be filed by the deleted rule", rules.learnedCategoryFor("MR DIY"))

        rules.teach(pay("MR DIY", at = 2_000L), shopping, 6_000L)

        assertEquals(shopping, rules.learnedCategoryFor("MR DIY"))
        assertEquals(1, rules.learnedCount())
    }
}
