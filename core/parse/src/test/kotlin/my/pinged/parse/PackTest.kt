package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackTest {
    // Serial name to enum entry, so this test asserts the wire format maps
    // onto the closed vocabulary rather than restating either side.
    private val FIELD_VALUES = mapOf(
        "title" to Field.TITLE,
        "text" to Field.TEXT,
        "bigText" to Field.BIG_TEXT,
        "concat" to Field.CONCAT,
    )
    private val DUPLICATE_PACKAGE =
        """{ "package": "my.com.tngdigital.ewallet", "label": "Duplicate" }"""

    private val json = """
    {
      "pack_version": 7,
      "packages": [{
        "package": "my.com.tngdigital.ewallet",
        "label": "Touch 'n Go eWallet",
        "reject": [
          { "id": "promo", "any_of": ["cashback", "voucher"] }
        ],
        "rules": [
          { "id": "tng-reload-v1", "priority": 90,
            "direction": "EXPENSE", "confidence": "REVIEW",
            "kind": "TRANSFER_SUSPECT", "exclusion_reason": "TRANSFER",
            "requires": { "text_contains_all": ["Reload"] },
            "pattern": "Reload of RM(?<amount>[0-9,]+\\.[0-9]{2})" },
          { "id": "tng-payment-v1", "priority": 100,
            "direction": "EXPENSE", "confidence": "HIGH",
            "requires": { "text_contains_all": ["Payment of", "successful"] },
            "pattern": "Payment of RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+?) successful" }
        ]
      }]
    }
    """.trimIndent()

    /**
     * Assert that [json] is rejected as a [PackValidationException] whose
     * message mentions each of [mustMention].
     *
     * Written out, this is three lines per case and there are fourteen of
     * them; the `!!.message!!` chain in particular threw a bare
     * NullPointerException when the type was right and the message was null,
     * which is the least useful way for a test to fail.
     */
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

    @Test fun `loads version and package`() {
        val pack = PackLoader.load(json)
        assertEquals(7, pack.packVersion)
        assertEquals(1, pack.packages.size)
        assertEquals("my.com.tngdigital.ewallet", pack.packages[0].pkg)
    }

    // The JSON above declares priority 90 before 100 on purpose: with them
    // declared in descending order this assertion held whether or not the
    // sort ran, which is why deleting the sort left the suite green.
    @Test fun `rules arrive sorted by descending priority`() {
        val rules = PackLoader.load(json).packages[0].rules
        assertEquals(listOf("tng-payment-v1", "tng-reload-v1"), rules.map { it.id })
    }

    @Test fun `optional fields default rather than fail`() {
        val rule = PackLoader.load(json).packages[0].rules[0]
        assertEquals(null, rule.kind)
        assertEquals(null, rule.exclusionReason)
        assertEquals(Confidence.HIGH, rule.confidence)
    }

    @Test fun `transfer suspect is parsed`() {
        val rule = PackLoader.load(json).packages[0].rules[1]
        assertEquals(Kind.TRANSFER_SUSPECT, rule.kind)
        assertEquals(Confidence.REVIEW, rule.confidence)
    }

    @Test fun `a pattern without an amount group is rejected at load`() {
        val bad = json.replace("(?<amount>[0-9,]+\\\\.[0-9]{2})", "[0-9,]+\\\\.[0-9]{2}")
        assertRejected(bad, "amount")
    }

    /**
     * `\d` is refused, because the two engines this app uses disagree about what it
     * means and only one of them is under test here.
     *
     * `java.util.regex`, which compiles this test, reads it as ASCII; ICU on the
     * device reads it as `\p{Nd}`, so a full-width amount matches -- and because
     * U+FF0E is not matched by `\.`, the rule captures the ringgit and drops the
     * sen. Every assertion in this file would stay green while RM12.50 was stored
     * as RM12.00 on a phone.
     *
     * Refused at load rather than corrected, because an author who wrote `\d` meant
     * one of two alphabets and the loader cannot know which. `FullWidthDigitsTest`,
     * on the device, is the other half.
     */
    @Test fun `a pattern using the unicode digit class is rejected at load`() {
        val bad = json.replace("[0-9,]+", "[\\\\d,]+")
        assertRejected(bad, "[0-9]", "Unicode")
    }

    /** `\D` is the same hazard spelled the other way round. */
    @Test fun `a pattern using the negated unicode digit class is rejected at load`() {
        val bad = json.replace("[0-9,]+", "[^\\\\D,]+")
        assertRejected(bad, "[0-9]")
    }

    /**
     * A merchant group anchored to the end of the concatenated fields is refused,
     * because the concatenation is not one field.
     *
     * Patterns compile DOTALL and normalization collapses newlines to spaces, so
     * `(?<merchant>.+)$` over the concat captures the title, the whole of
     * `big_text` and every line below the merchant. The bundled MAE rule did this:
     * against a body with a reference number and a balance under it, the merchant
     * became "Restoran X Ref ABC123 Baki RM500.00" -- at HIGH confidence, straight
     * to the ledger.
     */
    @Test fun `a merchant group anchored to the end of the concat is rejected at load`() {
        val bad = json.replace(
            """"pattern": "Payment of RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+?) successful"""",
            """"pattern": "Payment of RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+)$"""",
        )
        assertRejected(bad, "unbounded merchant", "field")
    }

    /**
     * `big_text` is a single field and still multi-line, so it is refused too.
     *
     * The exemption was written as "not concat", which let a rule say
     * `"field": "big_text"` and reproduce the original bug exactly -- the
     * reference number and the balance below the payment line, swallowed into
     * the merchant, at whatever confidence the rule declares.
     */
    @Test fun `a merchant anchored to the end of big_text is rejected at load`() {
        val bad = json
            .replace(
                """"pattern": "Payment of RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+?) successful"""",
                """"pattern": "Payment of RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+)$"""",
            )
            .replace(
                """"text_contains_all": ["Payment of", "successful"]""",
                """"text_contains_all": ["Payment of", "successful"], "field": "bigText"""",
            )
        assertRejected(bad, "unbounded merchant", "more than")
    }

    /** Scoped to one field, the same anchor means the end of that field. */
    @Test fun `a merchant anchored to the end of a single field is accepted`() {
        val scoped = json
            .replace(
                """"pattern": "Payment of RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+?) successful"""",
                """"pattern": "Payment of RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+)$"""",
            )
            .replace(
                """"text_contains_all": ["Payment of", "successful"]""",
                """"text_contains_all": ["Payment of", "successful"], "field": "text"""",
            )
        PackLoader.load(scoped)
    }

    @Test fun `an uncompilable pattern is rejected at load`() {
        val bad = json.replace("Payment of RM", "Payment of RM(")
        assertRejected(bad)
    }

    @Test fun `duplicate rule ids within a package are rejected`() {
        val bad = json.replace("tng-reload-v1", "tng-payment-v1")
        assertRejected(bad)
    }

    // C2. `ignoreUnknownKeys` drops a misspelled predicate, `Conditions`
    // defaults every list to empty, and the pattern then runs unguarded on
    // every notification from that package — spec 5.3's largest
    // false-positive source, delivered by one typo in a pack file.
    @Test fun `a misspelled condition predicate is rejected at load`() {
        val bad = json.replace("\"text_contains_all\": [\"Payment of\"", "\"text_contains_alll\": [\"Payment of\"")
        assertRejected(bad, "text_contains_alll", "tng-payment-v1")
    }

    // The level that had no check. Each of these fields is optional, so
    // `ignoreUnknownKeys` drops a misspelling and the rule loads with the
    // default in its place -- and every one of those defaults is a decision
    // about money.
    @Test fun `a misspelled requires key turns a guarded rule into an amount sniffer`() {
        val bad = json.replace("\"requires\":", "\"require\":")
        assertNotEquals("the fixture must actually contain the key", bad, json)
        assertRejected(bad, "require")
    }

    @Test fun `a misspelled confidence key is rejected at load`() {
        val bad = json.replace("\"confidence\":", "\"confidance\":")
        assertNotEquals("the fixture must actually contain the key", bad, json)
        assertRejected(bad, "confidance")
    }

    @Test fun `an unknown key on a package entry is rejected at load`() {
        val bad = json.replace("\"label\":", "\"lable\":")
        assertNotEquals("the fixture must actually contain the key", bad, json)
        assertRejected(bad, "lable")
    }

    @Test fun `an unknown key on a reject rule is rejected at load`() {
        val bad = json.replace("\"any_of\":", "\"anyof\":")
        assertNotEquals("the fixture must actually contain the key", bad, json)
        assertRejected(bad, "anyof")
    }

    @Test fun `an unknown field selector is rejected at load`() {
        val bad = json.replace(
            "\"requires\": { \"text_contains_all\": [\"Reload\"] }",
            "\"requires\": { \"text_contains_all\": [\"Reload\"], \"field\": \"body\" }",
        )
        assertRejected(bad, "body")
    }

    // `exclusion_reason` was the one field in this vocabulary typed as an open
    // String while `direction`, `confidence`, `kind` and `requires.field` were
    // all closed. A pack that misspelled it loaded clean and the mistake
    // surfaced, if ever, in whatever finally wrote the column.
    @Test fun `a misspelled exclusion reason is rejected at load`() {
        val bad = json.replace("\"exclusion_reason\": \"TRANSFER\"", "\"exclusion_reason\": \"TRANFSER\"")
        assertNotEquals("the fixture must actually contain the value", bad, json)
        assertRejected(bad, "TRANFSER")
    }

    @Test fun `every exclusion reason loads`() {
        ExclusionReason.entries.forEach { reason ->
            val good = json.replace("\"exclusion_reason\": \"TRANSFER\"", "\"exclusion_reason\": \"$reason\"")
            assertEquals(reason, PackLoader.load(good).packages[0].rules.first { it.exclusionReason != null }.exclusionReason)
        }
    }

    @Test fun `every allowed field selector loads`() {
        FIELD_VALUES.forEach { (serialName, entry) ->
            val good = json.replace(
                "\"requires\": { \"text_contains_all\": [\"Reload\"] }",
                "\"requires\": { \"text_contains_all\": [\"Reload\"], \"field\": \"$serialName\" }",
            )
            assertEquals(entry, PackLoader.load(good).packages[0].rules[1].requires!!.field)
        }
    }

    @Test fun `a requires object with no constraints is rejected at load`() {
        val bad = json.replace("\"text_contains_all\": [\"Reload\"]", "")
        assertRejected(bad, "no constraints")
    }

    // I1. Two entries for one package: `associateBy` kept the last, so the
    // first entry's rules AND its reject list were both dead with no error.
    @Test fun `a duplicate package entry is rejected at load`() {
        val bad = json.replace("\"packages\": [{", "\"packages\": [$DUPLICATE_PACKAGE, {")
        assertRejected(bad, "my.com.tngdigital.ewallet")
    }

    // I2. `haystack.contains("")` is always true, so one blank reject term
    // rejects every capture for the package.
    @Test fun `a blank reject term is rejected at load`() {
        val bad = json.replace("\"any_of\": [\"cashback\", \"voucher\"]", "\"any_of\": [\"cashback\", \"  \"]")
        assertRejected(bad, "promo")
    }

    // Symmetrically, a blank in text_contains_none makes a rule never fire.
    @Test fun `a blank condition term is rejected at load`() {
        val bad = json.replace(
            "\"requires\": { \"text_contains_all\": [\"Reload\"] }",
            "\"requires\": { \"text_contains_all\": [\"Reload\"], \"text_contains_none\": [\"\"] }",
        )
        assertRejected(bad, "tng-reload-v1")
    }

    @Test fun `the bundled pack loads and validates`() {
        val text = ParseFixtures.bundledPackText()
        assertTrue(PackLoader.load(text).packages.isNotEmpty())
    }

    // Spec 5.4's lists. A pack may legitimately declare none -- spec 5.8
    // builds one-rule packs at runtime -- so the loader defaults them empty
    // and it is the *bundled* pack that has to carry them. Without this,
    // deleting the block from pack.json leaves every test below green and
    // every merchant on a device shouting.
    @Test fun `the bundled pack declares all three normalization lists`() {
        val text = ParseFixtures.bundledPackText()
        val n = PackLoader.load(text).merchantNormalization
        assertTrue("no acquirer prefixes", n.acquirerPrefixes.isNotEmpty())
        assertTrue("no trailing noise suffixes", n.trailingNoiseSuffixes.isNotEmpty())
        assertTrue("no title case exceptions", n.titleCaseExceptions.isNotEmpty())
        assertTrue("the DuitNow rails suffix is the whole point", n.trailingNoiseSuffixes.any {
            it.equals(" via DuitNow", ignoreCase = true)
        })
    }

    // Same shape as the test above, and the same reason it cannot live in
    // PackLoader: spec 5.8 builds one-rule packs at runtime and those
    // legitimately have no reject block, so emptiness is not a load error. It
    // is a *bundled pack* defect, and until now nothing checked it. A package
    // added with rules and no rejects would load clean, pass every test here,
    // and ship with spec 5.3's "single largest false-positive source"
    // -- promos, OTPs and failed transactions -- filtered by nothing.
    @Test fun `every bundled package with rules also declares rejects`() {
        ParseFixtures.bundledPack.packages.forEach { p ->
            if (p.rules.isNotEmpty()) {
                assertTrue(
                    "package '${p.pkg}' declares ${p.rules.size} rules and no reject patterns, " +
                        "so its templates run with default-deny switched off",
                    p.reject.isNotEmpty(),
                )
            }
        }
    }

    @Test fun `merchant normalization is absent-by-default rather than a load error`() {
        assertEquals(MerchantNormalization.EMPTY, PackLoader.load(json).merchantNormalization)
    }

    @Test fun `merchant normalization lists load`() {
        val good = json.replace(
            "\"pack_version\": 7,",
            "\"pack_version\": 7, \"merchant_normalization\": { " +
                "\"acquirer_prefixes\": [\"TNG*\"], " +
                "\"trailing_noise_suffixes\": [\" via DuitNow\"], " +
                "\"title_case_exceptions\": [\"KK\"] },",
        )
        val n = PackLoader.load(good).merchantNormalization
        assertEquals(listOf("TNG*"), n.acquirerPrefixes)
        assertEquals(listOf(" via DuitNow"), n.trailingNoiseSuffixes)
        assertEquals(listOf("KK"), n.titleCaseExceptions)
    }

    // C2, one level up. `ignoreUnknownKeys` drops a top-level typo, the lists
    // default to empty, and normalization silently stops happening -- which on
    // a device looks like a parser that never worked rather than one edit.
    @Test fun `a misspelled top-level key is rejected at load`() {
        val bad = json.replace(
            "\"pack_version\": 7,",
            "\"pack_version\": 7, \"merchant_normalisation\": {},",
        )
        assertRejected(bad, "merchant_normalisation")
    }

    @Test fun `a misspelled normalization list name is rejected at load`() {
        val bad = json.replace(
            "\"pack_version\": 7,",
            "\"pack_version\": 7, \"merchant_normalization\": { \"acquirer_prefix\": [\"TNG*\"] },",
        )
        assertRejected(bad, "acquirer_prefix")
    }

    @Test fun `a non-list normalization value is rejected at load`() {
        val bad = json.replace(
            "\"pack_version\": 7,",
            "\"pack_version\": 7, \"merchant_normalization\": { \"acquirer_prefixes\": \"TNG*\" },",
        )
        assertRejected(bad, "acquirer_prefixes")
    }

    // A blank suffix ends every string and a blank prefix starts every one,
    // so one stray "" turns the whole list into a no-op.
    @Test fun `a blank normalization entry is rejected at load`() {
        val bad = json.replace(
            "\"pack_version\": 7,",
            "\"pack_version\": 7, \"merchant_normalization\": " +
                "{ \"trailing_noise_suffixes\": [\" MY\", \"  \"] },",
        )
        assertRejected(bad, "trailing_noise_suffixes", "blank")
    }

    @Test fun `patterns compile with the mandated flag set`() {
        val flags = PackRegex.compile("x").toPattern().flags()
        assertTrue(flags and java.util.regex.Pattern.CASE_INSENSITIVE != 0)
        assertTrue(flags and java.util.regex.Pattern.UNICODE_CASE != 0)
        assertTrue(flags and java.util.regex.Pattern.DOTALL != 0)
        assertEquals(0, flags and java.util.regex.Pattern.MULTILINE)
    }

    // --- Spec 5.2's shared amount fragment ---------------------------------
    //
    // "The amount fragment is a shared primitive, not retyped per rule... Every
    // rule author reinventing `RM(?<amount>[\d,]+\.\d{2})` guarantees that
    // half of them reject 'RM 50' and 'MYR50.00'." The mechanism did not exist
    // and the predicted outcome had already landed: all four bundled rules had
    // retyped it, and none accepted MYR.

    @Test fun `the bundled pack declares the amount fragment once`() {
        val pack = PackLoader.load(ParseFixtures.bundledPackText())
        assertEquals(setOf("amount"), pack.fragments.keys)
        // Every bundled rule references it rather than spelling it out.
        pack.packages.flatMap { it.rules }.forEach { rule ->
            assertTrue(
                "rule '${rule.id}' retypes the amount fragment: ${rule.pattern}",
                rule.pattern.contains("{{amount}}"),
            )
            assertTrue(
                "rule '${rule.id}' spells a currency itself",
                !rule.pattern.contains("RM") && !rule.pattern.contains("MYR"),
            )
        }
    }

    @Test fun `the shared fragment accepts every tolerance spec 5-2 names`() {
        val matcher = RuleMatcher(PackLoader.load(ParseFixtures.bundledPackText()))
        // The four tolerances, against the real bundled TnG payment rule.
        val cases = mapOf(
            "Payment of RM12.00 to Kedai Ali successful" to 1200L,
            "Payment of RM 50 to Kedai Ali successful" to 5000L,
            "Payment of MYR50.00 to Kedai Ali successful" to 5000L,
            "Payment of RM1,234.50 to Kedai Ali successful" to 123450L,
        )
        cases.forEach { (text, sen) ->
            val out = matcher.match("my.com.tngdigital.ewallet", "TNG", text, null)
            assertTrue("did not match: $text (outcome $out)", out is MatchOutcome.Matched)
            assertEquals(text, sen, (out as MatchOutcome.Matched).amountSen)
        }
    }

    @Test fun `a pattern referencing an undeclared fragment is rejected at load`() {
        val bad = json.replace(
            "\"pattern\": \"Reload of RM(?<amount>[0-9,]+\\\\.[0-9]{2})\"",
            "\"pattern\": \"Reload of {{amount}}\"",
        )
        assertRejected(bad, "unknown fragment", "declares none")
    }

    @Test fun `a rule whose amount group arrives from a fragment is accepted`() {
        // The check used to read the authored pattern, which would call this
        // rule "declares no amount group" -- the whole point of the fragment.
        val withFragment = """
        {
          "pack_version": 7,
          "fragments": { "amount": "RM(?<amount>[0-9,]+\\.[0-9]{2})" },
          "packages": [{
            "package": "bank.x", "label": "X",
            "reject": [{ "id": "promo", "any_of": ["cashback"] }],
            "rules": [{ "id": "x-v1", "priority": 10, "direction": "EXPENSE",
                        "confidence": "HIGH", "pattern": "Paid {{amount}}" }]
          }]
        }
        """.trimIndent()
        val pack = PackLoader.load(withFragment)
        assertEquals("Paid {{amount}}", pack.packages[0].rules[0].pattern)
        val out = RuleMatcher(pack).match("bank.x", "X", "Paid RM7.50", null)
        assertEquals(750L, (out as MatchOutcome.Matched).amountSen)
    }
}
