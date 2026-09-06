package my.pinged.capture

import java.security.MessageDigest

internal object ContentHash {
    /**
     * sha256(package | user | normalized text). **No timestamp.**
     *
     * Identity comes from content; recency is a separate predicate (spec 4, spec
     * 7.2). Hashing a second-bucketed `postTime` alongside the text and then
     * looking for repeats "within 60 seconds" is self-cancelling: two posts a
     * second apart hash differently.
     *
     * The consequence is that two genuinely separate identical payments have the
     * same identity by construction, which is why spec 7.2's rule 1 carries a
     * ten-minute window rather than leaning on the hash.
     *
     * The separator is part of the input so that ("com.a", 1, "0x") and ("com.a",
     * 10, "x") cannot concatenate to the same string.
     */
    fun of(pkg: String, user: Int, normalizedText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$pkg|$user|$normalizedText".toByteArray(Charsets.UTF_8))
        // `toHexString`, not String.format("%02x"): Formatter is
        // locale-sensitive by construction and spec 15.7 requires every
        // machine-facing string operation to be locale-independent. A hash
        // written with non-ASCII digits under some default locale would be a
        // capture nothing else can match. `HexFormat.Default` is lowercase and
        // locale-independent by construction, which is that guarantee without
        // a hand-rolled table to get wrong.
        return digest.toHexString()
    }
}
