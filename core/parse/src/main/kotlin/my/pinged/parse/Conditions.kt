package my.pinged.parse

/** One selectable field, normalized once for matching and once for comparison. */
internal class FieldValue(val forMatch: String) {
    /**
     * Lazy, and folded through [TextNormalizer] rather than by hand.
     *
     * [CaptureFields] builds all four fields for every capture, and the
     * bundled pack reads only two of them: `concat` for rejects and most
     * conditions, `title` for `title_contains_any`. Eager, this was two
     * whole-string copies per capture that nothing ever looked at.
     */
    val forCompare: String by lazy { TextNormalizer.fold(forMatch) }
}

/**
 * The four values spec 5.2's `field` selector can name, computed once per
 * capture rather than once per rule. A capture is re-parsed on every pack
 * upgrade (spec 5.5), so normalizing per rule put O(rules) string work on
 * that path for four possible answers.
 */
internal class CaptureFields(
    val title: FieldValue,
    val text: FieldValue,
    val bigText: FieldValue,
    val concat: FieldValue,
) {
    fun forField(field: Field): FieldValue = when (field) {
        Field.TITLE -> title
        Field.TEXT -> text
        Field.BIG_TEXT -> bigText
        Field.CONCAT -> concat
    }

    /** Longest normalized field, for the input cap in [RuleMatcher]. */
    val longestLength: Int =
        maxOf(title.forMatch.length, text.forMatch.length, bigText.forMatch.length, concat.forMatch.length)

    companion object {
        fun of(title: String?, text: String?, bigText: String?): CaptureFields {
            val body = bigText ?: text
            return CaptureFields(
                title = FieldValue(TextNormalizer.forMatch(title.orEmpty())),
                text = FieldValue(TextNormalizer.forMatch(text.orEmpty())),
                bigText = FieldValue(TextNormalizer.forMatch(bigText.orEmpty())),
                concat = FieldValue(TextNormalizer.forMatch(listOfNotNull(title, body).joinToString(" "))),
            )
        }
    }
}

/**
 * A rule's `requires` block with its terms folded for comparison once, at
 * matcher construction, instead of on every comparison.
 */
internal class PreparedConditions(
    val field: Field,
    private val textContainsAll: List<String>,
    private val textContainsAny: List<String>,
    private val textContainsNone: List<String>,
    private val titleContainsAny: List<String>,
) {
    fun matches(fields: CaptureFields): Boolean {
        val haystack = fields.forField(field).forCompare
        val titleHay = fields.title.forCompare

        if (textContainsAll.any { !haystack.contains(it) }) return false
        if (textContainsAny.isNotEmpty() && textContainsAny.none { haystack.contains(it) }) return false
        if (textContainsNone.any { haystack.contains(it) }) return false
        if (titleContainsAny.isNotEmpty() && titleContainsAny.none { titleHay.contains(it) }) return false
        return true
    }

    companion object {
        /** No `requires` block: the rule's pattern is its only guard. */
        private val UNCONDITIONAL =
            PreparedConditions(Field.CONCAT, emptyList(), emptyList(), emptyList(), emptyList())

        fun of(conditions: Conditions?): PreparedConditions = when (conditions) {
            null -> UNCONDITIONAL
            else -> PreparedConditions(
                field = conditions.field,
                textContainsAll = conditions.textContainsAll.map(TextNormalizer::forCompare),
                textContainsAny = conditions.textContainsAny.map(TextNormalizer::forCompare),
                textContainsNone = conditions.textContainsNone.map(TextNormalizer::forCompare),
                titleContainsAny = conditions.titleContainsAny.map(TextNormalizer::forCompare),
            )
        }
    }
}
