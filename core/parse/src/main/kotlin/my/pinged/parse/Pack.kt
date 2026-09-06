package my.pinged.parse

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

class PackValidationException(message: String) : IllegalArgumentException(message)

/**
 * Regex flags are fixed for every pattern (spec 5.2):
 * CASE_INSENSITIVE | UNICODE_CASE | DOTALL, never MULTILINE.
 *
 * Kotlin's RegexOption enum has no UNICODE_CASE member, so patterns are
 * compiled through java.util.regex.Pattern and converted. DOTALL matters
 * because bigText contains newlines and without it `(?<merchant>.+?)`
 * silently fails to match across one.
 */
object PackRegex {
    private const val FLAGS =
        java.util.regex.Pattern.CASE_INSENSITIVE or
        java.util.regex.Pattern.UNICODE_CASE or
        java.util.regex.Pattern.DOTALL

    /**
     * Spec 5.2's shared primitives: `{{name}}` in a pattern is replaced by
     * the pack's `fragments[name]` before compiling.
     *
     * Spec 5.2: "The amount fragment is a shared primitive, not retyped per
     * rule... Every rule author reinventing `RM(?<amount>[\d,]+\.\d{2})`
     * guarantees that half of them reject 'RM 50' and 'MYR50.00'."
     */
    // Two JVM-green, device-broken traps, both avoided here.
    //
    // Every brace escaped, including the closing pair: desktop java.util.regex
    // tolerates a dangling `}` and Android's ICU rejects it, so the object's
    // initializer throws NoClassDefFoundError on device while every JVM test
    // passes.
    //
    // `[A-Za-z0-9_]` not `\w`, for the reason `PackLoader` refuses `\d`: `\w` is
    // ASCII to java.util.regex and Unicode to ICU. This regex decides what a
    // *fragment name* is, so a pack naming one in a non-ASCII script would load
    // clean on the JVM and die on device with "unknown fragment".
    //
    // `\s` elsewhere is left alone: it differs too, but `TextNormalizer.forMatch`
    // collapses every Unicode space to ASCII before a pattern runs.
    private val REFERENCE = Regex("\\{\\{([A-Za-z0-9_]+)\\}\\}")

    /**
     * The two named groups a template rule may capture. The loader requires
     * [AMOUNT_GROUP] and the matcher reads both, so they must agree or a pack
     * validates and then yields nothing on device.
     */
    const val AMOUNT_GROUP = "amount"
    const val MERCHANT_GROUP = "merchant"

    fun expand(pattern: String, fragments: Map<String, String>): String =
        REFERENCE.replace(pattern) { match ->
            val name = match.groupValues[1]
            fragments[name] ?: throw PackValidationException(
                "Pattern references unknown fragment '{{$name}}'" +
                    if (fragments.isEmpty()) "; the pack declares none"
                    else "; declared: " + fragments.keys.sorted().joinToString(", ")
            )
        }

    /**
     * Compile an already-expanded pattern.
     *
     * A malformed pattern fails as [PackValidationException] whichever door it
     * came through. A runtime-built pack (spec 5.8) skips the loader, and a raw
     * PatternSyntaxException out of RuleMatcher's constructor lands outside
     * ParsePass's per-capture catch -- from a Graph that caches on success only,
     * so it re-throws on every subsequent notification forever.
     */
    fun compile(pattern: String): Regex =
        try {
            java.util.regex.Pattern.compile(pattern, FLAGS).toRegex()
        } catch (e: java.util.regex.PatternSyntaxException) {
            throw PackValidationException("Pattern does not compile: ${e.message}")
        }

    /** Expand then compile, which is what every caller actually wants. */
    fun compile(pattern: String, fragments: Map<String, String>): Regex =
        compile(expand(pattern, fragments))
}

/**
 * Spec 5.2's field selector, closed the way `direction`, `confidence` and
 * `kind` are. As an enum, [CaptureFields.forField]'s `when` is exhaustive: a
 * bare `String` left it an `else` branch, so a fifth selector added to the
 * loader's allow-list but not wired in would silently read `concat`.
 */
@Serializable
enum class Field {
    @SerialName("title") TITLE,
    @SerialName("text") TEXT,
    @SerialName("bigText") BIG_TEXT,
    @SerialName("concat") CONCAT,
}

@Serializable
data class Conditions(
    @SerialName("text_contains_all") val textContainsAll: List<String> = emptyList(),
    @SerialName("text_contains_any") val textContainsAny: List<String> = emptyList(),
    @SerialName("text_contains_none") val textContainsNone: List<String> = emptyList(),
    @SerialName("title_contains_any") val titleContainsAny: List<String> = emptyList(),
    val field: Field = Field.CONCAT,
)

@Serializable
data class RejectRule(
    val id: String,
    @SerialName("any_of") val anyOf: List<String>,
)

@Serializable
data class TemplateRule(
    val id: String,
    val priority: Int,
    val direction: Direction,
    val confidence: Confidence = Confidence.HIGH,
    val kind: Kind? = null,
    @SerialName("exclusion_reason") val exclusionReason: ExclusionReason? = null,
    val requires: Conditions? = null,
    val pattern: String,
)

@Serializable
data class PackagePack(
    @SerialName("package") val pkg: String,
    val label: String,
    val reject: List<RejectRule> = emptyList(),
    val rules: List<TemplateRule> = emptyList(),
)

/**
 * Spec 5.4's normalization lists, which the spec puts in the pack and not in
 * code "so they are editable without a release".
 *
 * Pack-level rather than per-package on purpose: an acquirer prefix like
 * `TNG*` or a rails suffix like ` via DuitNow` arrives inside notifications
 * from whichever bank happens to be on the far end of the transaction, so
 * scoping the lists to one package would mean repeating them in every one.
 *
 * All three are optional. A pack that declares none normalizes nothing,
 * which is the honest reading of an absent list -- the guard against a
 * *production* pack shipping without them is a test over the bundled file,
 * not a parse error that would also reject every legitimately minimal pack
 * built at runtime by spec 5.8's teach-by-example.
 */
@Serializable
data class MerchantNormalization(
    /** Matched against the start of the uppercased merchant, e.g. `TNG*`. */
    @SerialName("acquirer_prefixes") val acquirerPrefixes: List<String> = emptyList(),
    /**
     * Matched against the end of the uppercased merchant and stripped
     * repeatedly, e.g. ` SDN BHD`, ` MY`, ` via DuitNow`. Entries include
     * their leading separator so that "SHOPEEMY" is not mangled.
     */
    @SerialName("trailing_noise_suffixes") val trailingNoiseSuffixes: List<String> = emptyList(),
    /**
     * Words (or whole names) that must survive title-casing with the casing
     * written here, e.g. `KK`, `TNG`, `McDonald's`.
     */
    @SerialName("title_case_exceptions") val titleCaseExceptions: List<String> = emptyList(),
) {
    // Derived once per pack, outside the constructor so serialization and
    // `equals` ignore them.
    //
    // These keep the case the pack author wrote and `Merchant` matches them
    // with `ignoreCase`, because `Merchant.displayFor` strips the same entries
    // off a string whose case must survive. Folding can also change a length --
    // a sharp s uppercases to two letters -- and the stripper removes by matched
    // length.
    internal val prefixes: List<String> by lazy { usable(acquirerPrefixes) }
    internal val suffixes: List<String> by lazy { usable(trailingNoiseSuffixes) }

    // Keyed with Locale.ROOT because spec 6.1 keys learned rules by the
    // cleaned string and a Turkish-locale device must not fold "I"
    // differently.
    internal val titleCaseExceptionsByUpper: Map<String, String> by lazy {
        titleCaseExceptions.filter { it.isNotBlank() }
            .associateBy { it.uppercase(java.util.Locale.ROOT) }
    }

    /**
     * Blanks are dropped rather than trusted. [PackLoader] rejects them, but
     * spec 5.8 builds packs at runtime with no loader in between, and a
     * blank suffix matches the end of every string.
     */
    private fun usable(entries: List<String>): List<String> = entries.filter { it.isNotBlank() }

    companion object {
        val EMPTY = MerchantNormalization()
    }
}

@Serializable
data class ParsePack(
    @SerialName("pack_version") val packVersion: Int,
    val packages: List<PackagePack>,
    @SerialName("merchant_normalization")
    val merchantNormalization: MerchantNormalization = MerchantNormalization.EMPTY,
    /**
     * Spec 5.2's shared regex primitives, referenced from a rule's `pattern`
     * as `{{name}}`. Patterns are stored as authored and expanded at compile
     * time, so this stays the single declaration rather than becoming four
     * copies the moment a pack is loaded.
     */
    val fragments: Map<String, String> = emptyMap(),
)

object PackLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `\d` or `\D` in a pack pattern, which no pack may contain.
     *
     * `java.util.regex` on the JVM reads `\d` as ASCII; ICU on Android reads it
     * as `\p{Nd}`. A pattern using it is therefore tested against one alphabet
     * and shipped against another, with the whole JVM test suite on the side
     * that cannot see the difference.
     *
     * The consequence is wrong money: a full-width amount pairs full-width
     * digits with the full-width stop U+FF0E, which `\.` does not match, so the
     * sen are dropped and RM12.50 stores as RM12.00. See [Amount].
     *
     * A preceding backslash is excluded so that an escaped literal backslash
     * followed by `d` is left alone.
     */
    private val UNICODE_DIGIT_CLASS = Regex("(?<!\\\\)\\\\[dD]")

    /**
     * A merchant group that runs to the end of the input, which is only ever
     * correct when the input is one field.
     *
     * Patterns compile DOTALL and [TextNormalizer.forMatch] collapses newlines
     * to spaces, so over [Field.CONCAT] a trailing `.` crosses the field
     * boundary and every line break in `big_text`. "You have paid RM12.00 to
     * Restoran X" against a body carrying a reference number and a balance
     * stores the merchant as "Restoran X Ref ABC123 Baki RM500.00" -- at HIGH,
     * straight to the ledger. The money is right; every merchant total in
     * section 8 is not, and the user cannot see why.
     *
     * **[Field.TITLE] and [Field.TEXT] are exempt; [Field.CONCAT] and
     * [Field.BIG_TEXT] are not.** On a title or a one-line `text` the anchor
     * does mean the end of the field. `big_text` is the *expanded* body and
     * multi-line by definition, so the exemption has to be "a field with one
     * line in it", not "not concat".
     *
     * The real rule is **the merchant group is followed by nothing**: a group
     * with a terminator after it says where the merchant ends; one with only an
     * optional anchor runs to wherever the engine stops.
     *
     * **A lint, not a proof.** A regex over regex source will always be behind
     * someone's next spelling of "run to the end". `PackBehaviourTest` is the
     * check that cannot be: it runs every bundled rule against a body with a
     * second line and asserts no merchant capture reaches it.
     */
    private val UNBOUNDED_TRAILING_MERCHANT = Regex(
        // A merchant group with a quantifier and then nothing: no literal, no
        // lookahead, only an optional anchor.
        "\\(\\?<" + PackRegex.MERCHANT_GROUP + ">[^)]*(?:[+*]|\\{[0-9]*,?[0-9]*\\})\\??\\)?(?:\\\\s\\*)?\\\$?\\)?(?:\\\\s\\*)?\\\$?\\s*\$",
    )

    /**
     * Every allow-list below is derived from the serial names of the class it
     * describes rather than restated, so adding a field cannot leave the
     * allow-list behind -- which would surface as a pack-authoring error, in a
     * file the author of the new field has no reason to open.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun serialNamesOf(descriptor: SerialDescriptor): Set<String> =
        descriptor.elementNames.toSet()

    /**
     * Spec 5.2's whole condition vocabulary: four predicates plus a field
     * selector. Nothing else is a condition.
     */
    private val CONDITION_KEYS: Set<String> = serialNamesOf(Conditions.serializer().descriptor)

    /** Spec 5.2's field selector, and nothing else. */
    private val FIELD_VALUES: Set<String> = serialNamesOf(Field.serializer().descriptor)

    /** Every key a pack file may declare at the top level. Nothing else. */
    private val TOP_LEVEL_KEYS: Set<String> = serialNamesOf(ParsePack.serializer().descriptor)

    /** Spec 5.4's three lists, and nothing else. */
    private val MERCHANT_NORMALIZATION_KEYS: Set<String> =
        serialNamesOf(MerchantNormalization.serializer().descriptor)

    /** Every key a package entry may declare. */
    private val PACKAGE_KEYS: Set<String> = serialNamesOf(PackagePack.serializer().descriptor)

    /**
     * Every key a template rule may declare.
     *
     * The most expensive level to miss. `confidence`, `kind`,
     * `exclusion_reason` and `requires` are all optional, so `ignoreUnknownKeys`
     * drops a misspelling silently and the rule loads with the default: `require`
     * for `requires` turns a guarded template into an amount-sniffer that reads
     * "Reload of RM50.00 ... was unsuccessful" as RM50 spent, and `confidance`
     * ships a REVIEW rule as HIGH.
     */
    private val RULE_KEYS: Set<String> = serialNamesOf(TemplateRule.serializer().descriptor)

    /** Every key a reject rule may declare. */
    private val REJECT_KEYS: Set<String> = serialNamesOf(RejectRule.serializer().descriptor)

    /**
     * Both parsing phases fail the same way, so the recovery is stated once.
     * A [PackValidationException] raised by a validator passes through
     * untouched rather than being re-wrapped as a parse failure.
     */
    private inline fun <T> parsing(body: () -> T): T =
        try {
            body()
        } catch (e: PackValidationException) {
            throw e
        } catch (e: Exception) {
            throw PackValidationException("Pack did not parse: ${e.message}")
        }

    fun load(text: String): ParsePack {
        val tree = parsing { json.parseToJsonElement(text) }

        // Decoding ignores unknown keys, so a misspelled predicate is dropped
        // and the rule loads with no constraints at all — turning a guarded
        // template into an amount-sniffer. Spec 5.9 requires every condition
        // to use only the listed predicates, so the key set is checked against
        // the raw tree before the decoded model is trusted.
        //
        // Before decoding, not after: these three read nothing but the tree,
        // and going first means `field: "body"` is reported as an unknown
        // selector rather than reaching the reader as a decode failure.
        validateTopLevelKeys(tree)
        validateMerchantNormalization(tree)
        validateDeclaredKeys(tree)

        val pack = parsing { json.decodeFromJsonElement<ParsePack>(tree) }

        val seenPackages = mutableSetOf<String>()
        pack.packages.forEach { p ->
            if (!seenPackages.add(p.pkg)) {
                throw PackValidationException(
                    "Duplicate package entry '${p.pkg}': its rules and reject list would be discarded"
                )
            }
            validate(p, pack.fragments)
        }
        return pack.copy(
            packages = pack.packages.map { it.copy(rules = it.rules.sortedByDescending(TemplateRule::priority)) }
        )
    }

    /**
     * A blank term matches everything, so one stray "" silently disables the
     * list it appears in. Three validators each said so in their own words;
     * it is one rule and now has one message.
     */
    private fun requireNonBlank(term: String, what: String) {
        if (term.isBlank()) {
            throw PackValidationException(
                "$what declares a blank entry, which matches every input"
            )
        }
    }

    /**
     * Reject the first key outside [allowed], naming what was expected.
     *
     * Three validators needed this and each wrote its own loop; the third had
     * already drifted, telling a pack author their condition key was unknown
     * without saying what the alternatives were. One spelling, one message.
     */
    private fun requireOnlyKeys(keys: Iterable<String>, allowed: Set<String>, what: String) {
        val unknown = keys.firstOrNull { it !in allowed } ?: return
        throw PackValidationException(
            "$what declares unknown key '$unknown'; expected one of " +
                allowed.sorted().joinToString(", ")
        )
    }

    private fun validateTopLevelKeys(tree: JsonElement) {
        val root = tree as? JsonObject
            ?: throw PackValidationException("Pack is not a JSON object")
        requireOnlyKeys(root.keys, TOP_LEVEL_KEYS, "Pack")
    }

    /**
     * The same C2 defect one level up. `ignoreUnknownKeys` means a pack that
     * writes `merchant_normalisation` -- or `merchant_normalization` at the
     * wrong nesting -- loads clean, declares no lists, and every merchant on
     * the device keeps its acquirer prefix. A dropped key must be an error,
     * not a silent no-op.
     */
    private fun validateMerchantNormalization(tree: JsonElement) {
        val root = tree as? JsonObject ?: return
        val element = root["merchant_normalization"] ?: return
        val obj = element as? JsonObject
            ?: throw PackValidationException("'merchant_normalization' is not a JSON object")
        requireOnlyKeys(obj.keys, MERCHANT_NORMALIZATION_KEYS, "'merchant_normalization'")
        obj.forEach { (key, value) ->
            val entries = value as? JsonArray
                ?: throw PackValidationException(
                    "'merchant_normalization.$key' is not a list of strings"
                )
            entries.forEach { entry ->
                val primitive = entry as? JsonPrimitive
                if (primitive == null || !primitive.isString) {
                    throw PackValidationException(
                        "'merchant_normalization.$key' contains a non-string entry"
                    )
                }
                requireNonBlank(primitive.content, "'merchant_normalization.$key'")
            }
        }
    }

    /**
     * Every declared key, at every level of a package entry.
     *
     * One walk rather than four, because the levels are nested and a reader
     * checking whether a level is covered should not have to find its
     * validator. Three of these four levels had no check at all until a
     * reviewer wrote `require` for `requires` and watched a failed reload
     * become money.
     */
    private fun validateDeclaredKeys(tree: JsonElement) {
        val packages = (tree as? JsonObject)?.get("packages") as? JsonArray ?: return
        for (packageElement in packages) {
            val packageObject = packageElement as? JsonObject ?: continue
            val pkg = (packageObject["package"] as? JsonPrimitive)?.contentOrNull ?: "<unnamed package>"
            requireOnlyKeys(packageObject.keys, PACKAGE_KEYS, "Package '$pkg'")

            for (rejectElement in packageObject["reject"] as? JsonArray ?: JsonArray(emptyList())) {
                val rejectObject = rejectElement as? JsonObject ?: continue
                val id = (rejectObject["id"] as? JsonPrimitive)?.contentOrNull ?: "<unnamed reject>"
                requireOnlyKeys(rejectObject.keys, REJECT_KEYS, "Reject rule '$id' in $pkg")
            }

            val rules = packageObject["rules"] as? JsonArray ?: continue
            for (ruleElement in rules) {
                val ruleObject = ruleElement as? JsonObject ?: continue
                val id = (ruleObject["id"] as? JsonPrimitive)?.contentOrNull ?: "<unnamed rule>"
                requireOnlyKeys(ruleObject.keys, RULE_KEYS, "Rule '$id' in $pkg")

                val requires = ruleObject["requires"] as? JsonObject ?: continue
                requireOnlyKeys(requires.keys, CONDITION_KEYS, "Rule '$id' in $pkg requires")
                val field = (requires["field"] as? JsonPrimitive)?.contentOrNull
                if (field != null && field !in FIELD_VALUES) {
                    throw PackValidationException("Rule '$id' in $pkg selects unknown field '$field'")
                }
            }
        }
    }

    private fun validate(p: PackagePack, fragments: Map<String, String>) {
        p.reject.forEach { reject ->
            reject.anyOf.forEach { term ->
                requireNonBlank(term, "Reject rule '${reject.id}' in ${p.pkg}")
            }
        }
        val seen = mutableSetOf<String>()
        p.rules.forEach { rule ->
            if (!seen.add(rule.id)) {
                throw PackValidationException("Duplicate rule id '${rule.id}' in ${p.pkg}")
            }
            // Both checks run on the *expanded* pattern. Checking the
            // authored one would report "declares no amount group" for a rule
            // whose group arrives from `{{amount}}`, which is the whole point
            // of the fragment.
            val expanded = try {
                PackRegex.expand(rule.pattern, fragments)
            } catch (e: PackValidationException) {
                throw PackValidationException("Rule '${rule.id}' in ${p.pkg}: ${e.message}")
            }
            if (!expanded.contains("(?<${PackRegex.AMOUNT_GROUP}>")) {
                throw PackValidationException("Rule '${rule.id}' declares no amount group")
            }
            val field = rule.requires?.field ?: Field.CONCAT
            if ((field == Field.CONCAT || field == Field.BIG_TEXT) &&
                UNBOUNDED_TRAILING_MERCHANT.containsMatchIn(expanded)
            ) {
                throw PackValidationException(
                    "Rule '${rule.id}' in ${p.pkg} ends with an unbounded merchant " +
                        "group over ${field.name.lowercase()}, which holds more than " +
                        "one line. Patterns are compiled DOTALL and normalization " +
                        "collapses newlines to spaces, so that captures every line " +
                        "after the merchant -- a merchant of 'Restoran X Ref ABC123 " +
                        "Baki RM500.00', stored confidently. Give the group a " +
                        "terminator, or scope the rule with \"field\": \"text\", " +
                        "which is the one-line summary and is where an anchor means " +
                        "what its author meant.",
                )
            }
            UNICODE_DIGIT_CLASS.find(expanded)?.let {
                throw PackValidationException(
                    "Rule '${rule.id}' in ${p.pkg} uses '${it.value}'. Write [0-9] " +
                        "instead. This app compiles patterns with java.util.regex " +
                        "on the JVM, where \\d is ASCII, and with ICU on Android, " +
                        "where it is \\p{Nd} -- every decimal digit in Unicode. A " +
                        "full-width amount then matches, and because the full-width " +
                        "stop U+FF0E is not matched by \\. the rule captures the " +
                        "ringgit and drops the sen: RM12.50 stored as RM12.00, with " +
                        "every JVM test in this module still green.",
                )
            }
            rule.requires?.let { validateRequires(p, rule.id, it) }
            try {
                PackRegex.compile(expanded)
            } catch (e: Exception) {
                throw PackValidationException("Rule '${rule.id}' pattern does not compile: ${e.message}")
            }
        }
    }

    private fun validateRequires(p: PackagePack, id: String, conditions: Conditions) {
        val terms = conditions.textContainsAll +
            conditions.textContainsAny +
            conditions.textContainsNone +
            conditions.titleContainsAny
        // A rule that declares `requires` and ends up with no constraints is
        // always a pack bug: its pattern would run unguarded.
        if (terms.isEmpty()) {
            throw PackValidationException("Rule '$id' in ${p.pkg} declares 'requires' with no constraints")
        }
        terms.forEach { term ->
            requireNonBlank(term, "Rule '$id' in ${p.pkg}")
        }
    }
}
