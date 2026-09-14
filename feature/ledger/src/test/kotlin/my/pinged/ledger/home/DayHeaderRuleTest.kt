package my.pinged.ledger.home

import my.pinged.data.LocalDate
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The day-heading rule, asserted without a list under it.
 *
 * `dayHeaderBetween` is what `LedgerViewModel.items` hands to
 * `insertSeparators`. Driven through a composition it can only be reached with
 * a `LazyColumn` beneath it, and the list's uniqueness check reports a wrong
 * separator as a duplicate key -- thrown out of measure, before any assertion
 * about separators runs. The interleaved case below was a crash on the home
 * screen for exactly that reason; the feed's ordering keeps it off the screen,
 * and this file is what says the rule underneath would still answer sanely.
 *
 * A plain JVM test: the rule is a comparison of two `Int`s, and the two types
 * it reads are a Room entity and a value class over an `Int`, neither of which
 * needs a device.
 */
class DayHeaderRuleTest {

    @Test fun theEndOfTheListTakesNoHeading() {
        assertNull(
            "A heading was generated below the last row, where there is no day " +
                "for it to introduce",
            dayHeaderBetween(row(SEP_7), null),
        )
        assertNull("Two absent ends are not a day boundary", dayHeaderBetween(null, null))
    }

    @Test fun theTopOfTheListAlwaysTakesAHeading() {
        val header = dayHeaderBetween(null, row(SEP_7, id = 11L))

        assertEquals(
            "The first row of the list got no heading, so the newest day on " +
                "screen is the one day that is not named",
            LedgerItem.DayHeader(SEP_7, 11L),
            header,
        )
    }

    @Test fun twoAdjacentRowsOfOneDayTakeOneHeading() {
        assertNull(
            "A second heading inside one run of a day. The list would then read " +
                "as two days, each with the whole day's subtotal beside it",
            dayHeaderBetween(row(SEP_7, id = 11L), row(SEP_7, id = 12L)),
        )
    }

    @Test fun aChangeOfDayTakesAHeadingNamingTheRowBelowIt() {
        val header = dayHeaderBetween(row(SEP_7, id = 12L), row(SEP_6, id = 13L))

        assertEquals(
            "The heading between two days names the day above it, or no day at " +
                "all. It introduces the rows below it and must carry theirs",
            LedgerItem.DayHeader(SEP_6, 13L),
            header,
        )
    }

    /**
     * The sequence that crashed the home screen: X, Y, X.
     *
     * **The feed no longer produces it.** `TxnDao.feed` leads on `local_date`,
     * so a date arrives in one piece; this asserts what the rule does if it
     * ever stops doing so, and the answer has to be *a heading per run*
     * rather than one heading or a crash. A single heading would file the
     * bottom rows under the day above them, and a repeated key throws out of
     * `LazyColumn`'s measure, which is neither a coroutine nor a composition
     * anything catches.
     *
     * What it does **not** claim is that drawing X twice is a good screen: two
     * headings for one date each look that date's subtotal up and print the
     * whole day's total, which is why the ordering closes the case rather than
     * the rule. See `LedgerItem.DayHeader`.
     */
    @Test fun anInterruptedDateWouldStillTakeADistinctHeadingPerRun() {
        val rows = listOf(row(SEP_7, id = 11L), row(SEP_6, id = 12L), row(SEP_7, id = 13L))

        // What `insertSeparators` walks: every adjacent pair, both ends included.
        val headers = (listOf<LedgerItem?>(null) + rows + listOf(null))
            .zipWithNext { before, after -> dayHeaderBetween(before, after) }
            .filterNotNull()

        assertEquals(
            "An interrupted date did not get a heading each time it resumes, so " +
                "the rows below the second one are filed under the day above them",
            listOf(
                LedgerItem.DayHeader(SEP_7, 11L),
                LedgerItem.DayHeader(SEP_6, 12L),
                LedgerItem.DayHeader(SEP_7, 13L),
            ),
            headers,
        )
        assertEquals(
            "Two headings for one date collided in `LazyColumn`'s key map, " +
                "which throws out of measure and takes the home screen with it",
            headers.size,
            headers.map { "day-" + it.date.yyyymmdd + "-" + it.firstRowId }.distinct().size,
        )
    }

    private fun row(day: LocalDate, id: Long = 1L) = LedgerItem.Row(
        Txn(
            id = id,
            rawCaptureId = null,
            amountSen = 1_000L,
            direction = Direction.EXPENSE,
            occurredAt = 1_000L,
            localDate = day,
            merchantRaw = "WARUNG PAK ALI",
            merchantDisplay = "Warung Pak Ali",
            merchantKey = "WARUNG PAK ALI",
            categoryId = 1L,
            sourcePackage = "my.com.tngdigital.ewallet",
            sourceLabel = "Touch 'n Go eWallet",
            confidence = Confidence.HIGH,
            state = TxnState.COMMITTED,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        ),
    )

    private companion object {
        val SEP_7 = LocalDate(20_260_907)
        val SEP_6 = LocalDate(20_260_906)
    }
}
