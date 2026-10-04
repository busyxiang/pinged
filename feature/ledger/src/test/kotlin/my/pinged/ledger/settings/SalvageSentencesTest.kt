package my.pinged.ledger.settings

import my.pinged.ledger.transfer.SalvageReport
import my.pinged.ledger.transfer.Unreadable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a salvage's sheet says. A salvaged file that silently omits rows is
 * worse than no file (design 5), so every figure here has to be true of the
 * file, and a bound must never read as a count.
 */
class SalvageSentencesTest {
    private fun report(
        unreadable: Map<String, Unreadable> = emptyMap(),
        txnsLeftOut: Int = 0,
        rows: Int = 100,
    ) = SalvageReport(
        rows = rows,
        unreadable = unreadable,
        lostWhole = emptySet(),
        txnsLeftOut = txnsLeftOut,
        linksCleared = 0,
        txnsUncategorized = 0,
        rulesLeftOut = 0,
        uncategorizedAdded = false,
    )

    @Test fun aCountedLossAddsUp() {
        val said = salvageSentences(report(mapOf("raw_capture" to Unreadable.Counted(recovered = 2_200, unread = 2_800))))
        assertTrue(said.toString(), "Recovered 2,200 of 5,000 notifications; 2,800 could not be read." in said)
    }

    /**
     * **`AtMost` is an upper bound**: ids no index would vouch for, some of
     * them never assigned. "Recovered R of R+N" would be a total that is not
     * one.
     */
    @Test fun aBoundIsNeverDrawnAsATotal() {
        val said = salvageSentences(report(mapOf("raw_capture" to Unreadable.AtMost(recovered = 1_981, ids = 19))))
        assertTrue(said.toString(), "Recovered 1,981 notifications; up to 19 could not be read." in said)
        assertFalse("a bound was drawn as a total: $said", said.any { "2,000" in it || " of " in it })
    }

    /**
     * `txn`'s recovered figure is transactions read, the ones then left out
     * among them. They are not in the file, so the left-out count is its own
     * sentence and nothing calls them rows in it.
     */
    @Test fun transactionsLeftOutAreTheirOwnSentence() {
        val said = salvageSentences(
            report(
                mapOf("txn" to Unreadable.Counted(recovered = 90, unread = 10)),
                txnsLeftOut = 7,
                rows = 83,
            ),
        )
        assertEquals(
            listOf(
                "83 rows are in the file.",
                "Recovered 90 of 100 transactions; 10 could not be read.",
                "7 transactions were left out, because the notification behind each could not be read.",
                "To start again from this file, use Replace everything from a backup and choose it.",
            ),
            said,
        )
    }

    /** A table with no damage needs no line. */
    @Test fun aTableThatLostNothingSaysNothing() {
        val said = salvageSentences(report(mapOf("raw_capture" to Unreadable.Counted(recovered = 9, unread = 1))))
        assertFalse(said.toString(), said.any { "transaction" in it || "learned merchant" in it })
    }

    /**
     * **Grouped as the rest of the app groups**, `Locale.ROOT`'s `%,d`: in
     * the damaged state the delete sheet says "50,000" one tap from here.
     */
    @Test fun everyFigureIsGroupedAsTheDeleteSheetGroupsIt() {
        val said = salvageSentences(
            report(mapOf("raw_capture" to Unreadable.Counted(recovered = 49_981, unread = 19)), txnsLeftOut = 1_019, rows = 98_943),
        )
        assertEquals(
            listOf(
                "98,943 rows are in the file.",
                "Recovered 49,981 of 50,000 notifications; 19 could not be read.",
                "1,019 transactions were left out, because the notification behind each could not be read.",
                "To start again from this file, use Replace everything from a backup and choose it.",
            ),
            said,
        )
    }

    /**
     * **What arrived after the rescue started is never said to be
     * unreadable**: each of those adjustments has its own sentence, and
     * none of them says "could not be read".
     */
    @Test fun whatArrivedSinceIsSaidAsSuch() {
        val said = salvageSentences(
            report().copy(
                txnsLeftOutSince = 1,
                linksClearedSince = 2,
                txnsUncategorizedSince = 3,
                rulesLeftOutSince = 1,
                linksUnheld = 2,
            ),
        )
        assertEquals(
            listOf(
                "100 rows are in the file.",
                "1 transaction recorded after the rescue started was left out, with the notification behind it.",
                "2 notifications marked as a copy of one that arrived after the rescue started are kept, without that mark.",
                "2 notifications marked as a copy of one that came after them are kept, without that mark.",
                "3 transactions are filed under Uncategorized, because their category was made after the rescue started.",
                "1 learned merchant was left out, because its category was made after the rescue started.",
                "To start again from this file, use Replace everything from a backup and choose it.",
            ),
            said,
        )
    }
}
