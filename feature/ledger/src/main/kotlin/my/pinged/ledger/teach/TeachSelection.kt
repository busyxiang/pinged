package my.pinged.ledger.teach

import my.pinged.parse.TaughtTemplate

/**
 * The Teach screen's two taps (spec 5.8, `design/Teach.dc.html`): the amount,
 * then the merchant, or "This message has no merchant in it".
 *
 * A tap on the amount's word again starts over, as the board's "TAP AGAIN TO
 * CHANGE" says. A merchant is a run of words: a tap beside it grows it, and
 * one anywhere else starts it again there.
 */
data class TeachSelection(
    val words: TaughtTemplate.Words,
    val amount: Int? = null,
    val merchant: IntRange? = null,
    /** The user said the message names no merchant (spec 5.8). */
    val withoutMerchant: Boolean = false,
) {
    enum class Step { AMOUNT, MERCHANT, READY }

    val step: Step
        get() = when {
            amount == null -> Step.AMOUNT
            merchant == null && !withoutMerchant -> Step.MERCHANT
            else -> Step.READY
        }

    fun tap(index: Int): TeachSelection {
        if (index !in words.words.indices) return this
        return when (amount) {
            // A word with no amount Pinged records in it is not taken for one.
            null -> if (TaughtTemplate.derive(words, index, null) != null) copy(amount = index) else this
            index -> TeachSelection(words)
            else -> copy(merchant = merchantWith(index), withoutMerchant = false)
        }
    }

    fun noMerchant(): TeachSelection = copy(merchant = null, withoutMerchant = true)

    /** What Pinged will look for, once there is an amount; null before. */
    fun derived(): TaughtTemplate.Derived? = amount?.let { TaughtTemplate.derive(words, it, merchant) }

    private fun merchantWith(index: Int): IntRange {
        val now = merchant ?: return index..index
        val grown = when (index) {
            now.first - 1 -> index..now.last
            now.last + 1 -> now.first..index
            else -> return index..index
        }
        return if (amount in grown) index..index else grown
    }
}
