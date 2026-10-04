package my.pinged.capture

import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.PingedDatabase
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * STAGE TWO. Reads the `raw_capture` table, parses, and writes transactions.
 *
 * All of the work is [ParsePass]; this class is the WorkManager adapter and the
 * dependency wiring. The boundary between the stages is the table, not a queue
 * (spec 3), so this worker holds none of the queue's state between runs --
 * only [KnownDamage], which says what not to read again.
 *
 * No constraints on the request: stage two needs no network -- the app has no
 * network permission -- and gating it on anything would leave captured money
 * unparsed while the condition was unmet.
 */
class ParseWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    /**
     * `Dispatchers.IO`, not `CoroutineWorker`'s default `Dispatchers.Default`.
     * Every call in the pass is a blocking SQLCipher statement, and a SQLCipher
     * insert costs 5-20ms (spec 3), so running them on the CPU-bound pool would
     * park its threads on IO.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        // **The generic catch is outside the guard, and that ordering is the point.**
        //
        // `DatabaseUnavailableException` extends `IllegalStateException`, so a
        // `catch (Exception)` *within* the guarded block caught the missing key before
        // `CaptureStorage.guarded` could see it. The `unavailable` lambda below then
        // became unreachable, so the worker answered a permanently dead database with
        // `retry()` -- the ~131 wakeups a day the `enqueue` note explains `failure()`
        // exists to avoid -- and `guarded` saw the block return normally and called
        // `clearStorageUnavailable`, wiping the flag the listener had just set.
        //
        // Nested this way the guard is innermost and decides the storage case first.
        // Outside the guard, so that a capture found reading damage stays
        // skipped when the guard runs the block again on a new instance.
        val progress = KnownDamage.progress()
        try {
            CaptureStorage.guarded(
                app,
                what = "Stage two cannot reach the database",
                unavailable = {
                    // Not transient, and not retryable: the key is gone, the
                    // file will not open through it (spec 11.1's transferred
                    // device and invalidated key), or damage lies on the
                    // drain's own path, which a new connection met again.
                    // `failure()` is the honest verdict. Nothing is lost by
                    // giving up -- every capture is still on the table at
                    // `NEW`, and the next successful open drains them, because
                    // a fresh worker is enqueued on the next notification.
                    Result.failure()
                },
                // Damage on stage two's path is damage, recorded as such for
                // the settings screen, and not capture stopping: the listener
                // is storing beside it, and a banner saying otherwise came and
                // went with every notification.
                damageStopsCapture = false,
            ) {
                val captures = Databases.rawCaptureDao(app)
                val matcher = Graph.ruleMatcher()

                // Before the drain, so what it requeues is parsed by this same
                // pass rather than waiting for the next notification.
                val swept = Reparse.sweep(app, captures, matcher.packVersion)

                // Also before the drain, and for a different reason: a database
                // damaged enough that the drain throws is exactly the one whose
                // verdict has to reach the settings screen, and a check placed
                // after the drain never runs on it. On an instance of its own
                // (`Integrity.check`), so finding the damage leaves the drain's
                // connection as it was.
                //
                // The check re-reads the gate, so this does add a failure the
                // lines above cannot have hit: a spec 11.3 delete or 11.2
                // restore starting after that DAO was resolved makes this throw
                // `DatabaseBeingDeletedException`. `CaptureStorage.guarded`
                // catches it -- it is a `DatabaseUnavailableException` -- and
                // answers `Result.failure()`. Nothing is lost by that: every
                // capture is still at `NEW`, and `APPEND_OR_REPLACE` gives the
                // next notification a run that starts after the delete.
                PeriodicIntegrity.checkIfDue(app, System.currentTimeMillis())

                val summary = drain(app, matcher, progress)
                // Only for damage this run met: what [KnownDamage] already
                // held was recorded by the run that found it.
                if (progress.replacements > 0) IntegrityStore.recordDamage(app)

                Log.i(
                    TAG,
                    "Stage two: ${summary.processed} processed, ${summary.failed} failed, " +
                        "${progress.undecidable.size} undecidable, ${progress.replacements} replaced, " +
                        "requeued=${swept.moved}, remaining=${summary.remaining}",
                )
                // `swept.more` as well as the drain's own answer: what a full
                // sweep batch left behind is still at a settled status, so no
                // later capture schedules a run on its behalf.
                if (summary.remaining || swept.more) Result.retry() else Result.success()
            }
        } catch (failure: Exception) {
            // A pack that will not load, an absent seed, a transient database
            // error. Spec 11.1 says the app never crash-loops, and every
            // capture is still on the table at `NEW`, so a retry is both
            // honest and harmless.
            Log.e(TAG, "Stage two could not run", failure)
            Result.retry()
        }
    }

    /**
     * [ParsePass], run again on a new instance each time a capture's reads
     * meet damage, until one finishes.
     *
     * **Why not the guard's retry.** A capture whose own row is on a damaged
     * page meets the damage every time it is parsed, and sorts where it
     * did: measured on emulator-5554, the capture behind it was still at
     * `NEW` after two runs, each refused. Each [ParsePass.DamagedCaptureException]
     * records its capture in [progress], which later passes skip or parse
     * without the lookup that met the damage, so this ends; and every pass
     * shares [progress], so the run's row bound and its position in the
     * queue hold across them.
     *
     * **At most [MAX_REPLACEMENTS_PER_RUN] replacements**, then `retry()`:
     * each instance replaced stays open until the run's lease ends --
     * three file descriptors each (`SharedInstanceUnderUseTest`) -- and the
     * next run skips what this one found, from [KnownDamage].
     *
     * **Announced once, after the last pass**, as `CaptureStorage.guarded`
     * does after its retry: announced at each replacement, the feed rebinds
     * onto the new instance and can read its damaged page there before the
     * next pass has run on it.
     */
    private fun drain(app: Context, matcher: my.pinged.parse.RuleMatcher, progress: ParsePass.Progress): ParsePass.Summary {
        var replaced = false
        try {
            while (true) {
                val db = Databases.shared(app)
                try {
                    return ParsePass(
                        captures = db.rawCaptureDao(),
                        txns = db.txnDao(),
                        matcher = matcher,
                        // requireUncategorizedId, not the nullable form: spec 7.1
                        // files an unknown merchant here, and a missing seed row
                        // must fail saying so rather than send a 0 down the money
                        // path into a foreign-key error far from the cause.
                        uncategorizedId = db.categoryDao().requireUncategorizedId(),
                        sourceLabel = sourceLabels(db),
                        isStopped = { isStopped },
                        progress = progress,
                    ).run()
                } catch (skipped: ParsePass.DamagedCaptureException) {
                    Log.e(TAG, "Stage two meets a damaged page at capture ${skipped.captureId}", skipped)
                    if (Databases.retire(db)) replaced = true
                    if (++progress.replacements >= MAX_REPLACEMENTS_PER_RUN) {
                        return progress.summary(remaining = true)
                    }
                }
            }
        } finally {
            if (replaced) Databases.announceReplacement()
        }
    }

    /**
     * `capture_source.label` for `txn.source_label` (spec 4), memoized for the
     * run. A batch is usually one or two packages, and the alternative is a
     * point query per capture for an answer that does not change.
     */
    private fun sourceLabels(db: PingedDatabase): (String) -> String? {
        val dao = db.captureSourceDao()
        val cache = HashMap<String, String?>()
        return { pkg -> cache.getOrPut(pkg) { dao.byPackage(pkg)?.label } }
    }

    companion object {
        private const val TAG = "PingedParse"

        /**
         * The unique work name. Internal rather than private so the tests can
         * cancel anything the listener scheduled before driving the worker
         * themselves.
         */
        internal const val UNIQUE_NAME = "pinged.parse"

        /** [drain]'s bound: [ParsePass.BATCH_SIZE], one batch of damage. */
        internal const val MAX_REPLACEMENTS_PER_RUN = ParsePass.BATCH_SIZE

        /**
         * Asks for a stage-two run. Called by the listener after a row is actually
         * written, so a notification from a package the user has not enabled schedules
         * nothing.
         *
         * `APPEND_OR_REPLACE`, not `KEEP`. With `KEEP` a request arriving while a run
         * is finishing is dropped, and if that run has passed its last `claimNext` the
         * new row sits at `NEW` until some later notification schedules another run --
         * a queue stalled by a race. Appending guarantees a run that starts after this
         * row was written; `OR_REPLACE` covers an existing chain that has already
         * failed or been cancelled, which `APPEND` alone refuses to extend.
         *
         * Linear backoff at WorkManager's floor rather than the default 30-second
         * exponential: a retry here means captured money is not in the ledger yet, and
         * the reasons a run reports work remaining are a row cap and a transient
         * database failure, neither helped by waiting longer each time.
         *
         * That is right for "there is more work", and it is why the permanent failure
         * above returns `failure()` rather than `retry()`. Linear at a 10-second floor
         * gives `10s x attempt`, so cumulative time to attempt n is `5n^2` seconds: a
         * permanently unopenable database would wake this worker roughly **131 times in
         * the first day** and take about half a year to reach WorkManager's five-hour
         * cap.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ParseWorker>()
                    .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    .build(),
            )
        }
    }
}

/**
 * What stage two has learned about the file's damage, for as long as the
 * file: the captures [ParsePass.Progress] names undecidable or unpaired.
 *
 * **Why it outlives a run.** Without it every run met each undecidable
 * capture again -- one open, one poisoned instance and one replacement
 * each, on every notification's run and every `retry()` -- and a damaged
 * row is damaged until the file is replaced. Not the queue's state, which is
 * the table's alone (spec 3): losing this, as a new process does, costs one
 * run meeting the damage again. What it does not cover: a capture behind
 * more than [ParseWorker.MAX_REPLACEMENTS_PER_RUN] undecidable ones waits for
 * a second run in the same process, so a process killed after every run
 * would keep it waiting. Not persisted, because nothing outside the file
 * can say which file its ids name.
 *
 * **Keyed on `Databases.rewrites`**, which a delete or a restore moves: ids
 * name rows of one file, and after a restore they name other rows.
 */
internal object KnownDamage {
    private var file = -1L
    private val undecidable: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val unpaired: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Synchronized
    fun progress(): ParsePass.Progress {
        val now = Databases.rewrites.value
        if (now != file) {
            undecidable.clear()
            unpaired.clear()
            file = now
        }
        return ParsePass.Progress(undecidable, unpaired)
    }
}
