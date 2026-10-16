package my.pinged.ledger.learned

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.dao.LearnedMerchant
import my.pinged.ledger.settings.grouped
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule

/**
 * What the user has taught, and a way to forget it (#50). Read and delete
 * only: a category is changed by tapping it again in the chooser.
 *
 * Read once per foreground, as `CorrectionsScreen` is, so a payment counted or
 * a merge made elsewhere shows in the PAYMENTS figure on return.
 */
@Composable
fun LearnedScreen(viewModel: LearnedViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LearnedContent(state, onBack, viewModel::delete, modifier)
}

/** [LearnedScreen] without its holder, so a test can draw any state. */
@Composable
internal fun LearnedContent(
    state: LearnedState,
    onBack: () -> Unit,
    onDelete: (LearnedMerchant) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().background(Paper)) {
        item { Header(onBack) }
        item {
            val notice = when {
                !state.loaded -> null
                state.storageUnavailable -> CANNOT_READ_YOUR_DATA
                state.items.isEmpty() -> NOTHING_TAUGHT
                else -> null
            }
            if (notice != null) {
                Text(notice, fontFamily = Body, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        items(state.items, key = { it.identity }) { merchant ->
            LearnedRow(merchant, onDelete = { onDelete(merchant) })
        }
    }
}

@Composable
private fun LearnedRow(merchant: LearnedMerchant, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = false)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Unfilled weight: a long name wraps rather than push the chip off the row.
                Text(
                    merchant.name,
                    fontFamily = Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    color = Ink,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CategoryChip(merchant.categoryName)
            }
            Text(paymentsLabel(merchant.payments), style = MonoLabel, color = Muted)
        }
        DeleteButton(onDelete)
    }
}

/**
 * The board's category chip: a 1dp [Border] box. 12sp where the board sets
 * 11, the floor `MonoLabel`'s KDoc gives small print on these screens.
 */
@Composable
private fun CategoryChip(name: String) {
    Text(
        name,
        fontFamily = Body,
        fontSize = 12.sp,
        color = Muted,
        modifier = Modifier
            .border(1.dp, Border, RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The board's trash can, 17dp in [Stamp] at its 1.6 stroke in a 24 box, in a
 * 44dp target. Offset 13.5dp, half the target's slack, so the glyph's own edge
 * sits on the 20dp margin, as `SheetHeader`'s X does.
 */
@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    Box(
        Modifier
            .offset(x = 13.5.dp)
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onDelete)
            .semantics { contentDescription = DELETE },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            // The board's `M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13`.
            val u = size.width / 24f
            val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawLine(Stamp, Offset(5 * u, 7 * u), Offset(19 * u, 7 * u), stroke.width, StrokeCap.Round)
            val lid = Path().apply {
                moveTo(9 * u, 7 * u); lineTo(9 * u, 5 * u); lineTo(15 * u, 5 * u); lineTo(15 * u, 7 * u)
            }
            val can = Path().apply {
                moveTo(7 * u, 7 * u); lineTo(8 * u, 20 * u); lineTo(16 * u, 20 * u); lineTo(17 * u, 7 * u)
            }
            drawPath(lid, Stamp, style = stroke)
            drawPath(can, Stamp, style = stroke)
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Back",
            style = ButtonLabel.copy(fontSize = 14.sp),
            color = Muted,
            modifier = Modifier.clickable(role = Role.Button, onClick = onBack).heightIn(min = 44.dp).wrapContentHeight(),
        )
        Text(
            LEARNED_MERCHANTS,
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The row's count, in the words the issue gives it. */
internal fun paymentsLabel(payments: Int): String = "PAYMENTS ${grouped(payments)}"

/** The settings row's label and this screen's title: the UI's name for a learned rule. */
const val LEARNED_MERCHANTS = "Learned merchants"
internal const val DELETE = "Delete"
internal const val NOTHING_TAUGHT =
    "Nothing taught yet. Pick a category on a payment and leave 'Always call this' on."
