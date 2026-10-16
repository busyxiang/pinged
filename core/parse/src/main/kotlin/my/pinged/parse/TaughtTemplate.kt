package my.pinged.parse

/**
 * Spec 5.8's rule taught by example: the user taps the amount and the
 * merchant, and the rest of the message becomes the pattern.
 *
 * **The pattern is the whole message.** Every word the user did not tap is a
 * literal, every other run of digits matches any number, and it is anchored at
 * both ends, so it reads only a message of the same shape. A message one word
 * off stays unread, and can be taught again.
 *
 * **Never `\d`, `\w` or `\s` in what this writes** (CLAUDE.md), because the
 * pattern is compiled by ICU on the phone. The amount reads ASCII digits, as
 * [Amount] does; another number is `\p{Nd}`, which both engines read alike.
 */
object TaughtTemplate {
    /** An amount's digits, `88.00` of `RM88.00`: ASCII, as [Amount] reads them. */
    private const val AMOUNT = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?"
    private val AMOUNT_IN_WORD = Regex(AMOUNT)

    /** What [Part.Amount] is in a pattern. */
    private const val AMOUNT_PATTERN = "(?<${PackRegex.AMOUNT_GROUP}>$AMOUNT)"

    /**
     * `\d`, `\w` or `\s` unescaped, which [derive] never writes. A stored
     * pattern with one came from somewhere else -- an edited backup -- and is
     * refused as `PackLoader` refuses it in a pack.
     */
    private val ENGINE_DEPENDENT_CLASS = Regex("(?<!\\\\)\\\\[dDwWsS]")

    /** [Skeleton]'s digit run, in a class both engines read the same way. */
    private const val NUMBER = "\\p{Nd}(?:[\\p{Nd},.]*\\p{Nd})?"
    private val NUMBER_RUN = Regex(NUMBER)

    /** Lazy, and bounded by the literal after it or the pattern's end. */
    private const val MERCHANT_PATTERN = "(?<${PackRegex.MERCHANT_GROUP}>.+?)"

    /** Stripped off the merchant's last word and kept literal: `SENG.` taps as `SENG`. */
    private val TRAILING_STOP = Regex("[.,;:!?]+$")

    /** Characters a pattern reads as syntax. Escaped one by one, never `\Q..\E`, which a `\E` in the text ends. */
    private const val SYNTAX = "\\^$.|?*+()[]{}"

    private const val RULE_ID_PREFIX = "taught-"

    /** Spec 5.8's first guard: this many matches go to review before one commits silently. */
    const val REVIEWED_MATCHES = 3

    /**
     * A capture's text as the Teach screen lays it out: spec 5.2's `concat`
     * through [TextNormalizer.forMatch], which is what a pattern runs
     * against, split at its spaces. The first [titleWords] are the title's.
     */
    data class Words(val words: List<String>, val titleWords: Int)

    fun words(title: String?, text: String?, bigText: String?): Words {
        val concat = TextNormalizer.forMatch(listOfNotNull(title, bigText ?: text).joinToString(" "))
        val titleOnly = TextNormalizer.forMatch(title.orEmpty())
        return Words(
            words = if (concat.isEmpty()) emptyList() else concat.split(' '),
            titleWords = if (titleOnly.isEmpty()) 0 else titleOnly.split(' ').size,
        )
    }

    /** One piece of what Pinged will look for, as the screen's preview draws it. */
    sealed interface Part {
        data class Literal(val text: String) : Part
        data object Amount : Part
        data object Merchant : Part
        data object AnyNumber : Part
    }

    /** A derived rule: its [pattern] to store, and the [preview] it was written from. */
    data class Derived(val pattern: String, val preview: List<Part>, val hasMerchant: Boolean)

    /**
     * The rule for [words] with word [amount] tapped as the amount and the
     * words of [merchant], if any, as the merchant. Null when the amount word
     * holds no amount Pinged records, or the merchant covers it: there is then
     * nothing to save.
     */
    fun derive(words: Words, amount: Int, merchant: IntRange?): Derived? {
        val list = words.words
        if (amount !in list.indices) return null
        if (merchant != null && (merchant.isEmpty() || merchant.first < 0 || merchant.last > list.lastIndex || amount in merchant)) {
            return null
        }
        val tapped = list[amount]
        val found = AMOUNT_IN_WORD.findAll(tapped).firstOrNull { Amount.toSen(it.value) != null } ?: return null

        val parts = mutableListOf<Part>()
        fun literal(text: String) {
            var at = 0
            for (run in NUMBER_RUN.findAll(text)) {
                if (run.range.first > at) parts += Part.Literal(text.substring(at, run.range.first))
                parts += Part.AnyNumber
                at = run.range.last + 1
            }
            if (at < text.length) parts += Part.Literal(text.substring(at))
        }

        var i = 0
        while (i < list.size) {
            if (i > 0) literal(" ")
            when {
                i == amount -> {
                    literal(tapped.substring(0, found.range.first))
                    parts += Part.Amount
                    literal(tapped.substring(found.range.last + 1))
                }
                merchant != null && i == merchant.first -> {
                    val stop = TRAILING_STOP.find(list[merchant.last])?.value.orEmpty()
                    parts += Part.Merchant
                    literal(stop)
                    i = merchant.last
                }
                else -> literal(list[i])
            }
            i++
        }

        val preview = parts.fold(mutableListOf<Part>()) { merged, part ->
            val last = merged.lastOrNull()
            if (part is Part.Literal && last is Part.Literal) merged[merged.lastIndex] = Part.Literal(last.text + part.text)
            else merged += part
            merged
        }
        val pattern = preview.joinToString("", prefix = "^", postfix = "$") { part ->
            when (part) {
                is Part.Literal -> escape(part.text)
                Part.Amount -> AMOUNT_PATTERN
                Part.Merchant -> MERCHANT_PATTERN
                Part.AnyNumber -> NUMBER
            }
        }
        return Derived(pattern, preview, hasMerchant = merchant != null)
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            if (c in SYNTAX) append('\\')
            append(c)
        }
    }

    /** A `user_template_rule` row, as much of it as matching needs. */
    data class Stored(
        val id: Long,
        val sourcePackage: String,
        val pattern: String,
        val direction: Direction,
        val matchCount: Int,
    )

    /** The `matched_rule_id` a capture [Stored] row [id] read records. */
    fun ruleId(id: Long): String = "$RULE_ID_PREFIX$id"

    /** The row a `matched_rule_id` names, or null for a pack rule's id. */
    fun storedIdOf(ruleId: String?): Long? =
        ruleId?.takeIf { it.startsWith(RULE_ID_PREFIX) }?.removePrefix(RULE_ID_PREFIX)?.toLongOrNull()

    /**
     * A matcher over [rules], each scoped to its own app and REVIEW until it
     * has read [REVIEWED_MATCHES] messages. Through [RuleMatcher], so a taught
     * rule meets the pack's time budget, input cap and truncated-number check.
     *
     * A stored pattern that does not compile, or that ICU would read
     * differently ([ENGINE_DEPENDENT_CLASS]), is left out rather than thrown:
     * one bad row would otherwise stop stage two for every app. The newest
     * rule wins where two read one message.
     */
    fun matcher(rules: List<Stored>): RuleMatcher {
        val compiling = rules.filter {
            !ENGINE_DEPENDENT_CLASS.containsMatchIn(it.pattern) && runCatching { PackRegex.compile(it.pattern) }.isSuccess
        }
        return RuleMatcher(
            ParsePack(
                packVersion = 0,
                packages = compiling.groupBy { it.sourcePackage }.map { (pkg, own) ->
                    PackagePack(
                        pkg = pkg,
                        label = pkg,
                        rules = own.map {
                            TemplateRule(
                                id = ruleId(it.id),
                                priority = it.id.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                direction = it.direction,
                                confidence = if (it.matchCount < REVIEWED_MATCHES) Confidence.REVIEW else Confidence.HIGH,
                                pattern = it.pattern,
                            )
                        },
                    )
                },
            ),
        )
    }
}
