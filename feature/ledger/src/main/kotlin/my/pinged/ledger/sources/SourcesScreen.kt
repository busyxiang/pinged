package my.pinged.ledger.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.Locale
import my.pinged.data.Databases
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ledger.theme.Card
import my.pinged.ledger.theme.Chevron
import my.pinged.ledger.theme.Display
import my.pinged.ledger.theme.Faint
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.Mono
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.Stamp
import my.pinged.ledger.theme.dottedRule

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    // The screen loads itself, so it is not silently blank in a host that does
    // not call `refresh()` from a lifecycle callback.
    //
    // **Once per foreground, not once per holder, which is the whole point of a
    // lifecycle effect here.** `MainActivity` scopes each holder to its
    // `NavEntry` (`rememberViewModelStoreNavEntryDecorator`), so the holder
    // survives backgrounding and a `LaunchedEffect(viewModel)` fires exactly
    // once for the life of the entry. A warm resume would then read nothing:
    // spec 9.6 discovery is "a package appears once it has spoken" -- which the
    // footer below promises in those words -- so a bank that posted while the
    // app was away would stay off the lower list, with no pull-to-refresh and
    // nowhere to navigate to force a read. The same freeze would hold
    // `storageUnavailable`, which is worse than stale: the screen could say
    // CANNOT READ YOUR DATA under a banner `onResume`'s `CaptureStorage` probe
    // had just cleared, the inverse of what that probe exists to prevent.
    //
    // `repeatOnLifecycle(RESUMED)` gives the same guarantee and costs a
    // restarting collector for a `refresh()` that is a one-shot, not a flow.
    //
    // Keyed on the view model, so a new entry is a new holder and a new read.
    // Nothing to undo on pause: `refresh()` is queued and ordered in the holder
    // (see `SourcesViewModel`), and cancelling it would only lose the read.
    //
    // **And on `Databases.rewrites`, so a replaced database is a new read
    // without a resume.** A delete or restore runs from settings on that
    // holder's own scope, and the user can open this screen while one is
    // still going; when it lands nothing resumes, and the screen would go on
    // drawing the replaced ledger's allow-list -- a source switched ON that
    // the delete has taken away, which is spec 9.6's consent shown inverted.
    // Here rather than in the holder because a holder re-reading on its own
    // opens the database for a screen nobody is looking at, and every holder
    // left on the back stack would do it at once.
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(viewModel, rewrites) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    SourcesScreenContent(
        state = state,
        onToggle = viewModel::setEnabled,
        onBack = onBack,
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
    onBack: () -> Unit,
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
        // every read. Each item carries its own background and horizontal padding,
        // because a lazy item has no enclosing Column to inherit them from.
        LazyColumn(
            Modifier
                .weight(1f)
                .background(Card),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().background(Paper)) {
                    Header(enabledCount = enabledCount, onBack = onBack)
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
                            Alert(CANNOT_READ_YOUR_DATA)
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
                        // The same claim as the lower list, and it belongs here
                        // more: a pack package stays in this section whether it
                        // is on or off, so this is where a disabled bank with
                        // text on disk is read. See `claimFor`.
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
                            Alert(CANNOT_READ_YOUR_DATA)
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
 * The artboard's back chevron.
 *
 * **A control that looks like a control and ignores every tap must not be
 * drawn** -- not on the screen that asks the user to trust this app with their
 * notifications. This chevron waited for its destination rather than being
 * drawn as decoration, and the rest of the feature points here for the rule.
 *
 * [onBack] pops the same entry the system back gesture pops -- `MainActivity`
 * hands both the same expression -- so the two cannot drift into meaning
 * different things.
 *
 * A [Role.Button] with a content description and a 44dp target, not a bare
 * `Canvas`: an unlabelled node is one a screen reader can say nothing about.
 */
@Composable
private fun Header(enabledCount: Int, onBack: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp, top = 12.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onBack)
            .size(44.dp)
            .semantics { contentDescription = BACK_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp),
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
 * **It must not be typeset like [Note].** "NOTHING ELSE HAS POSTED YET" says
 * the phone is quiet; this says the app cannot open its own database. Drawn
 * identically, a user scanning an apparently empty allow-list concludes no app
 * has ever notified them, which is the opposite of the truth. So: the accent
 * this screen reserves for things that need answering, inside the same bordered
 * frame as the authoritative stamp and the capture banner.
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
                    // contrast back with every unit test still green.
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
 * One colour rather than one per ground: a per-ground parameter exists to keep
 * the outline equally faint against two backgrounds, which is the property
 * that was the bug.
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
 * The seen count and spec 9.6's claim, at `MonoLabel`'s tracking.
 *
 * **No override**: the artboard sets every string at 0.08em, 0.1em or 0.14em,
 * and `MonoLabel` is the board's 0.1em.
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

/**
 * What a screen reader says about the back chevron, and what the test that
 * proves it pops looks the node up by. One constant rather than two literals:
 * a chevron the test cannot find is a test that passes whether or not the
 * control is there.
 */
const val BACK_DESCRIPTION = "Back to the ledger"

/** The upper list's ground and gutters, which a lazy item cannot inherit. */
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
 * **No dashed frame, because there is no button inside it.**
 *
 * The artboard puts a 46dp dashed box here reading "SHOW ALL APPS", which spec
 * 9.6 dropped; the sentence replacing it is an explanation, not an action. A
 * dashed box with a minimum height and centred mono type is button chrome in
 * this design language and nowhere else, so keeping the frame would put the
 * most press-looking thing on the screen directly under a list of real
 * controls (see [Header] for the rule).
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
 * Spec 9.6's claim about what this app is holding for a source: a fact about
 * `capture_source.last_notification_at`, not about which list the row landed
 * in, so no layout decision can make the app dishonest. If this line is on
 * screen it has to be true, which is what `CaptureIngest`'s stage-one gate is
 * for. An enabled source says nothing -- the heading above it already does.
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
