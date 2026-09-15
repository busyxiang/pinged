package my.pinged.parse

import java.io.File
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
     * One corpus fixture: a header block of expectations and the notification
     * body they are about.
     */
    data class Fixture(
        val name: String,
        val pkg: String,
        val title: String?,
        val bigText: String?,
        val expect: String,
        val rule: String?,
        val amountSen: Long?,
        val merchant: String?,
        val merchantDisplay: String?,
        val direction: Direction?,
        val body: String,
    )

    /**
     * The corpus, listed and parsed once for the whole run.
     *
     * Here rather than private to `CorpusTest` because a fixture holds a real
     * notification off a phone, and a test that cannot reach one retypes it --
     * a second copy with nothing linking it to the sample, which is how the
     * MAE card merchant came to be spelled two ways.
     */
    val corpus: List<Fixture> by lazy {
        val dir = File(ParseFixtures::class.java.getResource("/fixtures")!!.toURI())
        val files = dir.listFiles { f: File -> f.extension == "txt" }.orEmpty().sortedBy { it.name }
        check(files.isNotEmpty()) { "No fixtures found" }
        files.map(::parse)
    }

    /** One fixture by file name, for a test that is about that sample. */
    fun fixture(name: String): Fixture =
        corpus.firstOrNull { it.name == name }
            ?: error("no fixture named '$name'; the corpus holds ${corpus.map { it.name }}")

    private fun parse(file: File): Fixture {
        // Tolerant of CRLF, and explicit about the one structural rule: a
        // header block, a blank line, then the notification body. Destructuring
        // a short split threw an opaque IndexOutOfBoundsException instead.
        val parts = file.readText().replace("\r\n", "\n").split("\n\n", limit = 2)
        require(parts.size == 2) {
            "${file.name} is malformed: expected a header block, a blank line, then the " +
                "notification body, but found no blank line"
        }
        val (header, body) = parts
        val h = header.lineSequence()
            .filter { it.contains(':') }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return Fixture(
            name = file.name,
            pkg = requireNotNull(h["package"]) { "${file.name} has no package" },
            title = h["title"],
            bigText = h["big_text"],
            expect = requireNotNull(h["expect"]) { "${file.name} has no expect" },
            rule = h["rule"],
            amountSen = h["amount_sen"]?.toLong(),
            merchant = h["merchant"],
            merchantDisplay = h["merchant_display"],
            direction = h["direction"]?.let { name ->
                Direction.entries.firstOrNull { it.name == name }
                    ?: error("${file.name} names an unknown direction '$name'")
            },
            body = body.trim(),
        )
    }

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
