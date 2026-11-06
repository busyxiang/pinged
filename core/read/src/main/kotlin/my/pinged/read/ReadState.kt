package my.pinged.read

/**
 * What a [ScreenRead] has published (#296): the answer and the question it
 * answered, whether a read for a different question is in flight, and what
 * the screen can do with the ledger now ([ledger]).
 *
 * **Every read keeps its last answer** while it reads again (#285), so a screen
 * draws [answer] dimmed under `UPDATING` while [rereading], rather than the
 * not-read-yet layout it drew for the first read. The drawing is `:core:ui`'s;
 * only the boolean comes from here.
 *
 * @property answered the last answer to land, with its question; null until the first
 *   does, which is *not read yet*. Kept through a refusal, so the next successful read
 *   of its question can be told from the one before.
 * @property rereading a read for a question other than [answered]'s is in flight. A re-read of
 *   the same question (a resume, `Databases.rewrites`, a write's read-back) never sets it, and
 *   nor does a read refused or dropped, which is no longer in flight. False while [answered] is
 *   null: there is nothing held to draw dimmed.
 * @property ledger the one field a screen draws its standing from; see [LedgerAccess].
 */
data class ReadState<out Q, out A>(
    val answered: Answered<Q, A>?,
    val rereading: Boolean,
    val ledger: LedgerAccess = LedgerAccess.Readable,
) {
    /** [answered]'s answer; null before the first lands, and for a read whose answer is null. */
    val answer: A? get() = answered?.answer

    /** No read has landed. Unlike a null [answer], this cannot be a read's own answer. */
    val notReadYet: Boolean get() = answered == null
}

/** An answer, with the question it answered: what a screen compares to the question it asks now. */
data class Answered<out Q, out A>(val question: Q, val answer: A)

/**
 * What a screen may do with the ledger now (#376): exactly one of four, so no
 * screen merges a claim and a refusal by hand.
 *
 * **Precedence**, highest first: [Unreadable], [BeingReplaced], [Withholding],
 * [Readable]. A refusal outranks the claim, because a screen that cannot read
 * has nothing to withhold; and a refusal lasts until a read lands, so
 * [BeingReplaced] outlives the claim that ended until the screen reads again.
 *
 * A read-only screen draws [Withholding] as it draws [Readable].
 */
sealed interface LedgerAccess {
    /** The last read landed and no replacement is running. */
    data object Readable : LedgerAccess

    /**
     * [replacement] is running, and the last read landed: the screen draws
     * its answer and says nothing can be changed until it finishes. Every
     * write asked for meanwhile is refused before the runner ([ScreenRead.write]).
     */
    data class Withholding(val replacement: LedgerReplacement) : LedgerAccess

    /**
     * The last read, write or lookup was turned away by a replacement's gate
     * (`Outcome.Replacing`, #328): the screen says the ledger is being
     * replaced and draws none of its answer, which describes the ledger going away.
     */
    data object BeingReplaced : LedgerAccess

    /** The last read, write or lookup was refused because the database would not open (spec 11.1). */
    data object Unreadable : LedgerAccess
}
