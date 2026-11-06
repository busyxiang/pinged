package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import my.pinged.data.DuplicateWindows
import my.pinged.data.LocalDates
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Reading's JVM tests pair through [InMemoryPairCandidates]; the phone
 * pairs through [RoomPairCandidates]. Both are given the same rows here, one
 * on each side of every clause `TxnDao.findDuplicateSuspects` decides, and
 * must give the same answer, in the same order, to each question.
 *
 * The ledger is the device's shared one, so the rows sit at a random instant
 * years back at an amount no fixture uses, and only this test's rows answer.
 */
@RunWith(AndroidJUnit4::class)
class PairCandidatesContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val txns = Databases.txnDao(context)

    private val marker = "ZZ" + System.nanoTime()
    private val at = System.currentTimeMillis() - 400 * DAY - kotlin.random.Random.nextLong(0, 3_000 * DAY)
    private val sen = 777_000L + kotlin.random.Random.nextLong(0, 1_000L)

    private val rows = mutableListOf<InMemoryPairCandidates.Row>()

    @Before fun prepare() = ParseFixtures.prepare(context)

    /** A transaction in the ledger, through [pkg]'s capture read by [ruleId], or a manual entry when [pkg] is null. */
    private fun row(
        name: String,
        pkg: String? = ParseFixtures.TNG,
        ruleId: String? = TNG_RULE,
        occurredAt: Long = at,
        amountSen: Long = sen,
        direction: Direction = Direction.EXPENSE,
        state: TxnState = TxnState.COMMITTED,
    ): Long {
        val captureId = pkg?.let {
            ParseFixtures.insertCapture(context, text = "$marker $name", pkg = it, sbnKey = "$marker-$name", postedAt = occurredAt)
                // MATCHED at the current pack, as stage two leaves it, so no
                // later pass or re-read in this APK takes the capture up.
                .also { id -> captures.markOutcome(id = id, status = ParseStatus.MATCHED, ruleId = ruleId, packVersion = Graph.parsePack().packVersion) }
        }
        val txn = Txn(
            rawCaptureId = captureId,
            amountSen = amountSen,
            direction = direction,
            occurredAt = occurredAt,
            localDate = LocalDates.of(occurredAt),
            merchantRaw = "KEDAI $name",
            merchantDisplay = "Kedai $name",
            merchantKey = "KEDAI $name",
            categoryId = Databases.categoryDao(context).requireUncategorizedId(),
            sourcePackage = pkg,
            sourceLabel = null,
            confidence = Confidence.HIGH,
            state = state,
            pendingReason = if (state == TxnState.PENDING) PendingReason.RULE_REVIEW else null,
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
        val id = txns.insert(txn)
        rows += InMemoryPairCandidates.Row(txn.copy(id = id), ruleId)
        return id
    }

    @Test fun bothAdaptersAnswerEveryClauseAlike() {
        val window = DuplicateWindows.LAYER_TWO_MILLIS
        val otherApp = row("other-app", occurredAt = at - 2 * 60_000L)
        val sameAppOtherRule = row("same-app-other-rule", pkg = ParseFixtures.MAE, ruleId = "mae-card-spend-v1")
        row("same-app-same-rule", pkg = ParseFixtures.MAE, ruleId = MAE_RULE)
        row("same-app-no-rule", pkg = ParseFixtures.MAE, ruleId = null)
        val manual = row("manual", pkg = null, ruleId = null, occurredAt = at + 5 * 60_000L)
        val pending = row("pending", state = TxnState.PENDING, occurredAt = at - 2 * 60_000L)
        row("rejected", state = TxnState.REJECTED)
        row("refund", direction = Direction.REFUND)
        row("other-amount", amountSen = sen + 1)
        val atTheEdge = row("at-the-edge", occurredAt = at + window)
        row("past-the-edge", occurredAt = at + window + 1)
        row("before-the-edge", occurredAt = at - window - 1)

        val memory = InMemoryPairCandidates(rows)
        val room = RoomPairCandidates(txns)
        fun both(query: PairQuery) = room.near(query).map { it.id } to memory.near(query).map { it.id }

        val fromMae = PairQuery(sen, at, Direction.EXPENSE, ParseFixtures.MAE, MAE_RULE)
        val (roomAnswer, memoryAnswer) = both(fromMae)
        assertEquals(
            "Room's answer is not the one this test expects, so the comparison below says nothing",
            setOf(otherApp, pending, sameAppOtherRule, manual, atTheEdge),
            roomAnswer.toSet(),
        )
        assertEquals("The adapters disagree", roomAnswer, memoryAnswer)

        for (query in listOf(
            fromMae.copy(sourcePackage = ParseFixtures.TNG, ruleId = TNG_RULE),
            fromMae.copy(direction = Direction.REFUND),
            fromMae.copy(occurredAt = at + 2 * window),
            fromMae.copy(amountSen = sen + 1),
        )) {
            val (r, m) = both(query)
            assertEquals("The adapters disagree on $query", r, m)
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val TNG_RULE = "tng-payment-v1"
        const val MAE_RULE = "mae-card-spend-fpx-v1"
    }
}
