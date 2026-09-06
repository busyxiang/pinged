package my.pinged.parse

import java.util.Locale

/**
 * Shared setup for this module's tests, in the convention the rest of the
 * repository already follows (`Fixtures.kt`, `CaptureFixtures.kt`,
 * `TransferFixtures.kt`). The JVM test source set added by this branch was
 * the one that did not have it, so the bundled pack was located and parsed
 * from five separate places in four files, in two spellings.
 */
object ParseFixtures {
    /** The bundled pack's text, resolved from one place. */
    fun bundledPackText(): String =
        ParseFixtures::class.java.getResource(BUNDLED)?.readText()
            ?: error("$BUNDLED is not on the test classpath")

    /**
     * The bundled pack, parsed once for the whole run.
     *
     * `PackTest` deliberately does *not* use this where the load itself is
     * the assertion: a memoized pack cannot demonstrate that loading
     * succeeds.
     */
    val bundledPack: ParsePack by lazy { PackLoader.load(bundledPackText()) }

    private const val BUNDLED = "/pack.json"

    /**
     * Run [body] with [tag] as the default locale, restoring it afterwards.
     *
     * Two tests need this to pin the `Locale.ROOT` casing calls, and the
     * `finally` is the only thing keeping every later test in the JVM honest
     * -- Turkish lowercases `I` to a dotless `ı`, so a leaked default breaks
     * assertions in unrelated classes with no hint as to why. Written twice,
     * a third copy would eventually omit the restore.
     */
    fun withLocale(tag: String, body: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag(tag))
            body()
        } finally {
            Locale.setDefault(original)
        }
    }
}
