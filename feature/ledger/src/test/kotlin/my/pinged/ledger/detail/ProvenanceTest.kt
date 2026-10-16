package my.pinged.ledger.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/** The detail screen's line naming what read a capture (#153), and spec 5.8's taught rules in it. */
class ProvenanceTest {
    @Test fun aPackRuleIsNamedWithItsPack() {
        assertEquals("RULE tng-payment-v1 · PACK 7 · NOT EDITED", provenance("tng-payment-v1", 7, edited = false))
    }

    /** No pack wrote a taught rule, so none is named. */
    @Test fun aTaughtRuleIsSaidToBeTheUsersAndNamesNoPack() {
        assertEquals("A RULE YOU TAUGHT · EDITED", provenance("taught-12", 7, edited = true))
    }
}
