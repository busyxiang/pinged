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

/**
 * The pack's dictionary as a matcher: which entry's category name a merchant
 * key falls under, and nothing about the learned rule, the seeded ids or
 * Uncategorized. [Filer] is the only way in, so no caller can rebuild spec 6's
 * resolution order around it.
 *
 * **No I/O and no regex.** The dictionary is matched against the capture's
 * **own** key, not the canonical one.
 *
 * Matching is `regionMatches` on the key, so `\d`, `\w`, `\s` and `\b` never
 * come into it: those classes read ASCII on the JVM and Unicode under ICU, and
 * the matcher is tested on the JVM. A word boundary is the end of the key or
 * a character that is not a letter or digit, decided by
 * [Character.isLetterOrDigit] on the code point either side -- the table
 * `SameShop` already uses. `CategorizerDeviceTest` runs the same table on
 * Android, through [Filer].
 *
 * Built once per [Filer]: [categoryOf] scans the entries per call, which at the
 * eight brands the first dictionary holds is not worth an index.
 */
internal class Categorizer(dictionary: List<DictionaryEntry>) {
    private class Candidate(val entry: DictionaryEntry, val order: Int)

    private val candidates: List<Candidate> =
        dictionary.mapIndexed { i, e -> Candidate(e, i) }.filter { it.entry.text.isNotEmpty() }

    /**
     * The category name of the longest matching entry; a tie in length goes to
     * the more specific mode (exact, prefix, contains), then to the entry listed
     * first, so no result depends on the order anything is iterated in. [key] is
     * null for a payment with no merchant, which no dictionary entry can match.
     */
    fun categoryOf(key: String?): String? {
        if (key == null) return null
        return candidates
            .filter { matches(it.entry, key) }
            .minWithOrNull(
                compareByDescending<Candidate> { it.entry.text.length }
                    .thenBy { it.entry.mode.ordinal }
                    .thenBy { it.order },
            )?.entry?.category
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
}
