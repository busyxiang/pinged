package my.pinged.capture

import my.pinged.data.DuplicateWindows
import my.pinged.data.LocalDates
import my.pinged.data.dao.StandingAnswerDao
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import my.pinged.parse.Filer
import my.pinged.parse.LearnedRules
import my.pinged.parse.MatchOutcome
import my.pinged.parse.RuleMatcher

/**
 * What layer two asks the ledger (spec 7.2): the transactions of [amountSen]
 * and [direction] within [DuplicateWindows.LAYER_TWO_MILLIS] of [occurredAt],
 * from an app other than [sourcePackage] or from it under a rule other than
 * [ruleId].
 */
internal data class PairQuery(
    val amountSen: Long,
    val occurredAt: Long,
    val direction: Direction,
    val sourcePackage: String,
    val ruleId: String,
)

/**
 * Layer two's candidates: exactly what `TxnDao.findDuplicateSuspects`'s SQL
 * decides -- amount, window, `direction`, another app or the same app under
 * another rule, and not `REJECTED` -- in the order its index returns them,
 * `occurred_at` then `id`. The merchant clause and the re-read's "older than"
 * are the [Reading]'s. `PairCandidatesContractTest` holds the Room and the
 * in-memory adapter to identical answers.
 *
 * May throw; the [Reading] writes nothing and lets it through.
 */
internal fun interface PairCandidates {
    fun near(query: PairQuery): List<Txn>
}

/** [PairCandidates] over [TxnDao.findDuplicateSuspects]. Blocking. */
internal class RoomPairCandidates(private val txns: TxnDao) : PairCandidates {
    override fun near(query: PairQuery): List<Txn> {
        val window = DuplicateWindows.layerTwo(query.occurredAt)
        return txns.findDuplicateSuspects(
            amountSen = query.amountSen,
            from = window.first,
            to = window.last,
            direction = query.direction,
            sourcePackage = query.sourcePackage,
            ruleId = query.ruleId,
        )
    }
}

/** Spec 7.3's standing answers: whether the user answered Always for this rule, app and counterparty. */
internal fun interface StandingAnswers {
    fun answered(ruleId: String, sourcePackage: String, merchantKey: String?): Boolean

    companion object {
        /** No answer for anything: a re-reading's, which never asks. */
        val NONE = StandingAnswers { _, _, _ -> false }
    }
}

/** [StandingAnswers] over [StandingAnswerDao.idFor]. Blocking. */
internal class RoomStandingAnswers(private val dao: StandingAnswerDao) : StandingAnswers {
    override fun answered(ruleId: String, sourcePackage: String, merchantKey: String?): Boolean =
        dao.idFor(ruleId, sourcePackage, merchantKey) != null
}

/**
 * The Reading (CONTEXT.md): what Pinged makes of a capture a rule matched --
 * its amount, merchant, date and filing, the earlier transaction it may
 * duplicate, and whether it counts, waits for review, or is left out.
 *
 * Stage two's [first] reading and spec 5.5's re-reading ([reread]) share every
 * step here, so a rule stage two gains reaches the re-read without being
 * written twice. Matching, the queue, layer one, status marking and the write
 * are [ParsePass]'s; paging, the field diff and accept are [Corrections]'.
 *
 * Writes nothing and reads only through its three ports -- [pairs][PairCandidates]
 * per call, [standingAnswers] and [learned] -- and the clock is an input, so a
 * JVM test reads exactly what the phone does.
 *
 * @param matcher the pack: its merchant normalization, and the exclusion a
 *   standing answer writes ([RuleMatcher.suggestedExclusion]).
 * @param checkLimitSen spec 7.1's check limit, [CheckLimit.sen].
 * @param standingAnswers asked by a first reading of a transfer only.
 * @param learned where a first reading files by merchant identity, then
 *   [dictionary], then [uncategorizedId]. Asked per capture, never a
 *   snapshot, so a rule taught part-way through a pass files every later
 *   capture of it.
 * @param categoryIds `category.id` by `seed_name`, which resolves a
 *   [dictionary] entry; an entry whose category is absent is dropped.
 * @param uncategorizedId where a first reading files what nothing else does.
 *   Null for a Reading that only re-reads, which files nothing.
 */
internal class Reading(
    private val matcher: RuleMatcher,
    private val checkLimitSen: Long,
    private val standingAnswers: StandingAnswers = StandingAnswers.NONE,
    learned: LearnedRules = LearnedRules.Snapshot(),
    dictionary: List<DictionaryEntry> = emptyList(),
    categoryIds: Map<String, Long> = emptyMap(),
    private val uncategorizedId: Long? = null,
) {
    private val filer = Filer(dictionary, categoryIds, learned)

    /** What layer one, or damage, already knows about a capture before its first reading. */
    sealed interface Prior {
        data object None : Prior

        /** Layer one's slot suspect, or a prior it cannot decide: a suspect naming [captureId]. */
        data class SlotSuspect(val captureId: Long) : Prior

        /**
         * Layer two cannot be asked: its lookup met damage (`ParsePass.Progress.unpaired`).
         * A suspect naming no pair, the review inbox, rather than money counted twice.
         */
        data object PairUnknown : Prior
    }

    /** The transaction a first reading writes, and the capture it is a suspected duplicate of. */
    data class First(val txn: Txn, val duplicateOf: Long?)

    /**
     * The transaction as the current pack would write it, and the capture whose
     * transaction it paired [after] with, if any.
     */
    data class Again(val after: Txn, val pairCaptureId: Long?)

    /**
     * A capture's first reading: the complete row `commitCapture` writes.
     *
     * A standing answer settles it only when the capture has never been read
     * by a pack (`pack_version` still [ParsePass.NO_PACK]): one spec 5.5's
     * re-parse or a taught rule handed back to `NEW` is not settled by an
     * answer made since.
     */
    fun first(
        capture: RawCapture,
        outcome: MatchOutcome.Matched,
        prior: Prior,
        pairs: PairCandidates,
        sourceLabel: String?,
        now: Long,
    ): First {
        val occurredAt = occurredAt(capture)
        val merchant = MerchantColumns.of(outcome.merchantRaw, matcher.merchantNormalization)
        // Asked whatever layer one said, as it always was; only damage skips it.
        val pair = if (prior == Prior.PairUnknown) null else pairOf(capture, outcome, merchant, occurredAt, pairs)
        val reasons = ConfidenceGate.reasons(
            confidence = outcome.confidence,
            kind = outcome.kind,
            merchantRaw = merchant.raw,
            amountSen = outcome.amountSen,
            duplicateSuspect = prior != Prior.None || pair != null,
            thresholdSen = checkLimitSen,
        )
        val decision = ConfidenceGate.decide(reasons, leftOutAs = standingExclusion(capture, outcome, merchant.key, reasons))
        // Learned, then dictionary, then Uncategorized: [Filer]'s order. The
        // learned rule answers by `category.id`, so it wins whether or not
        // `categoryIds`, a snapshot, still names that category: the foreign
        // key's RESTRICT means the id exists.
        val categoryId = filer.file(merchant.key).categoryId
            ?: checkNotNull(uncategorizedId) { "A Reading built only to re-read cannot file a first reading" }
        val txn = Txn(
            rawCaptureId = capture.id,
            amountSen = outcome.amountSen,
            direction = outcome.direction,
            occurredAt = occurredAt,
            // Computed once and stored. Spec 15.7: deriving the day at query
            // time depends on the process zone, cannot use an index, and
            // reshuffles history when the user travels.
            localDate = LocalDates.of(occurredAt),
            merchantRaw = merchant.raw,
            merchantDisplay = merchant.display,
            merchantKey = merchant.key,
            categoryId = categoryId,
            sourcePackage = capture.sourcePackage,
            sourceLabel = sourceLabel,
            confidence = outcome.confidence,
            state = decision.state,
            pendingReason = decision.reason,
            isExcluded = decision.exclusion != null,
            exclusionReason = decision.exclusion,
            createdAt = now,
            updatedAt = now,
        )
        return First(txn, duplicateOf = (prior as? Prior.SlotSuspect)?.captureId ?: pair?.rawCaptureId)
    }

    /**
     * A re-reading of [stored], the row [capture]'s first reading wrote:
     * amount, direction, merchant and confidence as the current pack reads
     * them, and nothing else of the row -- no filing, no date, no exclusion,
     * and no standing answer.
     *
     * Spec 7.1's gate runs again on a row still `PENDING`, which can move to a
     * new reason or be released. A `COMMITTED` row keeps its state and reason
     * (spec 5.5): it was answered in the inbox, or never held, and a Keep
     * both row's `duplicate_of_id` stays set for ever and would otherwise
     * re-raise the duplicate every pack.
     *
     * A pending row that was a suspect stays one. One that was not is paired
     * again through [pairs], against rows written before it (`txn.id`), so a
     * payment whose card spend already counts it is held as that spend's
     * duplicate rather than released (#255), and the older of a pair is never
     * paired with the newer. Only a pending row asks.
     *
     * The gate holds over the check limit now, not the one the row arrived
     * under (#362).
     */
    fun reread(
        capture: RawCapture,
        outcome: MatchOutcome.Matched,
        stored: Txn,
        pairs: PairCandidates,
        now: Long,
    ): Again {
        val merchant = MerchantColumns.of(outcome.merchantRaw, matcher.merchantNormalization)
        val committed = stored.state == TxnState.COMMITTED
        val carried = capture.duplicateOfId != null || stored.pendingReason == PendingReason.DUPLICATE_SUSPECT
        val pair = if (committed || carried) {
            null
        } else {
            pairOf(capture, outcome, merchant, stored.occurredAt, pairs, olderThan = stored.id)
        }
        val decision = ConfidenceGate.decide(
            ConfidenceGate.reasons(
                confidence = outcome.confidence,
                kind = outcome.kind,
                merchantRaw = merchant.raw,
                amountSen = outcome.amountSen,
                duplicateSuspect = carried || pair != null,
                thresholdSen = checkLimitSen,
            ),
        )
        val after = stored.copy(
            amountSen = outcome.amountSen,
            direction = outcome.direction,
            merchantRaw = merchant.raw,
            merchantDisplay = merchant.display,
            merchantKey = merchant.key,
            confidence = outcome.confidence,
            state = if (committed) stored.state else decision.state,
            pendingReason = if (committed) stored.pendingReason else decision.reason,
            updatedAt = now,
        )
        return Again(after, pairCaptureId = pair?.rawCaptureId)
    }

    /**
     * Spec 7.2 layer two: the first of [pairs]' candidates written before
     * [olderThan] whose merchant agrees ([Dedup.merchantsAgree]). Only the
     * newer of a pair is flagged, which for a first reading falls out of the
     * queue's `posted_at` order: nothing is written until this returns, so
     * every row is older. `is_authoritative` is not consulted; see [ParsePass].
     */
    private fun pairOf(
        capture: RawCapture,
        outcome: MatchOutcome.Matched,
        merchant: MerchantColumns,
        occurredAt: Long,
        pairs: PairCandidates,
        olderThan: Long = Long.MAX_VALUE,
    ): Txn? = pairs.near(
        PairQuery(
            amountSen = outcome.amountSen,
            occurredAt = occurredAt,
            direction = outcome.direction,
            sourcePackage = capture.sourcePackage,
            ruleId = outcome.ruleId,
        ),
    ).firstOrNull { it.id < olderThan && Dedup.merchantsAgree(merchant.raw, it.merchantRaw, matcher.merchantNormalization) }

    /**
     * The exclusion a standing answer (spec 7.3) writes, or null when none is
     * consulted or none matches. The value is what Leave out would have
     * written -- the rule's suggestion, `TRANSFER` when it has none -- so the
     * row is identical to a Leave out row.
     *
     * Asked only when [ConfidenceGate.standingAnswerApplies], which no capture
     * but a transfer passes, and outside `commitCapture`'s transaction, as the
     * filing is: an answer deleted between the two settles one capture the
     * user would have been asked about, which their Undo takes back.
     */
    private fun standingExclusion(
        capture: RawCapture,
        outcome: MatchOutcome.Matched,
        merchantKey: String?,
        reasons: Set<PendingReason>,
    ): ExclusionReason? {
        if (capture.packVersion != ParsePass.NO_PACK || !ConfidenceGate.standingAnswerApplies(reasons)) return null
        if (!standingAnswers.answered(outcome.ruleId, capture.sourcePackage, merchantKey)) return null
        return matcher.suggestedExclusion(capture.sourcePackage, outcome.ruleId) ?: ExclusionReason.TRANSFER
    }

    /**
     * `occurred_at` for a capture. `sbn.postTime` is the value, and
     * `notification.when` displaces it only when it is non-zero, **not in the
     * future**, and no more than [WHEN_TRUST_WINDOW_MILLIS] before it.
     *
     * The one-sided window is the point. Spec 4 keeps `when` because a push can be
     * delivered long after the event, and distrusts it because it is app-controlled
     * and "regularly left at or set to zero". A symmetric tolerance accepts a
     * `when` in the *future*, dating a transaction into a month that has not
     * happened; a wide one lets a bank that puts a statement date in `when` move a
     * purchase into the previous month, changing a total the user has read. So:
     * past only, capped at a day, which is as much delay as Doze produces.
     *
     * `when_millis` is already null unless it was non-zero (`NotificationFields`).
     */
    private fun occurredAt(capture: RawCapture): Long {
        val whenMillis = capture.whenMillis ?: return capture.postedAt
        val drift = capture.postedAt - whenMillis
        return if (drift in 0..WHEN_TRUST_WINDOW_MILLIS) whenMillis else capture.postedAt
    }

    companion object {
        /** See [occurredAt]. */
        const val WHEN_TRUST_WINDOW_MILLIS = 24 * 60 * 60 * 1000L
    }
}
