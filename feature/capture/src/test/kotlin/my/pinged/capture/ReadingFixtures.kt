package my.pinged.capture

import my.pinged.data.Seed
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.parse.Confidence
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.Direction
import my.pinged.parse.Kind
import my.pinged.parse.LearnedRules
import my.pinged.parse.MatchOutcome
import my.pinged.parse.PackagePack
import my.pinged.parse.ParsePack
import my.pinged.parse.RuleMatcher
import my.pinged.parse.TemplateRule

/**
 * The [Reading]'s JVM tests: captures read through the pack that ships, and
 * the in-memory ports. Nothing here opens a database, so a capture or a
 * transaction is a value with an id this file hands out.
 */
internal object ReadingFixtures {
    const val TNG = "my.com.tngdigital.ewallet"
    const val MAE = "com.maybank2u.life"

    /** A package the bundled pack knows nothing about, read by [syntheticMatcher]. */
    const val SYNTHETIC = "com.example.syntheticbank"

    /** An instant on no boundary: 2026-06-15, 10:00 in Kuala Lumpur. */
    const val AT = 1_781_488_800_000L
    const val MINUTE = 60_000L

    val matcher: RuleMatcher = Graph.ruleMatcher()

    /**
     * `category.id` by seed name, as `CategoryDao.seededIds` answers on a
     * fresh install: one onward, in seed order.
     */
    val categoryIds: Map<String, Long> = Seed.categories().mapIndexed { i, it -> it.name to i + 1L }.toMap()
    val uncategorizedId: Long = categoryIds.getValue(Seed.UNCATEGORIZED)

    fun category(name: String): Long = categoryIds.getValue(name)

    /** A first reading's ports, as `ParseWorker.drain` gives them: the bundled dictionary, no answers, no rules. */
    fun reading(
        matcher: RuleMatcher = this.matcher,
        checkLimitSen: Long = REVIEW_THRESHOLD_SEN,
        answers: Set<InMemoryStandingAnswers.Answer> = emptySet(),
        learned: LearnedRules = LearnedRules.Snapshot(),
        dictionary: List<DictionaryEntry> = Graph.parsePack().dictionary,
        categoryIds: Map<String, Long> = this.categoryIds,
    ) = Reading(
        matcher = matcher,
        checkLimitSen = checkLimitSen,
        standingAnswers = InMemoryStandingAnswers(answers),
        learned = learned,
        dictionary = dictionary,
        categoryIds = categoryIds,
        uncategorizedId = uncategorizedId,
    )

    private var nextId = 1_000L

    /** A fresh id, for a capture or a transaction; each later than the last. */
    fun id(): Long = nextId++

    /** A capture as stage one stores it, at `pack_version` 0 unless [packVersion] says a pack has read it. */
    fun capture(
        text: String?,
        pkg: String = TNG,
        title: String? = null,
        bigText: String? = null,
        postedAt: Long = AT,
        whenMillis: Long? = null,
        packVersion: Int = ParsePass.NO_PACK,
        duplicateOfId: Long? = null,
        matchedRuleId: String? = null,
    ): RawCapture {
        val id = id()
        return RawCapture(
            id = id,
            sourcePackage = pkg,
            postedAt = postedAt,
            whenMillis = whenMillis,
            capturedAt = postedAt,
            sbnKey = "key-$id",
            notifId = 1,
            notifTag = null,
            userHandle = 0,
            channelId = null,
            flags = 0,
            arrival = Arrival.POSTED,
            title = title,
            text = text,
            bigText = bigText,
            subText = null,
            extrasJson = null,
            contentHash = "hash-$id",
            packVersion = packVersion,
            duplicateOfId = duplicateOfId,
            matchedRuleId = matchedRuleId,
        )
    }

    /** A core/parse corpus fixture as a capture: its header's package and title, and its body. */
    fun corpus(name: String, postedAt: Long = AT, body: (String) -> String = { it }): RawCapture {
        val text = requireNotNull(ReadingFixtures::class.java.getResource("/fixtures/$name")) {
            "no fixture named '$name' on the test classpath"
        }.readText().replace("\r\n", "\n")
        val (header, rest) = text.split("\n\n", limit = 2)
        val h = header.lineSequence()
            .filter { it.contains(':') && !it.startsWith("#") }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return capture(text = body(rest.trim()), pkg = requireNotNull(h["package"]), title = h["title"], postedAt = postedAt)
    }

    /** What [matcher] reads in [capture]; fails the test when it reads nothing. */
    fun matched(capture: RawCapture, matcher: RuleMatcher = this.matcher): MatchOutcome.Matched =
        when (val outcome = matcher.match(capture.sourcePackage, capture.title, capture.text, capture.bigText)) {
            is MatchOutcome.Matched -> outcome
            else -> throw AssertionError("The pack does not read '${capture.text ?: capture.bigText}': $outcome")
        }

    /** No transaction anywhere near. */
    val NOTHING = PairCandidates { emptyList() }

    /** A port that must not be asked. */
    val UNASKED = PairCandidates { query -> throw AssertionError("Layer two was asked $query") }

    /** [capture]'s first reading by [reading], under the pack's own rule unless [outcome] is given. */
    fun firstReading(
        capture: RawCapture,
        reading: Reading = reading(),
        outcome: MatchOutcome.Matched = matched(capture),
        prior: Reading.Prior = Reading.Prior.None,
        pairs: PairCandidates = NOTHING,
    ): Reading.First = reading.first(capture, outcome, prior, pairs, sourceLabel = "Touch 'n Go eWallet", now = AT + MINUTE)

    /** [capture]'s transaction as it stands in the ledger, with an id of its own, and the rule that read it. */
    fun inLedger(capture: RawCapture, reading: Reading = reading()): InMemoryPairCandidates.Row =
        InMemoryPairCandidates.Row(firstReading(capture, reading).txn.copy(id = id()), matched(capture).ruleId)

    /**
     * A pack for the gate conditions the bundled pack cannot isolate:
     * `tng-reload-v1` is the only `confidence: REVIEW` rule that ships, and it
     * also declares `kind: TRANSFER_SUSPECT` and extracts no merchant, so it
     * fires three of spec 7.1's five conditions at once. These rules each fire
     * exactly one, with the bundled pack's fragments rather than a retyped amount.
     */
    fun syntheticMatcher(): RuleMatcher = RuleMatcher(
        ParsePack(
            packVersion = 9_001,
            packages = listOf(
                PackagePack(
                    pkg = SYNTHETIC,
                    label = "Synthetic Bank",
                    rules = listOf(
                        TemplateRule(
                            id = "synthetic-review-only-v1",
                            priority = 100,
                            direction = Direction.EXPENSE,
                            confidence = Confidence.REVIEW,
                            kind = null,
                            pattern = "Maybe spent {{amount}} at (?<merchant>.+?) today",
                        ),
                        TemplateRule(
                            id = "synthetic-no-merchant-v1",
                            priority = 90,
                            direction = Direction.EXPENSE,
                            confidence = Confidence.HIGH,
                            kind = null,
                            pattern = "Charge of {{amount}} applied",
                        ),
                        TemplateRule(
                            id = "synthetic-transfer-only-v1",
                            priority = 80,
                            direction = Direction.EXPENSE,
                            confidence = Confidence.HIGH,
                            kind = Kind.TRANSFER_SUSPECT,
                            pattern = "Moved {{amount}} to (?<merchant>.+?) account",
                        ),
                    ),
                ),
            ),
            fragments = Graph.parsePack().fragments,
        ),
    )

    /** How a row's verdict reads: its state, its reason, and whether and why it is left out. */
    fun verdict(txn: Txn) = listOf(txn.state, txn.pendingReason, txn.isExcluded, txn.exclusionReason)
}
