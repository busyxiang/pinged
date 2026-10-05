package my.pinged.parse

/**
 * Spec 6.4's "Same shop as..." ordering: which other merchants to offer first.
 *
 * **It orders a list and decides nothing.** A merge is only ever the user's
 * choice from that list, so a wrong suggestion costs a scroll and never a
 * write -- which is the only reason a threshold that is judgement rather than
 * measurement is acceptable here.
 *
 * Takes `merchant_key` values, which are already uppercased by
 * [Merchant.clean], so no case folding happens here.
 */
object SameShop {
    /**
     * Spec 6.2's eight, reused: the shorter key, with separators removed, must
     * be at least this long. Judgement, not measurement.
     */
    const val MIN_LENGTH = 8

    /**
     * Whether one key, with everything but letters and digits removed, contains
     * the other. That recovers a name an acquirer sent with its spaces stripped
     * (`YUENKEEHOMETOWNCAFE` inside `RESTORAN YUEN KEE HOME TOWN CAFE`), which
     * no normalization can.
     *
     * [Char.isLetterOrDigit] rather than a regex class: it is `Character`'s own
     * table on the JVM and on Android alike, where `\w` is ASCII on one and
     * Unicode on the other (spec 5.4).
     */
    fun likely(key: String, other: String): Boolean {
        if (key == other) return false
        val a = compact(key)
        val b = compact(other)
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        return shorter.length >= MIN_LENGTH && longer.contains(shorter)
    }

    /** [candidates] with every [likely] match for [key] moved to the front, each half in its given order. */
    fun <T> suggestionsFirst(key: String, candidates: List<T>, keyOf: (T) -> String): List<T> {
        val (first, rest) = candidates.partition { likely(key, keyOf(it)) }
        return first + rest
    }

    private fun compact(key: String): String = key.filter(Char::isLetterOrDigit)
}
