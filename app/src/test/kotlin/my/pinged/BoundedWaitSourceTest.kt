package my.pinged

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every instrumented `waitUntil` goes through `boundedWaitUntil`
 * (core/ui/src/sharedAndroidTest). A bare one checks its timeout only between
 * calls to its condition, so a condition that waits for idle on a main thread
 * that never idles hangs for good, holding the emulator every worktree
 * shares. The `waitUntil*Exists` and `waitUntilNodeCount` forms fetch
 * semantics inside the same loop, so they are refused too.
 *
 * Matched as text: an identifier followed by `(` or `{`. A KDoc naming one
 * in backticks or brackets is not a call and is not matched.
 *
 * Read from disk, so `app/build.gradle.kts` declares these sources as the
 * test task's input; otherwise a change to an androidTest file alone leaves
 * this test up to date.
 */
class BoundedWaitSourceTest {

    @Test fun everyInstrumentedWaitIsBounded() {
        val sources = instrumentedSources()
        assertTrue("Found no instrumented sources under $root, so this test checked nothing", sources.size > 50)
        val bare = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> "${relative(file)}:${i + 1}".takeIf { BARE.containsMatchIn(line) } }
        }
        assertEquals(
            "A bare waitUntil hangs while its condition waits for an idle that never comes; " +
                "call my.pinged.ui.test.boundedWaitUntil instead",
            emptyList<String>(),
            bare,
        )
    }

    @Test fun thePatternTellsACallFromAMention() {
        listOf(
            "compose.waitUntil(TIMEOUT) { done }",
            "runCatching { waitUntil(TIMEOUT) { condition() } }",
            "compose.waitUntil(\"a node\", 10_000) {",
            "compose.waitUntil { done }",
            "compose.waitUntilExactlyOneExists(hasText(\"x\"))",
            "compose.waitUntilNodeCount(hasText(\"x\"), 2)",
        ).forEach { assertTrue("Not refused: $it", BARE.containsMatchIn(it)) }
        listOf(
            "compose.boundedWaitUntil(TIMEOUT) { done }",
            "private fun waitUntilShown(text: String) {",
            " * a bare `waitUntil` fails the precondition",
            " * [ComposeTestRule.waitUntil] wrapped so a failure is the assertion",
        ).forEach { assertFalse("Refused: $it", BARE.containsMatchIn(it)) }
    }

    /** Every `.kt` file in a module's `androidTest`, or in a `sharedTest` an androidTest compiles. */
    private fun instrumentedSources(): List<File> =
        root.walkTopDown()
            .onEnter { dir -> dir == root || !skipped(dir) }
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> relative(file).split('/').let { it.indexOf("src").let { src -> src >= 0 && it.getOrNull(src + 1) in SETS } } }
            .toList()

    /** Tool state, and build output outside a `src` tree, where `build` can be a package. */
    private fun skipped(dir: File): Boolean =
        dir.name.startsWith(".") || (dir.name == "build" && "src" !in relative(dir).split('/'))

    private fun relative(file: File) = file.relativeTo(root).invariantSeparatorsPath

    /** The repository root: the nearest directory above this test's own that holds the settings script. */
    private val root: File by lazy {
        val here = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        generateSequence(here) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("No settings.gradle.kts above $here")
    }

    private companion object {
        val SETS = setOf("androidTest", "sharedTest")

        // No \s: see CLAUDE.md. A test pattern is no exception worth making.
        val BARE = Regex("""(?<![A-Za-z0-9_`])waitUntil(NodeCount|AtLeastOneExists|ExactlyOneExists|DoesNotExist)?[ \t]*[({]""")
    }
}
