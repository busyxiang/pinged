package my.pinged

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling R42, held by the build rather than by convention.
 *
 * `runInTransaction` and `openHelper` reach the file around Room's connection
 * manager, and on a closed instance they reopen it: measured on
 * emulator-5554, `runInTransaction` on an instance `Databases.reset` had
 * closed returned `Success(7)`, left `isOpen = true`, recreated `pinged.db`
 * after a delete's destroy, and the next `Databases.shared` threw
 * `DatabaseKeyUnavailableException` -- a key missing beside a database
 * present, which is spec 11.1's unrecoverable state manufactured by the app
 * itself. `Databases.whileLive` and `requireLive` refuse a closed instance
 * before either runs, and nothing makes a new caller use them.
 *
 * So every production source set in every module is read, and each use of
 * either name must be one of [ALLOWED], which says why that one is safe.
 * **Counted per file**, so a second use added beside an allowed one fails
 * too. Comments and literal text are removed first by [kotlinCode] -- the
 * KDocs that explain R42 name both -- and a string template's `$name` and
 * `${...}` are kept, being code.
 *
 * **It fails closed.** A source [kotlinCode] cannot lex to the end, or whose
 * code is left with unbalanced brackets, fails this test rather than being
 * counted: a lexer that lost its place in a file would otherwise read the
 * rest of it as text and see nothing. And a `.java` source fails it, because
 * the lexer reads Kotlin and a Java caller would name `getOpenHelper`.
 *
 * Only these two names. `withTransaction`, `useWriterConnection` and
 * `beginTransaction` are not used here and have not been measured on a
 * closed instance, so nothing is claimed about them.
 *
 * Read from disk rather than from the classpath because the subject is the
 * source. `app/build.gradle.kts` declares those sources as this task's
 * inputs, and [theSourcesReadHereAreTheOnesGradleWatches] fails if its rule
 * and [productionSources]' ever pick different files, so a call site in a
 * module Gradle is not watching cannot leave this up to date.
 */
class RawDatabaseAccessTest {

    @Test fun everyUseOfARawDatabasePathIsOneThatIsKnownToBeSafe() {
        val sources = productionSources()
        assertTrue(
            "Found no production sources under $root, so this test checked nothing",
            sources.size > 50,
        )
        val java = sources.filter { it.extension == "java" }
        assertEquals(
            "A Java production source exists. kotlinCode reads Kotlin, and a Java caller " +
                "reaches the helper as getOpenHelper(), which WATCHED does not name -- teach " +
                "both before adding one",
            emptyList<String>(),
            java.map(::relative),
        )
        val found = sources.associate { file -> relative(file) to uses(file) }.filterValues { it.isNotEmpty() }
        val allowed = ALLOWED.mapValues { (_, site) -> site.uses }

        assertEquals(
            "A production source reaches the database file around Room's connection " +
                "manager. On an instance Databases.reset has closed, runInTransaction " +
                "and openHelper reopen the file outside Databases' gate (ruling R42) -- " +
                "wrap the call in Databases.whileLive, or requireLive on an instance " +
                "Databases never serves, and add it to ALLOWED with the reason it is safe. " +
                "Allowed now:\n" + ALLOWED.entries.joinToString("\n") { (path, site) -> "  $path: ${site.reason}" },
            allowed,
            found,
        )
    }

    /**
     * **The files this test reads are the files Gradle re-runs it for.** The
     * build script cannot share [productionSources]' code, so it hands over a
     * digest of what its own rule picked; a module deeper than its patterns
     * reach, or a directory one side skips and the other does not, lands
     * here instead of leaving the test up to date over a new call site.
     */
    @Test fun theSourcesReadHereAreTheOnesGradleWatches() {
        val declared = System.getProperty(DIGEST_PROPERTY)
            ?: throw AssertionError(
                "$DIGEST_PROPERTY is not set, so nothing says which sources Gradle watches " +
                    "for this test. app/build.gradle.kts sets it on every Test task",
            )
        val read = productionSources().map(::relative).sorted()

        assertEquals(
            "app/build.gradle.kts declares a different set of production sources from the " +
                "ones this test reads, so a change to one it reads can leave it up to date. " +
                "Read here:\n  " + read.joinToString("\n  "),
            declared,
            digestOf(read),
        )
    }

    @Test fun aCallInsideAStringTemplateIsCode() {
        val source = """
            fun f() {
                Log.i("x", "${'$'}{db.openHelper.writableDatabase.version}")
                Log.i("x", ${"\"\"\""}${'$'}{db.runInTransaction { 1 }}${"\"\"\""})
                Log.i("x", "${'$'}openHelper")
            }
        """.trimIndent()

        assertEquals(
            "A call inside a string template was read as text, and a real reopen path " +
                "passes unseen",
            mapOf("openHelper" to 2, "runInTransaction" to 1),
            countsIn(source),
        )
    }

    @Test fun aRawStringEndingInExtraQuotesClosesOnItsLastThree() {
        val q = "\"\"\""
        val source = """
            val sql = ${q}SELECT * FROM "txn${q}"
            fun f() = db.openHelper.writableDatabase
        """.trimIndent()

        assertEquals(
            "A raw string ending in \"\"\"\" was closed on its first three quotes, so the " +
                "fourth opened a string that hid the code after it",
            mapOf("openHelper" to 1),
            countsIn(source),
        )
    }

    /** The one use here is the last line's: `$$"..."` takes `$$` to start a template, so `$` alone is text. */
    @Test fun whatIsNotCodeIsNotCounted() {
        val source = """
            /** Uses `openHelper` -- /* nested runInTransaction */ still a comment. */
            // runInTransaction
            val a = "openHelper \" ${'$'} runInTransaction"
            val b = '"'
            val c = ${'$'}${'$'}"${'$'}openHelper ${'$'}{runInTransaction}"
            val d = ${'$'}${'$'}"${'$'}${'$'}openHelper"
        """.trimIndent()

        assertEquals(
            "Comments and literal text were counted as code, or the one `$$` template " +
                "among them was not",
            mapOf("openHelper" to 1),
            countsIn(source),
        )
    }

    @Test fun aSourceThatDoesNotLexFailsRatherThanPassing() {
        for (broken in listOf("val a = \"open", "/* open", "fun f() { g(", "val s = \"\"\"x", "val t = \"\${a\"")) {
            assertTrue(
                "kotlinCode read `$broken` to the end without complaint, so a lexer that " +
                    "loses its place passes the file it lost it in",
                runCatching { kotlinCode(broken) }.isFailure,
            )
        }
    }

    @Test fun onlyBuildOutputIsSkipped() {
        assertFalse(
            "A source package named `build` was skipped as if it were build output",
            skipped("core/data/src/main/kotlin/my/pinged/data/build".split('/')),
        )
        assertTrue("A module's build output was read", skipped("core/data/build".split('/')))
        assertTrue("The root's build output was read", skipped(listOf("build")))
    }

    private fun countsIn(source: String): Map<String, Int> =
        identifiers(kotlinCode(source)).filter { it in WATCHED }.groupingBy { it }.eachCount()

    /** Every `.kt` and `.java` file outside a module's test source sets. */
    private fun productionSources(): List<File> =
        root.walkTopDown()
            .onEnter { dir -> dir == root || !skipped(relative(dir).split('/')) }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { file -> sourceSetOf(file)?.let { set -> !set.startsWith("test") && !set.startsWith("androidTest") } == true }
            .toList()

    /** The `<set>` in `.../src/<set>/...`, or null for a file outside any module's `src`. */
    private fun sourceSetOf(file: File): String? {
        val parts = relative(file).split('/')
        val src = parts.indexOf("src")
        return if (src >= 0 && src + 1 < parts.size) parts[src + 1] else null
    }

    private fun uses(file: File): Map<String, Int> {
        val code = try {
            kotlinCode(file.readText())
        } catch (thrown: IllegalArgumentException) {
            throw AssertionError("${relative(file)} could not be lexed, so its calls cannot be counted: ${thrown.message}")
        }
        return identifiers(code).filter { it in WATCHED }.groupingBy { it }.eachCount()
    }

    private fun relative(file: File) = file.relativeTo(root).invariantSeparatorsPath

    /** The repository root: the nearest directory above this test's own that holds the settings script. */
    private val root: File by lazy {
        val here = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        generateSequence(here) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("No settings.gradle.kts above $here")
    }

    /** One allowed file, how many of each name it may use, and why they are safe. */
    private class Site(val uses: Map<String, Int>, val reason: String)

    private companion object {
        val WATCHED = setOf("runInTransaction", "openHelper")

        /** Set by `app/build.gradle.kts`: see [theSourcesReadHereAreTheOnesGradleWatches]. */
        const val DIGEST_PROPERTY = "pinged.productionSources"

        val ALLOWED = mapOf(
            "core/data/src/main/kotlin/my/pinged/data/Integrity.kt" to Site(
                mapOf("openHelper" to 1),
                "Integrity.check runs the pragma inside Databases.aside, on an instance " +
                    "opened for it and closed after under the monitor Databases.reset takes, " +
                    "which Databases never serves and so never closes underneath it.",
            ),
            "feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/ExportJson.kt" to Site(
                mapOf("runInTransaction" to 1, "openHelper" to 1),
                "ExportJson.write wraps the transaction, and the schema-version read " +
                    "inside it, in Databases.whileLive.",
            ),
            "feature/ledger/src/main/kotlin/my/pinged/ledger/settings/Transfers.kt" to Site(
                mapOf("runInTransaction" to 1),
                "Transfers.readable probes the export's instance with a transaction inside " +
                    "Databases.whileLive, which refuses a closed or retired instance.",
            ),
            "feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/ImportJson.kt" to Site(
                mapOf("runInTransaction" to 1),
                "ImportJson.read calls Databases.requireLive first, on an instance only " +
                    "Restore holds -- its probe or its rebuild -- which Databases never " +
                    "serves and so never closes.",
            ),
        )
    }
}

/**
 * Whether the directory at [path], relative to the root, is not searched.
 *
 * **Never inside a `src` tree**, where these are ordinary names: a package
 * called `build` holds sources. Outside one, a `build` directory is always
 * a project's output -- `:feature` and `:core` are projects too, so a module
 * named `build` beneath either would share its parent's build directory, and
 * Gradle does not allow that. `app/build.gradle.kts` applies the same rule.
 */
private fun skipped(path: List<String>): Boolean =
    "src" !in path.dropLast(1) && path.last() in setOf("build", ".gradle", ".git", ".idea", ".kotlin")

/** `count:sha256` of [paths] one per line, as `app/build.gradle.kts` writes it. */
private fun digestOf(paths: List<String>): String {
    val sha = MessageDigest.getInstance("SHA-256").digest(paths.joinToString("\n").toByteArray())
    return "${paths.size}:" + sha.joinToString("") { "%02x".format(it) }
}

/**
 * [source] with every comment and every run of literal text replaced by a
 * space, so what is left is code -- including the code inside a string
 * template, which runs.
 *
 * A lexer rather than a pattern because Kotlin's strings nest: `"${f("x")}"`
 * is a string holding code holding a string. The rules are Kotlin's own:
 * block comments nest; a raw string closes on the **last** three quotes of
 * the first run of three or more, so `"""a""""` holds `a"`; a template is
 * `$name` or `${...}`, and `$$"..."` needs `$$` to start one; a backticked
 * name is a name.
 *
 * **Throws [IllegalArgumentException]** on a literal or comment that never
 * closes and on code whose brackets do not balance, since either means the
 * lexer has lost its place and what follows cannot be trusted.
 */
internal fun kotlinCode(source: String): String = KotlinLexer(source).lex()

private class KotlinLexer(private val source: String) {
    private val out = StringBuilder(source.length)
    private var i = 0

    fun lex(): String {
        code(inTemplate = false)
        val code = out.toString()
        for ((open, close) in listOf('{' to '}', '(' to ')', '[' to ']')) {
            var depth = 0
            for (c in code) {
                if (c == open) depth++
                if (c == close) depth--
                require(depth >= 0) { "a '$close' with no '$open' before it" }
            }
            require(depth == 0) { "$depth '$open' never closed" }
        }
        return code
    }

    /** Code up to the end, or inside a template up to the `}` that closes it. */
    private fun code(inTemplate: Boolean) {
        var depth = 0
        while (i < source.length) {
            val c = source[i]
            when {
                at("//") -> while (i < source.length && source[i] != '\n') i++
                at("/*") -> blockComment()
                c == '$' && dollarsThenQuote() -> {
                    var dollars = 0
                    while (source[i] == '$') { dollars++; i++ }
                    string(dollars)
                }
                c == '"' -> string(dollars = 1)
                c == '\'' -> char()
                c == '`' -> backticked()
                c == '{' -> { depth++; out.append(c); i++ }
                c == '}' && inTemplate && depth == 0 -> { i++; return }
                c == '}' -> { depth--; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        require(!inTemplate) { "a \${ template that never closes" }
    }

    private fun blockComment() {
        val start = i
        var depth = 0
        do {
            require(i < source.length) { "a comment opened at offset $start never closes" }
            when {
                at("/*") -> { depth++; i += 2 }
                at("*/") -> { depth--; i += 2 }
                else -> i++
            }
        } while (depth > 0)
        out.append(' ')
    }

    /** A string at [i], its templates starting with [dollars] `$`s. */
    private fun string(dollars: Int) {
        val start = i
        val raw = at("\"\"\"")
        i += if (raw) 3 else 1
        while (true) {
            require(i < source.length) { "a string opened at offset $start never closes" }
            val c = source[i]
            when {
                raw && at("\"\"\"") -> {
                    while (i < source.length && source[i] == '"') i++
                    break
                }
                !raw && c == '"' -> { i++; break }
                !raw && c == '\\' -> i += 2
                !raw && c == '\n' -> throw IllegalArgumentException("a line break in the string opened at offset $start")
                c == '$' -> template(dollars)
                else -> i++
            }
        }
        out.append(' ')
    }

    /** At a `$` inside a string: a template if [dollars] of them start one, else text. */
    private fun template(dollars: Int) {
        var n = 0
        while (source.getOrNull(i + n) == '$') n++
        val after = source.getOrNull(i + n)
        if (n < dollars) { i += n; return }
        i += n
        when {
            after == '{' -> {
                i++
                out.append(' ')
                code(inTemplate = true)
                out.append(' ')
            }
            after != null && (after.isLetter() || after == '_') -> {
                out.append(' ')
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) out.append(source[i++])
                out.append(' ')
            }
        }
    }

    private fun char() {
        val start = i++
        while (true) {
            require(i < source.length && source[i] != '\n') { "a character literal opened at offset $start never closes" }
            when (source[i]) {
                '\\' -> i += 2
                '\'' -> { i++; break }
                else -> i++
            }
        }
        out.append(' ')
    }

    /** A backticked name is still a name, so its text is kept, without the quotes. */
    private fun backticked() {
        val start = i++
        out.append(' ')
        while (true) {
            require(i < source.length && source[i] != '\n') { "a backticked name opened at offset $start never closes" }
            if (source[i] == '`') { i++; break }
            out.append(source[i++])
        }
        out.append(' ')
    }

    private fun at(text: String) = source.startsWith(text, i)

    private fun dollarsThenQuote(): Boolean {
        var j = i
        while (source.getOrNull(j) == '$') j++
        return source.getOrNull(j) == '"'
    }
}

/** Every identifier in [code], in order. */
private fun identifiers(code: String): List<String> {
    val found = mutableListOf<String>()
    var i = 0
    while (i < code.length) {
        if (code[i].isJavaIdentifierStart()) {
            val start = i
            while (i < code.length && code[i].isJavaIdentifierPart()) i++
            found += code.substring(start, i)
        } else {
            i++
        }
    }
    return found
}
