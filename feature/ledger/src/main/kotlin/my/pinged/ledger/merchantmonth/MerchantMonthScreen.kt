package my.pinged.ledger.merchantmonth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import my.pinged.ledger.BackChevron
import my.pinged.ledger.MonthHeader
import my.pinged.ledger.MonthHeaderNotReadYet
import my.pinged.ledger.RowNotReadYet
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.home.CategoryActions
import my.pinged.ledger.home.LedgerRow
import my.pinged.ledger.home.MerchantActions
import my.pinged.ledger.home.MerchantSheet
import my.pinged.ledger.home.RowCategoryChooser
import my.pinged.ledger.home.chooserChoices
import my.pinged.read.rememberReadState
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import java.time.LocalDate
import java.time.YearMonth

/**
 * One merchant identity's month (#344; spec 9.3, Merchant month), opened
 * from its `WHO GOT IT` row on Charts or a line of the `N more merchants`
 * sheet: the merchant's net, its count and what was kept out, in Breakdown's
 * header with no icon, then every counted ringgit row of it in the month,
 * across every category, in one flat list in the feed's order, drawn by the
 * Ledger's own [LedgerRow] with the chip and the merchant long press live.
 *
 * Re-read on each foreground and when a delete or restore rewrites the
 * ledger, as Day is. It never leaves of itself: a merchant merged away
 * re-reads as the one it joined, and a month with nothing counted left is a
 * zero header.
 *
 * [onOpenReview] is the review inbox, from the kept-out line's *M awaiting
 * review* with the first held row of this merchant and month, or from a
 * row's badge with its own. [onOpenEntry] and [onOpenDetail] are a row's
 * body, as on Day.
 */
@Composable
fun MerchantMonthScreen(
    viewModel: MerchantMonthViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenReview: (focus: Long?) -> Unit = {},
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
) {
    val read = viewModel.read.rememberReadState()
    MerchantMonthScreenContent(
        month = viewModel.month,
        read = read.answer,
        storageUnavailable = read.unavailable,
        replacing = read.replacing,
        onBack = onBack,
        categories = viewModel.rows,
        merchants = viewModel.rows,
        modifier = modifier,
        onOpenReview = onOpenReview,
        onOpenEntry = onOpenEntry,
        onOpenDetail = onOpenDetail,
    )
}

/** [MerchantMonthScreen] without its holder, so a test can draw any state. */
@Composable
internal fun MerchantMonthScreenContent(
    month: YearMonth,
    read: MerchantMonthRead?,
    storageUnavailable: Boolean,
    onBack: () -> Unit,
    categories: CategoryActions,
    merchants: MerchantActions,
    modifier: Modifier = Modifier,
    onOpenReview: (focus: Long?) -> Unit = {},
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    today: LocalDate = LocalDate.now(),
    /** A ledger replacement turned the read away (#328): [REPLACING_YOUR_DATA] in place of anything read. */
    replacing: Boolean = false,
) {
    // Hoisted out of the list for the reason `LedgerScreenContent` gives.
    var categorizing by rememberSaveable { mutableStateOf<Long?>(null) }
    val chooser = read?.chooser
    val choices = remember(chooser) { chooser?.let(::chooserChoices).orEmpty() }
    val categoriesById = remember(chooser) { chooser?.categories.orEmpty().associateBy { it.id } }
    val view = remember(read) { read?.let(::merchantMonth) }
    val refused = storageUnavailable || replacing

    if (read == null && !refused) {
        Column(modifier.fillMaxSize().background(Paper)) {
            Chevron(onBack)
            NotReadYet(month, today, Modifier.weight(1f))
        }
    } else {
        // Card from the torn edge to the foot however few rows, as Day's; a
        // notice draws no torn edge, and so stays on Paper.
        LazyColumn(modifier.fillMaxSize().background(if (refused) Paper else Card)) {
            item { Column(Modifier.fillMaxWidth().background(Paper)) { Chevron(onBack) } }
            when {
                // First: stale rows under a ledger that cannot be read would
                // be a claim about money no read supports.
                replacing -> item { Notice(REPLACING_YOUR_DATA) }
                storageUnavailable || read == null || view == null -> item { Notice(CANNOT_READ_YOUR_DATA) }
                else -> {
                    item { MonthHeader(view.name, iconKey = null, view.figures) { onOpenReview(read.firstHeld) } }
                    item { TornEdge(Modifier.background(Paper).padding(top = 20.dp)) }
                    item { Spacer(Modifier.height(14.dp)) }
                    items(read.rows, key = { "txn-" + it.txn.id }) { row ->
                        Box {
                            LedgerRow(
                                row = row,
                                categoriesById = categoriesById,
                                uncategorizedId = read.chooser.uncategorizedId,
                                canCategorize = choices.isNotEmpty(),
                                onCategorize = { categorizing = it },
                                onOpenMerchant = { merchants.openMerchant(it) },
                                onOpenReview = onOpenReview,
                                onOpenEntry = onOpenEntry,
                                onOpenDetail = onOpenDetail,
                                greyed = view.figures.greyed,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }

    // Found among the rows read, for the reason `LedgerScreenContent` gives.
    val chosen = categorizing
    val chosenRow = chosen?.let { id -> read?.rows?.firstOrNull { it.txn.id == id } }
    if (chosen != null && chosenRow != null && chooser != null && choices.isNotEmpty()) {
        RowCategoryChooser(
            row = chosenRow,
            chooser = chooser,
            actions = categories,
            onClose = { categorizing = null },
        )
    }
    MerchantSheet(merchants)
}

@Composable
private fun Chevron(onBack: () -> Unit) {
    BackChevron(MERCHANT_MONTH_BACK, onBack, Modifier.padding(start = 8.dp, top = 12.dp))
}

/**
 * The read in flight, or `LoadingWaiting`'s status line while an export or
 * the integrity check holds the database: Breakdown's header not read yet
 * with no icon cell, then Day's four rows not read yet, a dotted circle, a
 * leader and a dash each, which say nothing about how many rows there are.
 */
@Composable
private fun NotReadYet(month: YearMonth, today: LocalDate, modifier: Modifier = Modifier) {
    ReadingLayout(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().clipToBounds()) {
            MonthHeaderNotReadYet(month, today, icon = false)
            TornEdge(Modifier.padding(top = 20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Card)
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp),
            ) {
                repeat(4) { RowNotReadYet(amount = true) }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MonoLabel, color = Stamp, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
}

/** The chevron's description, and what a test finds it by. */
const val MERCHANT_MONTH_BACK = "Back to Charts"
