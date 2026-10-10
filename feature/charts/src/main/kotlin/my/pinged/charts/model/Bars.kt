package my.pinged.charts.model

import my.pinged.data.dao.CategoryTotal
import my.pinged.ui.RINGGIT
import my.pinged.ui.ringgit
import my.pinged.ui.ringgitDigits
import my.pinged.ui.theme.Separator
import java.util.Locale

/** What the category bars section draws (#81, Bars). */
sealed interface Bars {
    /**
     * One bar per drawn category or remainder, in drawing order.
     *
     * @property greyed every amount takes the hero's grey: the month is
     *   untrusted (#73). The fills keep the ramp, which means magnitude only.
     */
    data class Drawn(val bars: List<Bar>, val greyed: Boolean) : Bars

    /**
     * Every category netted at or below zero, and at least one below: there
     * is no share to draw, so one sentence stands in for the bars (#68).
     *
     * @property negatives the negative categories, `SHOPPING −RM45.00 · …`.
     */
    data class Suppressed(val sentence: String, val negatives: String, val greyed: Boolean) : Bars

    /**
     * No category moved money: a month with no counted rows, or one whose
     * every category nets to exactly zero. The sentence would claim more came
     * back than went out, which is false for both, so nothing is drawn.
     */
    data object Nothing : Bars
}

/**
 * One bar.
 *
 * @property amount the signed amount without the symbol, `842.50` or `−45.00`.
 * @property sen the amount the bar stands for, signed.
 * @property fraction of the track, in `(0, 1]` above zero and 0 at or below it.
 * @property rampStep the `ChartRamp` step, darkest first; null fills grey.
 * @property quiet the name is drawn grey: `Everything else` and Uncategorized.
 * @property ruleBefore a dotted rule sets the bar apart: Uncategorized only.
 */
data class Bar(
    val name: String,
    val amount: String,
    val sen: Long,
    val fraction: Float,
    val rampStep: Int?,
    val quiet: Boolean = false,
    val ruleBefore: Boolean = false,
)

/**
 * The bars for a month's [totals] by category (#81, Bars; #68). Only the
 * ringgit rows are read.
 *
 * **Every category is drawn exactly once**, as its own bar or inside
 * `Everything else`, so the drawn amounts add up to the hero.
 *
 * Order: the categories at or above zero, Uncategorized aside, largest first;
 * above [ALL_DRAWN] of them, the top [TOP] and then `Everything else (N)`. Then
 * each net-negative category on its own bar, never folded into the remainder,
 * where it would shrink a sum the reader takes for spending. Then
 * Uncategorized, counted but never ranked.
 *
 * Scale: the largest positive drawn bar is the full track, not the month
 * total, which a refund can push below a category or to zero (§15.3, #68).
 */
fun bars(
    totals: List<CategoryTotal>,
    names: Map<Long, String>,
    uncategorizedId: Long?,
    trust: MonthTrust,
): Bars {
    val ringgit = totals.filter { it.currency == RINGGIT }
    val uncategorized = ringgit.firstOrNull { it.categoryId == uncategorizedId }
    val categories = ringgit.filter { it.categoryId != uncategorizedId }
    // Ties by id, so equal categories do not swap places between two reads.
    val ranked = categories.filter { it.netSen >= 0 }.sortedWith(compareByDescending<CategoryTotal> { it.netSen }.thenBy { it.categoryId })
    val negative = categories.filter { it.netSen < 0 }.sortedWith(compareByDescending<CategoryTotal> { it.netSen }.thenBy { it.categoryId })
    val shown = if (ranked.size <= ALL_DRAWN) ranked else ranked.take(TOP)
    val rest = ranked.drop(shown.size)

    val parts = buildList {
        shown.forEachIndexed { i, it -> add(Part(nameOf(it, names), it.netSen, rampStep = i)) }
        if (rest.isNotEmpty()) add(Part("Everything else (${rest.size})", rest.sumOf { it.netSen }, null, quiet = true))
        negative.forEach { add(Part(nameOf(it, names), it.netSen, rampStep = null)) }
        uncategorized?.let { add(Part(nameOf(it, names), it.netSen, null, quiet = true, ruleBefore = true)) }
    }
    val largest = parts.maxOfOrNull { it.sen } ?: 0L
    if (largest <= 0L) {
        val below = parts.filter { it.sen < 0L }
        if (below.isEmpty()) return Bars.Nothing
        return Bars.Suppressed(
            sentence = NO_SHARE,
            negatives = below.joinToString(Separator) { "${it.name.uppercase(Locale.ROOT)} ${ringgit(it.sen)}" },
            greyed = trust.untrusted,
        )
    }
    return Bars.Drawn(
        parts.map { Bar(it.name, ringgitDigits(it.sen), it.sen, fraction(it.sen, largest), it.rampStep, it.quiet, it.ruleBefore) },
        greyed = trust.untrusted,
    )
}

/** A bar before it is scaled. */
private data class Part(
    val name: String,
    val sen: Long,
    val rampStep: Int?,
    val quiet: Boolean = false,
    val ruleBefore: Boolean = false,
)

/** [sen] of [largest], clamped to `(0, 1]`; a bar at or below zero has no width. */
private fun fraction(sen: Long, largest: Long): Float =
    if (sen <= 0L || largest <= 0L) 0f else (sen.toDouble() / largest).toFloat().coerceIn(Float.MIN_VALUE, 1f)

/** A category's name; an id with no row (never, under the foreign key) still draws. */
private fun nameOf(total: CategoryTotal, names: Map<Long, String>): String =
    names[total.categoryId] ?: "Category ${total.categoryId}"

/** The suppressed bars' sentence (#81, Bars), as the prototype prints it. */
const val NO_SHARE = "MORE CAME BACK THAN WENT OUT THIS MONTH, SO THERE IS NO SHARE TO DRAW."

/** Up to this many ranked categories are drawn whole. */
private const val ALL_DRAWN = 6

/** Above [ALL_DRAWN], this many are drawn and the rest fold into one bar. */
private const val TOP = 5
