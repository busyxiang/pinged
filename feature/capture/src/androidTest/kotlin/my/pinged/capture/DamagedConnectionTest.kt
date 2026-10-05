package my.pinged.capture

import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capture on a ledger with one damaged page.
 *
 * Measured on emulator-5554, SQLCipher 4.18.0: a read that meets the damage
 * outside a transaction throws code 11 and leaves its connection answering
 * every later statement with code 26, capture's included, until the
 * instance is closed. `PRAGMA integrity_check` is such a read: run on the
 * shared instance, finding damage cost every notification that followed --
 * one line each in the listener's handler, nothing stored, the storage flag
 * clear.
 *
 * The damage is on a middle `raw_capture` leaf, which nothing capture does
 * touches, so a capture failing here is failing on the connection and not on
 * the page -- except in [aCaptureWhoseOwnTableIsDamagedIsRecordedAndNotRetriedForever],
 * which damages the page capture reads.
 */
@RunWith(AndroidJUnit4::class)
class DamagedConnectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startClean() = runBlocking<Unit> {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        CaptureCaches.clear(context)
        IntegrityStore.forget(context)
        CaptureFixtures.cancelStageTwo(context)
        // Spec 5.5's third mode reads every MATCHED row once per pack, the
        // damaged ones included; what is under test here is stage two's drain.
        Corrections.markSwept(context, Graph.ruleMatcher().packVersion)
    }

    /** The damaged file must not outlive this class: every other one shares `pinged.db`. */
    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        CaptureFixtures.cancelStageTwo(context)
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        CaptureCaches.clear(context)
        IntegrityStore.forget(context)
    }

    /**
     * The headline. The weekly check runs where production runs it -- the
     * first stage-two run of the week -- finds the damage, and the next
     * notification from an enabled bank is still stored.
     */
    @Test(timeout = 60_000)
    fun aNotificationAfterTheWeeklyCheckFindsDamageIsCaptured() = runBlocking<Unit> {
        damagedLedger()
        ParseFixtures.runWorker(context)
        assertTrue(
            "the weekly check did not record the damage, so this test proves nothing",
            IntegrityStore.damaged(context),
        )
        val before = rowsOnAnInstanceOfItsOwn()

        val stored = ingestOne("Payment of RM3.00 to AFTER THE CHECK successful")

        assertNotNull("the notification after the weekly check was not stored", stored)
        assertEquals("no row reached the table", before + 1, rowsOnAnInstanceOfItsOwn())
    }

    /**
     * **The weekly check leaves the shared instance as it found it.** A check
     * run on it poisons it, and every capture after that has to replace it.
     * Run on an instance of its own, the
     * check cannot touch the one capture writes through.
     */
    @Test(timeout = 60_000)
    fun theWeeklyCheckLeavesTheSharedDatabaseAsItWas() = runBlocking<Unit> {
        damagedLedger()
        val shared = Databases.shared(context)
        val generation = Databases.generation.value

        ParseFixtures.runWorker(context)

        assertTrue("the weekly check did not run, so this test proves nothing", IntegrityStore.damaged(context))
        val read = runCatching { shared.captureSourceDao().all() }
        assertNull("a read on the shared instance after the check threw ${read.exceptionOrNull()}", read.exceptionOrNull())
        assertSame("the check replaced the shared instance rather than leaving it alone", shared, Databases.shared(context))
        assertEquals("the check replaced the shared instance", generation, Databases.generation.value)
    }

    /**
     * The ledger feed scrolling into an old month, or any other read that
     * meets the damage outside a transaction: the shared instance is poisoned
     * without anything having checked. The next capture replaces it, is
     * stored, and records the damage where the settings screen reads it.
     */
    @Test(timeout = 60_000)
    fun aNotificationAfterAReadMetDamageIsCapturedAndTheDamageRecorded() = runBlocking<Unit> {
        damagedLedger()
        poisonTheSharedInstance()
        val before = rowsOnAnInstanceOfItsOwn()

        val stored = runCatching { ingestOne("Payment of RM4.00 to AFTER A READ successful") }

        assertNull("the capture threw ${stored.exceptionOrNull()}", stored.exceptionOrNull())
        assertNotNull("the notification after a damaged read was not stored", stored.getOrNull())
        assertEquals("no row reached the table", before + 1, rowsOnAnInstanceOfItsOwn())
        assertFalse(
            "a capture that was stored left the storage flag up",
            CaptureHealth.storageUnavailable(context),
        )
        assertTrue(
            "the capture met a poisoned connection and did not record the damage behind it",
            IntegrityStore.damaged(context),
        )
    }

    /**
     * Stage two on a poisoned instance. Answering `retry()` there is measured
     * at four attempts in 65 s through WorkManager, every one failing on code
     * 26 for as long as the process lives, while the captures it is there to
     * parse sit at `NEW`.
     */
    @Test(timeout = 60_000)
    fun stageTwoOnAPoisonedConnectionParsesRatherThanRetrying() = runBlocking<Unit> {
        damagedLedger()
        // Not due, so the weekly check is not what this run meets.
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        val id = DatabaseFactory.build(context).let { own ->
            try {
                own.rawCaptureDao().insert(newCapture("Payment of RM5.00 to 99 SPEEDMART successful"))
            } finally {
                own.close()
            }
        }
        poisonTheSharedInstance()

        val result = ParseFixtures.runWorker(context)

        assertEquals("stage two on a poisoned connection", ListenableWorker.Result.success(), result)
        val status = DatabaseFactory.build(context).let { own ->
            try { own.rawCaptureDao().byId(id).parseStatus } finally { own.close() }
        }
        assertEquals("the capture was not parsed", ParseStatus.MATCHED, status)
    }

    /**
     * Spec 5.5's third mode walks every `MATCHED` row, which is where damage
     * on a ledger usually is. Meeting it is damage recorded and the instance
     * replaced -- not a failed run when the drain succeeded -- and it ends
     * this pack's sweep, so the next run does not meet the page again.
     */
    @Test(timeout = 60_000)
    fun theMatchedRereadMeetingDamageDoesNotFailTheRunAndIsNotRepeated() = runBlocking<Unit> {
        damagedLedger()
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        Corrections.forgetSweeps(context)
        val generation = Databases.generation.value

        assertEquals("stage two failed for damage its drain never met", ListenableWorker.Result.success(), ParseFixtures.runWorker(context))

        assertTrue("the sweep met no damage, so this test proves nothing", Databases.generation.value > generation)
        assertTrue("the damage the sweep met was not recorded", IntegrityStore.damaged(context))
        val replaced = Databases.generation.value
        assertEquals(ListenableWorker.Result.success(), ParseFixtures.runWorker(context))
        assertEquals("the next run met the same damaged page again", replaced, Databases.generation.value)
    }

    /**
     * Damage on the page capture itself reads: `capture_source`, the
     * allow-list. A fresh connection meets it again, so the capture cannot be
     * stored -- and that has to be recorded, not dropped, and not tried
     * again: code 11 is the capture's own read meeting the damage, and one
     * replacement, of the instance that read poisoned, is all it costs.
     */
    @Test(timeout = 60_000)
    fun aCaptureWhoseOwnTableIsDamagedIsRecordedAndNotRetriedForever() = runBlocking<Unit> {
        damagedLedger(tree = "capture_source")
        val generation = Databases.generation.value

        val stored = runCatching { ingestOne("Payment of RM6.00 to OWN PAGE successful") }

        assertNull("the capture threw ${stored.exceptionOrNull()} rather than being refused", stored.exceptionOrNull())
        assertNull("a capture was stored past a damaged allow-list", stored.getOrNull())
        assertTrue(
            "a capture that damage refused left no record, so it was dropped silently",
            CaptureHealth.storageUnavailable(context),
        )
        assertTrue("the damage behind the refusal was not recorded", IntegrityStore.damaged(context))
        assertEquals(
            "a capture refused by damage on its own path announced a replacement other than once",
            generation + 1,
            Databases.generation.value,
        )
    }

    /**
     * **Code 11 is not tried again.** It is the block's own read meeting
     * the damage, which every new connection meets as well; tried again, each
     * attempt poisons one more instance to learn nothing.
     */
    @Test(timeout = 60_000)
    fun aBlockWhoseOwnReadMeetsTheDamageIsNotTriedAgain() = runBlocking<Unit> {
        damagedLedger(tree = "capture_source")
        var attempts = 0

        val result = CaptureStorage.guarded(context, "a capture", unavailable = { "refused" }) {
            attempts++
            Databases.captureSourceDao(context).all()
            "stored"
        }

        assertEquals("a block whose own read is damaged", "refused", result)
        assertEquals("attempts at a block whose own read is damaged", 1, attempts)
    }

    /**
     * **A retry whose new instance another read poisons first is tried
     * again.** A screen reading a damaged month beside a capture can poison
     * the instance the capture's retry has just opened before the retry's
     * statement runs; the capture's own path is clean, and with one retry it
     * was refused. Deterministic: the block itself plays the other reader,
     * on the retry and before its own statement.
     */
    @Test(timeout = 60_000)
    fun aRetryWhoseNewInstanceAnotherReadPoisonsFirstIsTriedAgain() = runBlocking<Unit> {
        damagedLedger()
        poisonTheSharedInstance()
        var attempts = 0

        val result = runCatching {
            CaptureStorage.guarded(context, "a capture", unavailable = { "refused" }) {
                attempts++
                if (attempts == 2) poisonTheSharedInstance()
                Databases.captureSourceDao(context).all()
                "stored"
            }
        }

        assertNull("the capture threw ${result.exceptionOrNull()}", result.exceptionOrNull())
        assertEquals("a capture whose own path is clean, after $attempts attempts", "stored", result.getOrNull())
        assertFalse("a capture that was stored left the storage flag up", CaptureHealth.storageUnavailable(context))
    }

    /**
     * **And not for ever.** A block every one of whose instances is
     * poisoned before its statement is refused after
     * [CaptureStorage.ATTEMPTS], with the refusal recorded.
     */
    @Test(timeout = 60_000)
    fun aBlockWhoseEveryInstanceIsPoisonedFirstIsRefusedAfterABoundedNumber() = runBlocking<Unit> {
        damagedLedger()
        var attempts = 0

        val result = CaptureStorage.guarded(context, "a capture", unavailable = { "refused" }) {
            attempts++
            // Past the bound it stops poisoning, so a guard that kept going
            // stores and fails the assertion rather than hanging the test.
            if (attempts <= 10) poisonTheSharedInstance()
            Databases.captureSourceDao(context).all()
            "stored"
        }

        assertEquals("a block poisoned on every attempt", "refused", result)
        assertEquals("attempts before the refusal", CaptureStorage.ATTEMPTS, attempts)
        assertTrue("the refusal left no record", CaptureHealth.storageUnavailable(context))
    }

    /**
     * **A replacement a gate has risen over is announced by the gate.** A
     * delete or a restore raising its gate between a guarded block's
     * replacement and the block's end: announced there, [Databases.generation]
     * moved mid-delete, and every holder rebinding on it met the gate and drew
     * an unreadable ledger over a delete about to succeed.
     */
    @Test(timeout = 60_000)
    fun aReplacementAGateRisesOverIsLeftForTheGateToAnnounce() = runBlocking<Unit> {
        damagedLedger()
        poisonTheSharedInstance()
        val lower = CountDownLatch(1)
        var during = -1L
        var beforeLowering = -2L
        var delete: Thread? = null
        var attempts = 0

        CaptureStorage.guarded(context, "a capture", unavailable = { Unit }) {
            attempts++
            Databases.captureSourceDao(context).all()
            // The retry has read; the gate rises before the block ends. Its
            // reset then waits for this block's lease, so the block cannot
            // wait for anything the delete does after it.
            delete = thread {
                Databases.whileDeleting {
                    lower.await(10, TimeUnit.SECONDS)
                    beforeLowering = Databases.generation.value
                }
            }
            val until = System.nanoTime() + 10_000_000_000L
            while (!Databases.gated) check(System.nanoTime() < until) { "the gate never rose" }
            during = Databases.generation.value
        }
        lower.countDown()
        delete?.join()

        assertEquals("fixture: the block did not meet the poison and retry", 2, attempts)
        assertEquals("the replacement was announced while the gate was up", during, beforeLowering)
    }

    /**
     * **A capture whose duplicate lookup would read the damage is decided
     * without reading it.** A notification repeating, an hour on, one whose
     * row is on the damaged page: layer one finds that row off its indexes,
     * so the repeat is the duplicate suspect it would be on a healthy
     * ledger, and nothing is replaced. Reading the row, the lookup met code
     * 11 on every run and the repeat stayed at `NEW` for good.
     */
    @Test(timeout = 60_000)
    fun aCaptureWhoseLookupWouldReadTheDamageIsDecidedWithoutReadingIt() = runBlocking<Unit> {
        damagedLedger()
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        val original = firstDamagedRow()
        val repeat = aCaptureRepeating(original)
        val behind = ingestOne("Payment of RM7.00 to BEHIND THE DAMAGE successful")
        assertNotNull("the capture behind was not stored", behind)
        val generation = Databases.generation.value

        val result = ParseFixtures.runWorker(context)

        assertEquals("the capture behind the repeat", ParseStatus.MATCHED, statusOf(behind!!))
        assertEquals("the repeat of a damaged row", ParseStatus.MATCHED, statusOf(repeat))
        val (reason, duplicateOf) = DatabaseFactory.build(context).let { own ->
            try {
                val txn = own.rawCaptureDao().txnIdForCapture(repeat)?.let { own.txnDao().byId(it) }
                txn?.pendingReason to own.rawCaptureDao().byId(repeat).duplicateOfId
            } finally {
                own.close()
            }
        }
        assertEquals("the repeat's reason for review", PendingReason.DUPLICATE_SUSPECT, reason)
        assertEquals("the repeat names the row it repeats", original, duplicateOf)
        assertEquals("stage two's run", ListenableWorker.Result.success(), result)
        assertEquals("the lookup replaced the shared instance", generation, Databases.generation.value)
    }

    /**
     * **And past a capture whose own row is on the damaged page**, which the
     * claim reads: every capture on that page is skipped, and the one after
     * them parsed.
     */
    @Test(timeout = 60_000)
    fun stageTwoGetsPastCapturesWhoseOwnRowsAreDamaged() = runBlocking<Unit> {
        val onTheDamage = damagedLedger(newOnTheDamagedLeaf = true)
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        val behind = ingestOne("Payment of RM8.00 to AFTER THE PAGE successful")
        assertNotNull("the capture behind was not stored", behind)

        val result = ParseFixtures.runWorker(context)

        assertEquals("the capture behind the damaged page", ParseStatus.MATCHED, statusOf(behind!!))
        assertEquals("stage two's run", ListenableWorker.Result.success(), result)
        assertTrue("fixture: no capture was left on the damaged page", onTheDamage.isNotEmpty())
    }

    /**
     * **Rebinds of a notification whose row is damaged stop nothing.** The
     * listener stores a still-posted notification again on every rebind, in
     * the original's slot and at its post time, so every copy sorts ahead of
     * whatever arrived since; layer one's lookup of the original read the
     * damaged page for each, and past [ParsePass.BATCH_SIZE] copies every
     * run ended at the head of the queue, `success()`, with nothing parsed.
     * The copies are refreshes of a row stage two has already decided, and
     * are recorded as that without their lookup reading it.
     */
    @Test(timeout = 120_000)
    fun rebindsOfANotificationWhoseRowIsDamagedDoNotStopStageTwo() = runBlocking<Unit> {
        damagedLedger()
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        val original = firstDamagedRow()
        val copies = rebindCopiesOf(original, REBINDS)
        val behind = ingestOne("Payment of RM7.00 to BEHIND THE REBINDS successful")
        assertNotNull("the capture behind was not stored", behind)
        val generation = Databases.generation.value

        val result = ParseFixtures.runWorker(context)

        assertEquals("the capture behind $REBINDS rebinds of a damaged row", ParseStatus.MATCHED, statusOf(behind!!))
        assertEquals(
            "the rebind copies of a row already decided",
            List(REBINDS) { ParseStatus.UPDATE_OF },
            copies.map(::statusOf),
        )
        assertEquals("stage two's run", ListenableWorker.Result.success(), result)
        assertEquals("the copies' lookups replaced the shared instance", generation, Databases.generation.value)
    }

    /**
     * **A copy of a notification whose own row cannot be decided carries its
     * money, for review.** The original is `NEW` on the damaged page, so no
     * transaction of it exists and none can be written; dropping the copy as
     * a refresh of it loses the money, and parsing it plainly counts it
     * twice if the page is ever read again. The first copy goes to the
     * inbox as a duplicate suspect of the original, and the copies after it
     * are refreshes of that one.
     */
    @Test(timeout = 120_000)
    fun copiesOfAnUndecidableCaptureCarryItsMoneyToReviewOnce() = runBlocking<Unit> {
        val onTheDamage = damagedLedger(newOnTheDamagedLeaf = true)
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        val original = onTheDamage.first()
        val copies = rebindCopiesOf(original, 3)

        val result = ParseFixtures.runWorker(context)

        assertTrue("the original on the damaged page was given a verdict", original in stillQueued())
        assertEquals(
            "the copies of a capture that cannot be decided",
            listOf(ParseStatus.MATCHED, ParseStatus.UPDATE_OF, ParseStatus.UPDATE_OF),
            copies.map(::statusOf),
        )
        val (state, reason, duplicateOf) = DatabaseFactory.build(context).let { own ->
            try {
                val txn = own.rawCaptureDao().txnIdForCapture(copies.first())?.let { own.txnDao().byId(it) }
                Triple(txn?.state, txn?.pendingReason, own.rawCaptureDao().byId(copies.first()).duplicateOfId)
            } finally {
                own.close()
            }
        }
        assertEquals("the first copy's transaction", TxnState.PENDING, state)
        assertEquals("the first copy's reason for review", PendingReason.DUPLICATE_SUSPECT, reason)
        assertEquals("the first copy names the original", original, duplicateOf)
        assertEquals("stage two's run", ListenableWorker.Result.success(), result)
    }

    /**
     * **Rule two's repeat of a capture that cannot be decided carries its
     * money too.** The same content from another slot inside sixty seconds
     * is a `DUPLICATE_OF` with no transaction; of an original on the
     * damaged page, which has none and can write none, that is the money
     * gone. A suspect naming the original instead.
     */
    @Test(timeout = 120_000)
    fun aRepeatFromAnotherSlotOfAnUndecidableCaptureGoesToReview() = runBlocking<Unit> {
        val original = damagedLedger(newOnTheDamagedLeaf = true).first()
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        val repeat = DatabaseFactory.build(context).let { own ->
            try {
                own.rawCaptureDao().insert(
                    settled((original - 1).toInt()).copy(parseStatus = ParseStatus.NEW, sbnKey = "0|another slot"),
                )
            } finally {
                own.close()
            }
        }

        val result = ParseFixtures.runWorker(context)

        assertEquals("the repeat from another slot", ParseStatus.MATCHED, statusOf(repeat))
        val (reason, duplicateOf) = DatabaseFactory.build(context).let { own ->
            try {
                val txn = own.rawCaptureDao().txnIdForCapture(repeat)?.let { own.txnDao().byId(it) }
                txn?.pendingReason to own.rawCaptureDao().byId(repeat).duplicateOfId
            } finally {
                own.close()
            }
        }
        assertEquals("its reason for review", PendingReason.DUPLICATE_SUSPECT, reason)
        assertEquals("it names the original", original, duplicateOf)
        assertEquals("stage two's run", ListenableWorker.Result.success(), result)
    }

    /**
     * **More damage than one run may replace for is worked through by the
     * runs after it, and met once.** Each capture whose row is damaged costs
     * a replacement, and each replaced instance stays open until the run's
     * lease ends, so a run stops at [ParseWorker.MAX_REPLACEMENTS_PER_RUN]
     * and asks for another; the next skips what the first found, unread, and
     * gets past the rest to the capture behind. Announced once a run, after
     * its last pass, and not again by a run that meets nothing new.
     */
    @Test(timeout = 120_000)
    fun damageBeyondOneRunsBoundIsWorkedThroughByTheNextAndMetOnce() = runBlocking<Unit> {
        val onTheDamage = damagedLedger(newOnTheDamagedLeaf = true, leaves = 4)
        assertTrue(
            "fixture: ${onTheDamage.size} captures on the damage, not past one run's bound",
            onTheDamage.size > ParseWorker.MAX_REPLACEMENTS_PER_RUN,
        )
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)
        val behind = ingestOne("Payment of RM8.00 to PAST THE BOUND successful")
        assertNotNull("the capture behind was not stored", behind)

        val before = Databases.generation.value
        val first = ParseFixtures.runWorker(context)
        val afterFirst = Databases.generation.value
        val second = ParseFixtures.runWorker(context)
        val afterSecond = Databases.generation.value
        val third = ParseFixtures.runWorker(context)

        assertEquals("the first run, past its bound", ListenableWorker.Result.retry(), first)
        assertEquals("the capture behind the damage, after the second run", ParseStatus.MATCHED, statusOf(behind!!))
        assertEquals("the second run", ListenableWorker.Result.success(), second)
        assertEquals("the first run's replacements were announced other than once", before + 1, afterFirst)
        assertEquals("a third run met damage the first two had found", afterSecond, Databases.generation.value)
        assertEquals("the third run", ListenableWorker.Result.success(), third)
    }

    /**
     * **A capture whose layer-two lookup meets damage is a suspect, not
     * stuck.** A transaction on the damaged page may be this purchase seen
     * through another app, and nothing can say; parsed as new it could
     * count twice unremarked, and skipped it is money left out for as long
     * as the page is damaged. So it goes to the inbox with no pair named.
     */
    @Test(timeout = 120_000)
    fun aCaptureWhoseLayerTwoLookupMeetsDamageGoesToReviewWithNoPair() = runBlocking<Unit> {
        val at = System.currentTimeMillis()
        damagedTxnsAround(at)
        IntegrityStore.record(context, at = at, ok = true)
        val capture = ingestOne("Payment of RM1.00 to BESIDE DAMAGED TXNS successful", at = at)
        assertNotNull("the capture was not stored", capture)

        val result = ParseFixtures.runWorker(context)

        assertEquals("the capture whose layer-two lookup met damage", ParseStatus.MATCHED, statusOf(capture!!))
        val (state, reason, duplicateOf) = DatabaseFactory.build(context).let { own ->
            try {
                val txn = own.rawCaptureDao().txnIdForCapture(capture)?.let { own.txnDao().byId(it) }
                Triple(txn?.state, txn?.pendingReason, own.rawCaptureDao().byId(capture).duplicateOfId)
            } finally {
                own.close()
            }
        }
        assertEquals("its transaction", TxnState.PENDING, state)
        assertEquals("its reason for review", PendingReason.DUPLICATE_SUSPECT, reason)
        assertNull("it names a pair nothing could read", duplicateOf)
        assertEquals("stage two's run", ListenableWorker.Result.success(), result)
    }

    /**
     * **A capture whose own row reads but whose layer-one lookup meets
     * damage carries its money, for review.** The index layer one reads is
     * damaged where the capture's content hash is: its row, the queue and
     * its commit all read. Left undecided, as a capture whose own row is
     * damaged must be, its money stayed out of the ledger for as long as the
     * index was damaged; parsed as new, it counts twice whenever it is the
     * refresh the lookup would have found. So it is a duplicate suspect
     * naming no pair, and the capture behind it is parsed too.
     */
    @Test(timeout = 120_000)
    fun aCaptureWhoseLayerOneLookupMeetsDamageGoesToReviewWithNoPair() = runBlocking<Unit> {
        val (repeat, behind) = aRepeatWhoseLookupMeetsDamage()
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)

        val result = ParseFixtures.runWorker(context)

        assertEquals("the capture whose layer-one lookup met damage", ParseStatus.MATCHED, statusOf(repeat))
        val (state, reason, duplicateOf) = DatabaseFactory.build(context).let { own ->
            try {
                val txn = own.rawCaptureDao().txnIdForCapture(repeat)?.let { own.txnDao().byId(it) }
                Triple(txn?.state, txn?.pendingReason, own.rawCaptureDao().byId(repeat).duplicateOfId)
            } finally {
                own.close()
            }
        }
        assertEquals("its transaction", TxnState.PENDING, state)
        assertEquals("its reason for review", PendingReason.DUPLICATE_SUSPECT, reason)
        assertNull("it names a pair nothing could read", duplicateOf)
        assertEquals("the capture behind it", ParseStatus.MATCHED, statusOf(behind))
        assertEquals("stage two's run", ListenableWorker.Result.success(), result)
        assertTrue("the damage stage two met was not recorded", IntegrityStore.damaged(context))
    }

    /**
     * **Damage stage two cannot get past is damage, not capture stopping.**
     * The work queue's own index is damaged, so no run can claim anything:
     * the settings screen says DAMAGED, and the banner stays down while the
     * listener stores beside it. Filed as capture stopped, the banner came up
     * after every run and went down after every notification.
     */
    @Test(timeout = 60_000)
    fun damageStageTwoCannotGetPastIsRecordedAsDamageAndNotAsCaptureStopped() = runBlocking<Unit> {
        damagedLedger(tree = "index_raw_capture_parse_status_posted_at", allNew = true)
        IntegrityStore.record(context, at = System.currentTimeMillis(), ok = true)

        val result = ParseFixtures.runWorker(context)

        assertEquals("stage two on a damaged queue", ListenableWorker.Result.failure(), result)
        assertTrue("the damage stage two met was not recorded", IntegrityStore.damaged(context))
        assertFalse(
            "damage on stage two's path raised the capture-stopped flag",
            CaptureHealth.storageUnavailable(context),
        )
        assertNotNull("capture did not store beside it", ingestOne("Payment of RM9.00 to BESIDE IT successful"))
    }

    // ----------------------------------------------------------------------

    private fun newCapture(text: String, at: Long = System.currentTimeMillis()): RawCapture {
        val fields = NotificationFields(
            sourcePackage = ParseFixtures.TNG, postedAt = at, whenMillis = null, sbnKey = "damaged|$at",
            notifId = 1, notifTag = null, userHandle = 0, channelId = null, flags = 0,
            title = "Touch 'n Go eWallet", text = text, bigText = null, subText = null,
        )
        return RawCapture(
            sourcePackage = fields.sourcePackage, postedAt = at, whenMillis = null, capturedAt = at,
            sbnKey = fields.sbnKey, notifId = 1, notifTag = null, userHandle = 0, channelId = null, flags = 0,
            arrival = Arrival.POSTED, title = fields.title, text = fields.text, bigText = null, subText = null,
            extrasJson = null,
            contentHash = ContentHash.of(fields.sourcePackage, fields.userHandle, fields.normalizedForHash),
        )
    }

    private suspend fun ingestOne(text: String, at: Long = System.currentTimeMillis()): Long? {
        val now = at
        return CaptureIngest.ingest(
            context,
            CaptureFixtures.posted(
                CaptureFixtures.notification(context, title = "Touch 'n Go eWallet", text = text),
                pkg = ParseFixtures.TNG,
                id = (now % 100_000).toInt(),
                postTime = now,
            ),
            Arrival.POSTED,
            now,
        )
    }

    /**
     * 3,000 settled captures and the allow-list, checkpointed into the main
     * file, then one byte flipped in the middle leaf of [tree]. Settled, so
     * stage two has nothing of its own to read there. The shared instance is
     * left closed, so the next one opens on the damage.
     *
     * @param newOnTheDamagedLeaf leave the captures on the damaged
     *   `raw_capture` leaves at `NEW`, the rest settled; their ids are
     *   returned.
     * @param allNew leave every capture at `NEW`, for damage to the queue's
     *   index; the first of its leaves is damaged, where every `NEW` entry
     *   begins.
     * @param leaves how many leaves in a row, from the middle, are damaged.
     */
    private fun damagedLedger(
        tree: String = "raw_capture",
        newOnTheDamagedLeaf: Boolean = false,
        allNew: Boolean = false,
        leaves: Int = 1,
    ): List<Long> {
        ParseFixtures.prepare(context)
        var onTheLeaf = emptyList<Long>()
        val pages = Databases.shared(context).let { db ->
            db.runInTransaction {
                (0 until 3_000).chunked(500).forEach { chunk ->
                    db.rawCaptureDao().insertAll(
                        chunk.map { i -> settled(i).let { if (allNew) it.copy(parseStatus = ParseStatus.NEW) else it } },
                    )
                }
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val all = leavesOf(db, tree)
            val middle = all.size / 2
            val damaged = all.subList(middle, middle + leaves)
            if (newOnTheDamagedLeaf) {
                // Ids are 1..3,000 in leaf order, so the middle leaf's are
                // one past every cell before it.
                val first = 1L + all.take(middle).sumOf { it.second }
                onTheLeaf = (first until first + damaged.sumOf { it.second }).toList()
                onTheLeaf.forEach { id ->
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE raw_capture SET parse_status = 'NEW' WHERE id = ?",
                        arrayOf<Any>(id),
                    )
                }
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }
            (if (allNew) listOf(all.first()) else damaged).map { it.first }
        }
        Graph.reset()
        RandomAccessFile(context.getDatabasePath(DatabaseFactory.NAME), "rw").use { raf ->
            for (page in pages) {
                val at = (page - 1).toLong() * 4096 + 1000
                raf.seek(at)
                val original = raf.readByte()
                raf.seek(at)
                raf.writeByte(original.toInt() xor 0xFF)
            }
        }
        return onTheLeaf
    }

    /**
     * 3,000 settled captures, a `NEW` rebind copy of the 1,500th and a `NEW`
     * capture behind it, then every leaf of `raw_capture(content_hash,
     * posted_at)` damaged but the first, which holds the capture behind's
     * hash and none of the copy's. The copy's row, the queue and both
     * commits read; the copy's layer-one lookup does not, which is checked.
     * Returns the copy's id and the capture behind's.
     */
    private fun aRepeatWhoseLookupMeetsDamage(): Pair<Long, Long> {
        ParseFixtures.prepare(context)
        val hashIndex = "index_raw_capture_content_hash_posted_at"
        val copy = settled(1_500).copy(parseStatus = ParseStatus.NEW, arrival = Arrival.CATCHUP)
        var ids = 0L to 0L
        val pages = Databases.shared(context).let { db ->
            db.runInTransaction {
                (0 until 3_000).chunked(500).forEach { chunk -> db.rawCaptureDao().insertAll(chunk.map(::settled)) }
            }
            val now = System.currentTimeMillis()
            ids = db.rawCaptureDao().insert(copy) to db.rawCaptureDao().insert(
                settled(5_000).copy(
                    parseStatus = ParseStatus.NEW, postedAt = now, capturedAt = now,
                    sbnKey = "0|behind", contentHash = "0-behind",
                    text = "Payment of RM7.00 to BEHIND THE LOOKUP successful",
                ),
            )
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            leavesOf(db, hashIndex).drop(1).map { it.first }
        }
        Graph.reset()
        RandomAccessFile(context.getDatabasePath(DatabaseFactory.NAME), "rw").use { raf ->
            for (page in pages) {
                val at = (page - 1).toLong() * 4096 + 1000
                raf.seek(at)
                val original = raf.readByte()
                raf.seek(at)
                raf.writeByte(original.toInt() xor 0xFF)
            }
        }
        DatabaseFactory.build(context).let { own ->
            try {
                check(own.rawCaptureDao().byId(ids.first).parseStatus == ParseStatus.NEW) { "fixture: the copy's row" }
                val met = runCatching {
                    own.rawCaptureDao().findEarlierInSlot(key = copy.sbnKey, hash = copy.contentHash, selfId = ids.first)
                }.exceptionOrNull()
                check(met is SQLiteDatabaseCorruptException) { "fixture: the copy's lookup did not meet the damage: $met" }
            } finally {
                own.close()
            }
        }
        return ids
    }

    /**
     * 3,000 settled captures, each with a transaction from another package
     * of RM1.00 inside ten minutes of [at] -- every one a layer-two
     * candidate for an RM1.00 capture from Touch 'n Go at [at] -- and the
     * middle leaf of `txn` damaged.
     */
    private fun damagedTxnsAround(at: Long) {
        ParseFixtures.prepare(context)
        val page = Databases.shared(context).let { db ->
            val uncategorized = db.categoryDao().requireUncategorizedId()
            db.runInTransaction {
                (0 until 3_000).chunked(500).forEach { chunk -> db.rawCaptureDao().insertAll(chunk.map(::settled)) }
                db.txnDao().insertAll(
                    (0 until 3_000).map { i ->
                        val occurred = at - 300_000 + i
                        Txn(
                            rawCaptureId = i + 1L, amountSen = 100, direction = Direction.EXPENSE,
                            occurredAt = occurred, localDate = LocalDates.of(occurred), merchantRaw = null,
                            merchantDisplay = null, merchantKey = null, categoryId = uncategorized,
                            sourcePackage = ParseFixtures.MAE, sourceLabel = null, confidence = Confidence.HIGH,
                            state = TxnState.COMMITTED, createdAt = occurred, updatedAt = occurred,
                        )
                    },
                )
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            leavesOf(db, "txn").let { it[it.size / 2].first }
        }
        Graph.reset()
        RandomAccessFile(context.getDatabasePath(DatabaseFactory.NAME), "rw").use { raf ->
            val offset = (page - 1).toLong() * 4096 + 1000
            raf.seek(offset)
            val original = raf.readByte()
            raf.seek(offset)
            raf.writeByte(original.toInt() xor 0xFF)
        }
    }

    /** [tree]'s leaves in key order, each with how many cells it holds. */
    private fun leavesOf(db: PingedDatabase, tree: String): List<Pair<Int, Int>> {
        val pages = mutableListOf<Pair<Int, Int>>()
        db.openHelper.readableDatabase
            .query("SELECT pageno, ncell FROM dbstat WHERE name = ? AND pagetype = 'leaf' ORDER BY path", arrayOf(tree))
            .use { c -> while (c.moveToNext()) pages += c.getInt(0) to c.getInt(1) }
        check(pages.isNotEmpty()) { "$tree has no leaf page" }
        return pages
    }

    /** The first `raw_capture` id on the damaged page, found on an instance of its own. */
    private fun firstDamagedRow(): Long = DatabaseFactory.build(context).let { own ->
        try {
            var after = 0L
            while (true) {
                val page = runCatching { own.rawCaptureDao().pageFrom(after, 1) }
                    .getOrElse { return@let after + 1 }
                check(page.isNotEmpty()) { "no row of raw_capture is damaged" }
                after = page.last().id
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            own.close()
        }
    }

    /**
     * A `NEW` capture in [damagedId]'s slot with its content, a minute old:
     * layer one's lookup of an earlier row in the slot reads [damagedId].
     */
    private fun aCaptureRepeating(damagedId: Long): Long = DatabaseFactory.build(context).let { own ->
        try {
            val now = System.currentTimeMillis()
            own.rawCaptureDao().insert(
                settled((damagedId - 1).toInt()).copy(parseStatus = ParseStatus.NEW, postedAt = now - 60_000, capturedAt = now),
            )
        } finally {
            own.close()
        }
    }

    /**
     * [count] captures of the notification in [id]'s row as a rebind stores
     * it again: its slot, its content and its post time, at `NEW`.
     */
    private fun rebindCopiesOf(id: Long, count: Int): List<Long> = DatabaseFactory.build(context).let { own ->
        try {
            val row = settled((id - 1).toInt()).copy(parseStatus = ParseStatus.NEW, arrival = Arrival.CATCHUP)
            List(count) { own.rawCaptureDao().insert(row.copy(capturedAt = System.currentTimeMillis())) }
        } finally {
            own.close()
        }
    }

    /** The queue's ids, off its index, which answers where a damaged row will not. */
    private fun stillQueued(): List<Long> = DatabaseFactory.build(context).let { own ->
        try { own.rawCaptureDao().claimNextIds(10_000).map { it.id } } finally { own.close() }
    }

    private fun statusOf(id: Long): ParseStatus = DatabaseFactory.build(context).let { own ->
        try { own.rawCaptureDao().byId(id).parseStatus } finally { own.close() }
    }

    private fun settled(i: Int) = RawCapture(
        sourcePackage = ParseFixtures.TNG, postedAt = 1_600_000_000_000L + i, whenMillis = null,
        capturedAt = 1_600_000_000_000L + i, sbnKey = "0|settled|$i", notifId = i, notifTag = null,
        userHandle = 0, channelId = "txn", flags = 0, arrival = Arrival.POSTED, title = "Touch 'n Go eWallet",
        text = "Payment of RM1.00 to merchant number $i successful", bigText = null, subText = null,
        extrasJson = null, contentHash = "settled-$i", parseStatus = ParseStatus.MATCHED,
    )

    /**
     * What the feed does scrolling into an old month: a plain read, outside a
     * transaction, that meets the damage. Asserted, so a read that stopped
     * meeting it would fail here and not pass the tests after it vacuously.
     */
    private fun poisonTheSharedInstance() {
        val shared = Databases.shared(context)
        var after = 0L
        val met = runCatching {
            while (true) {
                val page = shared.rawCaptureDao().pageFrom(after, 500)
                if (page.size < 500) break
                after = page.last().id
            }
        }.exceptionOrNull()
        assertTrue("the read did not meet the damage but $met", met is SQLiteDatabaseCorruptException)
        val next = runCatching { shared.captureSourceDao().all() }.exceptionOrNull()
        assertEquals(
            "the connection was not poisoned by the damaged read, so this test proves nothing",
            "net.zetetic.database.sqlcipher.SQLiteNotADatabaseException",
            next?.javaClass?.name,
        )
    }

    private fun rowsOnAnInstanceOfItsOwn(): Int = DatabaseFactory.build(context).let { own ->
        try { own.rawCaptureDao().countAll() } finally { own.close() }
    }

    private companion object {
        /** Past [ParsePass.BATCH_SIZE], which a queue of nothing else ended a run at. */
        const val REBINDS = 60
    }
}
