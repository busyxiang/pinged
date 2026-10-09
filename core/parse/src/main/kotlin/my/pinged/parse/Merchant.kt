package my.pinged.parse

import java.util.Locale

/**
 * Spec 5.4's merchant normalization.
 *
 * The three lists are **pack data, not constants here**, so a merchant that
 * comes out wrong on a device is a one-line pack edit and a `pack_version`
 * bump rather than a release. It corrects what is captured next, not what is
 * already stored: spec 5.5's re-parse is what would go back over the latter
 * and nothing implements it yet.
 *
 * Both entry points therefore take a [MerchantNormalization], with no default:
 * the lists must come from the pack the capture was parsed under, which once
 * spec 5.9's pack import exists is not necessarily the bundled one.
 *
 * There are exactly two entry points, and they are **not composable**:
 *
 *  - [clean] is the canonical, **uppercased** key. Spec 6.1 learns merchant
 *    rules by it and spec 7.2 layer 2 tokenizes it.
 *  - [displayFor] is `merchant_display`, derived from the raw string's own case.
 *
 * Both take the same raw string; neither takes the other's output. Composing
 * them feeds title-casing a string that is uppercase by construction, so spec
 * 5.4's "only when the raw string is entirely uppercase" guard cannot fail to
 * pass -- `TNG*foodpanda KLCC` becomes `Foodpanda Klcc`.
 *
 * So [clean] returns a [MerchantKey] rather than a `String`, which makes the
 * composition a type error rather than a paragraph asking you not to write it.
 */
@JvmInline
value class MerchantKey(val value: String)

object Merchant {
    /**
     * Spec 5.4 sanctions this, but it also eats real name segments:
     * `clean("ABC-12")` gives "ABC". Accepted, recorded here so it is a known
     * behaviour rather than a surprise.
     *
     * Not pack data, unlike the three lists: it is a shape, and a pack-editable
     * regex is a pack-editable denial of service on the notification path.
     *
     * The class is spelled out in both cases rather than carrying
     * [RegexOption.IGNORE_CASE], because [displayFor] runs it against a string
     * whose case has deliberately not been folded and both paths must strip exactly
     * the same characters.
     */
    private val TERMINAL_CODE = Regex("-[A-Za-z0-9]{2,4}$")

    /**
     * The canonical form: uppercase, prefix- and suffix-stripped, whitespace
     * collapsed. This is what spec 7.2 layer 2 compares tokens of and what
     * `txn.merchant_key` stores, so it must be stable across locales and across the
     * order two suffixes happen to appear in.
     *
     * It is **not** spec 6.1's learned-rule key, which replaces every
     * non-alphanumeric with a space and strips nothing: 6.1's own example turns
     * `TNG*99SPEEDMART` into `TNG 99SPEEDMART`, where this returns `99SPEEDMART`.
     * They are different functions and they **disagree**: on 36 of the 80 rows of
     * the 2026-10-05 device export, because acquirer strings do reach the learning
     * path. A learned rule is therefore matched against this form, the stored
     * `merchant_key` (spec #45's departure from 6.1), never against 6.1's.
     *
     * The uppercasing is load-bearing for those subsystems, not a display decision;
     * [displayFor] is that, and starts from the same raw string rather than from
     * this. `merchant_raw` is never passed through here on its way to storage.
     */
    fun clean(
        raw: String,
        normalization: MerchantNormalization,
    ): MerchantKey =
        MerchantKey(strip(TextNormalizer.forMatch(raw).uppercase(Locale.ROOT), normalization))

    /**
     * Spec 5.4's `merchant_display`, derived from [raw] and from nothing else.
     *
     * The same prefixes and suffixes [clean] removes are removed here, matched
     * case-insensitively but stripped **without folding the case of what is left**.
     *
     * Title-casing is applied **only when what survives stripping is entirely
     * uppercase** -- the acquirer is shouting and none of the merchant's own
     * capitalisation is being thrown away. The guard is applied after stripping
     * rather than to the whole of [raw] because `TENAGA NASIONAL via DuitNow` is
     * not an entirely uppercase string, and its only lower-case letters are the
     * payment rails the pack exists to remove.
     *
     * The exception list is the half the guard cannot cover: "KK SUPERMART" *is*
     * entirely uppercase, so the guard passes it through and a device recorded "Kk
     * Supermart". An entry matches a whole word, or the whole string, and is
     * emitted with the casing the pack wrote.
     */
    fun displayFor(
        raw: String,
        normalization: MerchantNormalization,
    ): String {
        val stripped = strip(TextNormalizer.forMatch(raw), normalization)
        if (stripped != stripped.uppercase(Locale.ROOT)) return stripped

        val exceptions = normalization.titleCaseExceptionsByUpper
        exceptions[stripped]?.let { return it }

        return stripped.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) {
                word
            } else {
                exceptions[word.uppercase(Locale.ROOT)]
                    ?: capitalised(word)
            }
        }
    }

    /**
     * [word] lower-cased with its first **letter** upper-cased, not its first
     * character: uppercasing a leading digit is a no-op, which would leave
     * `7ELEVEN` as `7eleven` (#32). Decided by [Char.isLetter], not a regex, for the reason
     * `\d` is banned (CLAUDE.md). A word with no letter is returned as it is.
     */
    private fun capitalised(word: String): String {
        val lower = word.lowercase(Locale.ROOT)
        val at = lower.indexOfFirst { it.isLetter() }
        if (at < 0) return lower
        return lower.substring(0, at) + lower[at].uppercase(Locale.ROOT) + lower.substring(at + 1)
    }

    /**
     * Prefix, suffixes, sentence stops and terminal code off [s], with whatever
     * case [s] arrived in left exactly as it was.
     *
     * Shared by both entry points: two copies would be two copies of the looping
     * rule below, and the day they diverge `merchant_display` and the string spec
     * 6.1 learns by describe different merchants, silently.
     *
     * Pack entries are matched with `ignoreCase`, which folds per character and so
     * cannot depend on the device locale. Removal is by matched length, so folding
     * can never change how much is taken off.
     */
    private fun strip(s: String, normalization: MerchantNormalization): String {
        var out = s

        normalization.prefixes.firstOrNull { out.startsWith(it, ignoreCase = true) }
            ?.let { out = out.substring(it.length) }

        // Looped, not single-pass. Walking the list once left an earlier suffix
        // untested after a later one was removed, so "SHOPEE MY SDN BHD" and "SHOPEE
        // SDN BHD MY" cleaned differently -- and spec 6.1 learns by exact normalized
        // string, so one shop became two learned merchants.
        //
        // A pass removes one suffix, or one stop. Interleaved rather than run
        // one after the other, because the two stack in both orders and
        // either order alone leaves the other untouched: "RESTORAN X via
        // DuitNow." needs the stop off before " via DuitNow" ends the string,
        // and "MACHINES SDN. BHD." needs the suffix -- which carries stops of
        // its own -- matched before any of them is taken. One stop, not the
        // run: TnG sends "SPADES BAKERY 3 SDN. BHD..", and taking both stops
        // takes the suffix's own, so " SDN. BHD." never matches and the shop
        // keys apart from the same shop paid through MAE.
        //
        // The stops are the sentence's own, taken off a merchant group that
        // ran to the end of one -- "THONG KEE." against "THONG KEE" is two
        // shops in spec 8 permanently. Here rather than in the rule's pattern
        // because every rule anchored to the end of a field repeats the
        // hazard, spec 5.8's runtime rules having no pattern author at all;
        // here rather than in `trailing_noise_suffixes` because that list is
        // literal phrases carrying their own separator, and pack-editable,
        // which TERMINAL_CODE's comment gives the reason against.
        //
        // The full stop alone: it is the only trailing punctuation the corpus
        // has sent, and a wider class would be a guess about commas.
        // A character test rather than `\.$`: one fewer pattern to differ
        // between java.util.regex and ICU.
        //
        // The cap is the string's length, not a count of the list: every
        // pass that continues removes at least one character, and a run of
        // stops is as long as the bank made it.
        val suffixes = normalization.suffixes
        var passes = 0
        val maxPasses = out.length + 1
        while (passes++ < maxPasses) {
            val hit = suffixes.firstOrNull { out.endsWith(it, ignoreCase = true) }
            if (hit != null) {
                out = out.substring(0, out.length - hit.length)
                continue
            }
            if (!out.endsWith('.')) break
            out = out.dropLast(1)
        }

        out = TERMINAL_CODE.replace(out, "")
        // No interior whitespace collapse here. `strip` is private and both
        // entry points hand it TextNormalizer.forMatch output, which has
        // already collapsed every run to one space; prefix removal takes from
        // the front and suffix and TERMINAL_CODE removal from the back, so
        // none of them can produce an interior run. A second regex here would
        // be a scan that cannot match, and a second definition of whitespace
        // weaker than TextNormalizer's.
        return out.trim()
    }
}
