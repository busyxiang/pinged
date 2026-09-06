package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountTest {
    @Test fun `plain amount`() = assertEquals(1200L, Amount.toSen("12.00"))
    @Test fun `with currency prefix`() = assertEquals(1200L, Amount.toSen("RM12.00"))
    @Test fun `with space after prefix`() = assertEquals(5000L, Amount.toSen("RM 50.00"))
    @Test fun `myr prefix`() = assertEquals(5000L, Amount.toSen("MYR50.00"))
    @Test fun `thousands separator`() = assertEquals(123450L, Amount.toSen("RM1,234.50"))
    @Test fun `no decimal part`() = assertEquals(1200L, Amount.toSen("RM12"))
    @Test fun `one decimal place`() = assertEquals(1250L, Amount.toSen("RM12.5"))
    @Test fun `non breaking space before amount`() =
        assertEquals(5000L, Amount.toSen("RM\u00A050.00"))

    @Test fun `three decimals is rejected`() = assertNull(Amount.toSen("RM12.005"))
    @Test fun `zero is rejected`() = assertNull(Amount.toSen("RM0.00"))
    @Test fun `above the ceiling is rejected`() = assertNull(Amount.toSen("RM1,000,000.01"))
    @Test fun `negative is rejected`() = assertNull(Amount.toSen("-RM12.00"))
    @Test fun `letters are rejected`() = assertNull(Amount.toSen("RM12.00OFF"))
    @Test fun `empty is rejected`() = assertNull(Amount.toSen(""))
    @Test fun `very large digit string is rejected`() = assertNull(Amount.toSen("1234567890123456789012345"))

    // The 25-digit case above is caught by the ceiling check alone, so it did
    // not exercise the overflow guards at all. 19 digits chosen so that
    // multiplying by 100 wraps to exactly 5000 in a Long: without the length
    // guard and the exact-conversion guard, this input fabricates RM50.00.
    @Test fun `a digit string that wraps a long is rejected`() =
        assertNull(Amount.toSen("4611686018427387954"))
}
