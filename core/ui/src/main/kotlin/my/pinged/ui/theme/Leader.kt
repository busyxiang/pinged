package my.pinged.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * A name, its count, a leader and an amount, on the name's baseline: exactly
 * four children in that order, the leader a [dottedRule] box.
 *
 * The count and the amount are measured first, and the name gets what is
 * left after them and the leader's 12dp least, so a long name ends in an
 * ellipsis and never pushes the amount out of the line. At CI's 320dp a
 * 220dp name cap did: "Restoran Sri Melayu Masakan Kampung Baru" cut the
 * 99.80 beside it to "99.". The leader takes whatever the name does not, 5dp
 * above the line's foot. Breakdown's lines and the `N more merchants` sheet's
 * are drawn with it.
 */
@Composable
fun LeaderLine(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (nameM, countM, leaderM, amountM) = measurables
        val countGap = 6.dp.roundToPx()
        val leaderGap = 8.dp.roundToPx()
        val count = countM.measure(Constraints())
        val amount = amountM.measure(Constraints())
        val fixed = count.width + countGap + 2 * leaderGap + amount.width
        val name = nameM.measure(Constraints(maxWidth = (constraints.maxWidth - fixed - 12.dp.roundToPx()).coerceAtLeast(0)))
        val leaderWidth = (constraints.maxWidth - fixed - name.width).coerceAtLeast(0)
        val leader = leaderM.measure(Constraints.fixed(leaderWidth, 1.dp.roundToPx()))
        val baseline = maxOf(name[FirstBaseline], count[FirstBaseline], amount[FirstBaseline])
        val height = maxOf(
            baseline + (name.height - name[FirstBaseline]),
            baseline + (count.height - count[FirstBaseline]),
            baseline + (amount.height - amount[FirstBaseline]),
        )
        layout(constraints.maxWidth, height) {
            name.place(0, baseline - name[FirstBaseline])
            count.place(name.width + countGap, baseline - count[FirstBaseline])
            leader.place(name.width + countGap + count.width + leaderGap, height - 5.dp.roundToPx() - leader.height)
            amount.place(constraints.maxWidth - amount.width, baseline - amount[FirstBaseline])
        }
    }
}
