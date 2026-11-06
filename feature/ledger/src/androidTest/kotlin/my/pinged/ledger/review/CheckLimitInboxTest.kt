package my.pinged.ledger.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.capture.CheckLimit
import my.pinged.data.entity.PendingReason
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #362: the review inbox reads the check limit saved now, through the store,
 * for Accept all's batch, its note and a card's label. `AcceptAllLimitTest`
 * holds the same rules over cards built by hand; this is the read that gives
 * the cards their limit.
 */
@RunWith(AndroidJUnit4::class)
class CheckLimitInboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() {
        runBlocking { CheckLimit.reset(context) }
        context.discardTheDatabase()
    }

    @Test fun acceptAllAndTheLabelFollowTheSavedLimitBothWays() = runBlocking {
        val dao = context.freshLedger()
        val held = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON, amountSen = 80_000L))
        dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 1, amountSen = 30_000L))
        dao.insert(pending(PendingReason.MERCHANT_MISSING, at = NOON + 2, amountSen = 5_000L))

        CheckLimit.set(context, 100_000L)
        val raised = inbox()
        assertEquals(
            "After a raise to RM1,000 the RM800 card held under RM500 is not in the batch and its sum",
            "Accept 3 · RM1,150.00",
            AcceptAll.of(raised.cards!!)?.label,
        )
        assertEquals("OVER YOUR CHECK LIMIT WHEN IT ARRIVED", card(raised, held).reason)

        CheckLimit.set(context, 20_000L)
        val lowered = inbox()
        assertEquals(
            "After a lower to RM200 the RM300 rule-review card is batched, or the note does not name RM200",
            "Accept 1 · RM50.00 · 2 over RM200 left to check",
            AcceptAll.of(lowered.cards!!)?.label,
        )
        assertEquals("OVER YOUR RM200 CHECK LIMIT", card(lowered, held).reason)
    }
}
