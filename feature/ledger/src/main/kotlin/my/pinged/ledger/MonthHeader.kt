package my.pinged.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import my.pinged.charts.model.MonthTrust
import my.pinged.charts.model.notCapturedLabel
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ledger.home.OPEN_REVIEW
import my.pinged.ledger.home.categoryIcon
import my.pinged.ui.RINGGIT
import my.pinged.ui.monthName
import my.pinged.ui.ringgit
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.DottedCell
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule
import my.pinged.ui.theme.heroFigure
import java.time.LocalDate
import java.time.YearMonth

/**
 * The header Breakdown and Merchant month (#344) both draw over one month of
 * the ledger, from plain values, so the two say a month's net, its count and
 * what was kept out in one set of words.
 *
 * @property label `SPENT IN SEPTEMBER 2026`, as Charts' hero words it:
 *   `NET SPENT` at or below zero, and `SO FAR` in the month still running.
 * @property figure the ringgit net, signed.
 * @property count `56 TRANSACTIONS`.
 * @property incomplete the one line that says why an untrusted month greys.
 * @property keptOut `RM45.00 kept out: 0 excluded, 1 awaiting review`, or
 *   null when nothing was; it ends with [awaitingReview] whenever that is not null.
 * @property awaitingReview the part that opens the review inbox, or null
 *   when nothing is held.
 * @property greyed every total and count greys; names stay in ink (#73).
 */
internal data class MonthFigures(
    val label: String,
    val figure: String,
    val count: String,
    val incomplete: String?,
    val keptOut: String?,
    val awaitingReview: String?,
    val greyed: Boolean,
)

/**
 * [netSen] and [count], the ringgit rows counted, over [month] with
 * [keptOut]'s ringgit lines, greyed by Charts' own rule for the month, so a
 * bar or a `WHO GOT IT` row and the screen it opens grey together.
 */
internal fun monthFigures(
    netSen: Long,
    count: Int,
    keptOut: List<NotInTotal>,
    month: YearMonth,
    today: LocalDate,
    notCaptured: Int,
): MonthFigures {
    val running = month == YearMonth.from(today)
    val spent = if (netSen > 0) "SPENT" else "NET SPENT"
    val ringgit = keptOut.filter { it.currency == RINGGIT }
    val excluded = ringgit.filter { it.line != NotInTotalLine.PENDING }.sumOf { it.txnCount }
    val pending = ringgit.filter { it.line == NotInTotalLine.PENDING }.sumOf { it.txnCount }
    val awaitingReview = "$pending awaiting review"
    val trust = MonthTrust(notCaptured)
    return MonthFigures(
        label = spent + (if (running) " SO FAR" else "") + " IN " + monthName(month),
        figure = ringgit(netSen),
        count = if (count == 1) "1 TRANSACTION" else "$count TRANSACTIONS",
        incomplete = if (trust.untrusted) "MAY BE INCOMPLETE$Separator${notCapturedLabel(trust.notCaptured)}" else null,
        // On a count, not a net, as Day's header is: an excluded purchase and
        // a held refund of one amount would hide each other.
        keptOut = if (excluded + pending == 0) {
            null
        } else {
            ringgit(ringgit.sumOf { it.netSen }) + " kept out: $excluded excluded, " + awaitingReview
        },
        awaitingReview = awaitingReview.takeIf { pending > 0 },
        greyed = trust.untrusted,
    )
}

/**
 * The icon, when there is one, and the name, the label, the figure, the
 * incomplete line in an untrusted month, the count and the kept-out line.
 * The kept-out line's *M awaiting review* is a link in the accent, as Day's
 * is; the rest of the line opens nothing.
 */
@Composable
internal fun MonthHeader(name: String, iconKey: String?, figures: MonthFigures, onOpenReview: () -> Unit) {
    val figure = if (figures.greyed) Muted else Ink
    Column(Modifier.fillMaxWidth().background(Paper).padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            iconKey?.let(::categoryIcon)?.let {
                Icon(painterResource(it), contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
            }
            Text(name, fontFamily = Display, fontSize = 25.sp, lineHeight = 1.15.em, color = Ink)
        }
        Text(figures.label, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 12.dp))
        Text(
            heroFigure(figures.figure, senSize = 26.sp, senColour = if (figures.greyed) Faint else Muted),
            style = FigureStyle,
            color = figure,
            modifier = Modifier.padding(top = 4.dp),
        )
        figures.incomplete?.let { Text(it, style = MonoLabel, color = Stamp, modifier = Modifier.padding(top = 7.dp)) }
        Text(figures.count, style = MonoLabel, color = figure, modifier = Modifier.padding(top = 7.dp))
        figures.keptOut?.let { line ->
            val review = figures.awaitingReview
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
    }
}

/**
 * [MonthHeader] while its read is in flight, as `design/LoadingBreakdown`
 * draws it: the month is the key's, and whether it is still running is the
 * clock's; the name is a read, so a dotted rule stands where it goes, after a
 * dotted cell where an [icon] goes. The figure is the hero's dash.
 */
@Composable
internal fun MonthHeaderNotReadYet(month: YearMonth, today: LocalDate, icon: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon) DottedCell(Modifier.size(22.dp))
            Box(Modifier.widthIn(min = 140.dp).height(20.dp).dottedRule())
        }
        Text(
            (if (month == YearMonth.from(today)) "SPENT SO FAR IN " else "SPENT IN ") + monthName(month),
            style = MonoLabel,
            color = Muted,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(HERO_NOT_READ_YET, style = FigureStyle, color = Rule, modifier = Modifier.padding(top = 4.dp))
        ReadingStatus(Modifier.padding(top = 7.dp))
    }
}

/** The board's 46px serif figure; [heroFigure] sets its sen at 26. */
private val FigureStyle = TextStyle(fontFamily = Display, fontSize = 46.sp, lineHeight = 1.05.em)
