package my.pinged.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
     * Written inline, the `!!.message!!` chain throws a bare
     * NullPointerException when the type is right and the message is null,
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
     * Refused rather than corrected, because an author who wrote `\d` meant one of
     * two alphabets and the loader cannot know which. `FullWidthDigitsTest`, on the
     * device, is the other half.
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

    // Same reason as the test above that this cannot live in PackLoader: spec
    // 5.8 builds one-rule packs at runtime and those legitimately declare no
    // rejects, so emptiness is not a load error, only a bundled-pack defect. A
    // package with rules and no rejects loads clean and ships with spec 5.3's
    // "single largest false-positive source" -- promos, OTPs, failed
    // transactions -- filtered by nothing. Unconditional: shared rules reach
    // every package, so one that declares no rule of its own still has every
    // rail template running against it.
    @Test fun `every bundled package with rules also declares rejects`() {
        val pack = ParseFixtures.bundledPack
        pack.packages.forEach { p ->
            val rules = p.rules.size + pack.sharedRules.size
            assertTrue(
                "package '${p.pkg}' has $rules rules reaching it (${p.rules.size} its own, " +
                    "${pack.sharedRules.size} shared) and no reject patterns, so its " +
                    "templates run with default-deny switched off",
                p.reject.isNotEmpty(),
            )
        }
    }

    /**
     * No bundled rule gates on a notification's title.
     *
     * Four real samples now carry one: "DuitNow Payment", "Maybank2u: Scan &
     * Pay", a plain "Payment", and the MAE card sample that arrived with none
     * at all. Each title has been seen exactly once, and a title is the string
     * a bank draws in a heading -- restyled, localised or dropped without the
     * body changing a character. A rule gated on one stops matching on a
     * release note nobody here will read, and fails silently: captures
     * arriving, ledger empty.
     *
     * The titles stay recorded in the fixtures, because they are evidence.
     * `title_contains_any` stays in the vocabulary, because a title is
     * sometimes the only place a bank says which product a body belongs to.
     *
     * **Delete this test when a sample proves a title is load-bearing** --
     * that is a finding, not a workaround.
     */
    @Test fun `no bundled rule gates on a title`() {
        val pack = ParseFixtures.bundledPack
        val gated = (pack.packages.flatMap { it.rules } + pack.sharedRules)
            .filter { it.requires?.titleContainsAny?.isNotEmpty() == true }
            .map { it.id }
        assertEquals(
            "these rules condition on a display string seen once each",
            emptyList<String>(),
            gated,
        )
    }

    /**
     * Every bundled template that commits money names the failure vocabulary,
     * and every copy of it agrees.
     *
     * A rule in `shared_rules` runs against every package, so one unguarded
     * rail template is a declined payment posted as an expense from every app
     * the user enabled. `RuleMatcherTest` holds the behaviour; this is what
     * notices the *next* rule added without a guard, and what keeps the copies
     * of the list in one spelling.
     *
     * `tng-reload-v1` is included: it commits at REVIEW, which is still a row
     * in the ledger with an amount on it.
     *
     * A pack may legitimately declare none -- spec 5.8 builds one-rule packs
     * at runtime -- so this is a bundled-pack assertion, not a load error, for
     * the reason the normalization-list test above is.
     */
    @Test fun `every bundled rule guards against a failed transaction, in one vocabulary`() {
        val pack = ParseFixtures.bundledPack
        val rules = pack.packages.flatMap { it.rules } + pack.sharedRules
        val unguarded = rules.filter { it.requires?.textContainsNone.isNullOrEmpty() }.map { it.id }
        assertEquals(
            "a template with no failure guard reads 'RM12.00 ... declined' as money spent",
            emptyList<String>(),
            unguarded,
        )
        val vocabularies = (rules.mapNotNull { it.requires?.textContainsNone } +
            pack.packages.flatMap { p -> p.reject.filter { it.id.endsWith("-failed") }.map { it.anyOf } })
            .map { it.sorted() }
            .distinct()
        assertEquals(
            "the failure vocabulary is written once per rule and nothing keeps the " +
                "copies in step, so a term added to one list is a term missing from " +
                "the others: $vocabularies",
            1,
            vocabularies.size,
        )
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
    // half of them reject 'RM 50' and 'MYR50.00'." Which had already happened:
    // all four bundled rules retyped it and none accepted MYR.

    @Test fun `the bundled pack declares the amount fragment once`() {
        val pack = PackLoader.load(ParseFixtures.bundledPackText())
        assertEquals(setOf("amount"), pack.fragments.keys)
        // Every bundled rule references it rather than spelling it out --
        // shared ones too, or moving a rule into `shared_rules` would quietly
        // exempt it from the one check spec 5.2 asks for.
        (pack.packages.flatMap { it.rules } + pack.sharedRules).forEach { rule ->
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

    // --- Shared rules ------------------------------------------------------
    //
    // A rule in `shared_rules` runs against every package in the pack, so it is
    // the *last* place a validation hole is affordable: a `\d` there is wrong
    // money from every app the user has enabled, not one.
    //
    // Two rejection tests, not one per check: what the shared block needs
    // proving about it is that `PackLoader` calls its two validators --
    // `validate` and `validateRuleKeys` -- on it at all, and the checks those
    // run are covered over package rules above. One test per call site,
    // because a test per check would die on the same deleted call as its
    // neighbours and say no more than they do.

    /**
     * One shared rule, spelled so that every mutation below can be made with a
     * `replace` that cannot also hit [json]'s own rules.
     */
    private val SHARED_RULE = """
        { "id": "duitnow-paid-v1", "priority": 100,
          "direction": "EXPENSE", "confidence": "HIGH",
          "requires": { "text_contains_all": ["You have paid"], "field": "text" },
          "pattern": "You have paid RM(?<amount>[0-9,]+\\.[0-9]{2}) to (?<merchant>.+)$" }
    """.trimIndent()

    /**
     * The key the shared block is declared under, read out of the serializer
     * rather than written down.
     *
     * [PackLoader] holds it as a private constant, the one key name in that
     * file not derived from a serial name. If it and
     * `@SerialName("shared_rules")` ever disagree, the block stops being
     * key-validated -- which every assertion below then fails on, instead of
     * passing against a stale literal.
     */
    private val SHARED_KEY: String = run {
        fun keysOf(pack: ParsePack) =
            Json.encodeToJsonElement(ParsePack.serializer(), pack).jsonObject.keys
        val bare = ParsePack(packVersion = 1, packages = emptyList())
        (keysOf(bare.copy(sharedRules = listOf(RAIL_RULE))) - keysOf(bare)).single()
    }

    private fun withShared(block: String) =
        json.replace("\"pack_version\": 7,", "\"pack_version\": 7, \"$SHARED_KEY\": [$block],")

    // Declared 100 then 110, so the assertion fails if the sort is dropped.
    @Test fun `shared rules load and sort by descending priority`() {
        val second = SHARED_RULE
            .replace("duitnow-paid-v1", "duitnow-paid-body-v1")
            .replace("\"priority\": 100", "\"priority\": 110")
        val pack = PackLoader.load(withShared("$SHARED_RULE, $second"))
        assertEquals(
            listOf("duitnow-paid-body-v1", "duitnow-paid-v1"),
            pack.sharedRules.map { it.id },
        )
    }

    @Test fun `a shared rule using the unicode digit class is rejected at load`() {
        assertRejected(withShared(SHARED_RULE.replace("[0-9,]+", "[\\\\d,]+")), "[0-9]", SHARED_KEY)
    }

    @Test fun `a shared rule with a misspelled condition predicate is rejected at load`() {
        assertRejected(
            withShared(SHARED_RULE.replace("\"text_contains_all\"", "\"text_contains_alll\"")),
            "text_contains_alll",
            SHARED_KEY,
        )
    }

    /**
     * `validate` sees one list at a time, so neither could catch an id in
     * both. `matched_rule_id` is stored on every capture and would stop naming
     * one rule.
     */
    @Test fun `a shared rule id colliding with a package rule id is rejected at load`() {
        assertRejected(
            withShared(SHARED_RULE.replace("duitnow-paid-v1", "tng-payment-v1")),
            "tng-payment-v1",
            SHARED_KEY,
        )
    }

    /**
     * The rail is shared because its wording is, so the bundled pack must
     * actually answer the same sentence from more than one app. Asserted as
     * behaviour rather than as a rule id: a pack that copied the rule into
     * every package would also pass.
     */
    @Test fun `the bundled duitnow rule answers every bundled package`() {
        val pack = ParseFixtures.bundledPack
        assertTrue("the bundled pack declares no shared rules", pack.sharedRules.isNotEmpty())
        val matcher = RuleMatcher(pack)
        val deaf = pack.packages.map { it.pkg }.filter { pkg ->
            matcher.match(
                pkg,
                "DuitNow Payment",
                "You have paid RM12.00 to Restoran Yuen Kee Home Town Cafe",
                null,
            ) !is MatchOutcome.Matched
        }
        assertEquals(
            "DuitNow's wording is the rail's, not the posting app's: these packages " +
                "see the same notification and read nothing from it",
            emptyList<String>(),
            deaf,
        )
    }

    @Test fun `a rule whose amount group arrives from a fragment is accepted`() {
        // The amount-group check has to read the expanded pattern: over the
        // authored one this rule "declares no amount group", which is the
        // whole point of the fragment.
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

    private companion object {
        /** A minimal shared rule, only ever encoded to find [SHARED_KEY]. */
        val RAIL_RULE = TemplateRule(
            id = "rail-v1",
            priority = 1,
            direction = Direction.EXPENSE,
            pattern = "(?<amount>0)",
        )
    }
}
