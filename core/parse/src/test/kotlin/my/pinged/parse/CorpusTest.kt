package my.pinged.parse

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusTest {

    private data class Fixture(
        val name: String,
        val pkg: String,
        val title: String?,
        val bigText: String?,
        val expect: String,
        val rule: String?,
        val amountSen: Long?,
        val merchant: String?,
        val merchantDisplay: String?,
        val direction: Direction?,
        val body: String,
    )

    private fun parse(file: File): Fixture {
        // Tolerant of CRLF, and explicit about the one structural rule: a
        // header block, a blank line, then the notification body. Destructuring
        // a short split threw an opaque IndexOutOfBoundsException instead.
        val parts = file.readText().replace("\r\n", "\n").split("\n\n", limit = 2)
        require(parts.size == 2) {
            "${file.name} is malformed: expected a header block, a blank line, then the " +
                "notification body, but found no blank line"
        }
        val (header, body) = parts
        val h = header.lineSequence()
            .filter { it.contains(':') }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return Fixture(
            name = file.name,
            pkg = requireNotNull(h["package"]) { "${file.name} has no package" },
            title = h["title"],
            bigText = h["big_text"],
            expect = requireNotNull(h["expect"]) { "${file.name} has no expect" },
            rule = h["rule"],
            amountSen = h["amount_sen"]?.toLong(),
            merchant = h["merchant"],
            merchantDisplay = h["merchant_display"],
            direction = h["direction"]?.let { name ->
                Direction.entries.firstOrNull { it.name == name }
                    ?: error("${file.name} names an unknown direction '$name'")
            },
            body = body.trim(),
        )
    }

    /**
     * The corpus, listed and parsed once for the whole class.
     *
     * Four tests each listed the directory themselves and three then
     * re-parsed every file, so the corpus was read four times per run and the
     * extension filter existed in four places.
     */
    private val fixtures: List<Fixture> by lazy {
        val dir = File(javaClass.getResource("/fixtures")!!.toURI())
        val files = dir.listFiles { f: File -> f.extension == "txt" }.orEmpty().sortedBy { it.name }
        assertTrue("No fixtures found", files.isNotEmpty())
        files.map(::parse)
    }

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
