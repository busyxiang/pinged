package my.pinged.ledger.learned

import my.pinged.data.dao.TaughtRuleRow
import my.pinged.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/** A taught message's second line names its direction in the words Teach offered it in. */
class MessageLabelTest {
    private fun rule(direction: Direction) = TaughtRuleRow(
        id = 1,
        sourcePackage = "com.maybank2u.life",
        sampleText = "You've received RM 27.95 from SAMPLE PAYER NAME. REF: 000000000Q.",
        direction = direction,
        hasMerchantGroup = true,
        matchCount = 2,
        label = "Maybank MAE",
    )

    @Test fun aRefundRuleIsLabelledMoneyBack() {
        assertEquals("MAYBANK MAE · MONEY BACK · READ 2", messageLabel(rule(Direction.REFUND)))
    }

    @Test fun anExpenseRuleIsStillLabelledMoneyOut() {
        assertEquals("MAYBANK MAE · MONEY OUT · READ 2", messageLabel(rule(Direction.EXPENSE)))
    }
}
