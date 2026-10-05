package my.pinged.capture

import my.pinged.parse.Merchant
import my.pinged.parse.MerchantNormalization

/**
 * The three merchant columns a parse writes onto `txn`, from the string a rule
 * captured. Stage two and spec 5.5's re-read both derive them here, so a
 * re-read cannot report a difference that is only two spellings of one rule.
 *
 * **[raw]**: blank collapses to null, so the stored column is exactly the
 * predicate spec 7.1's `MERCHANT_MISSING` is recomputable from. Otherwise the
 * string is untouched: spec 5.4 preserves `merchant_raw` as parsed.
 *
 * **A capture that cleans away to nothing has no merchant either.** A pattern
 * can capture a string that is entirely payment rail -- `TNG*`, `DUITNOWQR-`, a
 * bare terminal code -- which `Merchant.clean` reduces to empty. Kept as raw it
 * reads as a merchant: no `MERCHANT_MISSING` fires, the row commits, and
 * section 8 groups it into the NULL bucket with every other such row, which is
 * what `merchant_key` exists to prevent. Nothing recoverable is lost: re-parse
 * works from the capture's own text, and an acquirer prefix on its own names
 * nobody, so it goes to the review inbox, where a person can say who it was.
 *
 * **[display]** is `displayFor(raw)`, not `display(clean(raw))`: `clean`
 * uppercases, which makes spec 5.4's "only when the raw string is entirely
 * uppercase" title-casing guard true by construction, and `foodpanda KLCC`
 * would be stored as `Foodpanda Klcc`.
 *
 * **[key]** is the identity section 8 groups by, null exactly when [raw] is --
 * which holds because [raw] is already filtered to strings that clean to
 * something.
 */
internal data class MerchantColumns(val raw: String?, val display: String?, val key: String?) {
    companion object {
        fun of(captured: String?, normalization: MerchantNormalization): MerchantColumns {
            val raw = captured
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { Merchant.clean(it, normalization).value.isNotEmpty() }
            return MerchantColumns(
                raw = raw,
                display = raw?.let { Merchant.displayFor(it, normalization) }?.takeIf { it.isNotEmpty() },
                key = raw?.let { Merchant.clean(it, normalization).value },
            )
        }
    }
}
