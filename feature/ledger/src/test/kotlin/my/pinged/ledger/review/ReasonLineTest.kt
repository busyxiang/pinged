package my.pinged.ledger.review

import my.pinged.data.entity.PendingReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A card's reason line in `design/Inbox.dc.html`'s words (#168): short mono
 * labels, uppercase in the string so what TalkBack reads is what is drawn.
 */
class ReasonLineTest {
    @Test fun eachReasonIsTheBoardsLabel() {
        assertEquals("LOOKS LIKE MOVING MONEY, NOT SPENDING IT", line(PendingReason.TRANSFER_SUSPECT))
        assertEquals("MAYBE THE SAME PURCHASE TWICE", line(PendingReason.DUPLICATE_SUSPECT))
        assertEquals("AMOUNT FOUND, NO NAME ATTACHED", line(PendingReason.MERCHANT_MISSING))
        assertEquals("OVER YOUR RM500 CHECK LIMIT", line(PendingReason.OVER_THRESHOLD))
    }

    /**
     * #362: the label names the limit now, and only while the card is still
     * over it. The limit it arrived under is not stored, so once the card is
     * under the limit the label says when, not how much.
     */
    @Test fun anOverLimitCardNamesTheLimitNowOnlyWhileItIsStillOverIt() {
        assertEquals("OVER YOUR RM1,000 CHECK LIMIT", reasonSentence(PendingReason.OVER_THRESHOLD, amountSen = 120_000L, limitSen = 100_000L))
        assertEquals("OVER YOUR CHECK LIMIT WHEN IT ARRIVED", reasonSentence(PendingReason.OVER_THRESHOLD, amountSen = 80_000L, limitSen = 100_000L))
        assertEquals("OVER YOUR CHECK LIMIT WHEN IT ARRIVED", reasonSentence(PendingReason.OVER_THRESHOLD, amountSen = 100_000L, limitSen = 100_000L))
        assertEquals(
            "Another reason's line does not depend on the limit",
            "AMOUNT FOUND, NO NAME ATTACHED",
            reasonSentence(PendingReason.MERCHANT_MISSING, amountSen = 120_000L, limitSen = 100_000L),
        )
    }

    /** The threshold is the gate's, whole ringgit without `.00`; sen kept if it ever has any. */
    @Test fun theLimitIsPrintedFromTheThreshold() {
        assertEquals("RM500", limit(50_000L))
        assertEquals("RM1,200", limit(120_000L))
        assertEquals("RM499.50", limit(49_950L))
    }

    @Test fun everyReasonIsInCapitals() {
        (PendingReason.entries + null).forEach {
            val line = line(it)
            assertEquals("\"$line\" is not in capitals", line.uppercase(), line)
        }
    }

    /** [reasonSentence] for an RM650 card under the default RM500. */
    private fun line(reason: PendingReason?) = reasonSentence(reason, amountSen = 65_000L, limitSen = 50_000L)
}
