package my.pinged.ledger.home

import my.pinged.parse.Filing

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
 * Both halves of [filing] are asked, not its winner: a row the dictionary filed
 * as Transport before the user taught Food starts off, and the winner alone
 * would turn it on.
 */
internal fun teachSwitchDefault(
    hasKey: Boolean,
    categoryId: Long,
    userEdited: Boolean,
    uncategorizedId: Long?,
    filing: Filing,
): Boolean? = when {
    !hasKey -> null
    categoryId == uncategorizedId -> true
    userEdited -> true
    filing.learned != null && categoryId == filing.learned -> true
    filing.dictionary != null && categoryId == filing.dictionary -> false
    else -> true
}
