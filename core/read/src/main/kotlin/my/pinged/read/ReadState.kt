package my.pinged.read

/**
 * What a [ScreenRead] has published (#296): the answer and the question it
 * answered, whether a read for a different question is in flight, and whether
 * the database could not be read.
 *
 * **Every read keeps its last answer** while it reads again (#285), so a screen
 * draws [answer] dimmed under `UPDATING` while [rereading], rather than the
 * not-read-yet layout it drew for the first read. The drawing is `:core:ui`'s;
 * only the boolean comes from here.
 *
 * @property answered the last answer to land, with its question; null until the first
 *   does, which is *not read yet*.
 * @property rereading a read for a question other than [answered]'s is in flight. A re-read of
 *   the same question (a resume, `Databases.rewrites`, a write's read-back) never sets it, and
 *   nor does a read refused or dropped, which is no longer in flight. False while [answered] is
 *   null: there is nothing held to draw dimmed.
 * @property unavailable the last read, or a write, was refused because the database could
 *   not be opened; the screen says so in place of whatever [answer] holds. A successful read
 *   clears it. [answered] is kept, so the next successful read of its question can be told
 *   from the one before.
 */
data class ReadState<out Q, out A>(
    val answered: Answered<Q, A>?,
    val rereading: Boolean,
    val unavailable: Boolean,
) {
    /** [answered]'s answer; null before the first lands, and for a read whose answer is null. */
    val answer: A? get() = answered?.answer

    /** No read has landed. Unlike a null [answer], this cannot be a read's own answer. */
    val notReadYet: Boolean get() = answered == null
}

/** An answer, with the question it answered: what a screen compares to the question it asks now. */
data class Answered<out Q, out A>(val question: Q, val answer: A)
