package my.pinged.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 7.2 layer 2's merchant clause: "merchant tokens overlap or one merchant
 * is absent".
 *
 * The comparison is over cleaned merchants, which is what makes the clause
 * useful at all: the two apps that see one purchase describe it differently,
 * and one of them is usually an acquirer string.
 */
class DedupMerchantTest {

    /**
     * The bundled lists, passed explicitly. `Merchant`'s default argument
     * still exists, but production now threads the *active* pack's lists
     * through instead -- so a test that relied on the default would be
     * exercising a path the app no longer takes.
     */
    private val normalization =
        Graph.parsePack().merchantNormalization

    private fun agree(a: String?, b: String?): Boolean =
        Dedup.merchantsAgree(a, b, normalization)

    @Test
    fun anAbsentMerchantOnEitherSideCountsAsAgreement() {
        assertTrue(agree(null, "STARBUCKS KLCC"))
        assertTrue(agree("STARBUCKS KLCC", null))
        assertTrue(agree(null, null))
        // Blank is absent. A card-alert app that posts an amount and a space
        // is the case layer 2 exists for, and treating it as disagreement
        // would let the purchase be counted twice.
        assertTrue(agree("   ", "STARBUCKS KLCC"))
    }

    @Test
    fun oneSharedTokenIsEnough() {
        assertTrue(agree("STARBUCKS KLCC", "STARBUCKS PAVILION"))
        assertTrue(agree("99 SPEEDMART", "SPEEDMART"))
    }

    /**
     * Case and the acquirer's noise are why the comparison goes through
     * [my.pinged.parse.Merchant.clean] rather than comparing raw strings. No
     * acquirer prefix here: a prefixed merchant is gateway-only, which agrees
     * with anything before cleaning is reached.
     */
    @Test
    fun cleaningIsWhatMakesTheClauseWork() {
        assertTrue(agree("STARBUCKS", "Starbucks KLCC MYS"))
        assertTrue(agree("SHOPEE SDN BHD", "shopee"))
    }

    /**
     * #255: MAE's card spend at the FPX gateway names the gateway, not the
     * payee, so it shares no token with the FPX notice that names the payee.
     * Both merchants are the committed fixtures' own.
     */
    @Test
    fun aGatewayOnlyMerchantCountsAsAbsent() {
        assertTrue(agree("FPX-CREDIT CARD-RPS KUALA LUMPUR MYS", "IBILIK 02"))
        assertTrue(agree("IBILIK 02", "FPX-CREDIT CARD-RPS KUALA LUMPUR MYS"))
        // Matched as the pack matches its prefixes: case folded.
        assertTrue(agree("fpx-credit card-rps kuala lumpur mys", "IBILIK 02"))
    }

    @Test
    fun aMerchantThatOnlyContainsAPrefixStillHasToShareAToken() {
        assertFalse(agree("PAYMENT FPX-CREDIT CARD", "IBILIK 02"))
    }

    @Test
    fun twoDifferentMerchantsDoNotAgree() {
        assertFalse(agree("99 SPEEDMART", "GUARDIAN PHARMACY"))
        assertFalse(agree("STARBUCKS KLCC", "MYNEWS MIDVALLEY"))
    }
}
