package my.pinged.parse

import java.util.Locale

object TextNormalizer {
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")
    // U+2028, U+2029 and U+0085 are line and paragraph separators outside
    // \p{Zs}. A U+2028 where a space belongs defeats a template match that a
    // plain newline in the same position does not, so they are folded too.
    private val SPACES = Regex("[\\p{Zs}\\t\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]+")

    fun forMatch(raw: String): String =
        SPACES.replace(INVISIBLE.replace(raw, ""), " ").trim()

    fun forCompare(raw: String): String = fold(forMatch(raw))

    /**
     * The case-folding half of [forCompare], for a string already through
     * [forMatch].
     *
     * Needles go through [forCompare] and haystacks through this. Without a
     * shared step the two folds are separate copies of the same idea, and a
     * step added later to one -- NFKC, accent folding, both plausible for
     * Malay and Chinese merchant text -- would silently stop every condition
     * term matching, with nothing red anywhere.
     */
    fun fold(normalized: String): String = normalized.lowercase(Locale.ROOT)
}
