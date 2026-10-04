package my.pinged.ledger.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's one sheet chrome: a Material3 `ModalBottomSheet` over [Paper],
 * always expanded to its full content rather than left at a half-height
 * default.
 *
 * **Every sheet of prose in the app goes through here**: `RestoreConfirmSheet`,
 * `OutcomeSheet`, `WipeSheet` and `MainActivity`'s `ExportSheetHost`. A
 * hand-rolled scrim-plus-card overlay looks like this until it is used -- it
 * is the drag handle, the system back gesture and the scrim's own dismissal
 * that a copy silently does without -- and four of them drift. Going through
 * here is also what leaves one place to change if the chrome ever does.
 *
 * `CategoryPicker` is the exception and stays one: its body is a `LazyColumn`,
 * which cannot live inside the scrolling `Column` below -- two scrollers in
 * one direction, which Compose throws on.
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
 * `@OptIn(ExperimentalMaterial3Api::class)`: required by `ModalBottomSheet`
 * and `rememberModalBottomSheetState` at material3 1.4.0, the same opt-in
 * `CategoryPicker` carries on its own copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSheet(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            content()
        }
    }
}
