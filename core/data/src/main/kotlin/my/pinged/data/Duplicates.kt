package my.pinged.data

/**
 * The two windows spec 7.2 carries, named once.
 *
 * The same numbers are read by the DAO's callers, by its tests and by
 * [slotRefreshOutcome], and a window that disagrees with itself between two of
 * those either drops a real transaction or files a refresh as spending.
 */
object DuplicateWindows {
    /** Layer 1 rule 2: same `content_hash`, different `sbn_key`, within 60s. */
    const val CONTENT_HASH_MILLIS = 60_000L

    /**
     * Layer 1 rule 1: same `sbn_key` **and** `content_hash` within ten minutes
     * is a refresh of one notification, not a second payment.
     *
     * There has to be a window at all because `content_hash` deliberately
     * excludes time (spec 4) and normalization preserves digits, so two
     * genuinely separate identical payments are identical by construction. Ten
     * minutes rather than an hour because the boundary is only safe in one
     * direction: inside it a match is dropped silently, outside it the pair
     * becomes a review item costing one tap.
     */
    const val SLOT_REFRESH_MILLIS = 600_000L

    /**
     * Layer 2: identical `amount_sen` across different packages within ten
     * minutes. Expressed as being the same number as [SLOT_REFRESH_MILLIS],
     * not a second literal, so the two cannot be tuned apart -- spec 7.2 chose
     * ten minutes for rule 1 precisely so the design carries two windows.
     */
    const val LAYER_TWO_MILLIS = SLOT_REFRESH_MILLIS
}

/**
 * What layer 1 rule 1 does with a slot-and-content match: a refresh of one
 * notification, or two payments that happen to read identically. Never a third
 * outcome -- spec 7.2 closes with "duplicates are never dropped automatically".
 */
enum class SlotRefreshOutcome {
    /** A refresh. No transaction; the capture is marked `UPDATE_OF`. */
    UPDATE_OF,

    /**
     * Beyond the window. A transaction **is** created, flagged as a duplicate
     * suspect and therefore `PENDING` (spec 7.1), landing in the review inbox
     * with Merge / Keep both. This extends layer 2 to the same-package case its
     * own conditions exclude.
     */
    DUPLICATE_SUSPECT,
}

/**
 * Rule 1 of spec 7.2's layer 1, given how long ago the earlier row with the
 * same `sbn_key` and `content_hash` was posted.
 *
 * [Arrival.CATCHUP][my.pinged.data.entity.Arrival.CATCHUP] gets no exemption
 * and needs none: a live notification re-delivered on rebind carries its
 * original `postTime`, so elapsed time against its own earlier row is zero and
 * the window returns `UPDATE_OF` anyway. A second posting reusing the slot has
 * a new `postTime` and is judged on it.
 *
 * The boundary is inclusive, matching layer 2's `BETWEEN`.
 *
 * A negative [elapsedMillis] means the matched row was posted *after* this
 * capture, which only happens when insertion order and `posted_at` order
 * disagree -- an import preserves both columns from a file the user can edit.
 * Out of order means unknown, and unknown goes to the review inbox.
 */
fun slotRefreshOutcome(elapsedMillis: Long): SlotRefreshOutcome = when {
    elapsedMillis < 0 -> SlotRefreshOutcome.DUPLICATE_SUSPECT
    elapsedMillis <= DuplicateWindows.SLOT_REFRESH_MILLIS -> SlotRefreshOutcome.UPDATE_OF
    else -> SlotRefreshOutcome.DUPLICATE_SUSPECT
}
