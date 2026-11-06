package my.pinged.capture

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import java.util.concurrent.CancellationException
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.data.LiveLearnedRules
import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.StaleCaptureException
import my.pinged.data.dao.StandingAnswerDao
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.Filer
import my.pinged.parse.MatchOutcome
import my.pinged.parse.RuleMatcher
import my.pinged.parse.Skeleton
import my.pinged.parse.TaughtTemplate

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
 * ## Filing
 *
 * A capture is filed by the learned rule for its merchant identity, else by the
 * pack's dictionary, else under `Uncategorized` (spec 7.1's non-blocking chip).
 * There is no `:core:categorize` module (spec #45): [Filer] lives in
 * `:core:parse` and the [Reading] asks it, so the filing runs in stage two from the
 * durable `raw_capture` table and never in the listener.
 *
 * ## What is not here
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
 * **`exclusion_reason`, unless the user has already answered.** A matched
 * rule can carry one (spec 7.3), and it is not written onto the transaction
 * on the rule's say-so: spec 7.3 asks Leave out / Keep as expense / Not a
 * payment, so the exclusion is the user's answer, and `is_excluded` with a
 * reason means "already excluded" -- money out of every total with nobody
 * asked. The proposal survives on `raw_capture.matched_rule_id`, from which
 * `Graph.suggestedExclusion` re-derives it when the user leaves the row out.
 * The one exception is a standing answer the user gave ([standingAnswers]),
 * which the [Reading] consults on a capture's first read only.
 */
internal class ParsePass(
    private val captures: RawCaptureDao,
    txns: TxnDao,
    /** Where a learned rule is looked up, by the capture's merchant identity. */
    merchants: MerchantIdentityDao,
    /** Spec 7.3's standing answers, asked by the [Reading] for a transfer on its first read. */
    standingAnswers: StandingAnswerDao,
    private val matcher: RuleMatcher,
    /**
     * Spec 5.8's taught rules ([TaughtTemplate.matcher]), asked only once
     * [matcher] has read nothing. Built per pass from `match_count` as it was
     * then, so a rule reading several captures in one pass holds them all for
     * review: the snapshot is never ahead of the count, only behind it.
     */
    private val taught: RuleMatcher = TaughtTemplate.matcher(emptyList()),
    uncategorizedId: Long,
    private val sourceLabel: (String) -> String?,
    /**
     * The pack's dictionary. An entry whose category is absent from
     * [categoryIds] is dropped before the [Filer] matches, so its
     * merchants fall through to a shorter entry or to Uncategorized rather than
     * to a category that is gone. Empty files every capture under Uncategorized
     * unless a learned rule applies.
     */
    dictionary: List<DictionaryEntry> = emptyList(),
    /**
     * The `category.id` of each seeded category that exists, by its
     * `seed_name` (`CategoryDao.seededIds`): what resolves a dictionary
     * entry's category name to an id, whatever the user has renamed it to. A snapshot of the moment the
     * pass was built; a learned rule is read by id and does not depend on it.
     */
    categoryIds: Map<String, Long> = emptyMap(),
    /**
     * Spec 7.1's check limit, [CheckLimit.sen] as [ParseWorker] read it at the
     * start of the run: the gate holds a payment over it. A limit changed while
     * a run is under way reaches the run after it.
     */
    checkLimitSen: Long = ConfidenceGate.DEFAULT_THRESHOLD_SEN,
    /** Across every pass sharing [progress], not per pass. */
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
    /**
     * Where this run has got to and what it has met, shared by every pass
     * of one run -- [ParseWorker] runs another on a new instance after each
     * [DamagedCaptureException] -- so that [maxRows], the failures and the
     * queue position hold across them.
     */
    private val progress: Progress = Progress(),
) {
    /**
     * What this pass makes of each matched capture. Its learned rules are the
     * live port, never a per-pass table of rules:
     * a rule the user teaches part-way through a pass runs its retroactive fix
     * at once, and every later capture of the pass has to be filed by the new
     * rule or the fix would miss it. The window is one capture, as
     * `learnedCategoryFor` runs just outside `commitCapture`'s transaction.
     */
    private val reading = Reading(
        matcher = matcher,
        checkLimitSen = checkLimitSen,
        standingAnswers = RoomStandingAnswers(standingAnswers),
        learned = LiveLearnedRules(merchants),
        dictionary = dictionary,
        categoryIds = categoryIds,
        uncategorizedId = uncategorizedId,
    )

    private val pairs = RoomPairCandidates(txns)

    /**
     * What one run did. [remaining] is true when this run knowingly left work
     * behind that another run could do -- it was stopped, it hit [maxRows],
     * or a capture failed -- which is what [ParseWorker] turns into a retry.
     * A capture skipped for damage is not that: a retry meets the same page.
     */
    data class Summary(val processed: Int, val failed: Int, val remaining: Boolean)

    /**
     * One run's position in the queue and its tally, across its passes; and
     * what damage has shown about captures on this file, which can outlive
     * the run -- [ParseWorker] keeps those two for as long as the file.
     *
     * @param undecidable captures stage two cannot decide on this file: the
     *   read of their own row, or their commit, met a damaged page, which
     *   every connection meets again. Skipped unread, and left at `NEW`,
     *   where they have a verdict nobody has reached.
     * @param unpaired captures whose own row reads but whose duplicate
     *   lookup -- layer one's indexes or layer two's transactions -- met
     *   damage. Parsed on the next pass without either lookup, and a match
     *   is a duplicate suspect naming no pair: the review inbox, where an
     *   earlier copy of the same money can be merged. Not [undecidable],
     *   which would leave the money out of the ledger for as long as the
     *   page is damaged, and not parsed as new, which would count it twice
     *   with nobody told whenever the lookup would have found a refresh.
     *   The cost is one review item for each copy a listener rebind stores
     *   while the page is damaged, each copy's lookup meeting it in turn.
     */
    class Progress(
        val undecidable: MutableSet<Long> = mutableSetOf(),
        val unpaired: MutableSet<Long> = mutableSetOf(),
    ) {
        val failed = mutableSetOf<Long>()
        var processed = 0
            internal set

        /** Instances replaced for damage this run; [ParseWorker] bounds it. */
        var replacements = 0

        /** The last capture passed, in the queue's `(posted_at, id)` order. */
        internal var afterPostedAt = Long.MIN_VALUE
        internal var afterId = Long.MIN_VALUE

        fun summary(remaining: Boolean = failed.isNotEmpty()) = Summary(processed, failed.size, remaining)
    }

    /**
     * A capture's reads or its commit met a damaged page: code 11, from
     * reading its row, from a duplicate lookup, or from its commit.
     *
     * **Thrown out of [run], not recorded and carried on from** as another
     * failure is, because the read has left the connection answering code
     * 26 to everything after it -- measured on emulator-5554, inside a
     * transaction as well as out -- so nothing more can be done on this
     * instance. [captureId] is in [Progress.undecidable] or
     * [Progress.unpaired] by then; the caller replaces the instance and
     * runs again from where this one stopped.
     */
    class DamagedCaptureException(val captureId: Long, cause: Throwable) :
        RuntimeException("Capture $captureId reads a damaged page", cause)

    /**
     * Drains the `NEW` queue in chunks.
     *
     * The queue is a table query, so this is idempotent by construction: a row
     * still at `NEW` is unfinished work, and a run that dies halfway leaves fewer
     * of them. Spec 15.5's three properties are all here -- chunked, cancellable
     * ([isStopped] before every row) and bounded ([maxRows]).
     *
     * **Termination is not left to the queue emptying.** A capture that cannot
     * be marked stays `NEW` and sorts where it did, so a claim from the head
     * of the queue returns it again: `while (true) { claimNext() }` spins, and
     * a run that ended on a batch of nothing but such captures left every
     * capture behind them unclaimed for good -- measured on emulator-5554:
     * with 50 skipped copies of one notification ahead of a capture, it was
     * still `NEW` after three runs, each `success()`; with 49 it was parsed.
     * So the claim is a cursor
     * ([Progress]), and a capture that failed or is undecidable is passed
     * over and stays behind it until the next run.
     *
     * **A delete or a restore stops the run** before its next capture, as
     * [isStopped] does: `ParseWorker` runs it inside a `DatabaseAccess.leased`
     * lease, and a reset (`WholeFile.replaceAll`) waits for every lease before
     * it closes the instance under it.
     */
    fun run(): Summary {
        fun stopped() = isStopped() || Databases.gated || progress.processed >= maxRows

        while (true) {
            if (stopped()) return progress.summary(remaining = true)

            // Ids first, off the index, and each row read on its own: a row on
            // a damaged page is then one capture skipped rather than a batch
            // that cannot be claimed, which left the capture behind it at NEW
            // and the run refused (measured on emulator-5554).
            val batch = captures.claimNextIds(BATCH_SIZE, progress.afterPostedAt, progress.afterId)
            if (batch.isEmpty()) return progress.summary()

            for (entry in batch) {
                if (stopped()) return progress.summary(remaining = true)
                if (entry.id !in progress.undecidable) decide(entry.id)
                progress.afterPostedAt = entry.postedAt
                progress.afterId = entry.id
            }
        }
    }

    /**
     * One capture: parsed and its outcome committed, recorded as failed, or
     * [DamagedCaptureException] -- after which the next pass takes it up
     * again, as [Progress] says.
     */
    private fun decide(id: Long) {
        val capture = try {
            captures.byId(id)
        } catch (damage: SQLiteDatabaseCorruptException) {
            progress.undecidable += id
            throw DamagedCaptureException(id, damage)
        }
        // Decided by another writer since the ids were read.
        if (capture.parseStatus != ParseStatus.NEW) return

        try {
            processOne(capture)
            progress.processed++
        } catch (cancelled: CancellationException) {
            // Cancellation is not a failure and must not be recorded as
            // one. It also must not be swallowed: the coroutine this
            // runs in is being torn down.
            throw cancelled
        } catch (damaged: DamagedCaptureException) {
            throw damaged
        } catch (stale: StaleCaptureException) {
            // Another writer decided this capture's outcome between
            // its row being read and the commit. Nothing was written
            // and there is nothing to retry -- the work is done, by
            // somebody else.
            Log.i(TAG, "Capture ${capture.id} was decided by another writer: ${stale.message}")
        } catch (damage: SQLiteDatabaseCorruptException) {
            // The lookups are caught in [processOne] and [commit], so this is
            // the commit's own write: a new connection meets it again.
            progress.undecidable += capture.id
            throw DamagedCaptureException(capture.id, damage)
        } catch (failure: RuntimeException) {
            // Code 26 is a connection something else poisoned, and
            // says nothing about this capture: `CaptureStorage.guarded`
            // retries it on a new one.
            if (Databases.poisons(failure)) throw failure
            // The row stays NEW, which is the honest record of
            // unfinished work (spec 3). Marking it anything else would
            // claim a verdict this run does not have.
            progress.failed += capture.id
            Log.e(TAG, "Stage two failed for capture ${capture.id}", failure)
        }

        onCaptureFinished(capture.id)
    }

    private fun processOne(capture: RawCapture) {
        // Its own row read, so it is decidable; only the lookup is not. See
        // [Progress.unpaired] for why a suspect and not undecidable.
        if (capture.id in progress.unpaired) return parse(capture, slotSuspectOf = null)
        val verdict = try {
            Dedup.layerOne(captures, capture, progress.undecidable)
        } catch (damage: SQLiteDatabaseCorruptException) {
            progress.unpaired += capture.id
            throw DamagedCaptureException(capture.id, damage)
        }
        when (verdict) {
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

            is Layer1Verdict.UndecidablePrior -> parse(capture, slotSuspectOf = verdict.priorId)

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
        // Spec 5.8: a taught rule is asked only once every pack template has
        // failed, so it cannot shadow a rule that ships -- and only once no
        // pack reject has met the capture either. A pack template outranks a
        // reject (spec 5.3) because its words say where the merchant ends; a
        // taught merchant last in its message runs to the end, so "... at
        // KEDAI failed" would read as a payment to "KEDAI failed".
        if (outcome is MatchOutcome.Unmatched) {
            when (val read = taught.match(capture.sourcePackage, capture.title, capture.text, capture.bigText)) {
                is MatchOutcome.Matched -> return commit(
                    capture,
                    read,
                    slotSuspectOf,
                    taughtRuleId = TaughtTemplate.storedIdOf(read.ruleId),
                )
                // A taught rule out of time has not said no, as a pack rule's has not.
                MatchOutcome.GaveUp -> return mark(
                    capture = capture,
                    status = ParseStatus.GAVE_UP,
                    duplicateOf = slotSuspectOf,
                    packVersion = matcher.packVersion,
                )
                else -> Unit
            }
        }
        when (outcome) {
            is MatchOutcome.Matched -> commit(capture, outcome, slotSuspectOf)

            is MatchOutcome.Rejected -> mark(
                capture = capture,
                status = ParseStatus.REJECTED,
                rejectId = outcome.rejectRuleId,
                duplicateOf = slotSuspectOf,
                packVersion = matcher.packVersion,
            )

            // Spec 5.7: a user reject rule is consulted here and nowhere else,
            // so it decides whether the unread list shows this, never whether
            // a transaction exists.
            MatchOutcome.Unmatched -> {
                val rows = captures.markUnmatched(
                    capture = capture,
                    skeleton = Skeleton.ofCapture(capture.title, capture.text, capture.bigText),
                    duplicateOf = slotSuspectOf,
                    packVersion = matcher.packVersion,
                )
                if (rows == 0) Log.i(TAG, "Capture ${capture.id} left NEW before UNMATCHED could be recorded")
            }

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
        taughtRuleId: Long? = null,
    ) {
        // A layer-two lookup that meets damage leaves the capture a suspect
        // with no pair named, on the next pass: a transaction on the damaged
        // page may be this purchase through another app, and parsed as new it
        // would count twice with nobody told. Skipped instead, it is money
        // left out of the ledger for as long as the page is damaged.
        val prior = when {
            capture.id in progress.unpaired -> Reading.Prior.PairUnknown
            slotSuspectOf != null -> Reading.Prior.SlotSuspect(slotSuspectOf)
            else -> Reading.Prior.None
        }
        // A standing answer's or a learned rule's lookup that meets a damaged
        // page throws out of here as the commit's own write would, and
        // [decide] leaves the capture at `NEW` rather than file it under
        // Uncategorized on a question that could not be answered.
        val read = reading.first(
            capture = capture,
            outcome = outcome,
            prior = prior,
            pairs = pairsFor(capture),
            sourceLabel = sourceLabel,
            now = System.currentTimeMillis(),
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
            txn = read.txn,
            status = ParseStatus.MATCHED,
            ruleId = outcome.ruleId,
            rejectId = null,
            // Spec 5.3: a capture that matched both a template and a reject is
            // MATCHED, and the collision is recorded for rule review.
            collisionId = outcome.rejectCollisionId,
            duplicateOf = read.duplicateOf,
            packVersion = matcher.packVersion,
            taughtRuleId = taughtRuleId,
        )
    }

    /**
     * Layer two's lookup for [capture]. Its damage is the lookup's and not the
     * capture's own: recorded in [Progress.unpaired], so the next pass reads
     * the capture as [Reading.Prior.PairUnknown].
     */
    private fun pairsFor(capture: RawCapture) = PairCandidates { query ->
        try {
            pairs.near(query)
        } catch (damage: SQLiteDatabaseCorruptException) {
            progress.unpaired += capture.id
            throw DamagedCaptureException(capture.id, damage)
        }
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
        // count, so zero means somebody else recorded an outcome between its
        // row being read and here. That is a signal, not success and not a
        // failure: there is nothing left to do for this capture and nothing
        // to retry.
        if (rows == 0) {
            Log.i(TAG, "Capture ${capture.id} left NEW before $status could be recorded")
        }
    }

    internal companion object {
        private const val TAG = "PingedParse"

        /**
         * A pass over [db] that reads what a run of [ParseWorker] reads, per
         * pass: the bundled pack's dictionary, the taught rules as their
         * `match_count` stands now, the seeded categories that exist and each
         * app's label. [checkLimitSen] is read by the caller, before any lease
         * ([CheckLimit.sen] suspends on DataStore, which has no place inside one).
         *
         * Each of [dictionary], [categoryIds] and [sourceLabel], when
         * given, **replaces** what would be read, and nothing is merged into it:
         * a test about filing passes its own dictionary and no other entry can
         * file its capture. Only tests give them.
         */
        fun over(
            db: PingedDatabase,
            matcher: RuleMatcher,
            checkLimitSen: Long,
            isStopped: () -> Boolean = { false },
            progress: Progress = Progress(),
            maxRows: Int = MAX_ROWS_PER_RUN,
            onCaptureFinished: (Long) -> Unit = {},
            dictionary: List<DictionaryEntry>? = null,
            categoryIds: Map<String, Long>? = null,
            sourceLabel: ((String) -> String?)? = null,
        ) = ParsePass(
            captures = db.rawCaptureDao(),
            txns = db.txnDao(),
            merchants = db.merchantIdentityDao(),
            standingAnswers = db.standingAnswerDao(),
            matcher = matcher,
            // Read per pass, so a rule saved or deleted between two runs counts on the next.
            taught = TaughtTemplate.matcher(db.userTemplateRuleDao().all().map { it.stored() }),
            // requireUncategorizedId, not the nullable form: spec 7.1 files an
            // unknown merchant here, and a missing seed row must fail saying so
            // rather than send a 0 down the money path into a foreign-key error
            // far from the cause.
            uncategorizedId = db.categoryDao().requireUncategorizedId(),
            sourceLabel = sourceLabel ?: sourceLabels(db),
            dictionary = dictionary ?: Graph.parsePack().dictionary,
            // Read per pass, so a category deleted between two runs stops its
            // entries filing on the next one.
            categoryIds = categoryIds ?: db.categoryDao().seededIds(),
            checkLimitSen = checkLimitSen,
            maxRows = maxRows,
            isStopped = isStopped,
            onCaptureFinished = onCaptureFinished,
            progress = progress,
        )

        /**
         * `capture_source.label` for `txn.source_label` (spec 4), memoized for
         * the pass. A batch is usually one or two packages, and the alternative
         * is a point query per capture for an answer that does not change.
         */
        private fun sourceLabels(db: PingedDatabase): (String) -> String? {
            val dao = db.captureSourceDao()
            val cache = HashMap<String, String?>()
            return { pkg -> cache.getOrPut(pkg) { dao.byPackage(pkg)?.label } }
        }

        /**
         * Rows per `claimNextIds`.
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

        /**
         * The `pack_version` recorded for an outcome no pack produced, i.e.
         * layer 1's two dropping rules. `pack_version` is what spec 5.5's
         * re-parse reasons about, so claiming a version for a decision the pack
         * had no part in would invite a re-parse to revisit it.
         */
        const val NO_PACK = 0
    }
}
