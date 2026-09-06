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
}
