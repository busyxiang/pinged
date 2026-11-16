package my.pinged.ledger.bill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.LocalDates
import my.pinged.data.dao.FeedRow
import my.pinged.ui.CALENDAR
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PlusMark
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.dottedRule

/**
 * What closes a bill's `PAID BACK` list: the share while the bill counts, or
 * while it counts nowhere the line that says where its paybacks count instead.
 */
internal sealed interface PaidBackFooter {
    /** `Your share`, the bill's amount less its counted paybacks, below zero when friends rounded up. */
    data class Share(val sen: Long, val currency: String) : PaidBackFooter

    /** `DetailBillExcluded`'s line, in the share's place. */
    data class OnTheirOwn(val line: String) : PaidBackFooter
}

/**
 * A bill's paybacks (`design/DetailBill.dc.html`, `CashBill`): `PAID BACK`,
 * a row for each that opens it ([onOpen]), the first [SHOWN_PAYBACKS] and an
 * `N more` row that opens the rest in place, so what follows stays in view;
 * then **Add a cash payback** when [onAdd] is given, and [footer].
 *
 * Captured detail and a typed bill's edit screen both draw it, so the two
 * cannot list a bill's paybacks differently.
 */
@Composable
internal fun PaidBackList(
    paybacks: List<FeedRow>,
    onOpen: (FeedRow) -> Unit,
    onAdd: (() -> Unit)?,
    footer: PaidBackFooter,
    modifier: Modifier = Modifier,
) {
    var all by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(PAID_BACK_HEADING, style = MonoLabel, color = Muted)
        Column(Modifier.padding(top = 6.dp)) {
            val shown = if (all) paybacks else paybacks.take(SHOWN_PAYBACKS)
            shown.forEach { PaybackRow(it, onOpen) }
            val hidden = paybacks.size - shown.size
            if (hidden > 0) {
                ListRow(onClick = { all = true }, tag = PAID_BACK_MORE) {
                    Text(moreLine(hidden), fontFamily = Body, fontSize = 14.sp, lineHeight = 18.sp, color = Muted)
                }
            }
            if (onAdd != null) {
                ListRow(onClick = onAdd, tag = ADD_CASH_PAYBACK_ROW, spacing = 8.dp) {
                    PlusMark(size = 16.dp, strokeWidth = 1.6.dp, tint = Muted)
                    Text(ADD_CASH_PAYBACK, fontFamily = Body, fontSize = 14.sp, lineHeight = 18.sp, color = Muted)
                }
            }
        }
        when (footer) {
            is PaidBackFooter.Share -> ShareLeader(footer, Modifier.padding(top = 12.dp))
            is PaidBackFooter.OnTheirOwn -> Text(
                footer.line,
                style = MonoLabel.copy(lineHeight = 18.sp),
                color = Muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** One payback: who, `20 SEP · MAYBANK MAE`, the amount, and a chevron, since it opens. */
@Composable
private fun PaybackRow(row: FeedRow, onOpen: (FeedRow) -> Unit) {
    ListRow(onClick = { onOpen(row) }, tag = paybackRowTag(row.txn.id), spacing = 10.dp) {
        Column(Modifier.weight(1f)) {
            Text(
                row.displayName ?: row.txn.merchantRaw ?: NO_MERCHANT,
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = if (row.counts) Ink else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Muted where the board has Faint, which is 3.12:1 on Card.
            Text(paybackSmallPrint(row), style = MonoLabel.copy(letterSpacing = 0.6.sp), color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Text(figure(row.txn.amountSen, row.txn.currency), style = MonoNumerals.copy(fontSize = 14.sp), color = if (row.counts) Ink else Muted)
        Chevron(pointsRight = true, size = 14.dp, strokeWidth = 1.5.dp, tint = Faint)
    }
}

/** A 44dp row of the list, its whole width one button, with the dotted rule under it. */
@Composable
private fun ListRow(onClick: () -> Unit, tag: String, spacing: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .dottedRule()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** `Your share ........ 32.00`, on one baseline, as Detail's fields are set. */
@Composable
private fun ShareLeader(share: PaidBackFooter.Share, modifier: Modifier) {
    val lift = with(LocalDensity.current) { 4.5.dp.roundToPx() }
    Row(modifier.fillMaxWidth().testTag(YOUR_SHARE_ROW)) {
        Text(YOUR_SHARE, fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, lineHeight = 18.sp, color = Ink, modifier = Modifier.alignByBaseline())
        Spacer(
            Modifier
                .weight(1f)
                .height(1.dp)
                .alignBy { it.measuredHeight + lift }
                .dottedRule(inset = 8.dp),
        )
        Text(
            figure(share.sen, share.currency),
            style = MonoNumerals.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
            color = Ink,
            modifier = Modifier.alignByBaseline().testTag(YOUR_SHARE_FIGURE),
        )
    }
}

/** `20 SEP · MAYBANK MAE`, or `· ENTERED BY HAND` for a typed payback (spec 9.1's wording for one). */
internal fun paybackSmallPrint(row: FeedRow): String {
    val source = if (row.txn.rawCaptureId == null) "ENTERED BY HAND" else (row.txn.sourceLabel ?: row.txn.sourcePackage)?.uppercase(CALENDAR)
    val day = shortDay(LocalDates.calendarDay(row.txn.localDate)).uppercase(CALENDAR)
    return listOfNotNull(day, source).joinToString(Separator)
}

/** The rest of a long list, behind one row: `6 more`. */
internal fun moreLine(hidden: Int): String = "$hidden more"

/**
 * `DetailBillExcluded`'s line: where a left-out bill's paybacks count, `THEY
 * COUNT ON THEIR OWN, ON 20 SEP, UNTIL THIS COUNTS AGAIN`, the day they
 * arrived, or each its own when they arrived on several.
 */
internal fun onTheirOwnLine(paybacks: List<FeedRow>): String {
    val days = paybacks.map { it.txn.localDate }.distinct()
    val on = if (days.size == 1) "ON " + shortDay(LocalDates.calendarDay(days.single())).uppercase(CALENDAR) else "EACH ON THE DAY IT ARRIVED"
    val they = if (paybacks.size == 1) "IT COUNTS ON ITS OWN" else "THEY COUNT ON THEIR OWN"
    return "$they, $on, UNTIL THIS COUNTS AGAIN"
}

/**
 * What a bill's removal says of its paybacks (spec 9.4, 9.7;
 * `NotPaymentBill`, `DeleteBill`): `Its 2 paybacks stay, as money
 * received.`, or with one, `Daniel Lim's RM20.00 stays, as money received
 * on 16 Aug.`; null with none.
 */
internal fun paybacksStayLine(paybacks: List<FeedRow>): String? = when (paybacks.size) {
    0 -> null
    1 -> paybacks.single().let {
        (it.displayName ?: it.txn.merchantRaw ?: NO_MERCHANT) + "'s " + money(it.txn.amountSen, it.txn.currency, symbol = true) +
            " stays, as money received on " + shortDay(LocalDates.calendarDay(it.txn.localDate)) + "."
    }
    else -> "Its ${paybacks.size} paybacks stay, as money received."
}

/**
 * Received's state on a typed bill paybacks point at (spec 9.4, `CashBill`):
 * the line under the row, and the segment's TalkBack state.
 */
internal fun receivedOffLine(pointers: Int): String =
    "RECEIVED IS OFF: " + if (pointers == 1) "1 PAYBACK POINTS HERE" else "$pointers PAYBACKS POINT HERE"

/** How many paybacks the list shows before `N more` (spec 9.7). */
internal const val SHOWN_PAYBACKS = 3

internal const val PAID_BACK_HEADING = "PAID BACK"
internal const val ADD_CASH_PAYBACK = "Add a cash payback"
internal const val YOUR_SHARE = "Your share"

/** What a test finds the list's rows by. */
internal fun paybackRowTag(id: Long): String = "paid-back-$id"
internal const val PAID_BACK_MORE = "paid-back-more"
internal const val ADD_CASH_PAYBACK_ROW = "paid-back-add"
internal const val YOUR_SHARE_ROW = "paid-back-share"
internal const val YOUR_SHARE_FIGURE = "paid-back-share-figure"

private const val NO_MERCHANT = "No merchant"
