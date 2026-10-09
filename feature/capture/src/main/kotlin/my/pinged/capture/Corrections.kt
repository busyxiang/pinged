package my.pinged.capture

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import my.pinged.data.Databases
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.StaleCaptureException
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.MatchOutcome
import my.pinged.parse.RuleMatcher

/**
 * Spec 5.5's third mode: `MATCHED` captures re-read by a newer pack and
 * compared against the transaction an older one wrote.
 *
 * [Reparse] only ever creates transactions, so without this a pack that fixes
 * a wrong read fixes the next capture and none of the ones already counted: a
 * RM2,262.76 card bill read as spending stays in every total.
 *
 * **Nothing the user can see changes without their answer.** A re-read that
 * differs in [Field] is left for [pending] to offer, and lands only through
 * [accept]. A re-read that differs only in `merchant_raw`, `merchant_key` or
 * the rule id is applied by [sweep] without asking: none of the three is drawn
 * anywhere, and `merchant_key` is the column section 8 groups by, so leaving a
 * shop keyed two ways until someone answers a question they cannot see the
 * point of is the worse outcome. Either way the user's merges, name and learned
 * rule move with the key, in the same transaction (`MerchantDecisions.carry`).
 *
 * **No table of its own.** Schema v1 is frozen, and the list is derivable: a
 * capture still at an older `pack_version` with an unedited transaction is
 * either unswept or awaiting an answer, and [pending] re-reads it to say which.
 * Agreeing, applied silently, accepted and declined all move the capture to the
 * current `pack_version`, so a declined correction returns only if a later pack
 * reads the capture differently again.
 *
 * **`user_edited` rows are never read** (spec 5.5), and that excludes every
 * one-off -- a row whose category was set for that one payment, which
 * `TxnDao.setCategory` flags. A row a teaching save filed by the merchant's
 * learned rule keeps `user_edited = 0` and **is** read, so a pack correction
 * can still reach it: a re-read never writes `category_id`, so the rule's
 * choice survives it.
 *
 * **Not offered: a `MATCHED` capture the current pack no longer matches.** It
 * stays at its old version, unanswered. What accepting "this was not a payment"
 * should do to a transaction is spec 9.2's reject, which is not built.
 */
object Corrections {
    /**
     * Captures compared per run, then the worker retries for the rest.
     *
     * Each comparison is one `match` and, for most captures, one short write;
     * stage two's own bound is the same figure for the same work plus duplicate
     * lookups, so this cannot take a run nearer WorkManager's ten-minute limit
     * than a full drain does.
     */
    const val MAX_COMPARED_PER_RUN = 2_000

    /** Rows per [RawCaptureDao.matchedStaleAfter]; bounds memory, not work. */
    private const val PAGE_SIZE = 100

    /** The last pack version whose sweep reached the end of the table. */
    private val COMPARED_PACK_VERSION = intPreferencesKey("compared_pack_version")

    /**
     * Where an unfinished sweep got to, and for which version. A cursor and not
     * the head of the set because a capture with a visible difference stays in
     * the set until it is answered: restarted from the head, a run would spend
     * its whole bound re-reading those and never reach the rest.
     */
    private val CURSOR_PACK_VERSION = intPreferencesKey("compared_cursor_pack_version")
    private val CURSOR_AFTER_ID = longPreferencesKey("compared_cursor_after_id")

    /** A column the user can see, which a re-read never changes unasked. */
    enum class Field { AMOUNT, DIRECTION, MERCHANT, CONFIDENCE, STATUS }

    /**
     * One difference, as [pending] offers it: the transaction as stored and as
     * the current pack would write it.
     *
     * [after] differs from [before] only in the parse-derived columns
     * [RawCaptureDao.rewriteParsedFields] names, so category, note and
     * exclusion are untouched by accepting it.
     */
    data class Correction(
        val captureId: Long,
        val fromPackVersion: Int,
        val toPackVersion: Int,
        val previousRuleId: String?,
        val ruleId: String,
        val before: Txn,
        val after: Txn,
        val changed: Set<Field>,
    )

    /** One sweep: captures compared, how many await an answer, and whether it stopped short. */
    data class Swept(val compared: Int, val offered: Int, val more: Boolean)

    internal sealed interface Reread {
        /** The current pack writes exactly what is stored. */
        data class Agrees(val ruleId: String) : Reread

        /** It differs only in columns nobody sees; [after] is applied unasked. */
        data class Silent(val ruleId: String, val after: Txn) : Reread

        data class Differs(val correction: Correction) : Reread

        /** No longer matched, or the row is not the parse's to change. */
        data object NotComparable : Reread
    }

    /**
     * What the current pack would write for [capture], against [txn].
     *
     * Spec 7.1's gate runs again on the new outcome, so a row re-read as a
     * card bill lands `PENDING` with `TRANSFER_SUSPECT` and is not excluded:
     * the exclusion is spec 7.3's prompt to answer, as it is on first read.
     * The duplicate condition is not re-derived -- layer two is a judgement
     * against the ledger as it stood -- but carried: a row that was a suspect
     * stays one.
     */
    internal fun reread(capture: RawCapture, txn: Txn, matcher: RuleMatcher, now: Long): Reread {
        // A REJECTED transaction is somebody's decision that it is not money.
        // `user_edited` is not asked here: [RawCaptureDao.matchedStaleAfter]
        // never returns an edited row, and the write refuses one.
        if (txn.state == TxnState.REJECTED) return Reread.NotComparable
        val outcome = matcher.match(
            pkg = capture.sourcePackage,
            title = capture.title,
            text = capture.text,
            bigText = capture.bigText,
        )
        if (outcome !is MatchOutcome.Matched) return Reread.NotComparable

        val merchant = MerchantColumns.of(outcome.merchantRaw, matcher.merchantNormalization)
        val decision = ConfidenceGate.decide(
            confidence = outcome.confidence,
            kind = outcome.kind,
            merchantRaw = merchant.raw,
            amountSen = outcome.amountSen,
            duplicateSuspect = capture.duplicateOfId != null ||
                txn.pendingReason == PendingReason.DUPLICATE_SUSPECT,
        )
        val after = txn.copy(
            amountSen = outcome.amountSen,
            direction = outcome.direction,
            merchantRaw = merchant.raw,
            merchantDisplay = merchant.display,
            merchantKey = merchant.key,
            confidence = outcome.confidence,
            state = decision.state,
            pendingReason = decision.reason,
            updatedAt = now,
        )
        val changed = buildSet {
            if (after.amountSen != txn.amountSen) add(Field.AMOUNT)
            if (after.direction != txn.direction) add(Field.DIRECTION)
            if (after.merchantDisplay != txn.merchantDisplay) add(Field.MERCHANT)
            if (after.confidence != txn.confidence) add(Field.CONFIDENCE)
            if (after.state != txn.state || after.pendingReason != txn.pendingReason) add(Field.STATUS)
        }
        val unseen = after.merchantRaw != txn.merchantRaw ||
            after.merchantKey != txn.merchantKey ||
            outcome.ruleId != capture.matchedRuleId
        return when {
            changed.isNotEmpty() -> Reread.Differs(
                Correction(
                    captureId = capture.id,
                    fromPackVersion = capture.packVersion,
                    toPackVersion = matcher.packVersion,
                    previousRuleId = capture.matchedRuleId,
                    ruleId = outcome.ruleId,
                    before = txn,
                    after = after,
                    changed = changed,
                ),
            )
            unseen -> Reread.Silent(outcome.ruleId, after)
            else -> Reread.Agrees(outcome.ruleId)
        }
    }

    /**
     * Compares up to [limit] captures, once per pack version, from where the
     * last run stopped. Agreeing captures are marked compared, silent
     * differences applied, and visible ones left for [pending].
     *
     * Stops before its next capture when [isStopped] or a delete or restore's
     * gate is up, as stage two does, so it never outlasts a gate by more than
     * one comparison. Every write is its own short transaction.
     */
    suspend fun sweep(
        context: Context,
        captures: RawCaptureDao,
        matcher: RuleMatcher,
        isStopped: () -> Boolean = { false },
        limit: Int = MAX_COMPARED_PER_RUN,
    ): Swept {
        val version = matcher.packVersion
        val prefs = context.captureStore.data.first()
        if ((prefs[COMPARED_PACK_VERSION] ?: 0) >= version) return Swept(0, 0, more = false)

        var afterId = if (prefs[CURSOR_PACK_VERSION] == version) prefs[CURSOR_AFTER_ID] ?: 0L else 0L
        var compared = 0
        var offered = 0
        var finished = false
        var damaged = false
        try {
            while (compared < limit) {
                val want = minOf(PAGE_SIZE, limit - compared)
                val page = captures.matchedStaleAfter(version, afterId, want)
                for (capture in page) {
                    if (isStopped() || Databases.gated) return Swept(compared, offered, more = true)
                    if (settle(captures, capture, matcher)) offered++
                    afterId = capture.id
                    compared++
                }
                if (page.size < want) {
                    finished = true
                    break
                }
            }
        } catch (failure: RuntimeException) {
            damaged = Databases.poisons(failure)
            throw failure
        } finally {
            // Written whatever ended the run, so a stop, a throw or a
            // cancellation resumes after the last capture compared rather than
            // from the head. NonCancellable, or a cancelled run's write is the
            // first thing the cancellation stops.
            //
            // **Damage ends this pack's sweep.** The page that threw is met
            // again by every connection, and the cursor cannot step past a
            // page whose ids it never read, so resuming would retire an
            // instance on every run. What was compared stands; the rest waits
            // for the next pack, or for a restore onto a file that reads.
            withContext(NonCancellable) {
                context.captureStore.edit {
                    it[CURSOR_PACK_VERSION] = version
                    it[CURSOR_AFTER_ID] = afterId
                    if (finished || damaged) it[COMPARED_PACK_VERSION] = version
                }
            }
        }
        return Swept(compared, offered, more = !finished)
    }

    /** One capture's comparison and its write. True when it awaits an answer. */
    private fun settle(captures: RawCaptureDao, capture: RawCapture, matcher: RuleMatcher): Boolean {
        val txn = captures.txnForCapture(capture.id) ?: return false
        return when (val reread = reread(capture, txn, matcher, System.currentTimeMillis())) {
            is Reread.Agrees -> {
                captures.markCompared(capture.id, capture.packVersion, matcher.packVersion, reread.ruleId)
                false
            }
            is Reread.Silent -> {
                try {
                    captures.applyReread(
                        captureId = capture.id,
                        fromPackVersion = capture.packVersion,
                        toPackVersion = matcher.packVersion,
                        ruleId = reread.ruleId,
                        corrected = reread.after,
                    )
                } catch (_: StaleCaptureException) {
                    // Answered by another writer since it was read.
                }
                false
            }
            is Reread.Differs -> true
            Reread.NotComparable -> false
        }
    }

    /**
     * Every correction awaiting an answer, oldest capture first; null while the
     * current version's [sweep] has not reached the end, because until then the
     * set this reads is mostly captures nobody has compared yet.
     */
    suspend fun pending(context: Context, captures: RawCaptureDao, matcher: RuleMatcher): List<Correction>? {
        val version = matcher.packVersion
        if ((context.captureStore.data.first()[COMPARED_PACK_VERSION] ?: 0) < version) return null
        val now = System.currentTimeMillis()
        val found = mutableListOf<Correction>()
        var afterId = 0L
        while (true) {
            val page = captures.matchedStaleAfter(version, afterId, PAGE_SIZE)
            for (capture in page) {
                val txn = captures.txnForCapture(capture.id) ?: continue
                val reread = reread(capture, txn, matcher, now)
                if (reread is Reread.Differs) found += reread.correction
            }
            if (page.size < PAGE_SIZE) return found
            afterId = page.last().id
        }
    }

    /**
     * Writes [correction]'s new values. False, having written nothing, when
     * the transaction has been edited or the capture answered since it was read.
     */
    fun accept(captures: RawCaptureDao, correction: Correction, now: Long = System.currentTimeMillis()): Boolean =
        try {
            captures.applyReread(
                captureId = correction.captureId,
                fromPackVersion = correction.fromPackVersion,
                toPackVersion = correction.toPackVersion,
                ruleId = correction.ruleId,
                corrected = correction.after.copy(updatedAt = now),
            )
        } catch (_: StaleCaptureException) {
            false
        }

    /**
     * Keeps the transaction as stored, and the rule that wrote it, until a
     * later pack reads the capture differently again.
     */
    fun decline(captures: RawCaptureDao, correction: Correction): Boolean =
        captures.markCompared(
            id = correction.captureId,
            fromPackVersion = correction.fromPackVersion,
            toPackVersion = correction.toPackVersion,
            ruleId = correction.previousRuleId,
        ) > 0

    /**
     * Tests only: record [packVersion]'s sweep as finished, for a suite about
     * stage two that must not have this sweep reading the pages it damages.
     */
    @VisibleForTesting
    internal suspend fun markSwept(context: Context, packVersion: Int) {
        context.captureStore.edit { it[COMPARED_PACK_VERSION] = packVersion }
    }

    /**
     * Tests only: forget every sweep's mark and cursor. The suite shares one
     * app, so the first test to finish a sweep would disable it for the rest.
     */
    @VisibleForTesting
    internal suspend fun forgetSweeps(context: Context) {
        context.captureStore.edit {
            it.remove(COMPARED_PACK_VERSION)
            it.remove(CURSOR_PACK_VERSION)
            it.remove(CURSOR_AFTER_ID)
        }
    }
}
