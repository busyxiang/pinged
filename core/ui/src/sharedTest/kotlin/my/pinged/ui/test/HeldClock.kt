package my.pinged.ui.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.PingedTheme

/**
 * A screen drawn under a clock that moves only when the test moves it, for
 * the Robolectric tests of a read in flight (#238): "before 200ms" is then a
 * time the test chose rather than one the machine happened to reach.
 *
 * Compiled into `:feature:ledger`'s and `:feature:charts`' JVM tests as a
 * source directory, so neither module gains a dependency for it. Call [hold]
 * before `setContent`, or let [draw] do both.
 *
 * [onFrame] runs after every step [at] takes, with the time reached: a test
 * that must see a state on no frame at all records it there.
 */
internal class HeldClock(
    val compose: ComposeContentTestRule,
    private val onFrame: (now: Long) -> Unit = {},
) {
    /** Milliseconds since the screen was first composed. */
    var now = 0L
        private set

    /** Counts [at]'s time from here: from a pick or a word typed, not from the first composition. */
    fun restart() {
        now = 0L
    }

    fun hold() {
        compose.mainClock.autoAdvance = false
    }

    /** [content] in the theme, with [heldBy] as what holds the database, on a held clock. */
    fun draw(heldBy: HeldBy? = null, content: @Composable () -> Unit) {
        hold()
        compose.setContent {
            PingedTheme { CompositionLocalProvider(LocalHeldBy provides heldBy) { content() } }
        }
    }

    /**
     * Advances to [millis], a frame at a time: a state set mid-test reaches
     * the screen over a few frames, and one jump of the clock runs only the
     * first of them. A landed paging page was still missing after a single
     * 160ms jump.
     */
    fun at(millis: Long) {
        while (now < millis) {
            val step = minOf(FRAME, millis - now)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            now += step
            onFrame(now)
        }
    }

    /** Every text on screen, unmerged, so a dash inside a row is its own entry. */
    fun texts(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    fun count(text: String): Int = texts().count { it == text }

    /** How many nodes carry [tag], unmerged. */
    fun tagged(tag: String): Int =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Texts holding a digit, less [known]: what would be a figure the read has not supplied. */
    fun figures(vararg known: String): List<String> = texts().filter { it !in known && it.any(Char::isDigit) }

    companion object {
        /** One frame at 60Hz, the step [at] takes. */
        const val FRAME = 16L
    }
}
