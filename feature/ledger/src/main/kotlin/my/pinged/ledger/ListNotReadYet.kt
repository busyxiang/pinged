package my.pinged.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ui.theme.DottedCell
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ui.theme.ValueDash
import my.pinged.ui.theme.dottedRule

/**
 * A list's body while its read is in flight (#283), drawn from
 * `design/LoadingList.dc.html` through `ReadingLayout`: the status line,
 * then [ROWS] rows of a dotted circle where the icon goes, a dotted rule
 * where the name goes, and [caption] under it.
 *
 * Draw it only in the screen's branch for a read in flight, never around
 * an empty or unreadable list, which draws at once. The rows are clipped,
 * not scrolled: they stand for rows, and say nothing about how many there
 * are.
 *
 * @param caption the loaded row's second line with every read in it a
 *   dash, `— TRANSACTIONS` on Categories; null for none.
 * @param icon false for a list whose rows draw no icon.
 */
@Composable
internal fun ListNotReadYet(caption: String?, modifier: Modifier = Modifier, icon: Boolean = true) {
    ReadingLayout(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().clipToBounds().padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
            ReadingStatus(Modifier.padding(top = 10.dp, bottom = 4.dp))
            repeat(ROWS) { RowNotReadYet(caption = caption, icon = icon) }
        }
    }
}

/** The board's eight. */
private const val ROWS = 8

/** A row not read yet, [RowNotReadYet], as a test finds it. */
internal const val ROW_NOT_READ_YET = "row-not-read-yet"

/** A row's icon not read yet: the dotted circle [RowNotReadYet] draws. */
internal const val NOT_READ_YET_ICON = "not-read-yet-icon"

/**
 * A row not read yet: a dotted circle where its icon goes, then one of two
 * shapes, as the row it stands for has an amount or not.
 *
 * With [amount], Spending's and Day's row (`LoadingSpending`, `LoadingDay`):
 * a dotted leader to a dash where the amount goes, on the list's own rhythm.
 * Without, a list's row (`LoadingList`): 44dp under a dotted rule, a dotted
 * rule where the name goes, and [caption] under it, the loaded row's second
 * line with every read in it a dash; [icon] false for a list whose rows draw
 * none.
 */
@Composable
internal fun RowNotReadYet(amount: Boolean = false, caption: String? = null, icon: Boolean = true) {
    val shape = if (amount) {
        Modifier.padding(vertical = 6.dp)
    } else {
        Modifier.dottedRule(atTop = false).heightIn(min = 44.dp).padding(vertical = 5.dp)
    }
    Row(
        Modifier.fillMaxWidth().testTag(ROW_NOT_READ_YET).then(shape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon) DottedCell(Modifier.testTag(NOT_READ_YET_ICON).size(18.dp), CircleShape)
        if (amount) {
            Box(Modifier.weight(1f).padding(top = 8.dp).height(1.dp).dottedRule())
            ValueDash(style = MonoNumerals.copy(fontSize = 15.sp))
        } else {
            Column(Modifier.weight(1f)) {
                // The name: a dotted rule under a 12dp line, 45% across.
                Box(Modifier.fillMaxWidth(0.45f).height(12.dp).dottedRule(atTop = false))
                if (caption != null) Text(caption, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
