package my.pinged.ledger.day

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import my.pinged.ledger.entry.EditTarget
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.LedgerRow
import my.pinged.ledger.home.MerchantActions
import my.pinged.ledger.home.MerchantSheet
import my.pinged.ledger.home.OpenMerchantSheet
import my.pinged.ledger.home.OPEN_REVIEW
import my.pinged.ledger.home.RowCategoryChooser
import my.pinged.ledger.home.chooserChoices
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ledger.RowNotReadYet
import my.pinged.read.rememberReadState
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.heroFigure
import my.pinged.ui.CALENDAR
import my.pinged.ui.weekdayDate
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle

/**
 * One day's transactions, opened from a Charts grid square (#69, #81's `Day`
 * screen): the date, the day's counted total, what was kept out of it, and
 * every row the Ledger would show under that day, drawn by the Ledger's own
 * [LedgerRow] -- excluded rows struck through, pending rows badged, the chip
 * and the merchant long press live.
 *
 * Re-read on each foreground and when a delete or restore rewrites the ledger,
 * as the Ledger's aggregates are.
 *
 * [onOpenReview] is the review inbox (§9.2), from a row's badge with that
 * row's id or from the heading's *M awaiting review* with the day's first
 * held row's; `:app` routes it, as it does Charts' `onDayClick`.
 * [onOpenEntry] is a manual entry's edit screen (#105), from its row's body,
 * and [onOpenDetail] a captured row's detail screen (#153). [onStep] opens
 * the day before or after (#228); null draws no steps.
 */
@Composable
fun DayScreen(
    viewModel: DayViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenReview: (focus: Long?) -> Unit = {},
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    onStep: ((LocalDate) -> Unit)? = null,
) {
    // Reads on each resume and on `Databases.rewrites`, through `rememberReadState`.
    val read = viewModel.read.rememberReadState()
    val merchantSheet by viewModel.merchantSheet.collectAsState()
    DayScreenContent(
        date = viewModel.date,
        read = read.answer,
        storageUnavailable = read.unavailable,
        onBack = onBack,
        onOpenReview = onOpenReview,
        onOpenEntry = onOpenEntry,
        onOpenDetail = onOpenDetail,
        onStep = onStep,
        onAssign = { txnId, categoryId, teach -> viewModel.assignCategory(txnId, categoryId, teach) },
        retroPreview = viewModel::retroPreview,
        addCategory = viewModel::addCategory,
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
    onOpenReview: (focus: Long?) -> Unit = {},
    retroPreview: suspend (txnId: Long, categoryId: Long) -> RetroPreview? = { _, _ -> RetroPreview(0, emptyList()) },
    /** The chooser's New category (#155); null draws no tile. */
    addCategory: (suspend (name: String, iconKey: String) -> Long?)? = null,
    merchantSheet: OpenMerchantSheet? = null,
    merchantActions: MerchantActions = MerchantActions.None,
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    onStep: ((LocalDate) -> Unit)? = null,
    today: LocalDate = LocalDate.now(),
) {
    // Hoisted out of the list for the reason `LedgerScreenContent` gives.
    var categorizing by rememberSaveable { mutableStateOf<Long?>(null) }
    val chooser = read?.chooser
    val choices = remember(chooser) { chooser?.let(::chooserChoices).orEmpty() }
    val categoriesById = remember(chooser) { chooser?.categories.orEmpty().associateBy { it.id } }

    // Card under a read day, its summary items laying Paper over it, so the
    // board's Card runs from the torn edge to the foot however few rows. A
    // notice draws no torn edge, and so stays on Paper.
    val ground = if (read != null && !storageUnavailable) Card else Paper
    if (read == null && !storageUnavailable) {
        // Not the list: the board's Card runs to the foot under rows that
        // stand for rows, so they are clipped rather than scrolled.
        Column(modifier.fillMaxSize().background(Paper)) {
            Header(date, onBack, onStep, today)
            NotReadYet(Modifier.weight(1f))
        }
    } else LazyColumn(modifier.fillMaxSize().background(ground)) {
        item { Header(date, onBack, onStep, today) }
        when {
            // First: stale rows under a ledger that cannot be read would be a
            // claim about money no read supports. A null read lands here only
            // with it.
            storageUnavailable || read == null -> item { Notice(CANNOT_READ_YOUR_DATA, Stamp) }
            else -> {
                // The day's first held row in the order drawn, which is
                // the inbox's newest-first order too, so the inbox opens on
                // the first of this day's cards.
                val firstHeld = read.rows.firstOrNull { it.txn.state == TxnState.PENDING }?.txn?.id
                item { Heading(read.heading) { onOpenReview(firstHeld) } }
                item { TornEdge(Modifier.background(Paper).padding(top = 20.dp).testTag(DAY_TORN_EDGE)) }
                item { Spacer(Modifier.height(14.dp)) }
                items(read.rows, key = { "txn-" + it.txn.id }) { row ->
                    Box {
                        LedgerRow(
                            row = row,
                            categoriesById = categoriesById,
                            uncategorizedId = read.chooser.uncategorizedId,
                            canCategorize = choices.isNotEmpty(),
                            onCategorize = { categorizing = it },
                            onOpenMerchant = merchantActions.open,
                            onOpenReview = onOpenReview,
                            onOpenEntry = onOpenEntry,
                            onOpenDetail = onOpenDetail,
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
            addCategory = addCategory,
        )
    }
    if (merchantSheet != null) MerchantSheet(merchantSheet, merchantActions)
}

/**
 * The chevron, the steps to the neighbouring days, and the date.
 *
 * The steps are [onStep]'s, and drawn only where it is given (#228): a 20dp
 * grid cell missed costs one tap here rather than a trip back to Charts. No
 * step leads past [today], into a day that has not happened.
 */
@Composable
private fun Header(date: LocalDate, onBack: () -> Unit, onStep: ((LocalDate) -> Unit)?, today: LocalDate) {
    Column(Modifier.fillMaxWidth().background(Paper)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .size(44.dp)
                    .semantics { contentDescription = DAY_BACK },
                contentAlignment = Alignment.Center,
            ) {
                Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
            }
            Spacer(Modifier.weight(1f))
            if (onStep != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Step(date.minusDays(1), forward = false, onStep)
                    if (date < today) Step(date.plusDays(1), forward = true, onStep)
                }
            }
        }
        Text(
            weekdayDate(date),
            fontFamily = Display,
            fontSize = 25.sp,
            lineHeight = 1.15.em,
            color = Ink,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
    }
}

/**
 * One step: the board's 44dp outlined button naming [to] as `WED 24`, the
 * chevron on the side it leads.
 */
@Composable
private fun Step(to: LocalDate, forward: Boolean, onStep: (LocalDate) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${weekdayDate(to)}") { onStep(to) }
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .border(1.dp, Border, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!forward) Chevron(pointsRight = false, size = 14.dp, strokeWidth = 1.05.dp, tint = Ink)
        Text(stepLabel(to), style = MonoLabel, color = Ink)
        if (forward) Chevron(pointsRight = true, size = 14.dp, strokeWidth = 1.05.dp, tint = Ink)
    }
}

/** `WED 24`: a step's name for the day it opens. */
internal fun stepLabel(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, CALENDAR).uppercase(CALENDAR) + " " + date.dayOfMonth

/**
 * The total, the capture line, the kept-out line and the not-watching line,
 * each only when [DayHeading] has it.
 *
 * The kept-out line's *M awaiting review* is a link, in the accent, that
 * calls [onOpenReview] (§9.1); the rest of the line is not, so a tap on
 * "excluded" opens nothing.
 */
@Composable
private fun Heading(heading: DayHeading, onOpenReview: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Paper).padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
        heading.total?.let { Text(heroFigure(it, senSize = 26.sp), style = TotalStyle, color = Ink) }
        heading.captureLine?.let {
            // MonoLabel in Muted, not the board's 10px in Faint: the type
            // file's overrule for small print, at a size and contrast that
            // can be read.
            Text(it, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        heading.keptOut?.let { line ->
            val review = heading.awaitingReview
            val text = buildAnnotatedString {
                if (review == null) {
                    append(line)
                } else {
                    append(line.removeSuffix(review))
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = OPEN_REVIEW,
                            styles = TextLinkStyles(SpanStyle(color = Stamp, textDecoration = TextDecoration.Underline)),
                        ) { onOpenReview() },
                    ) { append(review) }
                }
            }
            Text(text, fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
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

/**
 * The day's read in flight: `design/LoadingDay.dc.html`, or
 * `LoadingWaiting.dc.html` while an export or the integrity check holds the
 * database. The date is the header's, known without a read; the total is the
 * hero's dash, and the board's four rows a dotted circle, a leader and a dash
 * each, which say nothing about how many rows the day has.
 */
@Composable
private fun NotReadYet(modifier: Modifier = Modifier) {
    ReadingLayout(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().clipToBounds()) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                Text(HERO_NOT_READ_YET, style = TotalStyle, color = Rule)
                ReadingStatus(Modifier.padding(top = 6.dp))
            }
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
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MonoLabel, color = color, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
}

/** A hatched day's line (#69). It stands alone when the day has no rows. */
internal const val NOT_WATCHING =
    "Pinged was not watching on this day. These are only the transactions it has from elsewhere."

/** The torn edge between the summary and the rows, as a test finds it. */
internal const val DAY_TORN_EDGE = "day-torn-edge"

/** The chevron's description, and what a test finds it by. */
const val DAY_BACK = "Back to Charts"

/** The board's 46px serif hero; [heroFigure] sets its sen at 26. */
private val TotalStyle = TextStyle(fontFamily = Display, fontSize = 46.sp, lineHeight = 1.05.em)
