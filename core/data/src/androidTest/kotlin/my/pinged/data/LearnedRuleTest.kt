package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.RuleOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #48: the write a teaching save makes and the lookup stage two makes,
 * against the real encrypted database.
 */
@RunWith(AndroidJUnit4::class)
class LearnedRuleTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val rules get() = db.merchantRuleDao()
    private val txns get() = db.txnDao()
    private val food get() = db.categoryId("Food & Drinks")
    private val shopping get() = db.categoryId("Shopping")

    private fun pay(key: String?, at: Long = 1_000L): Long = txns.insert(
        sampleTxn(occurredAt = at, categoryId = db.categoryDao().requireUncategorizedId())
            .copy(merchantRaw = key, merchantDisplay = key, merchantKey = key),
    )

    private fun rulesOf(): List<my.pinged.data.entity.MerchantRule> = rules.pageFrom(0L, 100)

    @Test fun aTeachingSaveWritesAnExactLearnedRuleOnTheKeyAndLeavesTheRowUnedited() {
        val id = pay("MR DIY")
        // A one-off first, so the flag to be cleared is really set: a save
        // that never wrote `user_edited` would pass on a row that began at 0.
        txns.setCategory(id, shopping, 2_000L)
        assertTrue("The fixture's one-off did not set user_edited", requireNotNull(txns.byId(id)).userEdited)

        assertTrue(rules.teach(id, food, 3_000L))

        val rule = rulesOf().single()
        assertEquals("MR DIY", rule.pattern)
        assertEquals(MatchType.EXACT, rule.matchType)
        assertEquals(RuleOrigin.LEARNED, rule.origin)
        assertEquals(food, rule.categoryId)
        assertEquals("", rule.scopedPackage)
        val row = requireNotNull(txns.byId(id))
        assertEquals(food, row.categoryId)
        assertFalse("A teaching save left the row a one-off", row.userEdited)
        assertEquals(3_000L, row.updatedAt)
    }

    @Test fun aRetapUpdatesTheRuleInsteadOfWritingASecond() {
        val first = pay("MR DIY", at = 1_000L)
        val second = pay("MR DIY", at = 2_000L)
        rules.teach(first, food, 3_000L)

        rules.teach(second, shopping, 4_000L)

        assertEquals("A re-tap wrote a second rule for one identity", listOf(shopping), rulesOf().map { it.categoryId })
    }

    /**
     * A row the rule filed and a later teaching save corrected is still the
     * rule's, not a one-off: it is neither confirmed history nor read as
     * hand-set, so a later retroactive fix may move it without a conflict.
     */
    @Test fun aRowFiledByTheRuleAndCorrectedByAnotherTeachingSaveIsNotAOneOff() {
        val id = pay("MR DIY")
        rules.teach(id, food, 2_000L)
        assertFalse(requireNotNull(txns.byId(id)).userEdited)

        rules.teach(id, shopping, 3_000L)

        val row = requireNotNull(txns.byId(id))
        assertEquals(shopping, row.categoryId)
        assertFalse("A correction by teaching counted the row as a one-off", row.userEdited)
    }

    @Test fun theRuleIsWrittenOnTheCanonicalKeyOfAMergedRow() {
        val source = pay("YUENKEEHOMETOWNCAFE")
        pay("RESTORAN YUEN KEE HOME TOWN CAFE")
        db.merchantIdentityDao().merge(source = "YUENKEEHOMETOWNCAFE", target = "RESTORAN YUEN KEE HOME TOWN CAFE", targetName = "Yuen Kee")

        rules.teach(source, food, 3_000L)

        assertEquals("RESTORAN YUEN KEE HOME TOWN CAFE", rulesOf().single().pattern)
    }

    @Test fun aRuleOnTheCanonicalKeyIsFoundThroughEitherKey() {
        pay("YUENKEEHOMETOWNCAFE")
        val target = pay("RESTORAN YUEN KEE HOME TOWN CAFE")
        db.merchantIdentityDao().merge(source = "YUENKEEHOMETOWNCAFE", target = "RESTORAN YUEN KEE HOME TOWN CAFE", targetName = "Yuen Kee")
        rules.teach(target, food, 3_000L)

        assertEquals(food, rules.learnedCategoryFor("RESTORAN YUEN KEE HOME TOWN CAFE"))
        assertEquals("The merged-away key did not resolve to the identity's rule", food, rules.learnedCategoryFor("YUENKEEHOMETOWNCAFE"))
    }

    @Test fun aRuleOnAMergedAwayKeyStaysStoredButIsNotRead() {
        val source = pay("YUENKEEHOMETOWNCAFE")
        val target = pay("RESTORAN YUEN KEE HOME TOWN CAFE")
        // Both halves have a rule, so the merge copies nothing (#51) and the
        // source's is the one left stored and unread.
        rules.teach(source, food, 3_000L)
        rules.teach(target, shopping, 3_000L)
        db.merchantIdentityDao().merge(source = "YUENKEEHOMETOWNCAFE", target = "RESTORAN YUEN KEE HOME TOWN CAFE", targetName = "Yuen Kee")

        assertEquals("The merged-away key's rule was deleted", 2, rules.countAll())
        assertEquals(shopping, rules.learnedCategoryFor("YUENKEEHOMETOWNCAFE"))
        assertEquals(shopping, rules.learnedCategoryFor("RESTORAN YUEN KEE HOME TOWN CAFE"))
    }

    @Test fun aRowWithNoMerchantKeyTeachesNothing() {
        val id = pay(null)

        assertFalse(rules.teach(id, food, 3_000L))

        assertEquals(0, rules.countAll())
        assertEquals("A refused teach moved the row", db.categoryDao().requireUncategorizedId(), requireNotNull(txns.byId(id)).categoryId)
    }

    @Test fun onlyAnExactLearnedUnscopedRuleIsRead() {
        db.insertMerchantRule("MR DIY", shopping, origin = RuleOrigin.BUNDLED)
        db.insertMerchantRule("GRAB", shopping, matchType = MatchType.CONTAINS)
        db.insertMerchantRule("DIGI", shopping, scopedPackage = "my.bank.app")

        assertNull(rules.learnedCategoryFor("MR DIY"))
        assertNull(rules.learnedCategoryFor("GRAB"))
        assertNull(rules.learnedCategoryFor("DIGI"))
    }

    @Test fun learnedRulesAreListedByIdentity() {
        pay("MR DIY")
        rules.teach(pay("99 SPEEDMART"), food, 3_000L)
        rules.teach(pay("MR DIY", at = 2_000L), shopping, 3_000L)

        assertEquals(
            mapOf("99 SPEEDMART" to food, "MR DIY" to shopping),
            rules.learnedRules().associate { it.pattern to it.categoryId },
        )
    }
}
