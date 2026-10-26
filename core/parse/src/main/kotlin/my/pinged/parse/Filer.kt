package my.pinged.parse

/** Which of a payment's two lookups put it where it goes, in the order they are tried. */
enum class FilingSource { LEARNED, DICTIONARY, NONE }

/**
 * Where a payment from one merchant key goes (CONTEXT.md, Filing): both halves
 * of the answer, because the chooser needs both. A row the dictionary filed as
 * Transport before the user taught Food has a [learned] of Food and a
 * [dictionary] of Transport; the winner alone cannot say that the row's switch
 * starts off.
 *
 * @property learned the `category.id` of the learned rule for the key's merchant
 *   identity, or null.
 * @property dictionary the `category.id` the dictionary files the key itself
 *   under, or null: the entry whose seeded category no longer exists is not one.
 */
data class Filing(val learned: Long?, val dictionary: Long?) {
    /** The winner, learned first. Null exactly when [source] is [FilingSource.NONE]: no Uncategorized id is known here. */
    val categoryId: Long? get() = learned ?: dictionary

    val source: FilingSource
        get() = when {
            learned != null -> FilingSource.LEARNED
            dictionary != null -> FilingSource.DICTIONARY
            else -> FilingSource.NONE
        }

    companion object {
        val NONE = Filing(learned = null, dictionary = null)
    }
}

/**
 * Where a learned rule is read from, by merchant identity: a key resolves to its
 * canonical key (`merchant_alias`), then to that key's rule. A dormant rule on a
 * key that has been merged away is therefore never read, whichever port answers.
 */
fun interface LearnedRules {
    /** The `category.id` of the rule on [key]'s identity, or null. */
    fun categoryFor(key: String): Long?

    /**
     * The rules and aliases as one read of the ledger saw them, for a screen
     * that has to answer many keys of one moment: `rules[aliases[key] ?: key]`,
     * which is what the SQL's `COALESCE` does. A data class, so two reads of an
     * unchanged ledger compare equal.
     *
     * @property rules identity to `category.id`, one per learned rule.
     * @property aliases merged-away key to the identity it was merged into.
     */
    data class Snapshot(
        val rules: Map<String, Long> = emptyMap(),
        val aliases: Map<String, String> = emptyMap(),
    ) : LearnedRules {
        /** Every identity another key has been merged into, derived from [aliases] and never read apart from them. */
        val mergedIdentities: Set<String> = aliases.values.toSet()

        override fun categoryFor(key: String): Long? = rules[aliases[key] ?: key]

        /** Whether [key] is one the user merged away, or an identity other keys were merged into. */
        fun isMerged(key: String): Boolean = key in aliases || key in mergedIdentities
    }
}

/**
 * Spec 6's resolution order, the one place it is written: the learned rule for a
 * key's merchant identity, else the dictionary's entry for the key itself, else
 * nothing, which the caller files under Uncategorized.
 *
 * The dictionary is about how a merchant arrives, so it is matched against the
 * key the payment came under, never its identity's. An entry whose category is
 * not in [categoryIds] is dropped before matching, so its merchants fall through
 * to a shorter entry rather than to a category that is gone.
 *
 * Pure but for what [learned] does: stage two, manual entry and the chooser each
 * pass the port that gives them the freshness they need, and the order cannot
 * disagree between them.
 *
 * @property dictionary the pack's dictionary.
 * @property categoryIds `category.id` by `seed_name`, so a renamed seeded
 *   category keeps its merchants.
 */
data class Filer<L : LearnedRules>(
    val dictionary: List<DictionaryEntry>,
    val categoryIds: Map<String, Long>,
    val learned: L,
) {
    private val categorizer = Categorizer(dictionary.filter { it.category in categoryIds })

    /** [key] is null for a payment with no merchant, which nothing files. */
    fun file(key: String?): Filing {
        if (key == null) return Filing.NONE
        return Filing(
            learned = learned.categoryFor(key),
            dictionary = categorizer.categoryOf(key)?.let(categoryIds::get),
        )
    }

    companion object {
        /** Files nothing: a read that has not resolved a pack. */
        val EMPTY: Filer<LearnedRules.Snapshot> = Filer(emptyList(), emptyMap(), LearnedRules.Snapshot())
    }
}
