package my.pinged.parse

enum class Direction { EXPENSE, REFUND }
enum class Confidence { HIGH, REVIEW }
enum class Kind { TRANSFER_SUSPECT }

/**
 * Spec 7.1's exclusion vocabulary, and nothing else.
 *
 * Here rather than in `:core:data` beside the column it is stored in, because
 * the pack is what *names* one: `TemplateRule.exclusion_reason` is where the
 * value enters the app. It was a `String?` on both sides while every sibling
 * field -- `direction`, `confidence`, `kind`, `requires.field` -- was a closed
 * enum whose unknown values fail the load. A pack that wrote "TRANFSER" loaded
 * clean, and the writer that finally stores the column would have discovered
 * it on a device, as either a crash converting the string or a silently
 * dropped exclusion.
 *
 * `EnumVocabularyTest` freezes these four spellings against the disk format.
 */
enum class ExclusionReason { TRANSFER, CARD_PAYMENT, ATM_WITHDRAWAL, USER }

sealed interface MatchOutcome {
    data class Matched(
        val ruleId: String,
        val amountSen: Long,
        val merchantRaw: String?,
        val direction: Direction,
        val confidence: Confidence,
        val kind: Kind?,
        val exclusionReason: ExclusionReason?,
        /** Non-null when a reject pattern also matched. Logged, never acted on. */
        val rejectCollisionId: String?,
    ) : MatchOutcome

    data class Rejected(val rejectRuleId: String) : MatchOutcome
    data object Unmatched : MatchOutcome
    data object NoExtras : MatchOutcome

    /**
     * A pattern exhausted its time budget, so this capture was abandoned
     * before every template had run. Distinct from [Unmatched] on purpose: a
     * timeout is not evidence that no rule matches, and on a device the
     * budget is wall clock, so a descheduled worker can produce this during
     * an ordinary match. Re-parse (spec 5.5) should revisit these.
     */
    data object GaveUp : MatchOutcome
}
