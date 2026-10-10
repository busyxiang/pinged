package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TxnDao.notInTotal`: the money that moved in a period without entering its
 * total, one line per kind (#89, #81 "The Charts read"). Every row it reads
 * falls in exactly one line, and a row the total counts falls in none.
 */
@RunWith(AndroidJUnit4::class)
class NotInTotalTest {
    private lateinit var db: PingedDatabase

    /** 2026-09-07T00:00:00Z, and the day after. */
    private val day1 = 1_788_739_200_000L
    private val day2 = day1 + 86_400_000L

    private val sept = LocalDate(20260901) to LocalDate(20260930)

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun insert(
        amountSen: Long,
        state: TxnState = TxnState.COMMITTED,
        reason: ExclusionReason? = null,
        direction: Direction = Direction.EXPENSE,
        currency: String = "MYR",
        occurredAt: Long = day1,
    ) {
        db.txnDao().insert(
            sampleTxn(
                amountSen = amountSen,
                occurredAt = occurredAt,
                categoryId = db.categoryDao().requireUncategorizedId(),
                state = state,
                isExcluded = reason != null,
                exclusionReason = reason,
                direction = direction,
                currency = currency,
            ),
        )
    }

    private fun lines(from: LocalDate = sept.first, to: LocalDate = sept.second): Map<NotInTotalLine, NotInTotal> =
        db.txnDao().notInTotal(from, to).associateBy { it.line }

    @Test fun eachKindLandsOnItsOwnLineAndAPendingExcludedRowCountsOnceAsPending() {
        // Counted, and so on no line.
        insert(1_000L)
        // Transfers, left out: every pack reason.
        insert(10_000L, reason = ExclusionReason.TRANSFER)
        insert(2_000L, reason = ExclusionReason.CARD_PAYMENT)
        insert(300L, reason = ExclusionReason.ATM_WITHDRAWAL)
        // You excluded.
        insert(4_000L, reason = ExclusionReason.USER)
        // Awaiting review, one of them excluded as well.
        insert(500L, state = TxnState.PENDING)
        insert(60L, state = TxnState.PENDING, reason = ExclusionReason.TRANSFER)
        // Rejected is not a transaction, excluded or not.
        insert(7L, state = TxnState.REJECTED)
        insert(8L, state = TxnState.REJECTED, reason = ExclusionReason.USER)

        val lines = lines()

        assertEquals("Not three lines: $lines", 3, lines.size)
        assertEquals(
            "Transfers, left out is not the three pack reasons",
            NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 3, 12_300L),
            lines[NotInTotalLine.TRANSFERS],
        )
        assertEquals(
            "You excluded is not the USER row",
            NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 4_000L),
            lines[NotInTotalLine.USER_EXCLUDED],
        )
        assertEquals(
            "A pending row that is also excluded must count once, as pending, and on no other line",
            NotInTotal(NotInTotalLine.PENDING, "MYR", 2, 560L),
            lines[NotInTotalLine.PENDING],
        )
    }

    @Test fun aRefundOnALineSubtractsFromItsNet() {
        insert(5_000L, reason = ExclusionReason.TRANSFER)
        insert(2_000L, reason = ExclusionReason.TRANSFER, direction = Direction.REFUND)

        assertEquals(
            "A transfer back in was added to the transfers out",
            NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 2, 3_000L),
            lines()[NotInTotalLine.TRANSFERS],
        )
    }

    @Test fun eachCurrencyIsItsOwnLineAndNeverOneSum() {
        insert(5_000L, state = TxnState.PENDING)
        insert(700L, state = TxnState.PENDING, currency = "SGD")

        val pending = db.txnDao().notInTotal(sept.first, sept.second).sortedBy { it.currency }

        assertEquals(
            listOf(
                NotInTotal(NotInTotalLine.PENDING, "MYR", 1, 5_000L),
                NotInTotal(NotInTotalLine.PENDING, "SGD", 1, 700L),
            ),
            pending,
        )
    }

    @Test fun aOneDayRangeReadsThatDayAlone() {
        // #90's Day header reads the same aggregate over one local_date.
        insert(5_000L, reason = ExclusionReason.USER, occurredAt = day1)
        insert(9_000L, reason = ExclusionReason.USER, occurredAt = day2)
        val day = LocalDates.of(day2)

        assertEquals(
            NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 9_000L),
            lines(day, day)[NotInTotalLine.USER_EXCLUDED],
        )
    }

    @Test fun aPeriodWithNothingKeptOutHasNoLines() {
        insert(1_000L)

        assertEquals(emptyList<NotInTotal>(), db.txnDao().notInTotal(sept.first, sept.second))
    }
}
