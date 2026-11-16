package my.pinged.ledger.review

import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.PackLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which bundled rules are received-money rules (spec 9.2), whose cards offer
 * a bill: [readsMoneyReceived] reads it off the rule's name, so this holds
 * the name to what the rule reads.
 */
class ReceivedMoneyRulesTest {
    private val rules = PackLoader.load(
        ReceivedMoneyRulesTest::class.java.getResourceAsStream("/pack.json")!!.use { it.readBytes().toString(Charsets.UTF_8) },
    ).packages.flatMap { it.rules }

    @Test fun theTwoReceivedRulesAreNamedSo() {
        assertEquals(
            listOf("tng-received-v1", "mae-received-v1"),
            rules.map { it.id }.filter(::readsMoneyReceived),
        )
    }

    @Test fun everyRuleNamedForMoneyReceivedReadsMoneyReceived() {
        rules.filter { readsMoneyReceived(it.id) }.forEach {
            assertEquals("${it.id} reads money out", Direction.REFUND, it.direction)
        }
    }

    /**
     * A friend's transfer reads under the friend's name, which no rule can
     * tell from a shop's, so every received rule asks to be checked; a rule
     * of that shape under another name would hide its cards' bill.
     */
    @Test fun everyRefundRuleThatAsksToBeCheckedIsNamedForMoneyReceived() {
        rules.filter { it.direction == Direction.REFUND && it.confidence == Confidence.REVIEW }.forEach {
            assertTrue("${it.id} is a refund held for review under another name", readsMoneyReceived(it.id))
        }
    }

    @Test fun aMerchantsRefundIsNot() {
        assertFalse(readsMoneyReceived("mae-refund-my-v1"))
        assertFalse(readsMoneyReceived(null))
        assertFalse("a name that only mentions it", readsMoneyReceived("tng-received-v1-old"))
    }
}
