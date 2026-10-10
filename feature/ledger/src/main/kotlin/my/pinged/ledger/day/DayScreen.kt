package my.pinged.ledger.day

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.Databases
import my.pinged.data.dao.RetroPreview
import my.pinged.ledger.home.LedgerRow
import my.pinged.ledger.home.MerchantActions
import my.pinged.ledger.home.MerchantSheet
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.RowCategoryChooser
import my.pinged.ledger.home.chooserChoices
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Stamp
import my.pinged.ui.weekdayDate
import java.time.LocalDate

/**
 * One day's transactions, opened from a Charts grid square (#69, #81's `Day`
 * screen): the date, the day's counted total, what was kept out of it, and
 * every row the Ledger would show under that day, drawn by the Ledger's own
 * [LedgerRow] -- excluded rows struck through, pending rows badged, the chip
 * and the merchant long press live.
 *
 * Re-read on each foreground and when a delete or restore rewrites the ledger,
 * as the Ledger's aggregates are.
 */
@Composable
fun DayScreen(viewModel: DayViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(viewModel, rewrites) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val read by viewModel.read.collectAsState()
    val unavailable by viewModel.storageUnavailable.collectAsState()
    val merchantSheet by viewModel.merchantSheet.collectAsState()
    DayScreenContent(
        date = viewModel.date,
        read = read,
        storageUnavailable = unavailable,
        onBack = onBack,
        onAssign = { txnId, categoryId, teach -> viewModel.assignCategory(txnId, categoryId, teach) },
        retroPreview = viewModel::retroPreview,
        merchantSheet = merchantSheet,
        merchantActions = remember(viewModel) {
            MerchantActions(
                open = viewModel::openMerchant,
                rename = viewModel::renameMerchant,
                merge = viewModel::mergeMerchant,
                separate = viewModel::separateMerchant,
                close = viewModel::closeMerchant,
            )
        },
        modifier = modifier,
    )
}

/** [DayScreen] without its holder, so a test can draw any state. */
@Composable
internal fun DayScreenContent(
    date: LocalDate,
    read: DayRead?,
    storageUnavailable: Boolean,
    onBack: () -> Unit,
    onAssign: (txnId: Long, categoryId: Long, teach: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    retroPreview: suspend (txnId: Long, categoryId: Long) -> RetroPreview = { _, _ -> RetroPreview(0, emptyList()) },
    merchantSheet: MerchantSheetState? = null,
    merchantActions: MerchantActions = MerchantActions.None,
) {
    // Hoisted out of the list for the reason `LedgerScreenContent` gives.
    var categorizing by rememberSaveable { mutableStateOf<Long?>(null) }
    val chooser = read?.chooser
    val choices = remember(chooser) { chooser?.let(::chooserChoices).orEmpty() }
    val categoriesById = remember(chooser) { chooser?.categories.orEmpty().associateBy { it.id } }

    LazyColumn(modifier.fillMaxSize().background(Paper)) {
        item { Header(date, onBack) }
        when {
            // First: stale rows under a ledger that cannot be read would be a
            // claim about money no read supports.
            storageUnavailable -> item { Notice(CANNOT_READ_YOUR_DATA, Stamp) }
            read == null -> item { Notice("READING", Muted) }
            else -> {
                item { Heading(read.heading) }
                items(read.rows, key = { "txn-" + it.txn.id }) { row ->
                    Box(Modifier.background(Card)) {
                        LedgerRow(
                            row = row,
                            categoriesById = categoriesById,
                            uncategorizedId = read.chooser.uncategorizedId,
                            canCategorize = choices.isNotEmpty(),
                            onCategorize = { categorizing = it },
                            onOpenMerchant = merchantActions.open,
                        )
                    }
                }
            }
        }
    }

    // Found among the rows read rather than saved with the id, for the reason
    // `LedgerScreenContent` gives; a row not read draws no sheet.
    val chosen = categorizing
    val chosenRow = chosen?.let { id -> read?.rows?.firstOrNull { it.txn.id == id } }
    if (chosen != null && chosenRow != null && chooser != null && choices.isNotEmpty()) {
        RowCategoryChooser(
            row = chosenRow,
            chooser = chooser,
            retroPreview = retroPreview,
            onSave = { categoryId, teach ->
                categorizing = null
                onAssign(chosen, categoryId, teach)
            },
            onDismiss = { categorizing = null },
        )
    }
    if (merchantSheet != null) MerchantSheet(merchantSheet, merchantActions)
}

/** The chevron and the date. */
@Composable
private fun Header(date: LocalDate, onBack: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp, top = 12.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onBack)
            .size(44.dp)
            .semantics { contentDescription = DAY_BACK },
        contentAlignment = Alignment.Center,
    ) {
        Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
    }
    Text(
        weekdayDate(date),
        fontFamily = Display,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        color = Ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
}

/**
 * The total, the kept-out line and the not-watching line, each only when
 * [DayHeading] has it.
 */
@Composable
private fun Heading(heading: DayHeading) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp)) {
        heading.total?.let { Text(it, style = TotalStyle, color = Ink) }
        heading.keptOut?.let {
            Text(it, fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        if (heading.notWatching) {
            Text(
                NOT_WATCHING,
                fontFamily = Body,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Stamp,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .border(1.dp, Stamp, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MonoLabel, color = color, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
}

/** A hatched day's line (#69). It stands alone when the day has no rows. */
internal const val NOT_WATCHING =
    "Pinged was not watching on this day. These are only the transactions it has from elsewhere."

/** The chevron's description, and what a test finds it by. */
const val DAY_BACK = "Back to Charts"

private val TotalStyle = TextStyle(fontFamily = Display, fontSize = 36.sp, lineHeight = 40.sp)
