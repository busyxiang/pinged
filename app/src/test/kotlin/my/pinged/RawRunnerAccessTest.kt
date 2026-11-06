package my.pinged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #376, held by the build rather than by convention: a screen reads, writes
 * and looks up through its `ScreenRead`, whose turn is where a ledger
 * replacement's claim refuses a write. `ReadRunner.read` and `write` skip
 * that turn, so they carry `@RawRunnerAccess`, an ERROR-level marker Gradle
 * opts only test compilations into, and a production source that calls one
 * has to name the marker in an `@OptIn`.
 *
 * So every production source set in every module is read for the name, and
 * each file outside `:core:read`'s own `main`, which declares the marker and
 * is the turn, must be one of [ALLOWED], which says why that one is not a
 * screen. **Counted per file**, the import included, so a second opt-in added
 * beside an allowed one fails too. Comments and literal text are removed
 * first, as `RawDatabaseAccessTest` removes them, so the KDocs that explain
 * the marker are not counted. **It fails closed**: a source the lexer cannot
 * read to the end fails this test rather than counting as none.
 *
 * `sharedTest` is a production source set by that test's rule, which is why
 * `ScriptedRunner` is listed: it is compiled into every module's JVM tests
 * and into nothing that ships.
 */
class RawRunnerAccessTest {

    @Test fun onlyTheReadModuleAndTheKnownNonScreensOptInToCallingARunner() {
        val sources = ProductionSources.all()
        assertTrue(
            "Found no production sources under ${ProductionSources.root}, so this test checked nothing",
            sources.size > 50,
        )
        val found = sources
            .filterNot { ProductionSources.relative(it).startsWith(OWN) }
            .associate { file -> ProductionSources.relative(file) to (ProductionSources.uses(file, setOf(MARKER))[MARKER] ?: 0) }
            .filterValues { it > 0 }

        assertEquals(
            "A production source names $MARKER, which opts it into calling ReadRunner.read or " +
                "write outside a ScreenRead, where a ledger replacement's claim does not refuse " +
                "its write. A screen uses ScreenRead.write or ScreenRead.lookup; anything else is " +
                "added to ALLOWED with the reason it is not a screen. Allowed now:\n" +
                ALLOWED.entries.joinToString("\n") { (path, site) -> "  $path: ${site.reason}" },
            ALLOWED.mapValues { (_, site) -> site.uses },
            found,
        )
    }

    /** One allowed file, how many times it may name the marker, and why it is not a screen. */
    private class Site(val uses: Int, val reason: String)

    private companion object {
        const val MARKER = "RawRunnerAccess"

        /** `:core:read`'s own sources: the marker's declaration, and `ScreenRead`, the turn itself. */
        const val OWN = "core/read/src/main/"

        val ALLOWED = mapOf(
            "feature/capture/src/main/kotlin/my/pinged/capture/GuardedRunner.kt" to Site(
                3,
                "The production ReadRunner. Its overrides carry the marker on, so a caller that holds " +
                    "it as itself needs an opt-in too; it calls neither.",
            ),
            "app/src/main/kotlin/my/pinged/AppStatus.kt" to Site(
                3,
                "AppStatus.probe and exportOverdue: the capture banner's health probe and the backup " +
                    "reminder's count, read on every foreground. Neither is a screen's read, and " +
                    "neither writes.",
            ),
            "core/read/src/sharedTest/kotlin/my/pinged/read/test/ScriptedRunner.kt" to Site(
                2,
                "The ReadRunner JVM tests drive. Compiled into test source sets only.",
            ),
        )
    }
}
