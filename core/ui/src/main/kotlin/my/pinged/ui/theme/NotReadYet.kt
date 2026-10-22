package my.pinged.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import my.pinged.ui.CALENDAR

// A screen whose read has not landed (#238): the glossary's **Not read yet**,
// drawn from `design/Loading*.dc.html`. Never "skeleton" here: `Skeleton` is
// the parser's taught-message template.

/**
 * What a screen's read is waiting behind: `Databases.Holder` (#279), which
 * this module cannot name, since `:core:ui` may not depend on `:core:data`
 * (#81). `:app` maps one to the other and provides it as [LocalHeldBy].
 */
enum class HeldBy {
    /** An export, 1,243ms at 50,000 captures. */
    Export,

    /** The integrity check, 644ms at 50,000; never blamed on an export. */
    IntegrityCheck,
}

/**
 * What the database is held by now, or null for nothing: what
 * [ReadingStatus] names. `MainActivity` provides it from
 * `Databases.holder`; a test provides it directly.
 */
val LocalHeldBy = compositionLocalOf<HeldBy?> { null }

/** The status line while nothing holds the database. */
const val READING: String = "READING"

/** `design/LoadingWaiting.dc.html`'s status line, while an export holds the database. */
const val WAITING_FOR_AN_EXPORT: String = "WAITING FOR AN EXPORT TO FINISH"

/** [WAITING_FOR_AN_EXPORT]'s layout, while the integrity check holds the database (#238). */
const val WAITING_FOR_A_CHECK: String = "WAITING FOR A CHECK TO FINISH"

/**
 * How long [ReadingLayout] draws nothing. Most reads land well inside it:
 * Day 1.6ms median, Search 3-37ms, Settings' counts under 5ms, Charts
 * 45-51ms (#280), so drawing at once would flash a layout for one frame.
 */
const val READING_DELAY_MILLIS: Long = 200L

/**
 * A screen's layout while its read is in flight: nothing for
 * [READING_DELAY_MILLIS], then [content].
 *
 * Draw it only in the branch for a read in flight. The delay must never
 * hide a state the read has decided: an empty or unreadable result is a
 * branch of its own and draws at once, so this goes inside the screen's
 * `when`, never around it.
 *
 * The delay restarts each time this enters the composition. A read that
 * passes through two branches, each drawing its own layout (Spending's whole
 * screen, then its hero alone once the rows land), would restart it and take
 * a drawn dash away; such a screen times the read once with
 * [rememberReadingShown] and passes the answer to the other overload.
 */
@Composable
fun ReadingLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ReadingLayout(rememberReadingShown(true), modifier, content)
}

/**
 * [ReadingLayout] timed by its caller: [content] when [shown], from
 * [rememberReadingShown] over the whole read; nothing otherwise.
 */
@Composable
fun ReadingLayout(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) { if (shown) content() }
}

/**
 * True once [reading] has held for [READING_DELAY_MILLIS], false the moment
 * it stops. The delay runs from when it turned true and does not restart
 * while it stays true, whichever branch draws the layout meanwhile.
 */
@Composable
fun rememberReadingShown(reading: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(reading) {
        shown = false
        if (reading) {
            delay(READING_DELAY_MILLIS)
            shown = true
        }
    }
    return reading && shown
}

/**
 * The status line under a hero or a header: a 6dp outlined square and
 * [READING] in [Muted], or, while [heldBy] names a holder, what the read is
 * waiting for in [Stamp] (`design/LoadingWaiting.dc.html`).
 */
@Composable
fun ReadingStatus(modifier: Modifier = Modifier, heldBy: HeldBy? = LocalHeldBy.current) {
    StatusLine(READING, heldBy, modifier)
}

/** [words] in [Muted], or what the read waits behind in [Stamp]: [ReadingStatus] and [UpdatingStatus]. */
@Composable
private fun StatusLine(words: String, heldBy: HeldBy?, modifier: Modifier) {
    val (said, colour) = when (heldBy) {
        null -> words to Muted
        HeldBy.Export -> WAITING_FOR_AN_EXPORT to Stamp
        HeldBy.IntegrityCheck -> WAITING_FOR_A_CHECK to Stamp
    }
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).border(1.5.dp, colour))
        Text(said, style = MonoLabel, color = colour)
    }
}

/**
 * The figure a dash stands for when its caller names none: the Day screen's
 * 13sp mono numerals, which [ValueDash] and [DashLeader] both default to.
 */
val DashFigure: TextStyle = MonoNumerals.copy(fontSize = 13.sp)

/**
 * A value not read yet: [NOT_READ_YET] where the figure will go, in the
 * figure's own [style], so the line holds the height it will have.
 *
 * [Muted] rather than the boards' `#8a8175`, which is [Faint] and fails
 * WCAG's 4.5:1 for text.
 */
@Composable
fun ValueDash(modifier: Modifier = Modifier, style: TextStyle = DashFigure, color: Color = Muted) {
    Text(NOT_READ_YET, style = style, color = color, modifier = modifier)
}

/**
 * The Day screen's idiom for a figure not read yet: [label], a dotted leader
 * and a [ValueDash], on one baseline. A null [label] is a row whose name is
 * not known without a read either, as the hero's top three are.
 *
 * The leader stands 8dp clear of the text either side and 4.5dp above the
 * baseline: the boards' `margin: 0 8px 4px` under a 1px rule.
 */
@Composable
fun DashLeader(
    modifier: Modifier = Modifier,
    label: String? = null,
    labelStyle: TextStyle = TextStyle(fontFamily = Body, fontSize = 13.sp),
    valueStyle: TextStyle = DashFigure,
) {
    val lift = with(LocalDensity.current) { 4.5.dp.roundToPx() }
    Row(modifier.fillMaxWidth()) {
        // An empty label still sets the baseline the leader and dash sit on.
        Text(label.orEmpty(), style = labelStyle, color = Muted, modifier = Modifier.alignByBaseline())
        Spacer(
            Modifier
                .weight(1f)
                .height(1.dp)
                .alignBy { it.measuredHeight + lift }
                .dottedRule(inset = 8.dp),
        )
        ValueDash(Modifier.alignByBaseline(), style = valueStyle)
    }
}

/**
 * A cell waiting to be read: [shape] outlined in [dottedRule]'s dots, and
 * empty. **Never hatched**, because hatching means **Not captured**: a cell
 * not read yet says nothing about the money, and a hatched one says Pinged
 * was not watching.
 */
@Composable
fun DottedCell(modifier: Modifier = Modifier, shape: Shape = RectangleShape) {
    Box(modifier.dottedOutline(shape))
}

// A re-read in place (#285), `design/LoadingSearch.dc.html`: the screen keeps
// what it last read, dimmed, while a read for a changed question is in flight.

/** The status line while a screen re-reads in place. */
const val UPDATING: String = "UPDATING"

/**
 * How opaque the last read stays while a re-read is in flight: the board's
 * `opacity: 0.45`. Dimmed rather than hidden, so the user keeps their place.
 */
const val UPDATING_ALPHA: Float = 0.45f

/**
 * Whether to draw a re-read in place: true once [rereading] has held for
 * [READING_DELAY_MILLIS], false the moment it stops. The same delay as
 * [ReadingLayout]'s, so a re-read that lands inside it is never seen.
 *
 * [rereading] is true while the screen shows a read and a read for a changed
 * question has not landed: Search's words, a picked month, a sheet opened
 * again. The delay runs from when it turned true and does not restart while
 * it stays true, so a user typing steadily sees `UPDATING` after 200ms.
 */
@Composable
fun rememberUpdating(rereading: Boolean): Boolean = rememberReadingShown(rereading)

/**
 * The last read's content while [updating]: drawn at [UPDATING_ALPHA]. Put
 * it on the content the re-read will replace, not on the status line, the
 * header the user just changed, or a count already drawn as a dash.
 */
fun Modifier.updating(updating: Boolean): Modifier = if (updating) alpha(UPDATING_ALPHA) else this

/**
 * [UPDATING]'s status line, as [ReadingStatus] draws [READING]: under the
 * hero, outside the dimmed content. [about] names the words the re-read is
 * for, `UPDATING FOR "GRAB F"`, as Search's board does; null or blank says
 * [UPDATING] alone. While [heldBy] names a holder it says that instead.
 */
@Composable
fun UpdatingStatus(modifier: Modifier = Modifier, about: String? = null, heldBy: HeldBy? = LocalHeldBy.current) {
    StatusLine(updatingFor(about), heldBy, modifier)
}

/** [UpdatingStatus]'s words: [UPDATING], then `FOR "WORDS"` when there are [about]. */
fun updatingFor(about: String?): String =
    if (about.isNullOrBlank()) UPDATING else "$UPDATING FOR \u201C${about.trim().uppercase(CALENDAR)}\u201D"
