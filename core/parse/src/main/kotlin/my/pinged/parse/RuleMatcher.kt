package my.pinged.parse

object RuleMatcherLimits {
    /** Wall-clock budget for one pattern against one capture. */
    const val TIME_BUDGET_MILLIS = 50L

    /**
     * Wall-clock budget for one capture across every pattern of its package.
     * Without it, twenty pathological rules cost a second per capture, and
     * spec 5.9's 5,000-row dry run becomes an hour and a half.
     */
    const val CAPTURE_BUDGET_MILLIS = 150L

    const val MAX_INPUT_CHARS = 4_000
}

/** A template rule with its pattern compiled and its conditions folded once. */
private class PreparedRule(
    val rule: TemplateRule,
    val regex: Regex,
    val conditions: PreparedConditions,
)

/** A package's rules in priority order, plus its reject terms folded once. */
private class PreparedPackage(
    val rules: List<PreparedRule>,
    val rejects: List<PreparedReject>,
)

private class PreparedReject(val id: String, val terms: List<String>)

/**
 * The value of a named group, or null when the pattern declares no such group.
 *
 * A pattern without an `amount` group is a non-match, not a crash: spec 5.4
 * requires a malformed amount to leave the capture unmatched rather than throw
 * on the listener path. `MatchResult.groups[name]` throws
 * IllegalArgumentException for a group the pattern never declared, so both
 * reads go through this.
 */
private fun MatchResult.groupOrNull(name: String): String? =
    try {
        groups[name]?.value
    } catch (_: IllegalArgumentException) {
        null
    }

/** As [groupOrNull], for where the group sits rather than what it holds. */
private fun MatchResult.rangeOrNull(name: String): IntRange? =
    try {
        groups[name]?.range
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Matches a capture against a pack. Deliberately defensive about its input:
 * spec 5.8's user-taught templates construct rules at runtime, so a
 * [ParsePack] reaching this constructor has not necessarily been through
 * [PackLoader]. Priority order, duplicate package entries and a missing
 * `amount` group are all handled here rather than assumed.
 */
class RuleMatcher(pack: ParsePack) {

    /** Recorded on every capture this matcher parses, so an outcome is traceable to a pack. */
    val packVersion: Int = pack.packVersion

    /**
     * Spec 5.4's lists, from the pack this matcher was built over.
     *
     * Re-exposed for the same reason [packVersion] is: a caller holding a matcher
     * holds the active pack and should not reach past it to a bundled default,
     * which would mean a second, independent load of pack.json at runtime -- and,
     * once spec 5.9's import exists, the *wrong* lists. `Merchant` takes these as
     * a required argument so that there is no other route.
     */
    val merchantNormalization: MerchantNormalization = pack.merchantNormalization

    private val byPackage: Map<String, PreparedPackage> = pack.packages
        .groupBy { it.pkg }
        .mapValues { (_, entries) ->
            PreparedPackage(
                // Merged here as well as sorted here, and for the same reason:
                // a runtime-built pack (spec 5.8's teach-by-example) has no
                // loader between it and this class, so neither step may live
                // only in PackLoader.
                //
                // The package's own rules are listed first and the sort is
                // stable, so at equal priority an app-specific rule beats the
                // rail's -- the app knows its own wording, the rail does not.
                rules = (entries.flatMap { it.rules } + pack.sharedRules)
                    .sortedByDescending(TemplateRule::priority)
                    .map {
                        PreparedRule(
                            it,
                            PackRegex.compile(it.pattern, pack.fragments),
                            PreparedConditions.of(it.requires),
                        )
                    },
                rejects = entries.flatMap { it.reject }
                    .map { PreparedReject(it.id, it.anyOf.map(TextNormalizer::forCompare)) },
            )
        }

    fun match(pkg: String, title: String?, text: String?, bigText: String?): MatchOutcome {
        if (title == null && text == null && bigText == null) return MatchOutcome.NoExtras
        val pp = byPackage[pkg] ?: return MatchOutcome.Unmatched

        val fields = CaptureFields.of(title, text, bigText)
        // The cap is a safety device, so it fails closed. Truncating instead
        // cut the last digit off an over-long body and recorded RM1,234.50
        // for RM1,234.56 — the cap producing the very error it exists to
        // prevent.
        if (fields.longestLength > RuleMatcherLimits.MAX_INPUT_CHARS) return MatchOutcome.Unmatched
        if (fields.concat.forMatch.isBlank()) return MatchOutcome.NoExtras

        val captureDeadline = System.nanoTime() + RuleMatcherLimits.CAPTURE_BUDGET_MILLIS * 1_000_000L
        var gaveUp = false

        // Templates first. A matched template is stronger evidence than a keyword.
        for (prepared in pp.rules) {
            if (System.nanoTime() > captureDeadline) {
                gaveUp = true
                break
            }
            if (!prepared.conditions.matches(fields)) continue
            val ruleField = fields.forField(prepared.conditions.field).forMatch

            // The smaller of what this rule may have and what the capture
            // has left, floored at 1ms so a nearly-spent capture budget still
            // gives the engine a chance rather than timing out by arithmetic.
            val budgetMillis = minOf(
                RuleMatcherLimits.TIME_BUDGET_MILLIS,
                (captureDeadline - System.nanoTime()) / 1_000_000L,
            ).coerceAtLeast(1L)
            val found = try {
                BoundedMatch.find(prepared.regex, ruleField, budgetMillis)
            } catch (_: RegexTimeoutException) {
                gaveUp = true
                continue
            } catch (_: StackOverflowError) {
                // java.util.regex recurses on some constructs; a pack arrives
                // as a file, so this is the same threat as a timeout.
                gaveUp = true
                continue
            } ?: continue

            if (truncatesANumber(found, ruleField)) continue
            val amount = Amount.toSen(found.groupOrNull(PackRegex.AMOUNT_GROUP) ?: "") ?: continue
            val merchant = found.groupOrNull(PackRegex.MERCHANT_GROUP)

            return MatchOutcome.Matched(
                ruleId = prepared.rule.id,
                amountSen = amount,
                merchantRaw = merchant?.trim()?.takeIf { it.isNotEmpty() },
                direction = prepared.rule.direction,
                confidence = prepared.rule.confidence,
                kind = prepared.rule.kind,
                exclusionReason = prepared.rule.exclusionReason,
                rejectCollisionId = firstReject(pp, fields),
            )
        }

        // A template that ran out of time is not a miss, and must not become
        // a reject either: the templates-before-rejects invariant means the
        // reject decision is only safe once every template has actually run.
        if (gaveUp) return MatchOutcome.GaveUp

        // Only now do rejects get a say.
        firstReject(pp, fields)?.let { return MatchOutcome.Rejected(it) }
        return MatchOutcome.Unmatched
    }

    private fun firstReject(pp: PreparedPackage, fields: CaptureFields): String? {
        val haystack = fields.concat.forCompare
        return pp.rejects.firstOrNull { r -> r.terms.any { haystack.contains(it) } }?.id
    }
}

/**
 * Whether an amount capture stopped in the middle of the number it was reading.
 *
 * **The general form of a bug found twice.** A pattern's amount group reads
 * ASCII digits and an ASCII decimal point, so it stops at anything else -- and
 * if what stopped it was a *different* decimal separator with more digits
 * behind it, the capture is a prefix of the real number. `Reload of RM12` +
 * U+FF0E + `50` captures `12`, and `tng-reload-v1` has nothing after its amount
 * to notice: RM12.50 stored as RM12.00, silently.
 */
private val CONTINUES_A_NUMBER =
    Regex("^(?:\\p{Nd}|[.,\u066B\uFF0E\u2024]\\p{Nd})")

private fun truncatesANumber(found: MatchResult, input: String): Boolean {
    // Through the same defensive accessor the value goes through: a rule that
    // declares no amount group reaches this function too, and asking for the
    // group by name throws rather than returning null.
    val amount = found.rangeOrNull(PackRegex.AMOUNT_GROUP) ?: return false
    val after = amount.last + 1
    if (after >= input.length) return false
    return CONTINUES_A_NUMBER.containsMatchIn(input.substring(after, minOf(after + 2, input.length)))
}
