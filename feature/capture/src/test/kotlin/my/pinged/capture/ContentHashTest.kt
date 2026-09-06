package my.pinged.capture

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No Robolectric here: hashing is plain JVM work, and a test that does not
 * need the framework should not pay for it.
 */
class ContentHashTest {
    @Test
    fun `the same content hashes the same regardless of spacing`() = assertEquals(
        ContentHash.of("com.a", 0, "payment of rm12.00"),
        ContentHash.of("com.a", 0, "payment of rm12.00"),
    )

    @Test
    fun `a different user handle hashes differently`() = assertNotEquals(
        ContentHash.of("com.a", 0, "x"),
        ContentHash.of("com.a", 10, "x"),
    )

    @Test
    fun `a different package hashes differently`() = assertNotEquals(
        ContentHash.of("com.a", 0, "x"),
        ContentHash.of("com.b", 0, "x"),
    )

    /**
     * The separator has to be part of the input, or (package, user, text)
     * triples that concatenate to the same string collide: `("com.a", 1, "0x")`
     * and `("com.a", 10, "x")` are different captures.
     */
    @Test
    fun `the field separator prevents a concatenation collision`() = assertNotEquals(
        ContentHash.of("com.a", 1, "0x"),
        ContentHash.of("com.a", 10, "x"),
    )

    /**
     * A fixed vector, because every other test here would still pass if the
     * digest changed algorithm, charset or hex encoding -- and a changed hash
     * function silently reclassifies every existing capture's duplicate
     * status.
     *
     * Produced independently of this code:
     * `printf 'com.a|0|payment of rm12.00' | sha256sum`
     */
    @Test
    fun `the digest matches an independently computed vector`() = assertEquals(
        "9d4f95fdca470dbcecaaff80fcaa5970fcbd72edebcad695a62c2435c7140558",
        ContentHash.of("com.a", 0, "payment of rm12.00"),
    )

    /**
     * Spec 15.7: every machine-facing string operation is `Locale.ROOT`. Hex
     * formatting through `String.format` inherits the default locale, and a
     * locale whose digits are not ASCII would write a hash no other run can
     * reproduce.
     */
    @Test
    fun `the hex encoding is lowercase ascii under a hostile default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val hash = ContentHash.of("com.a", 0, "payment of rm12.00")
            assertEquals(64, hash.length)
            assertTrue(hash, hash.all { it in '0'..'9' || it in 'a'..'f' })
            assertEquals(
                "9d4f95fdca470dbcecaaff80fcaa5970fcbd72edebcad695a62c2435c7140558",
                hash,
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * Spec 4 and 7.2: identity comes from content, recency is a separate
     * predicate. Nothing time-varying may reach the digest, so the same
     * arguments hash the same across a clock change -- which is exactly what
     * makes two identical payments identical, and why rule 1 needs its
     * ten-minute window rather than the hash.
     */
    @Test
    fun `the digest is stable across repeated calls with a clock in between`() {
        val first = ContentHash.of("com.a", 0, "payment of rm12.00")
        val secondAtAnotherInstant = ContentHash.of("com.a", 0, "payment of rm12.00")

        assertEquals(first, secondAtAnotherInstant)
    }
}
