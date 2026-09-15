package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusTest {

    private val fixtures = ParseFixtures.corpus

    @Test fun `every fixture produces its expected outcome`() {
        val pack = ParseFixtures.bundledPack
        val matcher = RuleMatcher(pack)
        // Spec 5.4's lists come from the pack under test, not from an ambient
        // default, so a fixture proves the loaded file drives normalization.
        val normalization = pack.merchantNormalization
        val failures = mutableListOf<String>()
        fixtures.forEach { fx ->
            val outcome = matcher.match(fx.pkg, fx.title, fx.body, fx.bigText)
            when (fx.expect) {
                "matched" -> {
                    if (outcome !is MatchOutcome.Matched) {
                        failures += "${fx.name}: expected matched, got $outcome"
                    } else {
                        if (fx.rule != null && outcome.ruleId != fx.rule) {
                            failures += "${fx.name}: rule ${outcome.ruleId}, expected ${fx.rule}"
                        }
                        if (fx.amountSen != null && outcome.amountSen != fx.amountSen) {
                            failures += "${fx.name}: amount ${outcome.amountSen}, expected ${fx.amountSen}"
                        }
                        // Spec 5.4: `merchant_raw` is preserved untouched, so
                        // this is compared against the string as the rule
                        // captured it, prefixes, shouting and all.
                        if (fx.merchant != null && outcome.merchantRaw != fx.merchant) {
                            failures += "${fx.name}: merchant '${outcome.merchantRaw}', expected '${fx.merchant}'"
                        }
                        // ...and the cleanup lands on `merchant_display`. The
                        // composition is the one `ParsePass` uses, so a fixture
                        // asserts what a device would actually show.
                        if (fx.merchantDisplay != null) {
                            val shown = outcome.merchantRaw
                                ?.let { Merchant.displayFor(it, normalization) }
                            if (shown != fx.merchantDisplay) {
                                failures +=
                                    "${fx.name}: merchant_display '$shown', expected '${fx.merchantDisplay}'"
                            }
                        }
                        if (fx.direction != null && outcome.direction != fx.direction) {
                            failures += "${fx.name}: direction ${outcome.direction}, expected ${fx.direction}"
                        }
                    }
                }
                "rejected" -> if (outcome !is MatchOutcome.Rejected) {
                    failures += "${fx.name}: expected rejected, got $outcome"
                }
                "unmatched" -> if (outcome != MatchOutcome.Unmatched) {
                    failures += "${fx.name}: expected unmatched, got $outcome"
                }
                else -> failures += "${fx.name}: unknown expect '${fx.expect}'"
            }
        }

        assertEquals(emptyList<String>(), failures)
    }

    @Test fun `the corpus contains negative fixtures`() {
        val expects = fixtures.map { it.expect }
        assertTrue("A corpus with no negative fixtures proves nothing", expects.contains("rejected"))
        assertTrue(expects.contains("unmatched"))
    }

    // Spec 5.4's normalization has no corpus coverage unless fixtures pin the
    // display string: `merchant_raw` is preserved untouched, so asserting it
    // alone passes whatever the regex caught straight through and a corpus
    // that stops there cannot notice the pack's lists doing nothing at all.
    // The three that must be covered are the three a device got wrong: a
    // payment-rails suffix, an acronym that title-casing mangles, and a
    // merchant whose own capitalisation must survive.
    @Test fun `the corpus pins normalized display strings`() {
        val displays = fixtures.mapNotNull { it.merchantDisplay }
        assertTrue(
            "Spec 5.4's normalization must be pinned by fixtures, not only by unit tests",
            displays.size >= 3,
        )
    }

    // I10. `Direction.REFUND` decides the sign of money and shipped with no
    // fixture, no test and no bundled rule producing it. Spec 5.3 trap 4 and
    // spec 13 both require refund handling, and the bundled pack had no
    // Malay template at all.
    @Test fun `the corpus exercises the refund direction`() {
        val directions = fixtures.map { it.direction }
        assertTrue(
            "REFUND decides the sign of money and must be exercised by a fixture",
            directions.contains(Direction.REFUND),
        )
    }
}
