package my.pinged.parse

/**
 * Spec 5.6's "copy as fixture": an unread capture as a corpus file for
 * `src/test/resources/fixtures/`, expecting `unmatched` until a rule is
 * written for it.
 *
 * **The body is `bigText ?: text`**, the half of `concat` a template reads,
 * because a fixture has one body. A capture whose `text` and `bigText` both
 * matter to a rule loses the shorter one here.
 *
 * Masked by nobody: the header says which masks the repository asks for
 * (CLAUDE.md), and the person committing it applies them. No colon in a `#`
 * line, so a reader that splits headers on one could never take it for a key.
 */
object FixtureText {
    fun of(pkg: String, label: String, title: String?, text: String?, bigText: String?): String = buildString {
        append("package: ").append(pkg).append('\n')
        if (title != null) append("title: ").append(oneLine(title)).append('\n')
        append("expect: unmatched\n")
        append("# A real ").append(oneLine(label)).append(" notification, copied from Pinged's unread list.\n")
        append("# Before committing, mask a card's digits as **** and every transaction\n")
        append("# reference as zeros of the same shape, and say here what was masked.\n")
        append('\n')
        append(bigText ?: text.orEmpty())
        append('\n')
    }

    /** A header value ends at its line. */
    private fun oneLine(value: String) = value.replace(Regex("[\\r\\n]+"), " ")
}
