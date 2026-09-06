package my.pinged.ledger.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Card
import my.pinged.ledger.theme.Display
import my.pinged.ledger.theme.Faint
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.Mono
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.Rule
import my.pinged.ledger.theme.Stamp

/**
 * Spec 9.6's allow-list, drawn from `design/Sources.dc.html`.
 *
 * **The artboard's "SHOW ALL APPS" button is deliberately not here.** Spec 9.6
 * settled that afterwards: browsing installed apps needs either the
 * package-visibility permission -- Play-restricted to device search, antivirus
 * and file managers -- or a `<queries>` list, which is not a browsing surface.
 * The footer says the true thing instead: a package appears once it has spoken.
 *
 * Icons are also absent. Spec 9.6 asks for them "where they can be resolved";
 * the artboard draws no icon column, and the artboard is the specification of
 * the visual result.
 */
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    // The screen loads itself, so it is not silently blank in a host that does not
    // call `refresh()` from a lifecycle callback.
    //
    // This fires *after* `MainActivity.onResume` returns, so a cold launch does two
    // reads. They now queue rather than cancel, and the second publishes last --
    // one redundant `PackageManager` pass per launch, spent on a banner that tells
    // the truth about the current foreground. See `SourcesViewModel.refresh`.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    SourcesScreenContent(
        state = state,
        onToggle = viewModel::setEnabled,
        modifier = modifier,
    )
}

/**
 * Stateless, so the screen test needs no database.
 *
 * Nothing here knows what a `CaptureSource` is or that a `DataStore` exists.
 */
@Composable
fun SourcesScreenContent(
    state: SourcesState,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggested = state.suggested
    val seenNotCaptured = state.seenNotCaptured
    val loaded = state.loaded
    val enabledCount = suggested.count { it.enabled } + seenNotCaptured.count { it.enabled }

    Column(modifier.fillMaxSize().background(Paper)) {
        // `LazyColumn`, not a `Column` with `verticalScroll`. `SourceCounters` never
        // prunes, so the lower list grows for the life of the install -- one row per
        // app that has ever posted -- and an eager column composes every one of them on
        // every read.
        //
        // Each item carries the background and horizontal padding it used to inherit
        // from the wrapper Columns, because a lazy item has no enclosing Column.
        LazyColumn(
            Modifier
                .weight(1f)
                .background(Card),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().background(Paper)) {
                    Header(enabledCount = enabledCount)
                    SectionLabel("ON YOUR PHONE", Modifier.padding(start = 20.dp, top = 26.dp))
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (suggested.isEmpty()) {
                item(key = "suggested-empty") {
                    Column(UpperRow) {
                        // Not "nothing is installed": that is a confident
                        // answer, and this is the state where nothing could be
                        // read at all. It is drawn as an [Alert] for the same
                        // reason -- see there.
                        if (state.storageUnavailable) {
                            Alert("CANNOT READ YOUR DATA")
                        } else {
                            Note(
                                if (loaded) "NO KNOWN SOURCE IS INSTALLED ON THIS DEVICE"
                                else "READING",
                            )
                        }
                    }
                }
            }

            itemsIndexed(suggested, key = { _, row -> "suggested-" + row.pkg }) { index, row ->
                Column(UpperRow) {
                    SourceListRow(
                        row = row,
                        // The same claim as the lower list, and it belongs here more.
                        //
                        // The upper list is the pack's installed packages plus everything enabled, and
                        // the pack holds two, both in `<queries>` -- so those two are always up here
                        // whether they are on or off. A disabled Maybank drew "12 SEEN" and nothing
                        // else, under a heading reading TEXT IS STORED ONLY FOR THESE, and "EARLIER
                        // TEXT STILL SAVED" was unreachable for the only two banks this app knows.
                        detail = seenLine(row.seenCount) + claimFor(row),
                        divider = index != suggested.lastIndex,
                        onToggle = onToggle,
                    )
                }
            }

            item(key = "tear") {
                // The 18dp above the tear is Paper, not Card: the artboard
                // puts it outside the torn edge as a margin, and painting it
                // Card would start the "not captured" block before the
                // perforation that announces it.
                Spacer(Modifier.fillMaxWidth().background(Paper).height(18.dp))
                TornEdge()
            }

            item(key = "not-captured-label") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                    SectionLabel("SEEN RECENTLY, NOT CAPTURED")
                    if (seenNotCaptured.isEmpty()) {
                        if (state.storageUnavailable) {
                            Alert("CANNOT READ YOUR DATA")
                        } else {
                            Note(if (loaded) "NOTHING ELSE HAS POSTED YET" else "READING")
                        }
                    }
                }
            }

            itemsIndexed(seenNotCaptured, key = { _, row -> "seen-" + row.pkg }) { index, row ->
                Column(Modifier.padding(start = 20.dp, end = 20.dp)) {
                    SourceListRow(
                        row = row,
                        // The claim spec 9.6 exists to make, printed next to the only thing kept about
                        // the package: a count. If this line is on screen it has to be true, which is
                        // what the stage-one gate in CaptureIngest is for.
                        //
                        // Conditional on the row, not the section. This component is public and
                        // stateless, and its callers are the only thing between the claim and a source
                        // whose text really is stored: a layout decision must not be able to make the
                        // app dishonest.
                        detail = seenLine(row.seenCount) + claimFor(row),
                        divider = index != seenNotCaptured.lastIndex,
                        topPadding = if (index == 0) 14.dp else 12.dp,
                        onToggle = onToggle,
                    )
                }
            }
        }

        Footer()
    }
}

/**
 * **The artboard's back chevron is not here.**
 *
 * It was, and it did nothing: a 20dp chevron on a `Canvas` with no click
 * handler, no semantics and nowhere to go -- `MainActivity` is the only
 * Activity and this the only screen. A control that looks like a control and
 * ignores every tap teaches the user that this app's controls cannot be
 * trusted, on the screen that asks them to trust it with their notifications.
 * A bare `Canvas` is also an unlabelled node, so a screen reader could say
 * nothing about it.
 *
 * Bring it back with the destination, not before.
 */
@Composable
private fun Header(enabledCount: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Capture sources",
            fontFamily = Display,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            color = Ink,
        )
    }
    Text(
        // Uppercased in the string, not by a text transform, so what is read
        // out is what is drawn.
        "$enabledCount ENABLED" + Separator + "TEXT IS STORED ONLY FOR THESE",
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = SectionLabelStyle,
        color = Muted,
        modifier = modifier,
    )
}

/** An empty section, in the same voice as the rest of the small print. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/**
 * The one state on this screen that is not a fact about the user's phone.
 *
 * "CANNOT READ YOUR DATA" was typeset exactly like "NOTHING ELSE HAS POSTED
 * YET" -- same size, colour and position. One says the phone is quiet; the
 * other says the app cannot open its own database. Drawn identically, a user
 * scanning an apparently empty allow-list concludes no app has ever notified
 * them, which is the opposite of the truth.
 *
 * So it is drawn in the accent this screen reserves for things that need
 * answering, inside the same bordered frame as the authoritative stamp and the
 * capture banner.
 */
@Composable
private fun Alert(text: String) {
    Text(
        text,
        style = MonoLabel,
        color = Stamp,
        modifier = Modifier
            .padding(top = 14.dp, bottom = 4.dp)
            .border(1.dp, Stamp, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun SourceListRow(
    row: SourceRow,
    detail: String,
    divider: Boolean,
    onToggle: (String, Boolean) -> Unit,
    topPadding: Dp = 12.dp,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // The whole row is the target, not the 42x24 pill: 44dp minimum,
            // and `toggleable` reports the state to accessibility services
            // rather than leaving a switch that reads as a button.
            .toggleable(
                value = row.enabled,
                role = Role.Switch,
                onValueChange = { onToggle(row.pkg, it) },
            )
            .heightIn(min = 44.dp)
            .then(if (divider) Modifier.dottedRule(atTop = false) else Modifier)
            .padding(top = topPadding, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = if (row.enabled) Ink else Muted,
            )
            Row(
                Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    detail,
                    style = DetailStyle,
                    color = Muted,
                    // Tagged so `DrawnColourTest` can sample the pixels this
                    // line is actually painted in. The palette test cannot:
                    // reverting this one argument to `Faint` puts the 3.12:1
                    // bug back with every unit test still green, which is
                    // measured rather than supposed.
                    modifier = Modifier.testTag(DETAIL_TAG),
                )
                if (row.authoritative) AuthoritativeChip()
            }
        }
        Pill(on = row.enabled)
    }
}

/**
 * Spec 7.2's `is_authoritative`: which side of a duplicate pair wins.
 *
 * **8.5sp, below the floor the rest of the small print was raised to, and left
 * there on purpose.** Nothing in this milestone writes
 * `capture_source.is_authoritative`, so this never draws on a device. Raising
 * it would mean guessing a size against an artboard whose reason for 8.5px was
 * that it sits beside 10px text that is now 12. It gets its size when it gets
 * its writer.
 */
@Composable
private fun AuthoritativeChip() {
    Text(
        "AUTHORITATIVE",
        fontFamily = Mono,
        fontSize = 8.5.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.68.sp,
        color = Stamp,
        modifier = Modifier
            .border(1.dp, Stamp, RoundedCornerShape(2.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * The capture toggle.
 *
 * **The off state is drawn in [Faint], and this is the one place the artboard
 * is overruled.** It drew the outline in `Border` on the upper ground and
 * `Rule` on the lower -- 1.35:1 and 1.41:1 -- with the knob `Rule` on both, at
 * 1.60:1 and 1.41:1. WCAG 1.4.11 asks 3:1 of a component and its states, and
 * this screen has exactly one question, so "not this one" being almost
 * invisible is not a stylistic matter. [Faint] clears 3:1 on both grounds.
 *
 * One colour rather than one per ground: the parameter that carried the
 * difference existed to keep the outline equally faint against two backgrounds,
 * which is the property that was the bug.
 *
 * The two states are **not** told apart by colour. On is a filled pill with the
 * knob right, off an outline with the knob left; the accent and [Faint] measure
 * 1.77:1 against each other, so a reader who sees no colour reads this entirely
 * from fill and position. That is WCAG 1.4.1, and why the fill is not
 * decoration.
 */
@Composable
private fun Pill(on: Boolean) {
    Box(
        Modifier
            // As on the detail line: what this is drawn in is the fix, and
            // only a device can see it.
            .testTag(PILL_TAG)
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (on) {
                    Modifier.background(Stamp)
                } else {
                    Modifier.border(1.dp, Faint, RoundedCornerShape(12.dp))
                },
            )
            .padding(horizontal = 3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (on) Paper else Faint),
        )
    }
}

// Hoisted rather than `MonoLabel.copy(...)` at the call site: `TextStyle.copy`
// builds a fresh SpanStyle and ParagraphStyle, and these three ran once per row
// per recomposition for values that never change.
private val SectionLabelStyle = MonoLabel.copy(letterSpacing = 1.4.sp)

/**
 * The seen count and spec 9.6's claim.
 *
 * **No tracking override, and there should never have been one.** This read
 * `letterSpacing = 0.sp`, which is not a value the artboard contains -- every
 * string there is 0.08em, 0.1em or 0.14em -- so the most important line on the
 * screen was set tighter than anything drawn, on top of being the smallest. It
 * takes `MonoLabel`'s tracking now, the board's 0.1em.
 */
private val DetailStyle = MonoLabel

/** The footer caption, at the base size rather than a point below it. */
private val FooterStyle = MonoLabel.copy(letterSpacing = 1.1.sp)

/**
 * Test tags for the two elements whose *colour* is the accessibility fix.
 *
 * Public because the test that reads their pixels is in another source set,
 * and named constants rather than literals so the tag cannot drift from the
 * assertion. See `DrawnColourTest`.
 */
const val DETAIL_TAG = "source-row-detail"
const val PILL_TAG = "source-row-pill"

/** What the upper rows used to inherit from the Paper-backed wrapper Column. */
private val UpperRow = Modifier.fillMaxWidth().background(Paper).padding(start = 20.dp, end = 20.dp)

/**
 * The perforation between "stored" and "not stored".
 *
 * A 12x9 tile: the band is Card, and a Paper wedge is cut out of the bottom of
 * each tile with its apex at the middle. That is what the artboard's pair of
 * 45-degree gradients draws, and it is the one piece of the screen whose whole
 * job is to say that the two halves are different kinds of thing.
 */
@Composable
private fun TornEdge(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        drawRect(color = Card, topLeft = Offset.Zero, size = size)
        val tile = 12.dp.toPx()
        val h = size.height
        // One Path, reset per tile. Built inside the loop this allocated a
        // native-backed Path per 12dp of width -- about 33 on a 400dp screen --
        // on every frame this strip is invalidated, i.e. throughout a scroll.
        val wedge = Path()
        var x = 0f
        while (x < size.width) {
            wedge.reset()
            wedge.moveTo(x + tile * 0.125f, h)
            wedge.lineTo(x + tile * 0.5f, h * 0.5f)
            wedge.lineTo(x + tile * 0.875f, h)
            wedge.close()
            drawPath(path = wedge, color = Paper)
            x += tile
        }
    }
}

/**
 * **The dashed frame is gone, because the button inside it is.**
 *
 * The artboard puts a 46dp dashed box here reading "SHOW ALL APPS". Spec 9.6
 * dropped that button, and the sentence replacing it is an explanation, not an
 * action -- but the frame stayed. A dashed box with a minimum height and
 * centred mono type is button chrome in this design language and nowhere else,
 * so the thing that looked most like something to press was the one thing that
 * could not be, directly under a list of real controls.
 */
@Composable
private fun Footer() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Paper)
            .dottedRule(atTop = true)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 26.dp),
    ) {
        Text(
            "AN APP APPEARS HERE ONCE IT NOTIFIES",
            style = FooterStyle,
            color = Muted,
        )
    }
}

/**
 * The perforation, drawn along one edge.
 *
 * One function, not two. `dottedBottomRule` and `dottedTopRule` were identical
 * but for the y coordinate, so the dash pattern and stroke weight existed
 * twice and only one of the two was under test.
 */
private fun Modifier.dottedRule(atTop: Boolean): Modifier = drawBehind {
    val y = if (atTop) 0f else size.height
    drawLine(
        color = Rule,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(1.dp.toPx(), 2.dp.toPx()),
        ),
    )
}

/**
 * What this app is holding for a source, drawn from the database rather than
 * from which list the row landed in.
 *
 * One function used by both sections: the claim is a fact about
 * `capture_source.last_notification_at`, not about layout. Inlined in the lower
 * section only, it was a statement about where a row was drawn -- and the two
 * banks the pack knows are always in the *upper* section, so the row that most
 * needed the claim never carried one.
 *
 * An enabled source says nothing: the heading above it already does.
 */
private fun claimFor(row: SourceRow): String = when {
    row.enabled -> ""
    row.textStored -> Separator + "EARLIER TEXT STILL SAVED"
    else -> Separator + "NOT ONE WORD STORED"
}

/**
 * "3,204 SEEN" -- notifications this listener has been handed from the package,
 * on either side of the allow-list gate.
 *
 * `Locale.ROOT`, not the device locale: the grouping separator is part of a
 * string the UI test matches exactly, and a device set to a locale that groups
 * with a full stop would turn a green test red for a reason that has nothing
 * to do with capture.
 */
private fun seenLine(count: Int): String =
    String.format(Locale.ROOT, "%,d SEEN", count)
