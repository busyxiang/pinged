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
        assertEquals("LOOKS LIKE MOVING MONEY, NOT SPENDING IT", reasonSentence(PendingReason.TRANSFER_SUSPECT))
        assertEquals("MAYBE THE SAME PURCHASE TWICE", reasonSentence(PendingReason.DUPLICATE_SUSPECT))
        assertEquals("AMOUNT FOUND, NO NAME ATTACHED", reasonSentence(PendingReason.MERCHANT_MISSING))
        assertEquals("OVER YOUR RM500 CHECK LIMIT", reasonSentence(PendingReason.OVER_THRESHOLD))
    }

    /** The threshold is the gate's, whole ringgit without `.00`; sen kept if it ever has any. */
    @Test fun theLimitIsPrintedFromTheThreshold() {
        assertEquals("RM500", limit(50_000L))
        assertEquals("RM1,200", limit(120_000L))
        assertEquals("RM499.50", limit(49_950L))
    }

    @Test fun everyReasonIsInCapitals() {
        (PendingReason.entries + null).forEach {
            val line = reasonSentence(it)
            assertEquals("\"$line\" is not in capitals", line.uppercase(), line)
        }
    }
}
