package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
import my.pinged.data.entity.Txn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONTEXT.md's Merge: separating restores the split exactly. Every case merges
 * a source into a target and separates it again, over the starting states a
 * user can reach, and compares everything a screen or stage two can observe
 * against the start.
 *
 * What the merge gave the target is the target's own from then on, so the
 * expectation is the start with exactly that added: the name the sheet showed
 * for it, when it had none, and the source's rule, when it had none.
 */
@RunWith(AndroidJUnit4::class)
class MerchantRoundTripTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val merchants get() = db.merchantIdentityDao()
    private val rules get() = db.merchantRuleDao()
    private val food get() = db.categoryId("Food & Drinks")
    private val shopping get() = db.categoryId("Shopping")

    private val source = "MR DIY KLCC"
    private val target = "MR D.I.Y."
    private val member = "MR DIY SETIA"
    private val oldKey = "MR DIY KLCC OLD PACK"

    private fun pay(key: String, at: Long): Long = db.txnDao().insert(
        sampleTxn(occurredAt = at, categoryId = db.categoryDao().requireUncategorizedId())
            .copy(merchantRaw = key, merchantDisplay = key, merchantKey = key),
    )

    private fun teach(key: String, categoryId: Long) {
        rules.insert(
            MerchantRule(
                matchType = MatchType.EXACT,
                pattern = key,
                merchantDisplay = key,
                categoryId = categoryId,
                origin = RuleOrigin.LEARNED,
                priority = MerchantRuleDao.LEARNED_PRIORITY,
            ),
        )
    }

    /** What a pack upgrade does to one row (spec 5.5): the key changes and the carry follows in the same transaction. */
    private fun rekey(from: String, to: String) = db.runInTransaction {
        db.openHelper.writableDatabase.execSQL("UPDATE txn SET merchant_key = '$to' WHERE merchant_key = '$from'")
        merchants.carry(from = from, to = to)
    }

    /** Everything one moment of the ledger lets a screen or stage two observe about [keys]. */
    private data class Seen(
        val txns: List<Txn>,
        val canonical: Map<String, String?>,
        val names: Map<String, String?>,
        val rules: Map<String, Long?>,
        val filedLive: Map<String, Long?>,
        val filedSnapshot: Map<String, Long?>,
        val choices: List<Triple<String, String?, Int>>,
        val learned: List<Pair<String, Long>>,
    )

    private fun seen(vararg keys: String): Seen {
        val snapshot = readLearnedRules(merchants)
        val live = LiveLearnedRules(merchants)
        return Seen(
            txns = db.txnDao().pageFrom(0L, 100),
            canonical = keys.associateWith(merchants::canonicalOf),
            names = keys.associateWith(merchants::nameOf),
            rules = keys.associateWith { merchants.learnedCategoryOn(it) },
            filedLive = keys.associateWith(live::categoryFor),
            filedSnapshot = keys.associateWith(snapshot::categoryFor),
            choices = merchants.choices().map { Triple(it.identityKey, it.displayName, it.txnCount) }.sortedBy { it.first },
            learned = merchants.learnedMerchants().map { it.identity to it.categoryId }.sortedBy { it.first },
        )
    }

    /** [before] as it is once the merge has given the target [targetName] and [sourceRule] where it had none. */
    private fun expectedAfter(before: Seen, targetName: String, sourceRule: Long?): Seen {
        val ruleOfTarget = before.rules.getValue(target) ?: sourceRule
        val rules = before.rules + (target to ruleOfTarget)
        return before.copy(
            names = before.names + (target to (before.names.getValue(target) ?: targetName)),
            rules = rules,
            // No key is merged once separated, so a key is filed by its own rule.
            filedLive = rules,
            filedSnapshot = rules,
            learned = rules.mapNotNull { (key, category) -> category?.let { key to it } }.sortedBy { it.first },
        )
    }

    private class Case(
        val name: String,
        val sourceRule: Boolean = false,
        val targetRule: Long? = null,
        val sourceName: String? = null,
        val targetName: String? = null,
        val rekeyed: Boolean = false,
    )

    private val cases get() = listOf(
        Case("neither has a rule or a name"),
        Case("only the source has a rule", sourceRule = true),
        Case("only the target has a rule", targetRule = food),
        Case("both have rules in different categories", sourceRule = true, targetRule = food),
        Case("both have rules in one category", sourceRule = true, targetRule = shopping),
        Case("the source has a name", sourceName = "Mr DIY Klcc"),
        Case("the target has a name and the source a rule", sourceRule = true, targetName = "Mr DIY"),
        Case("the source has a rule and a name", sourceRule = true, sourceName = "Mr DIY Klcc", targetName = "Mr DIY"),
        Case("the source got its rule and name from a pack re-key", sourceRule = true, sourceName = "Mr DIY Klcc", rekeyed = true),
    )

    @Test fun separatingRestoresTheSplitExactlyOverEveryStartingState() {
        for (case in cases) {
            db.close()
            db = freshDatabase()
            // The source's rule is the shopping category, so "both in one category" gives the target shopping too.
            val sourceRule = if (case.sourceRule) shopping else null
            if (case.rekeyed) {
                pay(oldKey, 1_000L)
                sourceRule?.let { teach(oldKey, it) }
                case.sourceName?.let { merchants.setName(oldKey, it) }
                rekey(oldKey, source)
            } else {
                pay(source, 1_000L)
                sourceRule?.let { teach(source, it) }
                case.sourceName?.let { merchants.setName(source, it) }
            }
            pay(target, 2_000L)
            case.targetRule?.let { teach(target, it) }
            case.targetName?.let { merchants.setName(target, it) }
            val before = seen(source, target)
            val shown = merchants.choices().single { it.identityKey == target }.let { it.displayName ?: target }

            assertEquals(case.name, true, merchants.merge(source = source, target = target, targetName = shown))
            assertEquals("${case.name}: the merge reads one way through both ports", seen(source, target).filedLive, seen(source, target).filedSnapshot)
            assertEquals(case.name, 1, merchants.separate(source))

            assertEquals(case.name, expectedAfter(before, shown, sourceRule), seen(source, target))
        }
    }

    @Test fun separatingLeavesTheTargetTheNameAndRuleTheMergeGaveIt() {
        pay(source, 1_000L); pay(target, 2_000L)
        teach(source, shopping)
        val shown = merchants.choices().single { it.identityKey == target }.displayName ?: target

        merchants.merge(source = source, target = target, targetName = shown)
        merchants.separate(source)

        assertEquals(shown, merchants.nameOf(target))
        assertEquals(shopping, merchants.learnedCategoryOn(target))
        assertEquals(shopping, merchants.learnedCategoryOn(source))
    }

    @Test fun separatingTheSourceLeavesTheKeysMergedIntoItWithTheTarget() {
        pay(member, 500L); pay(source, 1_000L); pay(target, 2_000L)
        merchants.merge(source = member, target = source, targetName = source)
        teach(source, shopping)
        val shown = merchants.choices().single { it.identityKey == target }.displayName ?: target

        merchants.merge(source = source, target = target, targetName = shown)
        merchants.separate(source)

        assertEquals("The merge repointed the member, and Separate undoes only the source's alias", target, merchants.canonicalOf(member))
        assertEquals(null, merchants.canonicalOf(source))
        assertEquals(listOf(source to 1, target to 2), merchants.choices().map { it.identityKey to it.txnCount }.sortedBy { it.second })
    }
}
