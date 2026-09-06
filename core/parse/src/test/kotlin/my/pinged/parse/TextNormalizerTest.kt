package my.pinged.parse

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    @Test fun `collapses runs of whitespace`() =
        assertEquals("a b", TextNormalizer.forMatch("a   \n  b"))

    @Test fun `non breaking space becomes a plain space`() =
        assertEquals("RM 50.00", TextNormalizer.forMatch("RM\u00A050.00"))

    @Test fun `narrow no break space becomes a plain space`() =
        assertEquals("RM 50.00", TextNormalizer.forMatch("RM\u202F50.00"))

    @Test fun `strips zero width and bidi marks`() =
        assertEquals("RM50.00", TextNormalizer.forMatch("RM\u200B50.00\u200E"))

    @Test fun `forMatch preserves case`() =
        assertEquals("Payment To Ali", TextNormalizer.forMatch("Payment To Ali"))

    @Test fun `forCompare folds case with root locale`() =
        assertEquals("payment to ali", TextNormalizer.forCompare("Payment To ALI"))

    @Test fun `line and paragraph separators become plain spaces`() {
        assertEquals("a b", TextNormalizer.forMatch("a\u2028b"))
        assertEquals("a b", TextNormalizer.forMatch("a\u2029b"))
        assertEquals("a b", TextNormalizer.forMatch("a\u0085b"))
    }

    // Locale.ROOT is not decoration. Under a Turkish default locale a
    // locale-sensitive lowercase turns "PAID IN FULL" into "paıd ın full",
    // and every condition term containing an i stops matching for that user.
    @Test fun `forCompare is unaffected by a dotless i locale`() {
        ParseFixtures.withLocale("tr-TR") {
            assertEquals("paid in full", TextNormalizer.forCompare("PAID IN FULL"))
        }
    }
}
