package my.pinged.capture

import android.util.Log
import java.util.concurrent.CancellationException
import my.pinged.data.LocalDates
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.StaleCaptureException
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.parse.MatchOutcome
import my.pinged.parse.Merchant
import my.pinged.parse.RuleMatcher

/**
 * Stage two, all of it, with no WorkManager in it.
 *
 * [ParseWorker] is the adapter over this class and holds nothing but wiring.
 * The split is not decoration: the pack has to be substitutable to test spec
 * 7.1's gate against a rule the bundled pack does not contain, and
 * `TestListenableWorkerBuilder` constructs a worker through its
 * `(Context, WorkerParameters)` constructor, so a worker is not injectable.
 * Every test here drives the real DAOs against the real encrypted database.
 *
 * Substituted: the pack (via [matcher]), [maxRows] and [onCaptureFinished] --
 * the last two by `ParseInterruptionTest`, to prove the loop stops where it
 * says it does.
 *
 * ## What is not here
 *
 * **Categorization.** `:core:categorize` is a later module (spec 3), so every
 * transaction is filed under `Uncategorized` -- which spec 7.1 calls the
 * intended landing place and a non-blocking chip, so this is a missing feature
 * and not a wrong state.
 *
 * **`capture_source.is_authoritative`.** Spec 7.2 says a `DUPLICATE_SUSPECT`
 * pair spanning an authoritative and a non-authoritative source resolves toward
 * the authoritative one without reaching the inbox. Nothing in this milestone
 * writes the column, so the branch is unreachable -- and "resolves toward" does
 * not say what happens to the losing side, which is a wrong-money decision
 * either way: committing it double-counts, rejecting it drops money
 * automatically, which the same section forbids. The pair reaches the review
 * inbox instead.
 *
 * **`exclusion_reason`.** A matched rule can carry one (spec 7.3), and it is
 * deliberately not written onto the transaction: spec 7.3 offers Exclude / Keep
 * as expense / Always exclude, so the exclusion is the user's answer, and
 * `is_excluded` with a reason means "already excluded" -- money out of every
 * total with nobody asked. The proposal survives on
 * `raw_capture.matched_rule_id`, which the prompt needs anyway.
 */
internal class ParsePass(
    private val captures: RawCaptureDao,
    private val txns: TxnDao,
    private val matcher: RuleMatcher,
    private val uncategorizedId: Long,
    private val sourceLabel: (String) -> String?,
    private val maxRows: Int = MAX_ROWS_PER_RUN,
    private val isStopped: () -> Boolean = { false },
    /**
     * Called once per capture, after its outcome has been committed.
     *
     * The interruption tests use it to throw `CancellationException` partway
     * through a batch, which is the only way to reach "the process died
     * mid-batch" on demand. It is a constructor parameter with a default
     * rather than a mutable global, so nothing in the app can be holding a
     * hook.
     */
    private val onCaptureFinished: (Long) -> Unit = {},
) {

    /**
     * What one run did. [remaining] is true when this run knowingly left work
     * behind -- it was stopped, it hit [maxRows], or a capture failed -- which
     * is what [ParseWorker] turns into a retry.
     */
    data class Summary(val processed: Int, val failed: Int, val remaining: Boolean)

    /**
     * Drains the `NEW` queue in chunks.
     *
     * The queue is a table query, so this is idempotent by construction: a row
     * still at `NEW` is unfinished work, and a run that dies halfway leaves fewer
     * of them. Spec 15.5's three properties are all here -- chunked, cancellable
     * ([isStopped] before every row) and bounded ([maxRows]).
     *
     * **Termination is not left to the queue emptying.** `while (true) {
     * claimNext() }` spins forever the moment one capture cannot be marked: the row
     * stays `NEW`, sorts first under `posted_at ASC`, and comes straight back. A
     * capture that throws is remembered in [failed] and not re-attempted within the
     * run, and a batch of nothing but already-failed rows ends it -- so one
     * poisoned row delays only the rows more than [batchSize] behind it, until the
     * retry.
     */
    fun run(): Summary {
        val failed = mutableSetOf<Long>()
        var processed = 0

        // `remaining = true` is the answer to every early exit here, and saying
        // so once keeps the three from drifting apart.
        fun stopHere() = Summary(processed, failed.size, remaining = true)

        while (true) {
            if (isStopped() || processed >= maxRows) return stopHere()

            val batch = captures.claimNext(BATCH_SIZE)
            if (batch.isEmpty()) {
                return Summary(processed, failed.size, remaining = failed.isNotEmpty())
            }
            val fresh = batch.filterNot { it.id in failed }
            if (fresh.isEmpty()) return stopHere()

            for (capture in fresh) {
                if (isStopped() || processed >= maxRows) return stopHere()

                try {
                    processOne(capture)
                    processed++
                } catch (cancelled: CancellationException) {
                    // Cancellation is not a failure and must not be recorded as
                    // one. It also must not be swallowed: the coroutine this
                    // runs in is being torn down.
                    throw cancelled
                } catch (stale: StaleCaptureException) {
                    // Another writer decided this capture's outcome between
                    // claimNext and the commit. Nothing was written and there is
                    // nothing to retry -- the work is done, by somebody else.
                    Log.i(TAG, "Capture ${capture.id} was decided by another writer: ${stale.message}")
                } catch (failure: RuntimeException) {
                    // The row stays NEW, which is the honest record of
                    // unfinished work (spec 3). Marking it anything else would
                    // claim a verdict this run does not have.
                    failed += capture.id
                    Log.e(TAG, "Stage two failed for capture ${capture.id}", failure)
                }

                onCaptureFinished(capture.id)
            }
        }
    }

    private fun processOne(capture: RawCapture) {
        when (val verdict = Dedup.layerOne(captures, capture)) {
            // Layer 1's two dropping rules. No transaction, by design: the same
            // notification twice is not money twice.
            is Layer1Verdict.SlotRefresh -> mark(
                capture = capture,
                status = ParseStatus.UPDATE_OF,
                duplicateOf = verdict.priorId,
                // No pack ran, so recording a pack version would claim this
                // outcome came from one. Spec 5.5 re-parses by pack version.
                packVersion = NO_PACK,
            )

            is Layer1Verdict.ContentRepeat -> mark(
                capture = capture,
                status = ParseStatus.DUPLICATE_OF,
                duplicateOf = verdict.priorId,
                packVersion = NO_PACK,
            )

            // Beyond the ten-minute window: this *does* become a transaction,
            // flagged as a duplicate suspect. Folding it in with the two above
            // is the wrong-money bug spec 7.2 was rewritten to remove.
            is Layer1Verdict.SlotSuspect -> parse(capture, slotSuspectOf = verdict.priorId)

            Layer1Verdict.NotADuplicate -> parse(capture, slotSuspectOf = null)
        }
    }

    private fun parse(capture: RawCapture, slotSuspectOf: Long?) {
        val outcome = matcher.match(
            pkg = capture.sourcePackage,
            title = capture.title,
            text = capture.text,
            bigText = capture.bigText,
        )
        when (outcome) {
            is MatchOutcome.Matched -> commit(capture, outcome, slotSuspectOf)

            is MatchOutcome.Rejected -> mark(
                capture = capture,
                status = ParseStatus.REJECTED,
                rejectId = outcome.rejectRuleId,
                duplicateOf = slotSuspectOf,
                packVersion = matcher.packVersion,
            )

            MatchOutcome.Unmatched -> mark(
                capture = capture,
                status = ParseStatus.UNMATCHED,
                duplicateOf = slotSuspectOf,
                packVersion = matcher.packVersion,
            )

            MatchOutcome.NoExtras -> mark(
                capture = capture,
                status = ParseStatus.NO_EXTRAS,
                duplicateOf = slotSuspectOf,
                packVersion = matcher.packVersion,
            )

            // Its own status, never folded into UNMATCHED. A pattern that ran
            // out of wall clock is not evidence that no rule matches, spec 5.5
            // has re-parse revisit exactly these, and recording it as UNMATCHED
            // would put a phantom fixture in front of spec 5.6's rule author.
            MatchOutcome.GaveUp -> mark(
                capture = capture,
                status = ParseStatus.GAVE_UP,
                duplicateOf = slotSuspectOf,
                packVersion = matcher.packVersion,
            )
        }
    }

    private fun commit(
        capture: RawCapture,
        outcome: MatchOutcome.Matched,
        slotSuspectOf: Long?,
    ) {
        val occurredAt = occurredAt(capture)
        // Blank collapses to null so the stored column is exactly the predicate spec
        // 7.1's MERCHANT_MISSING is recomputable from. The string is otherwise
        // untouched: spec 5.4 preserves `merchant_raw` as parsed.
        //
        // **A capture that cleans away to nothing has no merchant either.** A pattern
        // can capture a string that is entirely payment rail -- `TNG*`, `DUITNOWQR-`, a
        // bare terminal code -- which `Merchant.clean` reduces to empty. Kept as raw
        // that reads as a merchant: no `MERCHANT_MISSING` fires, the row commits, and
        // section 8 groups it into the NULL bucket with every other such row, which is
        // what `merchant_key` exists to prevent.
        //
        // Nothing recoverable is lost. Spec 5.5's re-parse works from the capture's own
        // text, and an acquirer prefix on its own names nobody -- so it goes to the
        // review inbox, where a person can say who it was.
        val merchantRaw = outcome.merchantRaw
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { Merchant.clean(it, matcher.merchantNormalization).value.isNotEmpty() }

        val layerTwoSuspect = Dedup.layerTwo(
            dao = txns,
            sourcePackage = capture.sourcePackage,
            amountSen = outcome.amountSen,
            occurredAt = occurredAt,
            direction = outcome.direction,
            merchantRaw = merchantRaw,
            normalization = matcher.merchantNormalization,
        )

        val decision = ConfidenceGate.decide(
            confidence = outcome.confidence,
            kind = outcome.kind,
            merchantRaw = merchantRaw,
            amountSen = outcome.amountSen,
            duplicateSuspect = slotSuspectOf != null || layerTwoSuspect != null,
        )

        val at = System.currentTimeMillis()
        val txn = Txn(
            rawCaptureId = capture.id,
            amountSen = outcome.amountSen,
            direction = outcome.direction,
            occurredAt = occurredAt,
            // Computed here, once, and stored. Spec 15.7: deriving the day at
            // query time depends on the process zone, cannot use an index, and
            // reshuffles history when the user travels.
            localDate = LocalDates.of(occurredAt),
            merchantRaw = merchantRaw,
            // `displayFor(raw)`, not `display(clean(raw))`. The old
            // composition handed the title-caser a string that `clean` had
            // already uppercased, so spec 5.4's "only when the raw string is
            // entirely uppercase" guard was true by construction and every
            // merchant was title-cased: `foodpanda KLCC` was stored as
            // `Foodpanda Klcc`. `displayFor` starts from the same raw string
            // `merchant_raw` keeps and strips without folding the case.
            merchantDisplay = merchantRaw
                ?.let { Merchant.displayFor(it, matcher.merchantNormalization) }
                ?.takeIf { it.isNotEmpty() },
            // The identity, beside the name. `merchant_display` is what the
            // user reads and may edit; this is what section 8 groups by, and
            // it is written from the same pack the rest of this parse used.
            // No `takeIf` here any more, and its absence is the invariant.
            // `merchantRaw` above is already filtered to strings that clean to
            // something, so this is null exactly when that is -- which is what
            // the column's KDoc has always claimed and did not used to be true.
            merchantKey = merchantRaw
                ?.let { Merchant.clean(it, matcher.merchantNormalization).value },
            categoryId = uncategorizedId,
            sourcePackage = capture.sourcePackage,
            sourceLabel = sourceLabel(capture.sourcePackage),
            confidence = outcome.confidence,
            state = decision.state,
            pendingReason = decision.reason,
            createdAt = at,
            updatedAt = at,
        )

        // commitCapture, never insert-then-mark. The two writes are one
        // `@Transaction`, so there is no interval in which the transaction
        // exists and the capture is still NEW -- the state that used to poison
        // the head of the queue permanently, because NEW sorts first and the
        // re-insert then threw against the unique index on txn.raw_capture_id.
        // It is also idempotent: a capture that already has a transaction gets
        // its outcome marked and the existing id back, which heals a row left
        // in that state by an older build.
        captures.commitCapture(
            captureId = capture.id,
            txn = txn,
            status = ParseStatus.MATCHED,
            ruleId = outcome.ruleId,
            rejectId = null,
            // Spec 5.3: a capture that matched both a template and a reject is
            // MATCHED, and the collision is recorded for rule review.
            collisionId = outcome.rejectCollisionId,
            duplicateOf = slotSuspectOf ?: layerTwoSuspect?.rawCaptureId,
            packVersion = matcher.packVersion,
        )
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

    private fun mark(
        capture: RawCapture,
        status: ParseStatus,
        rejectId: String? = null,
        duplicateOf: Long? = null,
        packVersion: Int,
    ) {
        val rows = captures.markOutcome(
            id = capture.id,
            status = status,
            rejectId = rejectId,
            duplicateOf = duplicateOf,
            packVersion = packVersion,
        )
        // markOutcome is guarded on parse_status = NEW and returns the row
        // count, so zero means somebody else recorded an outcome between
        // claimNext and here. That is a signal, not success and not a failure:
        // there is nothing left to do for this capture and nothing to retry.
        if (rows == 0) {
            Log.i(TAG, "Capture ${capture.id} left NEW before $status could be recorded")
        }
    }

    internal companion object {
        private const val TAG = "PingedParse"

        /**
         * Rows per `claimNext`.
         *
         * A query bound and not a transaction bound: each capture's decision
         * commits on its own inside `commitCapture`, so the chunk width decides
         * only how many rows are held in memory at once, never how much work a
         * kill can undo. Fifty is the figure the plan's own queue tests use.
         */
        const val BATCH_SIZE = 50

        /**
         * Rows per run, after which the run reports work remaining and lets WorkManager
         * schedule the next one (spec 15.5: bounded).
         *
         * Two thousand is a little over two months at spec 15's ~850 a month, so a
         * realistic backlog drains in one run while a pathological one cannot walk into
         * WorkManager's ten-minute limit and be killed at an arbitrary point.
         */
        const val MAX_ROWS_PER_RUN = 2_000

        /** See [occurredAt]. */
        const val WHEN_TRUST_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

        /**
         * The `pack_version` recorded for an outcome no pack produced, i.e.
         * layer 1's two dropping rules. `pack_version` is what spec 5.5's
         * re-parse reasons about, so claiming a version for a decision the pack
         * had no part in would invite a re-parse to revisit it.
         */
        const val NO_PACK = 0
    }
}
