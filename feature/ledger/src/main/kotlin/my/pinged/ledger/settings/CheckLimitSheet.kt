package my.pinged.ledger.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.capture.CheckLimit
import my.pinged.ledger.review.limit
import my.pinged.ledger.search.PickRow
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.ReceiptSheet

/** The settings row for spec 7.1's check limit, and its sheet's title. */
internal const val CHECK_LIMIT_ROW = "Check payments over"

/** The line under the steps: what a change does and does not move (#362). */
internal const val CHECK_LIMIT_NOTE =
    "Applies to payments that arrive from now on. Payments already awaiting review stay there; " +
        "Accept all takes any now under your limit. Restoring a backup keeps this phone's limit."

/**
 * The check limit's steps, [CheckLimit.STEPS_SEN], as a check-mark list -- the
 * filter sheets' [PickRow] (#274) -- with [selectedSen] checked. No "any" row
 * and no typed amount: the store takes nothing else. A pick is the answer;
 * there is no Save button.
 */
@Composable
internal fun CheckLimitSheet(selectedSen: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    ReceiptSheet(onDismissRequest = onDismiss, title = CHECK_LIMIT_ROW) {
        CheckLimit.STEPS_SEN.forEach { sen -> PickRow(limit(sen), sen == selectedSen) { onPick(sen) } }
        Text(
            CHECK_LIMIT_NOTE,
            fontFamily = Body,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )
    }
}

/**
 * [content], handed the way to open [CheckLimitSheet]; a pick closes the
 * sheet and hands the step to [onSave]. The settings screen's wiring, apart
 * from its holder so a test can draw it.
 */
@Composable
internal fun CheckLimitChooser(selectedSen: Long?, onSave: (Long) -> Unit, content: @Composable (open: () -> Unit) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    content { choosing = true }
    if (choosing) {
        CheckLimitSheet(
            selectedSen = selectedSen,
            onPick = {
                choosing = false
                onSave(it)
            },
            onDismiss = { choosing = false },
        )
    }
}
