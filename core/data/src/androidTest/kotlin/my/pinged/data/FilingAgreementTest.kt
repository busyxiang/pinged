package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.Filer
import my.pinged.parse.Filing
import my.pinged.parse.LearnedRules
import my.pinged.parse.MatchMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #303: the three callers of filing (stage two, manual entry, the chooser) all
 * ask one [Filer], so the order cannot disagree between them. What can is the
 * learned-rule port: stage two and manual entry read it live in SQL, the chooser
 * from a [readLearnedRules] snapshot. This runs every key of one real database
 * through both and asserts equal [Filing]s.
 *
 * The database holds what makes the two diverge if either is wrong: a merge, a
 * dormant rule on the merged-away key, a seeded category the user renamed, a
 * user-made category, and a dictionary entry whose seeded category is gone.
 * Falsify it by dropping the alias lookup from `Snapshot.categoryFor`: the
 * merged-away keys then answer no rule from the snapshot and a rule from SQL.
 */
@RunWith(AndroidJUnit4::class)
class FilingAgreementTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val rules get() = db.merchantRuleDao()
    private val merchants get() = db.merchantIdentityDao()

    private fun pay(key: String): Long = db.txnDao().insert(
        sampleTxn(occurredAt = 1_000L, categoryId = db.categoryDao().requireUncategorizedId())
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

    @Test fun theLivePortAndTheSnapshotFileEveryKeyAlike() {
        val food = db.categoryId("Food & Drinks")
        val shopping = db.categoryId("Shopping")
        val transport = db.categoryId("Transport")
        val userMade = db.categoryDao().add("Kopitiam", "utensils")
        db.categoryDao().edit(transport, "Getting around", "car")

        val source = "MR DIY KLCC"
        val target = "MR D.I.Y."
        val aliasOnly = "MR DIY SENTRAL"
        listOf(source, target, aliasOnly).forEach(::pay)
        teach(source, food)
        teach(target, shopping)
        merchants.merge(source = source, target = target, targetName = target)
        merchants.merge(source = aliasOnly, target = target, targetName = target)
        teach("KEDAI KOPI", userMade)

        val filer = { learned: LearnedRules ->
            Filer(
                dictionary = listOf(
                    DictionaryEntry("MR DIY", "Transport"),
                    DictionaryEntry("MY50", "Transport"),
                    DictionaryEntry("GONE", "Deleted category"),
                    DictionaryEntry("KEDAI", "Food & Drinks", MatchMode.PREFIX),
                ),
                categoryIds = db.categoryDao().seededIds(),
                learned = learned,
            )
        }
        val live = filer(LiveLearnedRules(rules))
        val snapshot = filer(readLearnedRules(rules, merchants))

        val keys = listOf(source, target, aliasOnly, "MY50 PASS", "GONE", "KEDAI KOPI", "UNKNOWN SHOP", null)
        keys.forEach { key ->
            assertEquals("the ports disagree on $key", live.file(key), snapshot.file(key))
        }

        // Agreement is vacuous if both ports answer nothing, so pin what they say.
        assertEquals("a merged-away key reads the identity's rule, not its own dormant one", shopping, live.file(source).learned)
        assertEquals(shopping, snapshot.file(aliasOnly).learned)
        assertEquals("the dictionary reads the key the payment arrived under", transport, snapshot.file(source).dictionary)
        assertNull("its identity's key is not the dictionary's question", snapshot.file(target).dictionary)
        assertEquals("a renamed seeded category keeps its merchants", transport, snapshot.file("MY50 PASS").dictionary)
        assertEquals(Filing.NONE, snapshot.file("GONE"))
        assertEquals("a user-made category's rule wins", userMade, snapshot.file("KEDAI KOPI").categoryId)
        assertEquals(Filing.NONE, snapshot.file(null))
    }
}
