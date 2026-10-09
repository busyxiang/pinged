package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #51 (spec 6.4 as amended by #35): what Merge and Separate do to the
 * learned rule of each half. A rule is read through the identity, so the
 * target's wins by resolution; the only write is the copy when just the source
 * had one.
 */
@RunWith(AndroidJUnit4::class)
class MerchantRuleCarryTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val rules get() = db.merchantRuleDao()
    private val merchants get() = db.merchantIdentityDao()
    private val food get() = db.categoryId("Food & Drinks")
    private val shopping get() = db.categoryId("Shopping")

    private val source = "MR DIY KLCC"
    private val target = "MR D.I.Y."

    private fun pay(key: String, at: Long): Long = db.txnDao().insert(
        sampleTxn(occurredAt = at, categoryId = db.categoryDao().requireUncategorizedId())
            .copy(merchantRaw = key, merchantDisplay = key, merchantKey = key),
    )

    private fun teach(key: String, categoryId: Long, hitCount: Int = 0) {
        rules.insert(
            MerchantRule(
                matchType = MatchType.EXACT,
                pattern = key,
                merchantDisplay = key,
                categoryId = categoryId,
                origin = RuleOrigin.LEARNED,
                priority = MerchantRuleDao.LEARNED_PRIORITY,
                hitCount = hitCount,
            ),
        )
    }

    private fun merge() = merchants.merge(source = source, target = target, targetName = target)

    @Test fun whenBothHalvesHaveRulesTheTargetsWinsForCapturesUnderEitherKey() {
        pay(source, 1_000L); pay(target, 2_000L)
        teach(source, shopping); teach(target, food)

        merge()

        assertEquals(food, rules.learnedCategoryFor(target))
        assertEquals("A capture under the source key must file under the target's rule", food, rules.learnedCategoryFor(source))
    }

    @Test fun whenBothHalvesHaveRulesTheSourcesStaysStoredAndSeparateRestoresIt() {
        pay(source, 1_000L); pay(target, 2_000L)
        teach(source, shopping); teach(target, food)

        merge()
        assertEquals(
            setOf(source to shopping, target to food),
            rules.pageFrom(0L, 10).map { it.pattern to it.categoryId }.toSet(),
        )

        merchants.separate(source)

        assertEquals(shopping, rules.learnedCategoryFor(source))
        assertEquals(food, rules.learnedCategoryFor(target))
    }

    @Test fun whenOnlyTheSourceHasARuleItIsCopiedToTheTargetWithAHitCountOfZero() {
        pay(source, 1_000L); pay(target, 2_000L)
        teach(source, shopping, hitCount = 7)

        merge()

        val copy = rules.pageFrom(0L, 10).single { it.pattern == target }
        assertEquals(shopping, copy.categoryId)
        assertEquals(0, copy.hitCount)
        assertEquals(MatchType.EXACT, copy.matchType)
        assertEquals(RuleOrigin.LEARNED, copy.origin)
        assertEquals("", copy.scopedPackage)
        assertEquals("The source's own rule must stay where it is", 7, rules.pageFrom(0L, 10).single { it.pattern == source }.hitCount)
        assertEquals(shopping, rules.learnedCategoryFor(target))
        assertEquals(shopping, rules.learnedCategoryFor(source))
    }

    @Test fun separateAfterASourceOnlyMergeGivesTheSourceItsOwnRuleBack() {
        pay(source, 1_000L); pay(target, 2_000L)
        teach(source, shopping)
        merge()

        merchants.separate(source)

        assertEquals(shopping, rules.learnedCategoryFor(source))
    }

    @Test fun whenOnlyTheTargetHasARuleBothKeysFileUnderItAndSeparateLeavesTheSourceWithNone() {
        pay(source, 1_000L); pay(target, 2_000L)
        teach(target, food)

        merge()
        assertEquals(food, rules.learnedCategoryFor(source))
        assertEquals("A merge wrote a rule nobody had", 1, rules.countAll())

        merchants.separate(source)

        assertNull("A source with no rule of its own goes back to no rule", rules.learnedCategoryFor(source))
        assertEquals(food, rules.learnedCategoryFor(target))
    }

    @Test fun whenNeitherHasARuleNothingIsWritten() {
        pay(source, 1_000L); pay(target, 2_000L)
        merge()
        assertEquals(0, rules.countAll())
        assertNull(rules.learnedCategoryFor(source))
    }

    @Test fun aMergeThatDoesNothingBecauseTheKeysAreOneShopCopiesNoRule() {
        pay(source, 1_000L); pay(target, 2_000L)
        teach(source, shopping)
        merge()
        val before = rules.pageFrom(0L, 10)

        merge()

        assertEquals(before, rules.pageFrom(0L, 10))
    }

    @Test fun aMergeReFilesNoTransactionEvenWhenItCopiesARule() {
        val a = pay(source, 1_000L)
        val b = pay(target, 2_000L)
        teach(source, shopping)
        val before = db.txnDao().pageFrom(0L, 100)

        merge()

        assertEquals(before, db.txnDao().pageFrom(0L, 100))
        assertEquals(db.categoryDao().requireUncategorizedId(), requireNotNull(db.txnDao().byId(a)).categoryId)
        assertEquals(db.categoryDao().requireUncategorizedId(), requireNotNull(db.txnDao().byId(b)).categoryId)
    }

    @Test fun theCopyReachesAKeyThatWasAlreadyMergedIntoTheSource() {
        val member = "MR DIY SETIA"
        pay(member, 500L); pay(source, 1_000L); pay(target, 2_000L)
        teach(source, shopping)
        merchants.merge(source = member, target = source, targetName = source)

        merge()

        assertEquals(shopping, rules.learnedCategoryFor(member))
        assertEquals(shopping, rules.learnedCategoryFor(target))
    }
}
