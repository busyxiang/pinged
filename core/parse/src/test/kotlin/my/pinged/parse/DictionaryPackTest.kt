package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictionary as pack data: what the loader refuses, and what the shipped
 * entries are held to. `PackTest` owns the rules half of the file.
 */
class DictionaryPackTest {

    private fun pack(dictionary: String) = """
        { "pack_version": 7, "packages": [], "dictionary": $dictionary }
    """.trimIndent()

    private fun assertRejected(json: String, vararg mustMention: String) {
        val failure = runCatching { PackLoader.load(json) }.exceptionOrNull()
        assertTrue(
            "expected a PackValidationException, got ${failure?.let { it::class.simpleName }}",
            failure is PackValidationException,
        )
        val message = failure!!.message.orEmpty()
        mustMention.forEach {
            assertTrue("message does not mention '$it': $message", message.contains(it))
        }
    }

    // ---- loading ---------------------------------------------------------

    @Test fun `a dictionary loads with its default mode`() {
        val loaded = PackLoader.load(
            pack(
                """[
                  { "text": "DIGI", "category": "Telco & internet" },
                  { "text": "RKL", "category": "Transport", "mode": "contains" }
                ]""",
            ),
        )
        assertEquals(
            listOf(
                DictionaryEntry("DIGI", "Telco & internet", MatchMode.PREFIX),
                DictionaryEntry("RKL", "Transport", MatchMode.CONTAINS),
            ),
            loaded.dictionary,
        )
    }

    @Test fun `a pack with no dictionary has an empty one`() {
        assertEquals(emptyList<DictionaryEntry>(), PackLoader.load(pack("[]")).dictionary)
    }

    // ---- the three refusals the ticket names -----------------------------

    @Test fun `an entry naming an unseeded category is refused`() {
        assertRejected(
            pack("""[{ "text": "DIGI", "category": "Telecoms" }]"""),
            "DIGI", "Telecoms", "seeded",
        )
    }

    @Test fun `two entries with the same text are refused whatever their modes`() {
        assertRejected(
            pack(
                """[
                  { "text": "DIGI", "category": "Telco & internet" },
                  { "text": "DIGI", "category": "Telco & internet", "mode": "exact" }
                ]""",
            ),
            "DIGI", "twice",
        )
    }

    @Test fun `two entries differing only in case are the same text`() {
        assertRejected(
            pack(
                """[
                  { "text": "Digi", "category": "Telco & internet" },
                  { "text": "DIGI", "category": "Telco & internet" }
                ]""",
            ),
            "twice",
        )
    }

    @Test fun `a pattern using the unicode digit class is refused`() {
        assertRejected(
            pack("""[{ "text": "99 \\d", "category": "Groceries" }]"""),
            "\\d", "plain text",
        )
    }

    // ---- shapes that would be silent no-ops ------------------------------

    @Test fun `a misspelt key is an error and not a default`() {
        // `mood` would otherwise load clean and file as a prefix entry.
        assertRejected(
            pack("""[{ "text": "RKL", "category": "Transport", "mood": "contains" }]"""),
            "mood", "mode",
        )
    }

    @Test fun `an unknown mode is refused`() {
        assertRejected(pack("""[{ "text": "RKL", "category": "Transport", "mode": "regex" }]"""))
    }

    @Test fun `a blank entry is refused because it would match every key`() {
        assertRejected(pack("""[{ "text": " ", "category": "Transport" }]"""), "blank")
    }

    @Test fun `an entry that starts or ends off a letter or digit is refused`() {
        assertRejected(pack("""[{ "text": "TNG*", "category": "Transport" }]"""), "TNG*", "letter or digit")
        assertRejected(pack("""[{ "text": " DIGI", "category": "Telco & internet" }]"""), "letter or digit")
    }

    // ---- what ships ------------------------------------------------------

    private val bundled get() = ParseFixtures.bundledPack.dictionary

    @Test fun `the bundled pack ships a dictionary naming only seeded categories`() {
        assertTrue("the first dictionary is empty", bundled.isNotEmpty())
        bundled.forEach { assertTrue("${it.text}: ${it.category}", it.category in SeededCategories.NAMES) }
    }

    /** What each shipped entry was observed catching, and where. */
    private data class Observed(val entry: String, val fixture: String, val category: String)

    private val observed = listOf(
        Observed("DIGI", "mae-fpx-from-account-digi.txt", "Telco & internet"),
        Observed("MY50", "tng-travel-pass-my50.txt", "Transport"),
    )

    private fun keyOf(fixtureName: String): String {
        val fx = ParseFixtures.fixture(fixtureName)
        val outcome = RuleMatcher(ParseFixtures.bundledPack).match(fx.pkg, fx.title, fx.body, fx.bigText)
        val raw = (outcome as MatchOutcome.Matched).merchantRaw!!
        return Merchant.clean(raw, ParseFixtures.bundledPack.merchantNormalization).value
    }

    @Test fun `every entry has an observed fixture the pack shows it catching`() {
        val categorizer = Categorizer(bundled)
        assertEquals(
            "an entry without an observed fixture, or a fixture for an entry that is gone",
            bundled.map { it.text }.sorted(),
            observed.map { it.entry }.sorted(),
        )
        observed.forEach { o ->
            assertEquals(
                "${o.entry} does not file the key of ${o.fixture}",
                Filing(o.category, FilingSource.DICTIONARY),
                categorizer.file(keyOf(o.fixture), learned = null),
            )
        }
    }

    @Test fun `the observed fixtures are real captures and not constructed ones`() {
        // The ticket's rule: an entry ships only with an observed string. The
        // fixture header says which a file is, so this reads it.
        observed.forEach { o ->
            val text = ParseFixtures::class.java.getResource("/fixtures/${o.fixture}")!!.readText()
            assertTrue("${o.fixture} must say it is a real notification", text.contains("A real "))
            assertTrue("${o.fixture} is constructed", !text.contains("Constructed, not observed"))
        }
    }

    // ---- negatives -------------------------------------------------------

    /** Short or common-word entries, each with keys it must not claim. Constructed. */
    private val negatives = mapOf(
        "DIGI" to listOf("DIGITAL BANK", "DIGIMAX CAFE", "MY DIGI", "INDIGI"),
        "MY50" to listOf("MY500 MART", "MY5", "SMY50", "MY50X"),
    )

    @Test fun `every entry that is short or a common word has a negative fixture`() {
        val short = bundled.filter { it.text.length <= 4 }.map { it.text }
        short.forEach { assertTrue("$it ships without a negative fixture", negatives[it].orEmpty().isNotEmpty()) }
    }

    @Test fun `negative fixtures file as nothing`() {
        val categorizer = Categorizer(bundled)
        negatives.forEach { (entry, keys) ->
            assertTrue("a negative for an entry that does not ship: $entry", bundled.any { it.text == entry })
            keys.forEach { key ->
                assertEquals(
                    "$key must not be filed by $entry",
                    Filing(null, FilingSource.NONE),
                    categorizer.file(key, learned = null),
                )
            }
        }
    }

    // ---- the near-miss corpus --------------------------------------------

    private data class NearMiss(val provenance: String, val raw: String)

    private fun nearMisses(): List<NearMiss> =
        DictionaryPackTest::class.java.getResourceAsStream("/near-miss-merchants.txt")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map {
                val (provenance, raw) = it.split('\t', limit = 2)
                NearMiss(provenance, raw)
            }
            .toList()

    @Test fun `the near-miss corpus files as nothing`() {
        val categorizer = Categorizer(bundled)
        val normalization = ParseFixtures.bundledPack.merchantNormalization
        val corpus = nearMisses()
        assertTrue("observed merchants are the point of the corpus", corpus.count { it.provenance == "observed" } >= 8)
        assertTrue(corpus.all { it.provenance == "observed" || it.provenance == "constructed" })
        corpus.forEach {
            val key = Merchant.clean(it.raw, normalization).value
            assertEquals(
                "${it.raw} (key $key) is another merchant and must file as NONE",
                Filing(null, FilingSource.NONE),
                categorizer.file(key, learned = null),
            )
        }
    }

    @Test fun `no observed near-miss is also a shipped dictionary merchant`() {
        // The corpus is other merchants by definition: a Digi row in it would
        // pass or fail for a reason that has nothing to do with near-misses.
        val shipped = bundled.map { it.text.uppercase() }
        nearMisses().forEach { m ->
            assertTrue("${m.raw} names a dictionary merchant", shipped.none { m.raw.uppercase().startsWith(it + " ") })
        }
    }
}
