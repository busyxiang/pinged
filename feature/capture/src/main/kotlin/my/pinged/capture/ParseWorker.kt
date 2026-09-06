package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * STAGE TWO. Reads the `raw_capture` table, parses, and writes transactions.
 *
 * All of the work is [ParsePass]; this class is the WorkManager adapter and the
 * dependency wiring. The boundary between the stages is the table, not a queue
 * (spec 3), so this worker holds no state between runs.
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
        try {
            CaptureStorage.guarded(
                app,
                what = "Stage two cannot reach the database",
                unavailable = {
                    // Not transient, and not retryable: the key is gone, or the
                    // file will not open through it (spec 11.1's transferred
                    // device and invalidated key). `failure()` is the honest
                    // verdict. Nothing is lost by giving up -- every capture is
                    // still on the table at `NEW`, and the next successful open
                    // drains them, because a fresh worker is enqueued on the
                    // next notification.
                    Result.failure()
                },
            ) {
                val summary = ParsePass(
                    captures = Databases.rawCaptureDao(app),
                    txns = Databases.txnDao(app),
                    matcher = Graph.ruleMatcher(),
                    // requireUncategorizedId, not the nullable form: spec 7.1
                    // files an unknown merchant here, and a missing seed row
                    // must fail saying so rather than send a 0 down the money
                    // path into a foreign-key error far from the cause.
                    uncategorizedId = Databases.categoryDao(app).requireUncategorizedId(),
                    sourceLabel = sourceLabels(app),
                    isStopped = { isStopped },
                ).run()

                Log.i(
                    TAG,
                    "Stage two: ${summary.processed} processed, ${summary.failed} failed, " +
                        "remaining=${summary.remaining}",
                )
                if (summary.remaining) Result.retry() else Result.success()
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
     * `capture_source.label` for `txn.source_label` (spec 4), memoized for the
     * run. A batch is usually one or two packages, and the alternative is a
     * point query per capture for an answer that does not change.
     */
    private fun sourceLabels(context: Context): (String) -> String? {
        val dao = Databases.captureSourceDao(context)
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
