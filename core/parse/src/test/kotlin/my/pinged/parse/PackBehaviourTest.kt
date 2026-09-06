package my.pinged.parse

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled pack, run against the shape of notification that breaks rules
 * rather than against the wording of the rules themselves.
 *
 * `PackLoader`'s two pattern checks are lints: they read regex source and look
 * for a spelling. The unbounded-merchant one shipped matching exactly one, so
 * five other ways of writing the same thing loaded clean and did identical
 * damage. A regex over regex source will always be one spelling behind.
 *
 * This is the check that cannot be: every rule the shipped pack declares, fed a
 * body with a second line, asserting no capture reaches that line.
 */
class PackBehaviourTest {

    private val pack = PackLoader.load(
        PackBehaviourTest::class.java.getResourceAsStream("/pack.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) },
    )
    private val matcher = RuleMatcher(pack)

    /**
     * The tail a real bank puts under the payment line: a reference number and
     * a balance. Normalization collapses the newline to a space before any
     * pattern runs, so a group that is not bounded reaches it as ordinary text.
     */
    private val tail = "Ref: ABC123\nBaki: RM500.00"

    /** Bodies that each rule in the bundled pack should match on their own. */
    private val bodies = listOf(
        "Payment of RM12.50 to STARBUCKS KLCC successful",
        "Reload of RM12.50",
        "You have paid RM12.50 to Restoran Yuen Kee",
        "Bayaran balik RM12.50 daripada Restoran Yuen Kee telah diterima",
    )

    @Test fun noRuleCapturesAMerchantThatReachesTheNextLine() {
        val offenders = mutableListOf<String>()
        for (pkg in pack.packages) {
            for (body in bodies) {
                val out = matcher.match(pkg.pkg, "Notification", body, "$body\n$tail")
                val merchant = (out as? MatchOutcome.Matched)?.merchantRaw ?: continue
                if (merchant.contains("Ref") || merchant.contains("Baki")) {
                    offenders += "${out.ruleId} captured '$merchant'"
                }
            }
        }
        assertTrue(
            "A merchant group ran past the line it was reading and swallowed " +
                "the rest of the notification:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty(),
        )
    }

    /**
     * **Every rule that can capture a merchant is exercised by a body above.**
     *
     * Without this the test above is a claim about the four strings someone
     * remembered to write -- and both its own KDoc and the commit that added it
     * said it "runs every rule the shipped pack declares", while it iterated
     * packages crossed with a hand-written list and never touched `pkg.rules`.
     *
     * Only rules with a merchant group, because a rule that captures no merchant
     * cannot swallow a line into one.
     */
    @Test fun everyMerchantCapturingRuleIsCoveredByABody() {
        val expanded = { rule: TemplateRule -> PackRegex.expand(rule.pattern, pack.fragments) }
        val shouldCover = pack.packages.flatMap { pkg ->
            pkg.rules
                .filter { expanded(it).contains("(?<" + PackRegex.MERCHANT_GROUP + ">") }
                .map { pkg.pkg to it.id }
        }
        val covered = pack.packages.flatMap { pkg ->
            bodies.mapNotNull { body ->
                (matcher.match(pkg.pkg, "Notification", body, "$body\n$tail") as? MatchOutcome.Matched)
                    ?.let { pkg.pkg to it.ruleId }
            }
        }.toSet()

        assertTrue(
            "No body in this file reaches these rules, so the assertion above " +
                "says nothing about them: " + (shouldCover - covered),
            (shouldCover - covered).isEmpty(),
        )
    }

    /**
     * And the same bodies without a tail still parse, so the assertion above
     * cannot be satisfied by a pack that matches nothing.
     */
    @Test fun everyBodyStillMatchesSomething() {
        val unmatched = bodies.filter { body ->
            pack.packages.none { matcher.match(it.pkg, "Notification", body, null) is MatchOutcome.Matched }
        }
        assertTrue("nothing in the pack matched: $unmatched", unmatched.isEmpty())
    }
}
