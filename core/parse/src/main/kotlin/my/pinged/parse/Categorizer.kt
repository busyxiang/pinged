package my.pinged.parse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a dictionary entry is matched against a merchant key. Declared most
 * specific first: [Categorizer] breaks a length tie by ordinal, so reordering
 * this enum changes which entry wins and `CategorizerTest` fails.
 */
@Serializable
enum class MatchMode {
    /** The whole key. */
    @SerialName("exact") EXACT,

    /** The start of the key, then a word boundary. The default. */
    @SerialName("prefix") PREFIX,

    /**
     * Anywhere in the key, between two word boundaries. Allowed only where the
     * corpus shows [PREFIX] cannot catch the merchant (a key like
     * `NNN-MY-RKL-<station>`), and sanctioned for the dictionary alone: a
     * learned rule is always exact.
     */
    @SerialName("contains") CONTAINS,
}

/**
 * One entry of the parser pack's dictionary: [text] as the merchant sends it,
 * filed under the seeded category named [category].
 */
@Serializable
data class DictionaryEntry(
    val text: String,
    val category: String,
    val mode: MatchMode = MatchMode.PREFIX,
)

/** The category the user taught for a merchant identity, by name. */
data class LearnedRule(val categoryName: String)

/** Which of the three lookups answered, in the order they are tried. */
enum class FilingSource { LEARNED, DICTIONARY, NONE }

/** [categoryName] is null exactly when [source] is [FilingSource.NONE]. */
data class Filing(val categoryName: String?, val source: FilingSource)

/**
 * Spec 6's resolution order as a pure function: the learned rule, then the
 * pack's dictionary, then nothing (the caller files that under Uncategorized).
 *
 * **No I/O and no regex.** The caller resolves the learned rule by merchant
 * identity and maps [Filing.categoryName] to a `category.id`; the dictionary
 * is matched against the capture's **own** key, not the canonical one.
 *
 * Matching is `regionMatches` on the key, so `\d`, `\w`, `\s` and `\b` never
 * come into it: those classes read ASCII on the JVM and Unicode under ICU, and
 * the matcher is tested on the JVM. A word boundary is the end of the key or
 * a character that is not a letter or digit, decided by
 * [Character.isLetterOrDigit] on the code point either side -- the table
 * `SameShop` already uses. `CategorizerDeviceTest` runs the same table on
 * Android.
 *
 * Built once per pack: [file] scans the entries per call, which at the eight
 * brands the first dictionary holds is not worth an index.
 */
class Categorizer(dictionary: List<DictionaryEntry>) {
    private class Candidate(val entry: DictionaryEntry, val order: Int)

    private val candidates: List<Candidate> =
        dictionary.mapIndexed { i, e -> Candidate(e, i) }.filter { it.entry.text.isNotEmpty() }

    /**
     * The longest matching entry wins; a tie in length goes to the more
     * specific mode (exact, prefix, contains), then to the entry listed first,
     * so no result depends on the order anything is iterated in.
     *
     * A learned rule is not compared with the dictionary at all: it answers
     * first, however long a dictionary hit is. [key] is null for a payment with
     * no merchant, which no dictionary entry can match.
     */
    fun file(key: String?, learned: LearnedRule?): Filing {
        if (learned != null) return Filing(learned.categoryName, FilingSource.LEARNED)
        if (key == null) return NONE
        val best = candidates
            .filter { matches(it.entry, key) }
            .minWithOrNull(
                compareByDescending<Candidate> { it.entry.text.length }
                    .thenBy { it.entry.mode.ordinal }
                    .thenBy { it.order },
            ) ?: return NONE
        return Filing(best.entry.category, FilingSource.DICTIONARY)
    }

    private fun matches(entry: DictionaryEntry, key: String): Boolean {
        val text = entry.text
        val n = text.length
        if (key.length < n) return false
        return when (entry.mode) {
            MatchMode.EXACT -> key.length == n && key.regionMatches(0, text, 0, n, ignoreCase = true)
            MatchMode.PREFIX -> key.regionMatches(0, text, 0, n, ignoreCase = true) && boundaryAt(key, n)
            MatchMode.CONTAINS -> (0..key.length - n).any { start ->
                key.regionMatches(start, text, 0, n, ignoreCase = true) &&
                    boundaryBefore(key, start) && boundaryAt(key, start + n)
            }
        }
    }

    /** True when no letter or digit sits at [index] to continue the word before it. */
    private fun boundaryAt(key: String, index: Int): Boolean =
        index >= key.length || !Character.isLetterOrDigit(key.codePointAt(index))

    /** True when no letter or digit sits before [index] to continue the word after it. */
    private fun boundaryBefore(key: String, index: Int): Boolean =
        index == 0 || !Character.isLetterOrDigit(key.codePointBefore(index))

    private companion object {
        val NONE = Filing(null, FilingSource.NONE)
    }
}
