package my.pinged.charts.model

import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "not in the total" block's lines, from the aggregate's rows (#89, #68). */
class NotInTotalTest {

    @Test fun everyLineInTheArtboardsOrderWithItsAmount() {
        val lines = notInTotal(
            listOf(
                NotInTotal(NotInTotalLine.PENDING, "MYR", 4, 21_240L),
                NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 2, 388_000L),
                NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 3, 45_000L),
            ),
        )

        assertEquals(
            listOf(
                KeptOut("Transfers, left out", "450.00"),
                KeptOut("You excluded", "3,880.00"),
                KeptOut("4 awaiting review", "212.40"),
            ),
            lines,
        )
    }

    @Test fun aLineThatNetsBelowZeroKeepsItsSign() {
        val lines = notInTotal(listOf(NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 2, -12_000L)))

        assertEquals(listOf(KeptOut("Transfers, left out", "−120.00")), lines)
    }

    @Test fun zeroLinesAreDropped() {
        // A transfer out and back the same month nets to nothing; a pending
        // pair can too, and still has its count to report.
        val lines = notInTotal(
            listOf(
                NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 2, 0L),
                NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 4_000L),
                NotInTotal(NotInTotalLine.PENDING, "MYR", 2, 0L),
            ),
        )

        assertEquals(
            listOf(
                KeptOut("You excluded", "40.00"),
                KeptOut("2 awaiting review", "0.00"),
            ),
            lines,
        )
    }

    @Test fun withEveryLineZeroTheBlockIsGone() {
        val lines = notInTotal(
            listOf(
                NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 2, 0L),
                NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 2, 0L),
            ),
        )

        assertEquals(emptyList<KeptOut>(), lines)
        assertEquals(emptyList<KeptOut>(), notInTotal(emptyList()))
    }

    @Test fun onlyRinggitIsListed() {
        val lines = notInTotal(
            listOf(
                NotInTotal(NotInTotalLine.PENDING, "SGD", 1, 700L),
                NotInTotal(NotInTotalLine.TRANSFERS, "SGD", 1, 900L),
            ),
        )

        assertEquals(emptyList<KeptOut>(), lines)
    }
}
