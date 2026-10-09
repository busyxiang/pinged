package my.pinged.ledger.home

import my.pinged.parse.Categorizer
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.FilingSource

/**
 * Where "Always call this" starts when the chooser opens (#45, #48), derived
 * from how the row was filed and never stored. Null means **no switch**: a row
 * with no merchant key has nothing to learn from, and its save is a one-off.
 *
 * - on for an Uncategorized row, a one-off (`user_edited`), and a row filed by
 *   the user's rule (its category is the learned rule's for its identity);
 * - off for a row filed by the dictionary (its category is the dictionary's for
 *   its own key), so correcting a one-time purchase does not write a rule that
 *   mis-files every later one;
 * - on for an "old rule" row, which is none of the above: a since-changed
 *   dictionary entry or rule filed it. No ticket decided this one; on is the
 *   natural reading, since nothing current filed the row.
 *
 * The order is the rule. A one-off is tested before the dictionary, so a row
 * the user set by hand to what the dictionary also says is still theirs; the
 * learned rule is tested before the dictionary, as it outranks it in filing.
 */
internal fun teachSwitchDefault(
    hasKey: Boolean,
    categoryId: Long,
    userEdited: Boolean,
    uncategorizedId: Long?,
    learnedCategoryId: Long?,
    dictionaryCategoryId: Long?,
): Boolean? = when {
    !hasKey -> null
    categoryId == uncategorizedId -> true
    userEdited -> true
    learnedCategoryId != null && categoryId == learnedCategoryId -> true
    dictionaryCategoryId != null && categoryId == dictionaryCategoryId -> false
    else -> true
}

/**
 * The `category.id` the dictionary files [key] under, or null where it files
 * nothing: the chooser's read-time "filed by a shipped rule" marking, through
 * the same [Categorizer.file] stage two calls (`source == DICTIONARY`). An
 * entry whose category is not in [categoryIds] is dropped first, as stage two
 * drops it, so the marking cannot claim a filing stage two would not make.
 */
internal fun dictionaryCategoryIds(
    dictionary: List<DictionaryEntry>,
    categoryIds: Map<String, Long>,
): (String?) -> Long? {
    val categorizer = Categorizer(dictionary.filter { it.category in categoryIds })
    return { key ->
        val filing = categorizer.file(key, learned = null)
        if (filing.source == FilingSource.DICTIONARY) filing.categoryName?.let(categoryIds::get) else null
    }
}
