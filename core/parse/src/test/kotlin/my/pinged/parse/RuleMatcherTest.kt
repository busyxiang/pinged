package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuleMatcherTest {
    private lateinit var matcher: RuleMatcher
    private val tng = "my.com.tngdigital.ewallet"
    private val mae = "com.maybank2u.life"
    private val probe = "com.example.probe"

    @Before fun setUp() {
        matcher = RuleMatcher(ParseFixtures.bundledPack)
    }

    /**
     * A matcher over a pack built in memory, bypassing [PackLoader].
     *
     * Both callers need that bypass -- they exist to prove RuleMatcher
     * defends itself against a pack the loader never saw (spec 5.8 builds
     * them at runtime) -- but neither needs to spell out the three levels of
     * wrapper to say so.
     */
    private fun directPack(vararg rules: TemplateRule): RuleMatcher = RuleMatcher(
        ParsePack(
            packVersion = 2,
            packages = listOf(PackagePack(pkg = probe, label = "Probe", rules = rules.toList())),
        )
    )

    /** A one-package probe pack, so a behaviour can be isolated from the bundled rules. */
    private fun probePack(rules: String, reject: String = "[]"): RuleMatcher = RuleMatcher(
        PackLoader.load(
            """
            {
              "pack_version": 7,
              "packages": [
                { "package": "$probe", "label": "Probe", "reject": $reject, "rules": [$rules] }
              ]
            }
            """.trimIndent()
        )
    )

    @Test fun `a plain payment matches`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM12.00 to 99 SPEEDMART successful", null)
        out as MatchOutcome.Matched
        assertEquals("tng-payment-v1", out.ruleId)
        assertEquals(1200L, out.amountSen)
        assertEquals("99 SPEEDMART", out.merchantRaw)
        assertEquals(Direction.EXPENSE, out.direction)
        assertEquals(Confidence.HIGH, out.confidence)
    }

    @Test fun `the duitnow example from the design parses`() {
        val out = matcher.match(mae, "DuitNow Payment", "You have paid RM12.00 to Restoran Yuen Kee Home Town Cafe", null)
        out as MatchOutcome.Matched
        assertEquals(1200L, out.amountSen)
        assertEquals("Restoran Yuen Kee Home Town Cafe", out.merchantRaw)
    }

    /**
     * The expanded body carries a reference number and a balance under the payment
     * line, and the merchant is still the merchant.
     *
     * Patterns compile DOTALL and normalization collapses newlines to spaces, so a
     * merchant group anchored to the end of the *concatenated* fields captured
     * "Restoran Yuen Kee Home Town Cafe Ref ABC123 Baki RM500.00" at HIGH
     * confidence. Scoping the rule to `text` bounds it: the anchor then means the
     * end of the one-line summary.
     */
    @Test fun `an expanded body below the payment line is not part of the merchant`() {
        val out = matcher.match(
            mae,
            "DuitNow Payment",
            "You have paid RM12.00 to Restoran Yuen Kee Home Town Cafe",
            "You have paid RM12.00 to Restoran Yuen Kee Home Town Cafe\nRef: ABC123\nBaki: RM500.00",
        )
        out as MatchOutcome.Matched
        assertEquals("mae-duitnow-paid-v1", out.ruleId)
        assertEquals(1200L, out.amountSen)
        assertEquals("Restoran Yuen Kee Home Town Cafe", out.merchantRaw)
        assertEquals(Confidence.HIGH, out.confidence)
    }

    /**
     * And with no one-line summary to scope to, the amount is still captured and
     * the merchant is deliberately not.
     *
     * This is the fallback, so bounding the rule above cannot lose a payment. What
     * it will not do is guess: over the whole body nothing says where a merchant
     * ends, so it captures none, `MERCHANT_MISSING` fires, and the transaction
     * reaches the review inbox with the amount right.
     *
     * **`confidence: HIGH`, which reads oddly on a fallback and is the point.** The
     * rule is not uncertain -- it matched a literal "You have paid" and the shared
     * amount fragment, exactly as much evidence as the scoped rule. Declaring
     * REVIEW fired `RULE_REVIEW`, which outranks `MERCHANT_MISSING` in
     * `PendingReasons.PRECEDENCE`, so the stored reason was "I am not sure" when
     * the one thing needed is for the user to name the shop.
     */
    @Test fun `a payment with no one-line summary is captured for review without a merchant`() {
        val out = matcher.match(
            mae,
            "DuitNow Payment",
            null,
            "You have paid RM12.00 to Restoran Yuen Kee Home Town Cafe\nRef: ABC123\nBaki: RM500.00",
        )
        out as MatchOutcome.Matched
        assertEquals("mae-duitnow-paid-body-v1", out.ruleId)
        assertEquals(1200L, out.amountSen)
        assertNull(
            "A merchant guessed off the whole body is worse than none: the inbox " +
                "can ask, and a committed wrong merchant cannot",
            out.merchantRaw,
        )
        // HIGH, and the KDoc above says why: the rule is not the uncertain
        // part, the merchant is, and the gate has a reason that says exactly
        // that.
        assertEquals(Confidence.HIGH, out.confidence)
    }

    /**
     * An amount whose number carries on past the capture is not an amount.
     *
     * The pattern's amount group reads ASCII digits and an ASCII point, so it stops
     * at anything else: `Reload of RM12` + U+FF0E + `50` captures `12`, and
     * `tng-reload-v1` has nothing after its amount to notice -- RM12.50 stored as
     * RM12.00, silently.
     *
     * Banning the Unicode digit class from patterns closed the *digits* half and
     * left the separator half open, because a separator is not a digit. The guard
     * is now where both end, at the point a capture becomes money.
     */
    @Test fun `an amount cut short by a foreign decimal separator is not captured`() {
        val out = matcher.match(tng, "Touch 'n Go", "Reload of RM12\uFF0E50", null)
        assertTrue(
            "RM12\uFF0E50 parsed as ${(out as? MatchOutcome.Matched)?.amountSen} sen",
            out is MatchOutcome.Unmatched,
        )
    }

    /** And a digit immediately after the capture, with no separator at all. */
    @Test fun `an amount followed by more digits is not captured`() {
        val out = matcher.match(tng, "Touch 'n Go", "Reload of RM12\uFF11\uFF12", null)
        assertTrue(out is MatchOutcome.Unmatched)
    }

    /**
     * A full stop that ends a sentence is not a truncation, and this is the
     * assertion that stops the guard being a blanket refusal of trailing
     * punctuation.
     */
    @Test fun `an amount at the end of a sentence is still captured`() {
        val out = matcher.match(tng, "Touch 'n Go", "Reload of RM12.50.", null)
        assertEquals(1250L, (out as MatchOutcome.Matched).amountSen)
    }

    // Trailing reward copy must not perturb the amount extraction. Note this
    // does NOT by itself prove templates run before rejects: none of the
    // bundled tng reject terms (OTP, TAC, do not share, jangan kongsi,
    // unsuccessful, gagal, failed, declined, voucher, diskaun, % off) appear
    // in this message, so it passes identically under a reject-first
    // implementation. The actual ordering guard is the collision test below.
    @Test fun `trailing reward copy does not change the amount`() {
        val out = matcher.match(
            tng, "Touch 'n Go",
            "Payment of RM52.30 to 99 SPEEDMART successful. You earned RM1.05 cashback.",
            null,
        )
        out as MatchOutcome.Matched
        assertEquals(5230L, out.amountSen)
    }

    // The load-bearing test. Spec 5.3. A test-local pack deliberately puts a
    // reject keyword ("bonus") inside a message that also matches a
    // template, so a reject-first implementation fails this immediately.
    // The bundled production pack.json intentionally omits "cashback" from
    // its reject lists (see its comment), so it cannot exercise this path;
    // this pack exists only to construct a real collision without polluting
    // the production pack.
    @Test fun `a template match wins over a colliding reject and records the collision`() {
        val collidePack = """
            {
              "pack_version": 1,
              "packages": [
                {
                  "package": "com.example.collide",
                  "label": "Collision Test",
                  "reject": [
                    { "id": "collide-bonus", "any_of": ["bonus"] }
                  ],
                  "rules": [
                    {
                      "id": "collide-payment-v1", "priority": 100,
                      "direction": "EXPENSE", "confidence": "HIGH",
                      "requires": { "text_contains_all": ["Payment of", "successful"] },
                      "pattern": "Payment of RM\\s?(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?) to (?<merchant>.+?) successful"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val collideMatcher = RuleMatcher(PackLoader.load(collidePack))

        val out = collideMatcher.match(
            "com.example.collide", "Test",
            "Payment of RM20.00 to Test Merchant successful. You earned a bonus.",
            null,
        )
        out as MatchOutcome.Matched
        assertEquals(2000L, out.amountSen)
        assertEquals("collide-bonus", out.rejectCollisionId)
    }

    @Test fun `a reload is flagged as a transfer suspect`() {
        val out = matcher.match(tng, "Touch 'n Go", "Reload of RM100.00 was successful", null)
        out as MatchOutcome.Matched
        assertEquals(Kind.TRANSFER_SUSPECT, out.kind)
        assertEquals(Confidence.REVIEW, out.confidence)
        assertEquals(ExclusionReason.TRANSFER, out.exclusionReason)
    }

    @Test fun `an otp message is rejected`() {
        val out = matcher.match(tng, "Touch 'n Go", "TAC 123456 for RM250.00 transfer. Do not share.", null)
        assertEquals("tng-otp", (out as MatchOutcome.Rejected).rejectRuleId)
    }

    @Test fun `a failed transaction is rejected`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM12.00 to 99 SPEEDMART unsuccessful", null)
        assertTrue(out is MatchOutcome.Rejected)
    }

    @Test fun `a promotional push produces nothing`() {
        val out = matcher.match(tng, "Touch 'n Go", "Get RM10 off your next order with this voucher", null)
        assertTrue(out is MatchOutcome.Rejected)
    }

    @Test fun `an amount alone is never a transaction`() {
        val out = matcher.match(tng, "Touch 'n Go", "Your balance is RM88.00", null)
        assertEquals(MatchOutcome.Unmatched, out)
    }

    @Test fun `an unknown package produces nothing`() {
        val out = matcher.match("com.example.other", "x", "Payment of RM12.00 to X successful", null)
        assertEquals(MatchOutcome.Unmatched, out)
    }

    @Test fun `all null fields produce NoExtras`() {
        assertEquals(MatchOutcome.NoExtras, matcher.match(tng, null, null, null))
    }

    // Renamed: the second half of the old name was untrue. TextNormalizer
    // collapses newlines before any regex runs, so no pattern in this
    // pipeline ever sees one and this test never exercised DOTALL. The flag
    // stays set — it costs nothing and protects a caller that skips
    // normalization — but nothing here proves it.
    @Test fun `bigText is preferred over text`() {
        val out = matcher.match(
            tng, "Touch 'n Go", "Payment received",
            "Payment of RM30.00\nto 99 SPEEDMART successful",
        )
        out as MatchOutcome.Matched
        assertEquals(3000L, out.amountSen)
    }

    @Test fun `a non breaking space in the amount still matches`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM\u00A012.00 to 99 SPEEDMART successful", null)
        assertEquals(1200L, (out as MatchOutcome.Matched).amountSen)
    }

    @Test fun `an amount the normalizer rejects yields Unmatched not a crash`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM0.00 to 99 SPEEDMART successful", null)
        assertEquals(MatchOutcome.Unmatched, out)
    }

    // C1. Spec 5.9 makes rule ids unique only WITHIN a package, so two banks
    // may legally both ship `paid-v1`. A single global id-to-regex map kept
    // the last one and ran bank.b's pattern for bank.a, committing the
    // balance instead of the debit.
    @Test fun `a rule id reused across packages runs its own package pattern`() {
        val shared = """
            {
              "pack_version": 1,
              "packages": [
                {
                  "package": "bank.a", "label": "Bank A",
                  "rules": [
                    { "id": "paid-v1", "priority": 100, "direction": "EXPENSE",
                      "requires": { "text_contains_all": ["Debit"] },
                      "pattern": "Debit RM(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)" }
                  ]
                },
                {
                  "package": "bank.b", "label": "Bank B",
                  "rules": [
                    { "id": "paid-v1", "priority": 100, "direction": "EXPENSE",
                      "requires": { "text_contains_all": ["Balance"] },
                      "pattern": "Balance RM(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val sharedMatcher = RuleMatcher(PackLoader.load(shared))

        val a = sharedMatcher.match("bank.a", "Bank A", "Debit RM12.00. Balance RM880.00", null)
        assertEquals(1200L, (a as MatchOutcome.Matched).amountSen)

        val b = sharedMatcher.match("bank.b", "Bank B", "Debit RM12.00. Balance RM880.00", null)
        assertEquals(88000L, (b as MatchOutcome.Matched).amountSen)
    }

    // The loaded pack's version has to reach the matcher: every outcome is
    // traceable to a pack version, and spec 5.5's re-parse is driven by it.
    @Test fun `the pack version reaches the matcher`() {
        assertEquals(7, matcher.packVersion)
        assertEquals(7, probePack(PAYMENT_RULE).packVersion)
    }

    // I4. The cap must fail closed. Truncating an over-long field cut the
    // last digit off the amount and recorded RM1,234.50 for RM1,234.56.
    @Test fun `a body over the input cap is unmatched rather than truncated`() {
        val tail = "Reload of RM1234.56"
        val overCap = "x".repeat(RuleMatcherLimits.MAX_INPUT_CHARS + 1 - tail.length) + tail
        assertEquals(RuleMatcherLimits.MAX_INPUT_CHARS + 1, overCap.length)
        assertEquals(MatchOutcome.Unmatched, matcher.match(tng, null, overCap, null))
    }

    // ...and the guard is a cap, not a blanket refusal of long messages.
    @Test fun `a body exactly at the input cap still matches in full`() {
        val tail = "Reload of RM1234.56"
        val atCap = "x".repeat(RuleMatcherLimits.MAX_INPUT_CHARS - tail.length) + tail
        assertEquals(RuleMatcherLimits.MAX_INPUT_CHARS, atCap.length)
        val out = matcher.match(tng, null, atCap, null)
        assertEquals(123456L, (out as MatchOutcome.Matched).amountSen)
    }

    // I5. A pathological pattern used to be indistinguishable from a miss,
    // so a capture that was never really examined looked settled. Unguarded,
    // this pattern runs for ~17s.
    @Test fun `a pathological pattern gives up instead of reporting a miss`() {
        val evil = probePack(
            """{ "id": "probe-evil-v1", "priority": 100, "direction": "EXPENSE",
                 "pattern": "^(a+)+\\1(?<amount>x)$" }"""
        )
        val started = System.nanoTime()
        val out = evil.match(probe, null, "a".repeat(30) + "b", null)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        assertEquals(MatchOutcome.GaveUp, out)
        assertTrue("gave up after ${elapsedMillis}ms", elapsedMillis < 2_000L)
    }

    // Residual 1. GaveUp takes precedence over Rejected, and nothing used to
    // hold that: inverting the two branches left all 90 tests green. The
    // ordering is load-bearing because templates-before-rejects means a
    // reject decision is only sound once every template has actually run. A
    // capture abandoned mid-sweep has not earned a verdict, and spec 5.5
    // re-parses GaveUp while a Rejected capture is only revisited on a pack
    // upgrade. Reporting Rejected here would settle a capture that was never
    // examined.
    @Test fun `a pathological pattern gives up rather than reporting a collided reject`() {
        val evil = probePack(
            rules = """{ "id": "probe-evil-v1", "priority": 100, "direction": "EXPENSE",
                 "pattern": "^(a+)+\\1(?<amount>x)$" }""",
            reject = """[{ "id": "probe-failed", "any_of": ["gagal"] }]""",
        )
        // The reject term is present in the body, so a reject-first matcher
        // returns Rejected for this exact input.
        val body = "a".repeat(30) + "b gagal"
        val out = evil.match(probe, null, body, null)
        assertEquals(MatchOutcome.GaveUp, out)

        // And the reject really would have fired, so the assertion above is
        // about precedence rather than about the reject failing to match.
        val benign = probePack(
            rules = """{ "id": "probe-none-v1", "priority": 100, "direction": "EXPENSE",
                 "pattern": "nothing here (?<amount>x)" }""",
            reject = """[{ "id": "probe-failed", "any_of": ["gagal"] }]""",
        )
        assertEquals(MatchOutcome.Rejected("probe-failed"), benign.match(probe, null, body, null))
    }

    // I5, second half. The per-rule budget alone is not a budget: twenty
    // pathological rules cost a second per capture, and over spec 5.9's
    // 5,000-row dry run that is roughly the hour and a half the guard exists
    // to prevent. The whole capture is bounded too.
    @Test fun `many pathological rules cannot outrun the per capture budget`() {
        val rules = (0 until 12).joinToString(",") { i ->
            """{ "id": "probe-evil-$i", "priority": ${100 - i}, "direction": "EXPENSE",
                 "pattern": "^(a+)+\\1(?<amount>x)$" }"""
        }
        val evil = probePack(rules)
        val started = System.nanoTime()
        val out = evil.match(probe, null, "a".repeat(30) + "b", null)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        assertEquals(MatchOutcome.GaveUp, out)
        // 12 rules at the 50ms per-rule budget would be ~600ms.
        assertTrue("12 pathological rules took ${elapsedMillis}ms", elapsedMillis < 400L)
    }

    // I3. A ParsePack can be built at runtime (spec 5.8's user-taught
    // templates), so the matcher cannot assume PackLoader validated it. A
    // pattern with no `amount` group threw IllegalArgumentException straight
    // out of match(), on the listener path, where spec 5.4 requires an
    // unmatched capture instead.
    @Test fun `a rule with no amount group is a non match rather than a crash`() {
        val direct = directPack(
            TemplateRule(
                id = "probe-no-group",
                priority = 100,
                direction = Direction.EXPENSE,
                pattern = "Payment of RM[0-9,.]+",
            ),
            TemplateRule(
                id = "probe-with-group",
                priority = 90,
                direction = Direction.EXPENSE,
                pattern = "Payment of RM(?<amount>[0-9,.]+)",
            ),
        )
        val out = direct.match(probe, "Probe", "Payment of RM12.00", null)
        out as MatchOutcome.Matched
        assertEquals("probe-with-group", out.ruleId)
        assertEquals(1200L, out.amountSen)
    }

    // I3. Priority order is enforced here as well as in PackLoader, because
    // a runtime-built pack never passes through the loader.
    @Test fun `an unsorted pack is still matched in priority order`() {
        val direct = directPack(
            TemplateRule(
                id = "probe-low",
                priority = 80,
                direction = Direction.EXPENSE,
                pattern = "RM(?<amount>[0-9,.]+)",
            ),
            TemplateRule(
                id = "probe-high",
                priority = 100,
                direction = Direction.EXPENSE,
                pattern = "total RM(?<amount>[0-9,.]+)",
            ),
        )
        val out = direct.match(probe, "Probe", "RM5.00 total RM12.00", null)
        out as MatchOutcome.Matched
        assertEquals("probe-high", out.ruleId)
        assertEquals(1200L, out.amountSen)
    }

    // Nothing proved the matcher honoured priority at all: two rules that
    // both match, declared low first.
    @Test fun `the higher priority rule wins when both match`() {
        val out = probePack(
            """
            { "id": "probe-low-v1", "priority": 80, "direction": "EXPENSE",
              "requires": { "text_contains_all": ["RM"] },
              "pattern": "RM(?<amount>[0-9,.]+)" },
            { "id": "probe-high-v1", "priority": 100, "direction": "EXPENSE",
              "requires": { "text_contains_all": ["total"] },
              "pattern": "total RM(?<amount>[0-9,.]+)" }
            """.trimIndent()
        ).match(probe, "Probe", "RM5.00 total RM12.00", null)
        out as MatchOutcome.Matched
        assertEquals("probe-high-v1", out.ruleId)
        assertEquals(1200L, out.amountSen)
    }

    // An unusable amount must skip to the next rule, not abandon the
    // capture: the rule that fired first is not necessarily the right one.
    @Test fun `a rule whose amount is unusable yields to the next rule`() {
        val out = probePack(
            """
            { "id": "probe-zero-v1", "priority": 100, "direction": "EXPENSE",
              "requires": { "text_contains_all": ["Payment of"] },
              "pattern": "Payment of RM(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)" },
            { "id": "probe-total-v1", "priority": 90, "direction": "EXPENSE",
              "requires": { "text_contains_all": ["total"] },
              "pattern": "total RM(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)" }
            """.trimIndent()
        ).match(probe, "Probe", "Payment of RM0.00, total RM12.00", null)
        out as MatchOutcome.Matched
        assertEquals("probe-total-v1", out.ruleId)
        assertEquals(1200L, out.amountSen)
    }

    @Test fun `a captured merchant is trimmed`() {
        val out = probePack(MERCHANT_RULE)
            .match(probe, "Probe", "Payment of RM12.00 to Kedai Ali successful", null)
        assertEquals("Kedai Ali", (out as MatchOutcome.Matched).merchantRaw)
    }

    @Test fun `a merchant that is only whitespace becomes null`() {
        val out = probePack(MERCHANT_RULE)
            .match(probe, "Probe", "Payment of RM12.00 to successful", null)
        assertEquals(null, (out as MatchOutcome.Matched).merchantRaw)
    }

    // The bundled rules all use text_contains_all, but no test made the
    // check load-bearing: their patterns are narrow enough to fail on their
    // own. Here the pattern is a bare amount, so the condition is the only
    // guard between a balance notice and a fabricated expense.
    @Test fun `text_contains_all requires every term`() {
        val allOf = probePack(
            """{ "id": "probe-all-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_all": ["Payment of", "successful"] },
                 "pattern": "RM(?<amount>[0-9,.]+)" }"""
        )
        assertEquals(
            1200L,
            (allOf.match(probe, "Probe", "Payment of RM12.00 was successful", null) as MatchOutcome.Matched)
                .amountSen,
        )
        assertEquals(MatchOutcome.Unmatched, allOf.match(probe, "Probe", "Payment of RM12.00 is pending", null))
        assertEquals(MatchOutcome.Unmatched, allOf.match(probe, "Probe", "Your balance is RM12.00", null))
    }

    @Test fun `text_contains_any admits any one term and refuses none`() {
        val anyOf = probePack(
            """{ "id": "probe-any-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_any": ["successful", "berjaya"] },
                 "pattern": "Payment of RM(?<amount>[0-9,.]+)" }"""
        )
        assertEquals(
            1200L,
            (anyOf.match(probe, "Probe", "Payment of RM12.00 berjaya", null) as MatchOutcome.Matched).amountSen,
        )
        assertEquals(MatchOutcome.Unmatched, anyOf.match(probe, "Probe", "Payment of RM12.00 pending", null))
    }

    @Test fun `text_contains_none blocks a rule its pattern would otherwise match`() {
        val noneOf = probePack(
            """{ "id": "probe-none-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_all": ["Payment of"], "text_contains_none": ["reversal"] },
                 "pattern": "Payment of RM(?<amount>[0-9,.]+)" }"""
        )
        assertEquals(
            1200L,
            (noneOf.match(probe, "Probe", "Payment of RM12.00", null) as MatchOutcome.Matched).amountSen,
        )
        assertEquals(
            MatchOutcome.Unmatched,
            noneOf.match(probe, "Probe", "Payment of RM12.00 reversal notice", null),
        )
    }

    @Test fun `title_contains_any is checked against the title alone`() {
        val titled = probePack(
            """{ "id": "probe-title-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_all": ["Payment of"], "title_contains_any": ["MyBank"] },
                 "pattern": "Payment of RM(?<amount>[0-9,.]+)" }"""
        )
        assertEquals(
            1200L,
            (titled.match(probe, "MyBank", "Payment of RM12.00", null) as MatchOutcome.Matched).amountSen,
        )
        // The term appears in the body, not the title, so the rule must not fire.
        assertEquals(
            MatchOutcome.Unmatched,
            titled.match(probe, "Promo", "Payment of RM12.00 at MyBank", null),
        )
    }

    // field: "text" — the title carries a decoy the concat default would
    // have matched. Under `concat` this returns RM99.00.
    @Test fun `field text ignores the title`() {
        val out = probePack(
            """{ "id": "probe-field-text-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_all": ["Payment of"], "field": "text" },
                 "pattern": "Payment of RM(?<amount>[0-9,.]+) to (?<merchant>.+?) successful" }"""
        ).match(probe, "Payment of RM99.00 to Ghost successful", "See the app for details", null)
        assertEquals(MatchOutcome.Unmatched, out)
    }

    // field: "title" — an anchored pattern that matches the title exactly
    // and cannot match the concatenation. Under `concat` this is Unmatched.
    @Test fun `field title matches the title alone`() {
        val out = probePack(
            """{ "id": "probe-field-title-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_all": ["Payment of"], "field": "title" },
                 "pattern": "^Payment of RM(?<amount>[0-9,.]+)$" }"""
        ).match(probe, "Payment of RM12.00", "Balance updated", null)
        assertEquals(1200L, (out as MatchOutcome.Matched).amountSen)
    }

    private companion object {
        const val PAYMENT_RULE =
            """{ "id": "probe-payment-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_all": ["Payment of"] },
                 "pattern": "Payment of RM(?<amount>[0-9,.]+)" }"""

        /** The merchant group deliberately swallows the surrounding spaces. */
        const val MERCHANT_RULE =
            """{ "id": "probe-merchant-v1", "priority": 100, "direction": "EXPENSE",
                 "requires": { "text_contains_all": ["Payment of"] },
                 "pattern": "Payment of RM(?<amount>[0-9,.]+) to(?<merchant>.*?)successful" }"""
    }
}
