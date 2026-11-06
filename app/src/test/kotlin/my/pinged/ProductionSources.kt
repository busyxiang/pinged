package my.pinged

import java.io.File

/**
 * Every module's production sources, read from disk, and the code in each:
 * what `RawDatabaseAccessTest` and `RawRunnerAccessTest` scan. One walk for
 * both, so the files they read are the files `app/build.gradle.kts` declares
 * as their input, which `RawDatabaseAccessTest.theSourcesReadHereAreTheOnesGradleWatches`
 * checks.
 */
internal object ProductionSources {
    /** The repository root: the nearest directory above this test's own that holds the settings script. */
    val root: File by lazy {
        val here = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        generateSequence(here) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("No settings.gradle.kts above $here")
    }

    /** Every `.kt` and `.java` file outside a module's test source sets. */
    fun all(): List<File> =
        root.walkTopDown()
            .onEnter { dir -> dir == root || !skipped(relative(dir).split('/')) }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { file -> sourceSetOf(file)?.let { set -> !set.startsWith("test") && !set.startsWith("androidTest") } == true }
            .toList()

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /**
     * How many times each of [watched] is named as code in [file], comments
     * and literal text removed by [kotlinCode]. **Fails closed**: a file the
     * lexer cannot read to the end is an assertion failure, not a file with
     * no uses.
     */
    fun uses(file: File, watched: Set<String>): Map<String, Int> {
        val code = try {
            kotlinCode(file.readText())
        } catch (thrown: IllegalArgumentException) {
            throw AssertionError("${relative(file)} could not be lexed, so its calls cannot be counted: ${thrown.message}")
        }
        return identifiers(code).filter { it in watched }.groupingBy { it }.eachCount()
    }

    /** The `<set>` in `.../src/<set>/...`, or null for a file outside any module's `src`. */
    private fun sourceSetOf(file: File): String? {
        val parts = relative(file).split('/')
        val src = parts.indexOf("src")
        return if (src >= 0 && src + 1 < parts.size) parts[src + 1] else null
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
internal fun skipped(path: List<String>): Boolean =
    "src" !in path.dropLast(1) && path.last() in setOf("build", ".gradle", ".git", ".idea", ".kotlin")

/** Every identifier in [code], in order. */
internal fun identifiers(code: String): List<String> {
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
