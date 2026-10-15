package my.pinged.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The sheet header's close control, as a screen reader names it. */
const val CLOSE = "Close"

/** The artboards' scrim, `rgba(26,23,20,0.42)`: [Ink] at 42%, not Material's black at 32%. */
private val Scrim = Ink.copy(alpha = 0.42f)

/**
 * The app's one sheet chrome: a Material3 `ModalBottomSheet` over [Paper],
 * always expanded to its full content rather than left at a half-height
 * default.
 *
 * **Every sheet of prose in the app goes through here**: `RestoreConfirmSheet`,
 * `OutcomeSheet`, `WipeSheet`, `MonthsSheet` and `MainActivity`'s
 * `ExportSheetHost`. A hand-rolled scrim-plus-card overlay looks like this
 * until it is used -- it is the system back gesture, the drag and the scrim's
 * own dismissal that a copy silently does without -- and four of them drift.
 * What it draws is [ReceiptSheetFrame]'s, shared with `CategoryPicker`.
 *
 * [title], when given, is the artboards' header: the serif title and a 20dp
 * close X that dismisses, 44dp as a target. [edge] is the dashed top edge's
 * colour, [Stamp] on the delete sheet and [Ink] everywhere else.
 *
 * **The `Column` scrolls, and that is not defensive.** A `Column` given less
 * height than its children measures the ones that no longer fit at zero, and
 * this sheet is given less the moment the keyboard opens: measured on
 * emulator-5554, `WipeSheet`'s `Delete` sat at 137px tall until the IME came
 * up for its own confirmation field and then at **exactly 0** -- the control
 * the whole sheet exists to arm, gone at the moment the user armed it, with
 * no error and nothing in the suite able to see it (`WipeHostTest` records
 * the run). Scrolling gives the children unbounded height and the reader the
 * rest of the sheet.
 *
 * Padding is the artboards': 20 at the top and sides, 24 under the last
 * control (the boards give 24 to 26).
 */
@Composable
fun ReceiptSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    edge: Color = Ink,
    content: @Composable () -> Unit,
) {
    ReceiptSheetFrame(onDismissRequest = onDismissRequest, edge = edge) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
        ) {
            if (title != null) SheetHeader(title, onDismissRequest)
            content()
        }
    }
}

/**
 * What every sheet is drawn as (#164), against the artboards' sheet edge
 * (`design/Chooser.dc.html`, `Export`, `Wipe`, `Months`): square corners, a
 * 1.5dp dashed top edge in [edge], no drag handle, and a scrim of [Ink] at
 * 42%. Material's defaults are 28dp corners, a pill handle and black at 32%,
 * and each shows through wherever a call site says nothing --
 * `SheetChromeTest` reads all four off the screen.
 *
 * The sheet still drags to dismiss and still answers the back gesture and a
 * tap on the scrim: those belong to the sheet's surface and its dialog, not
 * to the handle, which only draws a grip.
 *
 * For a sheet whose body cannot sit in [ReceiptSheet]'s scrolling `Column`,
 * which is `CategoryPicker`: its body is a `LazyColumn`, and two scrollers in
 * one direction throw. [content] is a `ColumnScope` so such a body can weight
 * its list.
 *
 * `@OptIn(ExperimentalMaterial3Api::class)`: required by `ModalBottomSheet`
 * and `rememberModalBottomSheetState` at material3 1.4.0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSheetFrame(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    edge: Color = Ink,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = Paper,
        scrimColor = Scrim,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().dashedEdge(edge), content = content)
    }
}

/**
 * The artboards' sheet header: the title in the serif at 23, and the close X,
 * 20dp at a 1.7 stroke, whose 44dp target reaches 12dp past the glyph so the
 * glyph's own edge sits on the 20dp margin.
 */
@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontFamily = Display, fontSize = 23.sp, color = Ink, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .offset(x = 12.dp)
                .size(44.dp)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = CLOSE },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                // The boards' `M6 6l12 12M18 6L6 18` in a 24 box.
                val a = size.width * 6f / 24f
                val b = size.width * 18f / 24f
                val stroke = 1.7.dp.toPx()
                drawLine(Ink, Offset(a, a), Offset(b, b), stroke, StrokeCap.Round)
                drawLine(Ink, Offset(b, a), Offset(a, b), stroke, StrokeCap.Round)
            }
        }
    }
}
