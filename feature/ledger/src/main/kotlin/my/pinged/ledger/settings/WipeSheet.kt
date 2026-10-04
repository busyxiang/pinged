package my.pinged.ledger.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Border
import my.pinged.ledger.theme.Display
import my.pinged.ledger.theme.Faint
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.Mono
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.MonoNumerals
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.ReceiptSheet
import my.pinged.ledger.theme.Rule
import my.pinged.ledger.theme.Stamp
import my.pinged.ledger.theme.dashedOutline
import my.pinged.ledger.theme.dottedRule

/**
 * What the delete is about to destroy, itemised -- or `null` when Pinged
 * cannot honestly itemise it.
 *
 * **Nullable because a count off a damaged database is a confident lie.**
 * `COUNT(*)` is answered out of an index without touching the pages that hold
 * the rows, so it reported 5,000 on a file that could actually produce 2,200
 * (design 2.3). This is the last thing the user reads before agreeing to lose
 * all of it, and a wrong number here is worse than no number: it is the one
 * place in the app where an overstated loss could talk somebody out of a
 * delete they wanted, or an understated one into a delete they did not.
 */
data class WipeCounts(val txns: Int, val captures: Int, val merchants: Int, val months: Int)

/**
 * The word, compared exactly.
 *
 * Accepting `delete` -- or `Delete`, or a stray trailing space -- makes the
 * friction decorative: the point of typing it is that it cannot happen by
 * brushing the screen, and a comparison that forgives case forgives most of
 * the ways that happens.
 */
private const val CONFIRMATION = "DELETE"

/**
 * `DrawnColourTest` reads the field's underline back off the screen, the way
 * it already reads `SourcesScreen`'s toggle outline: `ContrastTest` guards the
 * palette and cannot see which token an element is drawn in, and this boundary
 * is the only thing on the sheet saying a field is there.
 */
internal const val CONFIRMATION_FIELD_TAG = "wipe-confirmation-field"

/**
 * The same, for the `Export it first` offer: a 48dp control whose only
 * boundary is the dashes [dashedOutline] draws, so `DrawnColourTest` reads
 * those back too. Dropping the modifier leaves a tappable row with no edge,
 * and no behavioural test can tell.
 */
internal const val EXPORT_FIRST_TAG = "wipe-export-first"

/**
 * `design/Wipe.dc.html`: spec 11.3's delete, behind the typed confirmation.
 *
 * [ReceiptSheet] plus [WipeSheetBody] and nothing else -- the app's one sheet
 * chrome, see that KDoc, which `ExportSheet`, `RestoreConfirmSheet` and
 * `OutcomeSheet` are all already inside.
 */
@Composable
fun WipeSheet(
    counts: WipeCounts?,
    onConfirm: () -> Unit,
    onExportFirst: () -> Unit,
    onDismiss: () -> Unit,
    exportFirstLabel: String? = EXPORT_FIRST,
) {
    ReceiptSheet(onDismissRequest = onDismiss) {
        WipeSheetBody(counts, onConfirm, onExportFirst, onDismiss, exportFirstLabel)
    }
}

/**
 * The sheet's contents, without the chrome, so a test can drive them.
 *
 * **Assumes it is already inside a vertical container with its own padding**,
 * the same contract `ExportSheet`'s KDoc states: [WipeSheet] supplies that by
 * calling this from inside [ReceiptSheet], whose `Column` already carries the
 * artboard's 20dp. A wrapper here would nest inside that one and double it.
 *
 * Drawn against the artboard, departing from it wherever the board's own
 * choice cannot survive being used. The list is exhaustive on purpose -- an
 * undeclared departure is indistinguishable from an implementation mistake:
 *
 * - The question is 24.sp, not the board's 30px. `RestoreConfirmSheet` settled
 *   on 24 for the same line in the same chrome; the board's 30 was measured
 *   against a full-height sheet, and this one is as tall as its content.
 * - The board's closing sentence, "Six months of history ends here.", is
 *   dropped. It restates the `Months of history` row, and it cannot be written
 *   at all when [counts] is null -- a sheet that sometimes has a closing
 *   sentence is worse than one that never does.
 * - The paragraph gains a clause about the capture allow-list, which the board
 *   does not mention. The database holds it, so the delete destroys it, and
 *   the module's CLAUDE.md calls that consent unrecoverable: re-granting it is
 *   per-app work the user has to redo, which is exactly the kind of cost this
 *   sheet exists to state before it is paid.
 * - Three colours are the app's tokens rather than the board's, all three for
 *   the reason `Color.kt` records: the board is a picture of paper and was not
 *   drawn against a contrast standard. `TYPE DELETE TO CONFIRM` is [Muted]
 *   where the board is `#8a8175` ([Faint], 3.55:1 on Paper and below 1.4.3's
 *   4.5:1 for text); the paragraph is [Muted] where the board is `#4a423a`,
 *   a darker grey that clears the standard but exists nowhere else in the
 *   palette; and the field's underline is [Faint] where the board is [Rule],
 *   see [ConfirmationField].
 * - Disabled `Delete` is [Faint], not the board's [Rule], for the same reason
 *   -- Rule is 1.60:1 on Paper, so the word the user is being asked to enable
 *   would be very nearly invisible until they enabled it. WCAG 1.4.3 exempts
 *   an inactive control from its ratio, which is why this is a legibility call
 *   rather than a conformance one.
 * - Enabled `Delete` is [Stamp], a state the board never draws: it only ever
 *   shows the control before the word is typed.
 */
@Composable
internal fun WipeSheetBody(
    counts: WipeCounts?,
    onConfirm: () -> Unit,
    onExportFirst: () -> Unit,
    onDismiss: () -> Unit,
    exportFirstLabel: String? = EXPORT_FIRST,
) {
    var typed by remember { mutableStateOf("") }
    val armed = typed == CONFIRMATION

    Text("THIS CANNOT BE UNDONE", style = MonoLabel, color = Stamp)
    Text(
        "Delete everything Pinged has recorded?",
        fontFamily = Display,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        color = Ink,
        modifier = Modifier.padding(top = 10.dp),
    )

    Column(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .border(1.dp, Border, RoundedCornerShape(2.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text("WHAT GOES", style = MonoLabel, color = Muted)
        if (counts == null) {
            Text(
                "Pinged cannot count what it cannot read.",
                fontFamily = Body,
                fontSize = 13.sp,
                color = Muted,
                modifier = Modifier.padding(top = 11.dp),
            )
        } else {
            Column(
                Modifier.padding(top = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CountRow("Transactions", counts.txns)
                CountRow("Original notifications", counts.captures)
                CountRow("Merchants you taught", counts.merchants)
                CountRow("Months of history", counts.months)
            }
        }
    }

    Text(
        "There is no copy anywhere. Pinged has no internet permission, so " +
            "nothing was ever uploaded, Android will not hand back " +
            "notifications from the past, and the list of apps you let it " +
            "read goes too -- you would choose them again from scratch.",
        fontFamily = Body,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = Muted,
        modifier = Modifier.padding(top = 16.dp),
    )

    // Kept from the artboard rather than dropped: the one moment a user is
    // about to destroy a ledger nothing has ever backed up is exactly when the
    // offer is worth making. Null where
    // no copy can be made (`copyFirstFor`): an offer that can only fail is
    // worse than none.
    if (exportFirstLabel != null) Row(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .testTag(EXPORT_FIRST_TAG)
            // Clipped before the tap target, or the ripple paints a square
            // over a 2dp-rounded outline -- the same order `LedgerScreen`'s
            // chip and `SourcesScreen`'s header use.
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onExportFirst)
            .heightIn(min = 48.dp)
            .dashedOutline(Muted),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadMark(Muted)
        Text(exportFirstLabel, fontFamily = Body, fontSize = 14.sp, color = Muted)
    }

    ConfirmationField(typed, onTyped = { typed = it })

    Row(
        Modifier.padding(top = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Choice(
            label = "Keep it",
            outline = Border,
            tint = Ink,
            enabled = true,
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
        // `clickable(enabled = ...)` rather than a conditional modifier: its
        // disabled state is what puts `Disabled` into semantics, which is what
        // `WipeSheetTest` reads. A tap target simply left off the node would
        // refuse the tap and still report as enabled.
        Choice(
            label = "Delete",
            outline = if (armed) Stamp else Faint,
            tint = if (armed) Stamp else Faint,
            enabled = armed,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * **This app's first and only text input**, so there is no idiom to match and
 * the artboard decides the shape: mono over a single underline, with the label
 * a separate `Text` above it.
 *
 * A `BasicTextField` and not an `OutlinedTextField`, which would bring
 * Material3's container, floating label and focus tint onto a letterpress
 * surface -- a second visual idiom on one sheet.
 *
 * **Three things the board does not supply, because an empty field is the only
 * state it draws that a user ever sees.**
 *
 * The underline is [Faint], not the board's [Rule]. It is the only thing on
 * screen saying a field is here at all, and WCAG 1.4.11 asks 3:1 of a
 * component boundary: Rule measures 1.60:1 on Paper, Faint 3.55:1. The same
 * overrule the capture toggle's off state got, for the same reason
 * (`Color.kt`).
 *
 * The ghosted `DELETE` is the board's, and it is what teaches the casing the
 * comparison insists on -- a `BasicTextField` has no placeholder of its own,
 * so it is drawn through `decorationBox`. Left at the board's [Rule] it would
 * be a hint nobody can read; at [Muted] it would read as text already typed,
 * beside a `Delete` that is somehow still disabled. [Faint] is the state
 * between, and the label above it carries the instruction at a text-legible
 * ratio.
 *
 * [KeyboardOptions] closes the gap between what the IME offers and what
 * [CONFIRMATION] accepts: without it the keyboard opens lowercase with
 * autocorrect on, so the obvious way to type the word produces `Delete` or
 * `delete ` and a refusal the user cannot see the cause of. Capitalising in
 * the IME rather than in the comparison is the fix that does not soften the
 * check.
 */
@Composable
private fun ConfirmationField(typed: String, onTyped: (String) -> Unit) {
    Text(
        "TYPE DELETE TO CONFIRM",
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(top = 20.dp),
    )
    val style = TextStyle(fontFamily = Mono, fontSize = 17.sp, letterSpacing = 1.7.sp, color = Ink)
    // The underline and the tag are on a wrapper rather than on the field's
    // own `modifier`: `BasicTextField` hands that modifier to the inner core
    // text field, whose bounds are the text line alone -- 22dp of the 40dp
    // this occupies, with the padding and the rule outside them. Drawn there,
    // the rule sits under the *text* rather than under the field, and
    // `DrawnColourTest` captures a node the rule is not in.
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(CONFIRMATION_FIELD_TAG)
            .drawBehind {
                val weight = 1.5.dp.toPx()
                drawLine(
                    color = Faint,
                    start = Offset(0f, size.height - weight / 2f),
                    end = Offset(size.width, size.height - weight / 2f),
                    strokeWidth = weight,
                )
            }
            .padding(top = 8.dp, bottom = 9.dp),
    ) {
        BasicTextField(
            value = typed,
            onValueChange = onTyped,
            singleLine = true,
            textStyle = style,
            // Unguarded: `performTextInput` writes into the field's state
            // without an IME, so no test in this repository can see either
            // option, and dropping them would fail nothing.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            cursorBrush = SolidColor(Stamp),
            decorationBox = { field ->
                Box {
                    if (typed.isEmpty()) Text(CONFIRMATION, style = style.copy(color = Faint))
                    field()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One `WHAT GOES` line: label, dotted leader, grouped value.
 *
 * **Merged into one semantics node.** A label and its value either side of a
 * leader are one fact, and read as two nodes a screen reader announces
 * "Transactions" and "1,204" with nothing joining them. It is also what lets
 * `WipeSheetTest` assert which value sits against which label: read as
 * separate nodes, swapping two rows' values leaves every assertion true.
 */
@Composable
private fun CountRow(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, fontFamily = Body, fontSize = 13.sp, color = Ink)
        Box(
            Modifier
                .weight(1f)
                .padding(bottom = 4.dp)
                .height(1.dp)
                .dottedRule(inset = 8.dp),
        )
        // `MonoNumerals`, not `Mono` raw: this is a right-aligned column of
        // figures, which is the shape that style's tabular-figures guarantee
        // exists for (`Type.kt`).
        //
        // [grouped], so `Locale.ROOT` and not the device locale: the
        // separator is part of a number this app prints the same way
        // everywhere, alongside `SourcesScreen.seenLine` and
        // `LedgerScreen.money`. Pinned by
        // `WipeSheetTest.theGroupingDoesNotFollowTheDeviceLocale`, which runs
        // the sheet under a locale that groups with a full stop -- the
        // emulator is en-US, so nothing else here can tell the two apart.
        Text(
            grouped(count),
            style = MonoNumerals.copy(fontSize = 13.sp),
            color = Ink,
        )
    }
}

/** One of the sheet's two closing buttons, the artboard's bordered pair. */
@Composable
private fun Choice(
    label: String,
    outline: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            // Before the tap target, so the ripple keeps the border's corner.
            .clip(RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .border(1.dp, outline, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Body, fontSize = 15.sp, color = tint)
    }
}

/**
 * The artboard's download glyph, drawn rather than shipped as a vector.
 *
 * Not because the app has no drawables -- `CategoryIcons` is a table of them
 * and both `LedgerScreen` and `CategoryPicker` draw `painterResource` marks.
 * It is that those are *data*: a category's icon is chosen at run time from a
 * key, so it has to be a resource. This is one 16dp arrow at one call site,
 * where a drawable plus a tint attribute buys nothing the four lines below do
 * not, and the two chevrons this app draws in `Canvas` are the nearer
 * precedent.
 *
 * Takes its colour, the way `SettingsScreen.Chevron` does: a glyph with a
 * hardcoded tint is one that cannot follow the row it sits in.
 */
@Composable
private fun DownloadMark(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val unit = size.width / 24f
        val stroke = 1.7.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke)
        line(12f, 4f, 12f, 15f)
        line(12f, 15f, 8f, 11f)
        line(12f, 15f, 16f, 11f)
        line(5f, 20f, 19f, 20f)
    }
}
