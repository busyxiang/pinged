package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.SOURCE_LABEL
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nearby-capture check Save runs before writing a manual entry (#104,
 * spec 9.4): a **captured** `EXPENSE` of the same amount, not `REJECTED`,
 * within layer 2's ten minutes of an entry dated now, or anywhere on the day
 * of a backdated one, whose time of day is made up.
 *
 * Every case puts the row under test beside nothing else, so a match is that
 * row's and a miss is that row's.
 */
@RunWith(AndroidJUnit4::class)
class NearbyCaptureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun aCaptureTenMinutesEitherSideMatchesAndNamesItsSourceAndTime() = runBlocking {
        for (offset in listOf(-TEN_MINUTES, TEN_MINUTES)) {
            val dao = context.freshLedger()
            val at = NOW + offset
            dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = at))

            assertEquals(
                "A capture ${offset / 60_000} minutes from an entry dated now was not found",
                NearbyCapture(amountSen = AMOUNT, source = SOURCE_LABEL, occurredAt = at, awaitingReview = false),
                check(ManualDraft(amountSen = AMOUNT)),
            )
        }
    }

    @Test fun aCaptureElevenMinutesAwayDoesNotMatch() = runBlocking {
        for (offset in listOf(-ELEVEN_MINUTES, ELEVEN_MINUTES)) {
            val dao = context.freshLedger()
            dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = NOW + offset))

            assertNull(
                "A capture ${offset / 60_000} minutes from an entry dated now was taken for it",
                check(ManualDraft(amountSen = AMOUNT)),
            )
        }
    }

    @Test fun aBackdatedEntryMatchesAnywhereOnItsDayAndNotTheDayBefore() = runBlocking {
        // Both ends of 30 August, hours from the made-up 14:37 the entry is dated.
        for (time in listOf(EARLY, LATE)) {
            val dao = context.freshLedger()
            val at = millis(BACKDATED.atTime(time.first, time.second))
            dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = at))

            assertEquals(
                "A capture at ${time.first}:${time.second} on the day of a backdated entry was not found",
                at,
                check(ManualDraft(amountSen = AMOUNT, day = BACKDATED))?.occurredAt,
            )
        }

        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = millis(BACKDATED.minusDays(1).atTime(23, 59, 59))))
        assertNull(
            "A capture on the day before a backdated entry was taken for it",
            check(ManualDraft(amountSen = AMOUNT, day = BACKDATED)),
        )
    }

    @Test fun aCaptureAwaitingReviewMatchesAndSaysSo() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(
            ledgerTxn(
                amountSen = AMOUNT,
                occurredAt = NOW - 60_000L,
                state = TxnState.PENDING,
                pendingReason = PendingReason.OVER_THRESHOLD,
            ),
        )

        assertEquals(
            "A capture awaiting review was not found, or not said to be awaiting review",
            true,
            check(ManualDraft(amountSen = AMOUNT))?.awaitingReview,
        )
    }

    @Test fun aDismissedCaptureDoesNotMatch() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = NOW - 60_000L, state = TxnState.REJECTED))

        assertNull("A dismissed capture was taken for the entry", check(ManualDraft(amountSen = AMOUNT)))
    }

    @Test fun anotherManualEntryDoesNotMatch() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = NOW - 60_000L, rawCaptureId = null))

        assertNull("Another manual entry was taken for a capture", check(ManualDraft(amountSen = AMOUNT)))
    }

    @Test fun aDifferentAmountDoesNotMatch() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = AMOUNT + 1, occurredAt = NOW - 60_000L))

        assertNull("A capture of RM12.51 was taken for RM12.50", check(ManualDraft(amountSen = AMOUNT)))
    }

    @Test fun theRowBeingEditedIsNeverItsOwnMatch() = runBlocking {
        val dao: TxnDao = context.freshLedger()
        val self = dao.insert(ledgerTxn(amountSen = AMOUNT, occurredAt = NOW - 60_000L))

        assertNull(
            "The row named as the one being edited was matched",
            entries().nearbyCapture(ManualDraft(amountSen = AMOUNT), editing = self).await(),
        )
    }

    private suspend fun check(draft: ManualDraft): NearbyCapture? = entries().nearbyCapture(draft, editing = null).await()

    private fun entries() = ManualEntries(context = { context }, scope = scope, clock = { NOW })

    private companion object {
        const val AMOUNT = 1_250L
        const val TEN_MINUTES = 600_000L
        const val ELEVEN_MINUTES = 660_000L

        /** Save's moment, mid-afternoon so ten minutes either side stays on its day. */
        val NOW: Long = millis(LocalDate.of(2026, 9, 2).atTime(14, 37, 12, 345_000_000))

        val BACKDATED: LocalDate = LocalDate.of(2026, 8, 30)
        val EARLY = 0 to 5
        val LATE = 23 to 55

        fun millis(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
