package my.pinged.ledger.learned

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.dao.LearnedMerchant
import my.pinged.ledger.settings.grouped
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
            Text(merchant.name, fontFamily = Body, fontSize = 15.sp, color = Ink)
            Row {
                Text(merchant.categoryName, style = MonoLabel, color = Muted)
                Text(Separator, style = MonoLabel, color = Muted)
                Text(paymentsLabel(merchant.payments), style = MonoLabel, color = Muted)
            }
        }
        Text(
            DELETE,
            style = MonoLabel,
            color = Muted,
            modifier = Modifier
                .border(1.dp, Muted, RoundedCornerShape(2.dp))
                .clickable(role = Role.Button, onClick = onDelete)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
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
            LEARNED_MERCHANTS,
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The row's count, in the words the issue gives it. */
internal fun paymentsLabel(payments: Int): String = "PAYMENTS ${grouped(payments)}"

/** The settings row's label and this screen's title: the UI's name for a learned rule. */
const val LEARNED_MERCHANTS = "Learned merchants"
internal const val DELETE = "DELETE"
internal const val NOTHING_TAUGHT =
    "Nothing taught yet. Pick a category on a payment and leave 'Always call this' on."
