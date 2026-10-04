package my.pinged.capture

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore

/**
 * The one place that says what happens when the database cannot be opened,
 * or its connection has met damage.
 *
 * Spec 11.1's device-transfer state -- an intact database whose
 * Keystore-wrapped key is gone -- reaches every caller of `Databases`, because
 * `DatabaseFactory.build` is eager and throws from the first access. This
 * catches [DatabaseUnavailableException], the whole family: a corrupt file, a
 * full disk and an IO error all mean there is nowhere to put a capture. The
 * policy is stated once here -- record it, and hand the caller something it
 * can render.
 */
object CaptureStorage {
    private const val TAG = "CaptureStorage"

    /**
     * Run [block], or [unavailable] if the database cannot be opened or its
     * connection has met damage. A success clears the flag, so a restored key
     * makes the banner go away.
     *
     * **The open happens here, not in [block].** Clearing the flag is only
     * honest if the database was actually tried, and blocks that skip it are
     * normal: `CaptureDays`' two writers both return early once the day is
     * recorded, which is every notification but the first each day.
     *
     * **[Dispatchers.IO] covers the whole body, [block] included, and that is
     * the point rather than a detail of the open.** The open is blocking --
     * `DatabaseFactory.build` is eager and runs a `SELECT COUNT(*)` -- and the
     * callers include `MainActivity`, whose `lifecycleScope` is the main
     * thread and carries no `CoroutineExceptionHandler`. But every block that
     * goes on to query is blocking too, and Room's `assertNotMainThread`
     * throws a plain `IllegalStateException`: outside
     * [DatabaseUnavailableException], so the catch below cannot see it, and on
     * `viewModelScope`'s bare `SupervisorJob` that kills the process.
     * `GuardedDispatchTest` is the falsifying test that the hop belongs here;
     * `MainThreadRefreshTest` and `LaunchTest` pin the callers.
     *
     * ## A connection that has met damage
     *
     * Measured on emulator-5554, SQLCipher 4.18.0: a read that meets a
     * damaged page throws code 11, inside a transaction or out, and its
     * connection answers every statement after with code 26 until the
     * instance is closed -- a second instance opened beside it reads
     * normally. Any read on the shared instance can do that, the ledger feed
     * scrolling into an old month among them. Let through, code 26 from
     * [block] was measured to cost every notification after it -- one log
     * line each in the listener's handler, nothing stored, the storage flag
     * clear -- to keep stage two answering `retry()` for the life of the
     * process, and to kill the app from the ledger's refresh.
     *
     * So [Databases.poisons] from [block] -- after the open, which is what
     * separates it from code 26 at open, the unreadable state -- retires the
     * instance ([Databases.retire]), runs [block] again on a new one, and
     * records the damage for the settings screen. Every [block] here is safe
     * to run again: capture's writes are an insert that did not commit and
     * idempotent updates, the allow-list toggle an insert-if-new and two
     * targeted updates, and the rest are reads or stage two, whose work is
     * the table.
     *
     * **Which code decides whether to try again.** Code 11 is [block]'s own
     * statement reading the damaged page: on its own path, which a new
     * connection meets every time, so it is refused at once. Code 26 is a
     * connection some *other* read poisoned -- a screen reading a damaged
     * month -- and says nothing about [block]'s path, so it is tried again,
     * up to [ATTEMPTS] in all. One retry is not enough: a screen reading
     * beside the capture can poison the instance the retry just opened
     * before the retry's first statement runs, refusing a capture nothing of
     * whose reads is damaged. So a capture is refused for someone else's
     * damage only if [ATTEMPTS] instances in a row were each poisoned first,
     * by as many damaged reads.
     *
     * **An instance closed under [block]** answers code 21 or
     * `IllegalStateException`, and is tried again the same way, once the
     * instance [block] opened is seen to be closed; the gate then refuses
     * it, as if [block] had arrived a moment later. Nothing closes one under
     * the whole attempt's [Databases.leasing] lease but a `Databases.reset`
     * that has waited out its bound for it: a replacement for damage never
     * closes one, and a reset for a delete or a restore waits for the lease.
     *
     * **What this costs beside a damaged month on screen.** The ledger feed
     * rebinds onto each new instance and reads its damaged page there,
     * poisoning it again, so every guarded write after that meets code 26
     * once, replaces the instance once, and costs the feed one reload.
     * Measured on emulator-5554 beside the ledger open on a damaged month:
     * 121 of 121 captures took one retry, and [Databases.generation] moved
     * 121 times. Accepted: one open, 4ms (`OpenTest`), and one page a write,
     * bounded by the writes, while the damage is on record for the settings
     * screen, which is the way out.
     *
     * **[damageStopsCapture]** says whether a refusal raises the storage
     * flag. It does for the callers that store -- a capture damage refused is
     * a capture lost, and dropped silently it is this object's original bug
     * again -- and not for the screens' reads or stage two, whose damage is
     * recorded as damage: a damaged index under the backup nudge's count
     * would otherwise put "nothing new is being recorded" up on every
     * foreground while capture works.
     *
     * Memoized by [Databases], so after the first call this is a volatile read.
     */
    suspend fun <T> guarded(
        context: Context,
        what: String,
        unavailable: () -> T,
        damageStopsCapture: Boolean = true,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) { attempt(context, what, unavailable, damageStopsCapture, block) }

    /**
     * [guarded] without its hop to [Dispatchers.IO], for stage one's insert
     * alone. `PingedNotificationListener` runs capture on one thread so that
     * captures are written in the order they arrived, and the insert runs on
     * it; on the IO pool two could land out of order.
     */
    internal suspend fun <T> guardedInOrder(
        context: Context,
        what: String,
        unavailable: () -> T,
        block: suspend () -> T,
    ): T = attempt(context, what, unavailable, damageStopsCapture = true, block)

    private suspend fun <T> attempt(
        context: Context,
        what: String,
        unavailable: () -> T,
        damageStopsCapture: Boolean,
        block: suspend () -> T,
    ): T = Databases.leasing {
        var replaced = false
        var damaged = false
        try {
            lateinit var met: RuntimeException
            var attempts = 0
            for (attempt in 1..ATTEMPTS) {
                attempts = attempt
                val opened = Databases.shared(context)
                met = try {
                    val result = block()
                    // The record after the capture, so nothing suspends
                    // between the replacement and the capture it is for.
                    if (damaged) IntegrityStore.recordDamage(context)
                    CaptureHealth.clearStorageUnavailable(context)
                    return@leasing result
                } catch (thrown: DatabaseUnavailableException) {
                    throw thrown
                } catch (thrown: CancellationException) {
                    // An `IllegalStateException` too, and never a retry.
                    throw thrown
                } catch (thrown: RuntimeException) {
                    thrown
                }
                val ownPath = when {
                    Databases.poisons(met) -> {
                        damaged = true
                        if (Databases.retire(opened)) replaced = true
                        met is SQLiteDatabaseCorruptException
                    }
                    // `SQLException` is Room's code 21, the superclass of
                    // `SQLiteException`; the pool's own refusal is an
                    // `IllegalStateException`. Either only once the instance
                    // is seen closed, so a programming error is not retried.
                    (met is SQLException || met is IllegalStateException) && opened.closed -> false
                    else -> throw met
                }
                if (ownPath) break
                if (attempt < ATTEMPTS) {
                    Log.w(TAG, "$what: attempt $attempt met a connection it cannot use; trying a new one", met)
                }
            }
            Log.e(TAG, "$what: refused after $attempts attempts", met)
            if (damaged) IntegrityStore.recordDamage(context)
            if (damageStopsCapture) CaptureHealth.recordStorageUnavailable(context)
            unavailable()
        } catch (thrown: DatabaseUnavailableException) {
            Log.e(TAG, "$what: " + thrown.message, thrown)
            // A retry's open can meet a gate, or a file that will not open,
            // after an attempt met damage; that damage is still news.
            if (damaged) IntegrityStore.recordDamage(context)
            CaptureHealth.recordStorageUnavailable(context)
            unavailable()
        } finally {
            // After the retry, so it runs on the new instance before the
            // feed, which rebinds on this, can reach it: a feed whose next
            // page is the damaged one would otherwise poison it first.
            if (replaced) Databases.announceReplacement()
        }
    }

    /**
     * How many instances [guarded] tries before it refuses: the first, and
     * a new one for each that answered code 26 or had been closed.
     *
     * **Each damaged read beside a capture poisons about one new instance**,
     * and in step with it: a reader that meets code 26 retries onto the same
     * fresh instance the capture's retry opens, and its own read poisons it.
     * Measured on emulator-5554, a capture beside damaged readers reading in
     * a transaction, as the ledger feed's first page does, 60 rounds each:
     * at 2 attempts, 40 of 60 captures refused beside one reader; at 4, none
     * beside one and 37 and 48 of 60 beside three and six; at 8, none beside
     * one, three or six, with some beside six needing all eight. A screen
     * resuming reads the month, the feed and the backup nudge's count --
     * three -- so eight holds twice that. Each attempt is one open, 4ms
     * (`OpenTest`).
     */
    internal const val ATTEMPTS = 8
}
