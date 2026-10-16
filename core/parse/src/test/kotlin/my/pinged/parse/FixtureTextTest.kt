package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 5.6's "copy as fixture": what the unread list copies must be a file
 * the corpus reads as it stands, once its masks are applied.
 */
class FixtureTextTest {
    @Test fun `a copied capture parses as an unmatched fixture of the same notification`() {
        val copied = FixtureText.of(
            pkg = "com.maybank2u.life",
            label = "MAE",
            title = "Maybank",
            text = "Your FD has matured.",
            bigText = "Your FD of RM5,000.00\nhas matured. View details in MAE.",
        )

        val fx = ParseFixtures.parse("copied.txt", copied)

        assertEquals("com.maybank2u.life", fx.pkg)
        assertEquals("Maybank", fx.title)
        assertEquals("unmatched", fx.expect)
        assertEquals("Your FD of RM5,000.00\nhas matured. View details in MAE.", fx.body)
        assertTrue("the header says it is real: $copied", copied.contains("# A real MAE notification"))
    }

    @Test fun `a capture with no title has no title line`() {
        val fx = ParseFixtures.parse("copied.txt", FixtureText.of("my.boost", "Boost", null, "You spent RM23.50.", null))
        assertEquals(null, fx.title)
        assertEquals("You spent RM23.50.", fx.body)
    }
}
