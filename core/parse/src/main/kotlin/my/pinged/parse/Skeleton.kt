package my.pinged.parse

/**
 * Spec 7.2's layer-2 comparison key, ahead of its consumer.
 *
 * Nothing in production reads this yet -- spec 5.7's `user_reject_rule` and
 * 5.8's taught templates are a later milestone -- and only `SkeletonTest`
 * exercises it. Noted the way `ChartRamp` is, so it is not mistaken for
 * something in use.
 */
object Skeleton {
    /**
     * `\d` here on purpose, and it is the one place in this module where it is
     * right. `PackLoader` refuses it in a pattern because there it decides how
     * much money a notification says; here every run of digits collapses to a
     * single `#` whatever alphabet wrote them, so ICU's wider reading on
     * Android is the reading this wants. Two notifications that differ only in
     * how their digits are encoded should have the same skeleton.
     */
    private val DIGIT_RUN = Regex("\\d[\\d,.]*\\d|\\d")

    fun of(raw: String): String =
        DIGIT_RUN.replace(TextNormalizer.forCompare(raw), "#")
}
