package my.pinged.ledger.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import my.pinged.ui.CALENDAR
import my.pinged.ui.monthName
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Rule

/**
 * The date chip's picker (#103): one month at a time, today and every past
 * day choosable, no future day (spec 9.4). It goes back without limit,
 * before the history start too: cash spent before Pinged was installed is
 * still spending, and a manual entry never moves that start.
 *
 * Hand-drawn rather than Material3's `DatePicker`, which spells its month and
 * weekday names in the device's locale; every calendar name in the app is
 * [CALENDAR]'s. A future day is drawn but disabled, and the month after
 * [today]'s cannot be reached, so tomorrow is never offered even on a
 * month's last day.
 *
 * [selected] is the day the draft holds; [onPick] hands back a day no later
 * than [today].
 */
@Composable
internal fun EntryDatePicker(
    selected: LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // A String, so the month a user paged to survives rotation in a Bundle.
    var shownText by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val shown = YearMonth.parse(shownText)
    val latest = YearMonth.from(today)
    ReceiptSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonthStep(PREVIOUS_MONTH, pointsRight = false, enabled = true) {
                shownText = shown.minusMonths(1).toString()
            }
            Text(
                monthName(shown),
                style = MonoLabel,
                color = Ink,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            MonthStep(NEXT_MONTH, pointsRight = true, enabled = shown < latest) {
                shownText = shown.plusMonths(1).toString()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            WEEK.forEach { weekday ->
                Text(
                    weekday.getDisplayName(TextStyle.NARROW, CALENDAR),
                    style = MonoLabel,
                    color = Muted,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
            }
        }
        weeks(shown).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        DayCell(date, chosen = date == selected, enabled = !date.isAfter(today)) { onPick(date) }
                    }
                }
            }
        }
    }
}

/** The previous- or next-month chevron. */
@Composable
private fun MonthStep(description: String, pointsRight: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .size(44.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Chevron(pointsRight = pointsRight, size = 16.dp, strokeWidth = 1.5.dp, tint = if (enabled) Ink else Rule)
    }
}

/** One day: filled in [Ink] when it is the draft's, [Faint] and inert when it is in the future. */
@Composable
private fun RowScope.DayCell(
    date: LocalDate,
    chosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(2.dp))
            .then(if (chosen) Modifier.background(Ink) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(dayTag(date)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MonoLabel,
            color = when {
                chosen -> Paper
                enabled -> Ink
                else -> Faint
            },
        )
    }
}

/** [month]'s days in Monday-first weeks, null where a week runs outside it. */
private fun weeks(month: YearMonth): List<List<LocalDate?>> {
    val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = List<LocalDate?>(lead) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    return cells.chunked(7).map { it + List(7 - it.size) { null } }
}

private val WEEK = DayOfWeek.entries

/** What a test finds [date]'s cell by. */
internal fun dayTag(date: LocalDate): String = "entry-day-$date"

/** The chevron to the month before, as TalkBack reads it and a test finds it. */
const val PREVIOUS_MONTH = "Previous month"

/** The chevron to the month after; disabled on today's month. */
const val NEXT_MONTH = "Next month"
