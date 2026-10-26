package my.pinged.data

import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.parse.LearnedRules

/**
 * The port that asks the database on every key, resolving the alias in SQL
 * (`MerchantRuleDao.learnedCategoryFor`). Stage two and manual entry use it, so
 * each reads a rule as fresh as its own lookup. Blocking, so only inside a lease
 * or `whileLive`.
 */
class LiveLearnedRules(private val dao: MerchantRuleDao) : LearnedRules {
    override fun categoryFor(key: String): Long? = dao.learnedCategoryFor(key)
}

/**
 * Every learned rule and every alias, for a read that answers many keys of one
 * moment: the chooser's, inside its read transaction. Both tables hold a row per
 * thing the user taught or merged by hand, so they are read whole.
 */
fun readLearnedRules(rules: MerchantRuleDao, identities: MerchantIdentityDao): LearnedRules.Snapshot =
    LearnedRules.Snapshot(
        rules = rules.learnedRules().associate { it.pattern to it.categoryId },
        aliases = identities.allAliases().associate { it.merchantKey to it.canonicalKey },
    )
