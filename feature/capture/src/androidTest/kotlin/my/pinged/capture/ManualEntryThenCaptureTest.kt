package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.entry.ManualDraft
import my.pinged.ledger.entry.ManualEntries
import my.pinged.ledger.review.CardKind
import my.pinged.ledger.review.ReviewInbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other order of #104's pair (spec 9.4 story 34, §7.2): a payment entered
 * by hand, then its notification. Nothing in the entry screen catches this;
 * layer 2 does, because a manual entry's null `source_package` is never the
 * capturing package, and the review inbox finds the pair by query since a
 * manual entry has no capture to record as `duplicate_of_id`.
 *
 * Pins behaviour manual entry relies on rather than code it adds. The entry
 * has no merchant: layer 2 also needs the merchants to agree, which a side
 * with none always does (`Dedup.merchantsAgree`).
 */
@RunWith(AndroidJUnit4::class)
class ManualEntryThenCaptureTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val marker = "ZZ" + System.nanoTime()

    @Before fun prepare() = ParseFixtures.prepare(context)

    @After fun stop() = scope.cancel()

    @Test fun aCaptureFiveMinutesAfterAManualEntryWaitsForReviewAndMergeKeepsTheEntry() = runBlocking {
        // An amount of this run's own: this database outlives the run, and an
        // earlier run's row of the same amount in the window would be paired instead.
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val typedAt = System.currentTimeMillis() - 7_200_000L
        val txns = Databases.txnDao(context)

        val manualId = requireNotNull(
            withTimeout(TIMEOUT) {
                ManualEntries(context = { context }, scope = scope, clock = { typedAt }).save(ManualDraft(amountSen = sen)).await()
            },
        ) { "The manual entry was not saved" }
        val typed = requireNotNull(txns.byId(manualId))

        val capture = ParseFixtures.insertCapture(
            context,
            text = "Payment of $rm to STARBUCKS KLCC successful",
            title = marker,
            pkg = ParseFixtures.TNG,
            sbnKey = "$marker-after-manual",
            postedAt = typedAt + 300_000L,
        )
        ParseFixtures.pass(context).run()

        val captured = requireNotNull(ParseFixtures.txnForCapture(context, capture)) { "The capture made no transaction" }
        assertEquals("The capture of a payment entered by hand was counted", TxnState.PENDING, captured.state)
        assertEquals(PendingReason.DUPLICATE_SUSPECT, captured.pendingReason)
        assertEquals("The capture changed the manual entry", typed, txns.byId(manualId))

        val inbox = ReviewInbox(scope = scope, runner = GuardedRunner(context))
        // A `ScreenRead`'s calls are made on its scope's thread, the main thread here.
        withTimeout(TIMEOUT) { withContext(Dispatchers.Main) { inbox.read.refresh() }.join() }
        val card = inbox.cards.orEmpty().single { it.txn.id == captured.id }
        assertEquals("The inbox does not pair the capture with the manual entry", manualId, card.pair?.id)
        assertEquals("The inbox does not draw a duplicate card", CardKind.DUPLICATE, card.kind)

        withTimeout(TIMEOUT) { inbox.merge(card).join() }

        assertEquals("Merge did not dismiss the capture", TxnState.REJECTED, txns.byId(captured.id)?.state)
        assertEquals("Merge changed the manual entry", typed, txns.byId(manualId))
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
