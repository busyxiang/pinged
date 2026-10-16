package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class SkeletonTest {
    @Test fun `amount and account number both generalize`() = assertEquals(
        "your fd of rm# has matured. view details in mae.",
        Skeleton.of("Your FD of RM5,000.00 has matured. View details in MAE.")
    )

    @Test fun `two messages differing only in amount share a skeleton`() = assertEquals(
        Skeleton.of("RM88.00 telah ditolak dari akaun anda 1234."),
        Skeleton.of("RM1,250.75 telah ditolak dari akaun anda 9876.")
    )

    @Test fun `messages differing in wording do not share a skeleton`() {
        val a = Skeleton.of("RM88.00 telah ditolak dari akaun anda 1234.")
        val b = Skeleton.of("RM88.00 telah dikreditkan ke akaun anda 1234.")
        assert(a != b)
    }

    @Test fun `non breaking space does not defeat equality`() = assertEquals(
        Skeleton.of("RM\u00A088.00 debited"),
        Skeleton.of("RM 88.00 debited")
    )

    /**
     * Spec 5.2's `concat`, which is what a template reads: the title, then the
     * big text where there is one, else the text. A skeleton of anything else
     * would let two captures the matcher reads alike be told apart.
     */
    @Test fun `a capture's skeleton is of its title and the body the matcher reads`() {
        assertEquals(
            "maybank your fd of rm# has matured.",
            Skeleton.ofCapture(title = "Maybank", text = "Your FD has matured.", bigText = "Your FD of RM5,000.00 has matured."),
        )
        assertEquals(
            "your fd of rm# has matured.",
            Skeleton.ofCapture(title = null, text = "Your FD of RM9.00 has matured.", bigText = null),
        )
    }
}
