package my.pinged.ledger.breakdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.dao.FeedRow
import my.pinged.ledger.BackChevron
import my.pinged.ledger.MonthHeader
import my.pinged.ledger.MonthHeaderNotReadYet
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.home.CategoryActions
import my.pinged.ledger.home.LedgerRow
import my.pinged.ledger.home.MerchantActions
import my.pinged.ledger.home.MerchantSheet
import my.pinged.ledger.home.RowCategoryChooser
import my.pinged.ledger.home.chooserChoices
import my.pinged.ledger.home.dayLabel
import my.pinged.read.rememberReadState
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.DashLeader
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.LeaderLine
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.dottedRule
import java.time.LocalDate
import java.time.YearMonth

/**
 * One category's month by merchant (#342; spec 9.3, Breakdown), opened from
 * its bar on Charts or a line of the `Everything else` sheet: the category's
 * net, its count and what was kept out, then a line per merchant identity
 * and one for the rows with no merchant, each opening the rows behind its
 * figure, drawn by the Ledger's own [LedgerRow] with the chip and the
 * merchant long press live.
 *
 * Re-read on each foreground and when a delete or restore rewrites the
 * ledger, as Day is. A category that no longer exists calls [onGone], which
 * `:app` pops back to Charts.
 *
 * [onOpenReview] is the review inbox, from the kept-out line's *M awaiting
 * review* with the first held row of this category and month, or from a
 * row's badge with its own. [onOpenEntry] and [onOpenDetail] are a row's
 * body, as on Day.
 */
@Composable
fun BreakdownScreen(
    viewModel: BreakdownViewModel,
    onBack: () -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenReview: (focus: Long?) -> Unit = {},
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
) {
    val read = viewModel.read.rememberReadState()
    val expanded by viewModel.read.question.collectAsState()
    BreakdownScreenContent(
        month = viewModel.month,
        read = read.answer,
        expanded = expanded,
        storageUnavailable = read.unavailable,
        replacing = read.replacing,
        onBack = onBack,
        onGone = onGone,
        onToggle = { viewModel.toggle(it) },
        categories = viewModel.rows,
        merchants = viewModel.rows,
        modifier = modifier,
        onOpenReview = onOpenReview,
        onOpenEntry = onOpenEntry,
        onOpenDetail = onOpenDetail,
    )
}

/** [BreakdownScreen] without its holder, so a test can draw any state. */
@Composable
internal fun BreakdownScreenContent(
    month: YearMonth,
    read: BreakdownRead?,
    expanded: Set<LineKey>,
    storageUnavailable: Boolean,
    onBack: () -> Unit,
    onGone: () -> Unit,
    onToggle: (LineKey) -> Unit,
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
    val view = remember(read) { read?.let(::breakdown) }
    val refused = storageUnavailable || replacing
    val gone = read != null && view == null && !refused
    LaunchedEffect(gone) { if (gone) onGone() }

    when {
        gone -> Box(modifier.fillMaxSize().background(Paper))
        read == null && !refused -> Column(modifier.fillMaxSize().background(Paper)) {
            Chevron(onBack)
            NotReadYet(month, today, Modifier.weight(1f))
        }
        else -> LazyColumn(modifier.fillMaxSize().background(if (refused) Paper else Card)) {
            item { Column(Modifier.fillMaxWidth().background(Paper)) { Chevron(onBack) } }
            when {
                // First: stale lines under a ledger that cannot be read would
                // be a claim about money no read supports.
                replacing -> item { Notice(REPLACING_YOUR_DATA) }
                storageUnavailable || read == null || view == null -> item { Notice(CANNOT_READ_YOUR_DATA) }
                else -> {
                    item { MonthHeader(view.name, view.iconKey, view.figures) { onOpenReview(read.firstHeld) } }
                    item { TornEdge(Modifier.background(Paper).padding(top = 20.dp)) }
                    item {
                        Text(WHO_GOT_IT, style = MonoLabel, color = Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
                    }
                    lines(view, expanded, read, onToggle) { row ->
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
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }

    // Found among the rows read, for the reason `LedgerScreenContent` gives.
    val chosen = categorizing
    val chosenRow = chosen?.let { id -> read?.rows?.values?.flatten()?.firstOrNull { it.txn.id == id } }
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

/**
 * Each line, and under each expanded one the rows behind its figure, in the
 * feed's order, each under its day's label as the board draws them. An
 * expanded line whose rows have not been read yet draws none until they are.
 */
private fun LazyListScope.lines(
    view: Breakdown,
    expanded: Set<LineKey>,
    read: BreakdownRead,
    onToggle: (LineKey) -> Unit,
    row: @Composable (FeedRow) -> Unit,
) {
    view.lines.forEach { line ->
        val open = line.key in expanded
        item(key = "line-" + line.key.saved) { LineRow(line, open, view.figures.greyed) { onToggle(line.key) } }
        val rows = read.rows[line.key]
        if (open && rows != null) {
            item(key = "rows-" + line.key.saved) {
                Column(
                    Modifier
                        .padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
                        .fillMaxWidth()
                        .background(Paper, RoundedCornerShape(2.dp))
                        .border(1.dp, Border, RoundedCornerShape(2.dp))
                        .padding(top = 4.dp, bottom = 8.dp)
                        .testTag(EXPANSION),
                ) {
                    rows.forEachIndexed { i, it ->
                        if (i == 0 || rows[i - 1].txn.localDate != it.txn.localDate) {
                            Text(dayLabel(it.txn.localDate), style = MonoLabel, color = Faint, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
                        }
                        row(it)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chevron(onBack: () -> Unit) {
    BackChevron(BREAKDOWN_BACK, onBack, Modifier.padding(start = 8.dp, top = 12.dp))
}

/**
 * One line: the name, `N×`, a dotted leader, the signed net and the chevron
 * that says whether it is open, on one baseline as `WHO GOT IT` sets them.
 * The whole line is the button; at the board's 27dp it is above WCAG
 * 2.5.8's 24dp floor, the lines tiling the column with nothing between them.
 */
@Composable
private fun LineRow(line: Line, open: Boolean, greyed: Boolean, onClick: () -> Unit) {
    val figure = if (greyed) Muted else Ink
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (open) HIDE_ROWS else SHOW_ROWS, onClick = onClick)
            .heightIn(min = 27.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeaderLine(Modifier.weight(1f)) {
            Text(line.name, style = MerchantName, color = if (line.noMerchant) Muted else Ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(line.count, style = MonoNumerals.copy(fontSize = 11.sp), color = figure, maxLines = 1, softWrap = false)
            Box(Modifier.dottedRule())
            Text(line.amount, style = MonoNumerals.copy(fontSize = 14.sp, lineHeight = 20.sp), color = figure, maxLines = 1, softWrap = false)
        }
        LineChevron(open, Modifier.padding(start = 6.dp))
    }
}

/** The board's `M6 9l6 6 6-6`, or `M6 15l6-6 6 6` on an open line, at 14dp in [Faint]. */
@Composable
private fun LineChevron(open: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val unit = size.width / 24f
        val (outer, tip) = if (open) 15f to 9f else 9f to 15f
        drawLine(Faint, Offset(6 * unit, outer * unit), Offset(12 * unit, tip * unit), 1.8f * unit, StrokeCap.Round)
        drawLine(Faint, Offset(12 * unit, tip * unit), Offset(18 * unit, outer * unit), 1.8f * unit, StrokeCap.Round)
    }
}

/**
 * The read in flight: `design/LoadingBreakdown.dc.html`, or `LoadingWaiting`'s
 * status line while an export or the integrity check holds the database:
 * [MonthHeaderNotReadYet] with the category's icon cell, and four leaders
 * ending in dashes.
 */
@Composable
private fun NotReadYet(month: YearMonth, today: LocalDate, modifier: Modifier = Modifier) {
    ReadingLayout(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().clipToBounds()) {
            MonthHeaderNotReadYet(month, today, icon = true)
            TornEdge(Modifier.padding(top = 20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Card)
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp),
            ) {
                Text(WHO_GOT_IT, style = MonoLabel, color = Muted)
                Column(Modifier.padding(top = 6.dp)) {
                    repeat(4) {
                        DashLeader(labelStyle = MerchantName, valueStyle = MonoNumerals.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.heightIn(min = 27.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MonoLabel, color = Stamp, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
}

/** The chevron's description, and what a test finds it by. */
const val BREAKDOWN_BACK = "Back to Charts"

/** The lines' heading, as Charts' merchant ranking is headed. */
internal const val WHO_GOT_IT = "WHO GOT IT"

/** A closed line's action, to TalkBack. */
internal const val SHOW_ROWS = "Show its transactions"

/** An open line's action, to TalkBack. */
internal const val HIDE_ROWS = "Hide its transactions"

/** The rows under an open line, as a test finds them. */
internal const val EXPANSION = "breakdown-expansion"

/** The merchant names, as `WHO GOT IT` sets them. */
private val MerchantName: TextStyle = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
