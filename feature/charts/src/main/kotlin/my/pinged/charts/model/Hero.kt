package my.pinged.charts.model

import my.pinged.ui.ringgit
import my.pinged.ui.theme.Separator

/**
 * The hero total and the lines under it (#81, Hero).
 *
 * @property split `SPENT RMx · CAME BACK RMy`, only when the net is ≤ 0.
 * @property incomplete `MAY BE INCOMPLETE · N DAYS NOT CAPTURED`, only when
 *   the month is untrusted. Not "at least": a missed refund makes the real
 *   figure lower, so it is no floor (#73).
 * @property greyed the figure takes the untrusted grey.
 */
data class Hero(
    val label: String,
    val figure: String,
    val split: String?,
    val incomplete: String?,
    val greyed: Boolean,
)

/**
 * The hero for a month's ringgit net, its two halves, and its trust.
 *
 * Above zero the figure is spending and reads `TOTAL SPENT`. At or below zero
 * it is not, so it reads `NET SPENT`, keeps its sign, and says what went out
 * and what came back (#68).
 */
fun hero(netSen: Long, spentSen: Long, cameBackSen: Long, trust: MonthTrust): Hero {
    val spending = netSen > 0
    return Hero(
        label = if (spending) "TOTAL SPENT" else "NET SPENT",
        figure = ringgit(netSen),
        split = if (spending) null else "SPENT ${ringgit(spentSen)}${Separator}CAME BACK ${ringgit(cameBackSen)}",
        incomplete = if (trust.untrusted) "MAY BE INCOMPLETE$Separator${notCapturedLabel(trust.notCaptured)}" else null,
        greyed = trust.untrusted,
    )
}
