package my.pinged.parse

/**
 * Spec 5.7's digit-stripped signature, which a `user_reject_rule` stores and
 * stage two compares a later unmatched capture against by equality.
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

    /**
     * A capture's skeleton, of spec 5.2's `concat` -- the text a template
     * reads. Built from the same fields as [CaptureFields.of], so two captures
     * every template reads alike cannot have different skeletons.
     */
    fun ofCapture(title: String?, text: String?, bigText: String?): String =
        of(listOfNotNull(title, bigText ?: text).joinToString(" "))
}
