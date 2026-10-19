package my.pinged.ledger.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.charts.MonthsSheet
import my.pinged.charts.model.monthsSheet
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.Seed
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.SourceApp
import my.pinged.data.entity.Category
import my.pinged.data.entity.TxnState
import my.pinged.ledger.entry.EditTarget
import my.pinged.data.dao.RetroPreview
import my.pinged.ledger.home.CategoryChip
import my.pinged.ledger.home.ENTERED_BY_HAND
import my.pinged.ledger.home.RowCategoryChooser
import my.pinged.ledger.home.chooserChoices
import my.pinged.ledger.settings.SearchExportSheet
import my.pinged.ledger.settings.TransferJob
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.settings.grouped
import my.pinged.parse.Amount
import my.pinged.parse.Direction
import my.pinged.ui.CALENDAR
import my.pinged.ui.RINGGIT
import my.pinged.ui.money
import my.pinged.ui.ringgit
import my.pinged.ui.monthName
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Magnifier
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedRule
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle

/**
 * Spec 9.1's search and filters, drawn from `design/Search.dc.html`: the
 * query field, four chips that open a sheet each, the query's own total as
 * the hero, and the rows it found.
 *
 * Matching is by word prefix (spec 15.2's accepted product decision), so the
 * field's placeholder says "starts with". Rows open as the list's do: a
 * manual entry's edit screen through [onOpenEntry], a capture's detail
 * through [onOpenDetail]. An Uncategorized row carries the ledger's
 * "+ CATEGORY", which opens the ledger's chooser over the search (#273).
 *
 * The artboard's "Export these N rows" opens [SearchExportSheet] over every
 * row the search found (#201), and its button hands to the host's
 * save-file picker through [onPickExport]; null draws no export, for a host
 * with no picker.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    onPickExport: (() -> Unit)? = null,
) {
    // Re-read on each foreground and on a delete or restore, as Day's is:
    // nothing invalidates a list read whole.
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(viewModel, rewrites) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val query by viewModel.query.collectAsState()
    val choice by viewModel.choice.collectAsState()
    val read by viewModel.read.collectAsState()
    val unavailable by viewModel.storageUnavailable.collectAsState()
    var sheet by rememberSaveable { mutableStateOf<FilterSheet?>(null) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }

    SearchScreenContent(
        query = query,
        onQuery = viewModel::setQuery,
        choice = choice,
        read = read,
        storageUnavailable = unavailable,
        onBack = onBack,
        onOpenSheet = { sheet = it },
        onOpenEntry = onOpenEntry,
        onOpenDetail = onOpenDetail,
        modifier = modifier,
        onExport = onPickExport?.let { { exportOpen = true } },
        onAssign = { txnId, categoryId, teach -> viewModel.assignCategory(txnId, categoryId, teach) },
        retroPreview = viewModel::retroPreview,
        addCategory = viewModel::addCategory,
    )

    if (exportOpen && onPickExport != null) {
        val transfers by Transfers.state.collectAsState()
        val exported by viewModel.exported.collectAsState()
        // Any export running, whichever screen asked for it -- one runs at a
        // time for the whole process, and this button would be refused --
        // but only this search's own result.
        val own = transfers.outcome?.takeIf { it.serial == exported }
        val job = transfers.running?.takeIf { it.operation.isExport } ?: own?.job ?: TransferJob.Idle
        SearchExportSheet(
            rows = read?.totals?.sumOf { it.rowCount },
            job = job,
            onPick = onPickExport,
            onCancel = viewModel::cancelExport,
            // Dismissing is having seen the result, as the export sheet's is.
            onDismiss = {
                exportOpen = false
                own?.let { Transfers.acknowledge(it.serial) }
            },
        )
    }

    val close = { sheet = null }
    when (sheet) {
        FilterSheet.Month -> {
            LaunchedEffect(viewModel) { viewModel.readMonths() }
            val months by viewModel.months.collectAsState()
            MonthsSheet(
                items = months?.let(::monthsSheet),
                onPick = { close(); viewModel.setMonth(it) },
                onDismiss = close,
                onAny = { close(); viewModel.setMonth(null) },
            )
        }
        FilterSheet.Category -> PickSheet(
            title = "Category",
            any = ANY_CATEGORY,
            options = read?.categories?.map { it.id to it.name },
            selected = choice.categoryId,
            onPick = { close(); viewModel.setCategory(it) },
            onDismiss = close,
        )
        FilterSheet.App -> {
            LaunchedEffect(viewModel) { viewModel.readApps() }
            val apps by viewModel.apps.collectAsState()
            PickSheet(
                title = "App",
                any = ANY_APP,
                options = apps?.map { it to appName(it) },
                selected = choice.app,
                onPick = { close(); viewModel.setApp(it) },
                onDismiss = close,
            )
        }
        FilterSheet.Amount -> AmountSheet(
            minSen = choice.minSen,
            maxSen = choice.maxSen,
            onApply = { min, max -> close(); viewModel.setAmount(min, max) },
            onDismiss = close,
        )
        null -> Unit
    }
}

/** Which chip's sheet is open. */
internal enum class FilterSheet { Month, Category, App, Amount }

/** [SearchScreen] without its holder, so a test can draw any state. */
@Composable
internal fun SearchScreenContent(
    query: String,
    onQuery: (String) -> Unit,
    choice: SearchChoice,
    read: SearchRead?,
    storageUnavailable: Boolean,
    onBack: () -> Unit,
    onOpenSheet: (FilterSheet) -> Unit,
    modifier: Modifier = Modifier,
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onAssign: (txnId: Long, categoryId: Long, teach: Boolean) -> Unit = { _, _, _ -> },
    retroPreview: suspend (txnId: Long, categoryId: Long) -> RetroPreview? = { _, _ -> RetroPreview(0, emptyList()) },
    addCategory: (suspend (name: String, iconKey: String) -> Long?)? = null,
) {
    // Hoisted out of the list for the reason `LedgerScreenContent` gives.
    var categorizing by rememberSaveable { mutableStateOf<Long?>(null) }
    val chooser = read?.chooser
    val choices = remember(chooser) { chooser?.let(::chooserChoices).orEmpty() }
    val categoriesById = remember(read?.categories) { read?.categories.orEmpty().associateBy { it.id } }
    val categoryName = choice.categoryId?.let { categoriesById[it]?.name }
    // The rows the totals count, not the ones drawn: the export has no limit.
    val found = read?.takeUnless { storageUnavailable }?.totals?.sumOf { it.rowCount } ?: 0
    Column(modifier.fillMaxSize().background(Paper)) {
        LazyColumn(Modifier.weight(1f)) {
            item { Field(query, onQuery, onBack) }
            item { Chips(choice, categoryName, onOpenSheet) }
            when {
                // First, for the reason Day's is: rows under a ledger that cannot
                // be read would be a claim about money no read supports.
                storageUnavailable -> item { Notice(CANNOT_READ_YOUR_DATA, Stamp) }
                read == null -> item { Notice("READING", Muted) }
                read.rows.isEmpty() -> item { Notice(nothingFound(read.choice), Muted) }
                else -> {
                    item { Hero(read) }
                    item { TornEdge(Modifier.padding(top = 20.dp)) }
                    items(read.rows, key = { "txn-" + it.txn.id }) { row ->
                        // As `LedgerRow` offers it: never on an excluded row,
                        // whose category is a fact about nothing.
                        val sortable = choices.isNotEmpty() && !row.txn.isExcluded &&
                            row.txn.categoryId == read.chooser.uncategorizedId
                        ResultRow(
                            row,
                            categoriesById[row.txn.categoryId],
                            onOpenEntry,
                            onOpenDetail,
                            onCategorize = if (sortable) ({ categorizing = row.txn.id }) else null,
                        )
                    }
                }
            }
        }
        if (onExport != null && found > 0) ExportFooter(found, onExport)
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
            addCategory = addCategory,
        )
    }
}

/**
 * The artboard's foot: "Export these N rows" in a dashed [Muted] outline
 * under a dotted rule, pinned below the list. [rows] is every row found.
 */
@Composable
private fun ExportFooter(rows: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = true)
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .dashedOutline(Muted)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        ) {
            Canvas(Modifier.size(16.dp)) {
                val stroke = 1.7.dp.toPx()
                val u = size.width / 24f
                drawLine(Muted, Offset(4 * u, 6 * u), Offset(20 * u, 6 * u), stroke, StrokeCap.Round)
                drawLine(Muted, Offset(7 * u, 12 * u), Offset(17 * u, 12 * u), stroke, StrokeCap.Round)
                drawLine(Muted, Offset(10 * u, 18 * u), Offset(14 * u, 18 * u), stroke, StrokeCap.Round)
            }
            Text(exportTheseRows(rows), style = MonoLabel, color = Muted)
        }
    }
}

/** The foot's label: `EXPORT THESE 18 ROWS`, or `THIS ROW` for one. */
internal fun exportTheseRows(rows: Int): String =
    if (rows == 1) "EXPORT THIS ROW" else "EXPORT THESE ${grouped(rows)} ROWS"

/**
 * The artboard's top line: the back chevron, then the field on a 1.5dp Ink
 * rule with the magnifier, and the clear X once there is text to clear.
 *
 * Focused on first composition, since a search screen opened is a question
 * about to be typed.
 */
@Composable
private fun Field(query: String, onQuery: (String) -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .size(44.dp)
                .semantics { contentDescription = SEARCH_BACK },
            contentAlignment = Alignment.Center,
        ) {
            Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .padding(start = 3.dp)
                .underline(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Magnifier(size = 17.dp, tint = Muted)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(PLACEHOLDER, fontFamily = Body, fontSize = 16.sp, color = Faint)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Body, fontSize = 16.sp, color = Ink),
                    cursorBrush = SolidColor(Stamp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(SEARCH_FIELD)
                        .semantics { contentDescription = PLACEHOLDER },
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .clickable(role = Role.Button) { onQuery("") }
                        .size(44.dp)
                        .semantics { contentDescription = CLEAR_SEARCH },
                    contentAlignment = Alignment.Center,
                ) { Cross() }
            }
        }
    }
}

/** The field's 1.5dp rule beneath it, the artboard's `border-bottom`. */
private fun Modifier.underline(): Modifier = drawBehind {
    val y = size.height - 0.75.dp.toPx()
    drawLine(Ink, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
}

/** The artboard's clear X, 16dp in Faint. */
@Composable
private fun Cross() {
    Canvas(Modifier.size(16.dp)) {
        val unit = size.width / 24f
        val stroke = 1.7f * unit
        drawLine(Faint, Offset(6 * unit, 6 * unit), Offset(18 * unit, 18 * unit), stroke, StrokeCap.Round)
        drawLine(Faint, Offset(18 * unit, 6 * unit), Offset(6 * unit, 18 * unit), stroke, StrokeCap.Round)
    }
}

/**
 * The four chips: filled in the accent when they narrow, outlined when they
 * say ANY. Each is drawn at the artboard's 30dp inside a 44dp target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(choice: SearchChoice, categoryName: String?, onOpen: (FilterSheet) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Chip(choice.month?.let(::monthName) ?: ANY_MONTH_CHIP, choice.month != null) { onOpen(FilterSheet.Month) }
        // Set but unnamed is a category no longer in the table, or one not
        // read yet: still a filter, so still drawn on.
        Chip(
            when {
                choice.categoryId == null -> ANY_CATEGORY_CHIP
                else -> categoryName?.uppercase(CALENDAR) ?: SOME_CATEGORY_CHIP
            },
            choice.categoryId != null,
        ) { onOpen(FilterSheet.Category) }
        Chip(choice.app?.let(::appName)?.uppercase(CALENDAR) ?: ANY_APP_CHIP, choice.app != null) {
            onOpen(FilterSheet.App)
        }
        Chip(amountChip(choice.minSen, choice.maxSen), choice.minSen != null || choice.maxSen != null) {
            onOpen(FilterSheet.Amount)
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(CHIP + label),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 30.dp)
                .then(
                    if (on) {
                        Modifier.background(Stamp, RoundedCornerShape(2.dp))
                    } else {
                        Modifier.border(1.dp, Border, RoundedCornerShape(2.dp))
                    },
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MonoLabel, color = if (on) Paper else Muted)
        }
    }
}

/**
 * The query's own total: how many payments and in what period, the signed
 * net in the serif, then the average and whatever the total leaves out.
 * The period is named so a count is never read as all time.
 */
@Composable
private fun Hero(read: SearchRead) {
    val hero = heroLines(read.totals, read.choice.month, shown = read.rows.size)
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 13.dp)) {
        Text(hero.label, style = HeroLabel, color = Faint, modifier = Modifier.testTag(SEARCH_COUNT))
        hero.amount?.let {
            Text(
                searchHeroText(it),
                style = HeroSerif,
                color = Ink,
                modifier = Modifier.padding(top = 4.dp).testTag(SEARCH_TOTAL),
            )
        }
        hero.notes.forEach {
            Text(it, style = MonoLabel, color = Faint, modifier = Modifier.padding(top = 7.dp).testTag(SEARCH_NOTE))
        }
    }
}

/**
 * One row as the artboard draws it -- the day, the name, the amount -- on the
 * card's band, with the list's line beneath the name: its [category] and how
 * it reached the ledger, which spec 9.1 asks of every screen that draws a
 * row. A row the total leaves out is greyed and says so in words, excluded
 * rows struck through as the list strikes them. [onCategorize] draws the
 * ledger's "+ CATEGORY" ahead of the line, as `LedgerRow` places it.
 */
@Composable
private fun ResultRow(
    row: FeedRow,
    category: Category?,
    onOpenEntry: ((EditTarget) -> Unit)?,
    onOpenDetail: ((EditTarget) -> Unit)?,
    onCategorize: (() -> Unit)? = null,
) {
    val txn = row.txn
    val open = (if (txn.rawCaptureId == null) onOpenEntry else onOpenDetail)
        ?.let { { it(EditTarget(txn.id, txn.createdAt)) } }
    val counted = txn.state == TxnState.COMMITTED && !txn.isExcluded
    val tone = if (counted) Ink else Muted
    Row(
        Modifier
            .fillMaxWidth()
            .background(Card)
            .then(if (open != null) Modifier.clickable(onClick = open) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp)
            .dottedRule()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(rowDate(row), style = MonoLabel, color = Faint, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(row.displayName ?: txn.merchantRaw ?: "Unknown merchant", fontFamily = Body, fontSize = 14.5.sp, color = tone)
            // As `LedgerRow` words it: Uncategorized is no name, and a manual
            // entry says how it arrived, never how it was paid.
            val source = if (txn.rawCaptureId == null) ENTERED_BY_HAND else txn.sourceLabel ?: txn.sourcePackage
            val line = listOfNotNull(category?.name?.takeUnless { it == Seed.UNCATEGORIZED }, source)
                .joinToString(Separator)
            if (onCategorize != null || line.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onCategorize != null) CategoryChip(onClick = onCategorize)
                    if (line.isNotEmpty()) {
                        Text(
                            (if (onCategorize != null) Separator else "") + line,
                            fontFamily = Body,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Muted,
                        )
                    }
                }
            }
            if (!counted) {
                val why = if (txn.state == TxnState.PENDING) "AWAITING REVIEW" else "NOT IN THE TOTAL"
                Text(why, style = MonoLabel, color = Muted)
            }
        }
        Text(
            (if (txn.direction == Direction.REFUND) "+" else "") + money(txn.amountSen, txn.currency, symbol = false),
            style = MonoNumerals,
            fontSize = 14.sp,
            color = tone,
            textDecoration = if (txn.isExcluded) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MonoLabel,
        color = color,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp).testTag(SEARCH_NOTICE),
    )
}

/**
 * A sheet of single choices: [any] first, then [options] by name. [options]
 * null is a read in flight, drawn as READING rather than as no options.
 */
@Composable
private fun <T> PickSheet(
    title: String,
    any: String,
    options: List<Pair<T, String>>?,
    selected: T?,
    onPick: (T?) -> Unit,
    onDismiss: () -> Unit,
) {
    ReceiptSheet(onDismissRequest = onDismiss, title = title) {
        PickRow(any, selected == null) { onPick(null) }
        if (options == null) {
            Text("READING", style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 12.dp))
        } else {
            options.forEach { (value, name) -> PickRow(name, value == selected) { onPick(value) } }
        }
    }
}

@Composable
private fun PickRow(text: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .testTag(PICK + text)
            .dottedRule()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontFamily = Body, fontSize = 15.5.sp, color = Ink, modifier = Modifier.weight(1f))
        if (on) Text("CHOSEN", style = MonoLabel, color = Stamp)
    }
}

/**
 * The amount filter: two fields in ringgit, either left empty to leave that
 * side open. Each is read by `Amount.toSen`, the parser every captured
 * amount goes through, so a figure that would not be money there is refused
 * here too, in words.
 */
@Composable
internal fun AmountSheet(minSen: Long?, maxSen: Long?, onApply: (Long?, Long?) -> Unit, onDismiss: () -> Unit) {
    var from by rememberSaveable { mutableStateOf(minSen?.let { money(it, RINGGIT, symbol = false) }.orEmpty()) }
    var to by rememberSaveable { mutableStateOf(maxSen?.let { money(it, RINGGIT, symbol = false) }.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    ReceiptSheet(onDismissRequest = onDismiss, title = "Amount") {
        AmountField(FROM_RM, from) { from = it; problem = null }
        AmountField(TO_RM, to) { to = it; problem = null }
        problem?.let { Text(it, fontFamily = Body, fontSize = 14.sp, color = Stamp, modifier = Modifier.padding(top = 10.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton(ANY_AMOUNT, filled = false, Modifier.weight(1f)) { onApply(null, null) }
            SheetButton(APPLY, filled = true, Modifier.weight(1f)) {
                val parsed = parseRange(from, to)
                if (parsed.problem != null) problem = parsed.problem else onApply(parsed.minSen, parsed.maxSen)
            }
        }
    }
}

@Composable
private fun AmountField(label: String, value: String, onValue: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MonoLabel, color = Muted)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MonoNumerals.copy(fontSize = 18.sp, color = Ink),
            cursorBrush = SolidColor(Stamp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .underline()
                .padding(vertical = 10.dp)
                .semantics { contentDescription = label },
        )
    }
}

/** The amount sheet's two answers: Apply, its primary, filled [Stamp] as every primary is; the other outlined. */
@Composable
private fun SheetButton(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(2.dp))
            .then(if (filled) Modifier.background(Stamp) else Modifier.border(1.dp, Muted, RoundedCornerShape(2.dp)))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = ButtonLabel, color = if (filled) Paper else Ink)
    }
}

/** What [parseRange] made of the two fields: sen for each side, or why not. */
internal data class ParsedRange(val minSen: Long?, val maxSen: Long?, val problem: String? = null)

/** The amount sheet's two fields as sen, each blank for an open side. */
internal fun parseRange(from: String, to: String): ParsedRange {
    val min = from.trim().takeIf { it.isNotEmpty() }
    val max = to.trim().takeIf { it.isNotEmpty() }
    val minSen = min?.let { Amount.toSen(it) ?: return ParsedRange(null, null, "$it is not an amount in ringgit.") }
    val maxSen = max?.let { Amount.toSen(it) ?: return ParsedRange(null, null, "$it is not an amount in ringgit.") }
    if (minSen != null && maxSen != null && minSen > maxSen) {
        return ParsedRange(null, null, "From is more than To.")
    }
    return ParsedRange(minSen, maxSen)
}

/** The hero's three parts, each worded by [heroLines]. */
internal data class HeroLines(val label: String, val amount: String?, val notes: List<String>)

/**
 * The hero's words for [totals] over [month] (null for every month), with
 * [shown] the rows drawn beneath it. The count is of payments, the total
 * their net after refunds, the average the one over the other.
 *
 * One currency leads: ringgit when it is there. Any other is a note, never
 * added to it.
 */
internal fun heroLines(totals: List<SearchTotal>, month: YearMonth?, shown: Int): HeroLines {
    val period = month?.let(::monthName) ?: "ALL TIME"
    val lead = totals.firstOrNull { it.currency == RINGGIT } ?: totals.firstOrNull()
    val notes = mutableListOf<String>()
    val paid = lead?.paymentCount ?: 0
    val label = payments(paid) + Separator + period
    var amount: String? = null
    if (lead != null && lead.countedCount > 0) {
        amount = money(lead.netSen, lead.currency, symbol = true)
    }
    // Net over payments: a refund lowers the money and is not a payment.
    if (lead != null && paid > 0) {
        notes += money(averageSen(lead.netSen, paid), lead.currency, symbol = true) + " A PAYMENT"
    }
    val rows = totals.sumOf { it.rowCount }
    // Payments, refunds and kept-out rows add up to [rows], the count
    // "Export these N rows" names (#216): a counted refund is in neither of
    // the other two, so it has a note of its own.
    val refunds = totals.sumOf { it.countedCount - it.paymentCount }
    val keptOut = totals.sumOf { it.rowCount - it.countedCount }
    totals.filter { it !== lead && it.countedCount > 0 }.forEach {
        notes += "ALSO " + money(it.netSen, it.currency, symbol = true) + " IN " + payments(it.paymentCount)
    }
    if (refunds > 0) notes += (if (refunds == 1) "1 REFUND" else "$refunds REFUNDS") + " IN THE TOTAL"
    if (keptOut > 0) notes += "$keptOut MORE NOT IN THE TOTAL"
    if (rows > shown) notes += "SHOWING THE NEWEST $shown OF $rows"
    return HeroLines(label, amount, notes)
}

private fun payments(n: Int): String = when (n) {
    0 -> "NO PAYMENTS"
    1 -> "1 PAYMENT"
    else -> "$n PAYMENTS"
}

/**
 * [netSen] over [count], to the nearest sen, a half sen away from zero.
 * `Long` throughout: money is sen, and a `Double` average is a rounding of
 * its own.
 */
internal fun averageSen(netSen: Long, count: Int): Long {
    val quotient = netSen / count
    val remainder = netSen % count
    return if (Math.abs(remainder) * 2 >= count) quotient + java.lang.Long.signum(netSen) else quotient
}

/** What the screen says when nothing matched, naming the period searched. */
internal fun nothingFound(choice: SearchChoice): String =
    "NOTHING FOUND" + (choice.month?.let { " IN " + monthName(it) } ?: "")

/** The amount chip: `ANY AMOUNT`, or the range set. */
internal fun amountChip(minSen: Long?, maxSen: Long?): String = when {
    minSen != null && maxSen != null -> ringgit(minSen) + " – " + ringgit(maxSen)
    minSen != null -> "FROM " + ringgit(minSen)
    maxSen != null -> "UP TO " + ringgit(maxSen)
    else -> ANY_AMOUNT_CHIP
}

/** An app as the sheet and chip name it: its label, else its package. */
internal fun appName(app: SourceApp): String = app.sourceLabel ?: app.sourcePackage

/** `30 SEP`, the artboard's day column. */
private fun rowDate(row: FeedRow): String {
    val day = LocalDates.calendarDay(row.txn.localDate)
    return "${day.dayOfMonth} " + day.month.getDisplayName(JavaTextStyle.SHORT, CALENDAR).uppercase(CALENDAR)
}

/** The artboard's `.40`: the sen in a 24sp span in Muted, after the ringgit. */
private fun searchHeroText(amount: String): AnnotatedString {
    val point = amount.lastIndexOf('.')
    if (point < 0) return AnnotatedString(amount)
    return buildAnnotatedString {
        append(amount.substring(0, point))
        withStyle(SpanStyle(fontSize = 24.sp, color = Muted)) { append(amount.substring(point)) }
    }
}

/** The artboard's 42px serif hero. */
private val HeroSerif = TextStyle(fontFamily = Display, fontSize = 42.sp, lineHeight = 1.05.em)

/** The hero's label, the board's widest tracking. */
private val HeroLabel = MonoLabel.copy(letterSpacing = 1.6.sp)

/** The field's placeholder: spec 15.2 says prefix matching is said out loud. */
const val PLACEHOLDER = "Starts with…"

const val SEARCH_BACK = "Back"
const val CLEAR_SEARCH = "Clear search"
const val SEARCH_FIELD = "searchField"
const val SEARCH_COUNT = "searchCount"
const val SEARCH_TOTAL = "searchTotal"
const val SEARCH_NOTE = "searchNote"
const val SEARCH_NOTICE = "searchNotice"

/** A chip's test tag is this followed by its label. */
const val CHIP = "chip:"

/** A sheet row's test tag is this followed by its text. */
const val PICK = "pick:"

const val ANY_MONTH_CHIP = "ANY MONTH"
const val ANY_CATEGORY_CHIP = "ANY CATEGORY"

/** The category chip while its category cannot be named. */
const val SOME_CATEGORY_CHIP = "ONE CATEGORY"
const val ANY_APP_CHIP = "ANY APP"
const val ANY_AMOUNT_CHIP = "ANY AMOUNT"
const val ANY_CATEGORY = "Any category"
const val ANY_APP = "Any app"
const val ANY_AMOUNT = "Any amount"
const val FROM_RM = "FROM RM"
const val TO_RM = "TO RM"
const val APPLY = "Apply"
