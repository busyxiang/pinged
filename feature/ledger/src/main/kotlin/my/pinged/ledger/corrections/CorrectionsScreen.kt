package my.pinged.ledger.corrections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.capture.Corrections
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.dayLabel
import my.pinged.ledger.home.words
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.dottedRule

/**
 * Spec 5.5's reviewable list: each transaction a newer pack reads differently,
 * with its old and new values, and the user's accept or decline.
 *
 * Read once per foreground, as `SourcesScreen` is, so returning from the
 * ledger after the worker has finished its sweep draws the list it found.
 */
@Composable
fun CorrectionsScreen(viewModel: CorrectionsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    CorrectionsContent(state, onBack, viewModel::accept, viewModel::decline, modifier)
}

/** [CorrectionsScreen] without its holder, so a test can draw any state. */
@Composable
internal fun CorrectionsContent(
    state: CorrectionsState,
    onBack: () -> Unit,
    onAccept: (Corrections.Correction) -> Unit,
    onDecline: (Corrections.Correction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().background(Paper)) {
        item { Header(onBack) }
        item {
            val notice = when {
                !state.loaded -> null
                state.storageUnavailable -> CANNOT_READ_YOUR_DATA
                state.checking -> "STILL CHECKING YOUR HISTORY AGAINST PACK ${state.packVersion}"
                state.items.isEmpty() -> NOTHING_TO_REVIEW
                else -> null
            }
            if (notice != null) {
                Text(notice, style = MonoLabel, color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
            } else if (state.items.isNotEmpty()) {
                Text(
                    "Pack ${state.packVersion} reads these payments differently from when they " +
                        "were recorded. Nothing changes unless you accept.",
                    fontFamily = Body,
                    fontSize = 14.sp,
                    color = Muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
        }
        items(state.items, key = { it.captureId }) { correction ->
            CorrectionCard(correction, onAccept = { onAccept(correction) }, onDecline = { onDecline(correction) })
        }
    }
}

@Composable
private fun CorrectionCard(correction: Corrections.Correction, onAccept: () -> Unit, onDecline: () -> Unit) {
    val before = correction.before
    Column(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = false)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            dayLabel(before.localDate) + (before.sourceLabel?.let { Separator + it.uppercase() } ?: ""),
            style = MonoLabel,
            color = Muted,
        )
        // Which payment this is, as recorded. A card whose only change is its
        // status would otherwise not say what it is about.
        Text(
            (before.merchantDisplay ?: "NO MERCHANT") + Separator + money(before.amountSen, before.currency, symbol = true),
            fontFamily = Body,
            fontSize = 15.sp,
            color = Ink,
        )
        Corrections.Field.entries.filter { it in correction.changed }.forEach { field ->
            Row(verticalAlignment = Alignment.Top) {
                Text(field.name, style = MonoLabel, color = Muted, modifier = Modifier.width(96.dp))
                Column(Modifier.weight(1f)) {
                    Text(value(field, before), fontFamily = Body, fontSize = 14.sp, color = Muted)
                    Text("→ " + value(field, correction.after), fontFamily = Body, fontSize = 15.sp, color = Ink)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            Action(ACCEPT, Ink, onAccept)
            Action(KEEP, Muted, onDecline)
        }
    }
}

@Composable
private fun Action(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MonoLabel,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** One column of [txn], as the ledger would word it. */
internal fun value(field: Corrections.Field, txn: Txn): String = when (field) {
    Corrections.Field.AMOUNT -> money(txn.amountSen, txn.currency, symbol = true)
    Corrections.Field.DIRECTION -> txn.direction.name
    Corrections.Field.MERCHANT -> txn.merchantDisplay ?: "NO MERCHANT"
    Corrections.Field.CONFIDENCE -> txn.confidence.name
    Corrections.Field.STATUS -> when (txn.state) {
        TxnState.COMMITTED -> "COUNTED"
        TxnState.PENDING -> "PENDING" + Separator + words(txn.pendingReason?.name)
        TxnState.REJECTED -> "REJECTED"
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Back",
            style = MonoLabel,
            color = Muted,
            modifier = Modifier.clickable(role = Role.Button, onClick = onBack),
        )
        Text(
            "Corrections",
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

internal const val ACCEPT = "ACCEPT"
internal const val KEEP = "KEEP AS RECORDED"
internal const val NOTHING_TO_REVIEW = "NOTHING TO REVIEW"
