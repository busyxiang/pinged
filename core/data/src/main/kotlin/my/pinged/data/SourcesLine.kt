package my.pinged.data

import my.pinged.data.entity.CaptureSource
import my.pinged.parse.ParsePack
import java.time.YearMonth
import java.util.Locale

/** One app a sources line names: its package, and its name as the line prints it. */
data class NamedSource(val pkg: String, val name: String)

/**
 * The **sources line** for one month (CONTEXT.md, Sources line; spec 8,
 * 9.3; #360): the apps its total comes from, and the switched-on apps no rule
 * reads. The one place Charts and Spending get it from, as [CaptureRecord] is
 * for the capture record, so the two screens cannot name different apps.
 *
 * - A **read source** is a `capture_source` switched on that a rule reads:
 *   the loaded pack has the package, or the user taught one message from it.
 * - An **unreadable source** is switched on and no rule reads it.
 * - [from] is every read source as of now, plus every app with a counted
 *   captured transaction in the month, so an app switched off since is still
 *   named on the months it paid in. Nothing stores when a source was switched
 *   on, so a past month can name an app that was not on yet (spec 8).
 * - [unreadable] is every unreadable source. One that paid in the month (a
 *   taught rule since deleted) is in [from] too.
 * - [byHand]: the month has a counted manual entry.
 *
 * Each list is sorted by name, then package, so the order is stable from one
 * read to the next and the same on both screens. A name is in capitals: a
 * pack app's is the pack's own `label`, as Capture sources' sentence names it
 * (`readsLine`, #366), so the two screens never name one app two ways; any
 * other app's is its `capture_source` label, or its package when it has no
 * label, as Could not read falls back.
 *
 * It is a claim about apps, never about days; greying stays the capture
 * record's (#73), and nothing here greys.
 */
class SourcesLine private constructor(
    val from: List<NamedSource>,
    val unreadable: List<NamedSource>,
    val byHand: Boolean,
) {
    /** A run of the line's text, so a screen can make parts of it tap targets. */
    sealed interface Segment {
        val text: String

        /** Words that open nothing. */
        data class Words(override val text: String) : Segment

        /**
         * An app's name. [unreadable] is a name in the cannot-read sentence,
         * which #368 makes open Could not read for [source]; the same app in
         * the `FROM` list is not.
         */
        data class App(val source: NamedSource, val unreadable: Boolean) : Segment {
            override val text: String get() = source.name
        }

        /** `NO APP IS READ YET`, which opens Capture sources. */
        data object NoAppRead : Segment {
            override val text: String get() = "NO APP IS READ YET"
        }
    }

    /**
     * The line as runs: `FROM A, B AND C`, then `, AND BY HAND` for a
     * manual entry; or `NO APP IS READ YET` / `ONLY BY HAND` with no app to
     * name. Then, for unreadable sources, `. X IS ON BUT PINGED CANNOT READ IT
     * YET` (`X AND Y ARE … THEM YET`).
     */
    val segments: List<Segment> = buildList {
        when {
            from.isNotEmpty() -> {
                add(Segment.Words("FROM "))
                addAll(listed(from, unreadable = false))
                if (byHand) add(Segment.Words(", AND BY HAND"))
            }
            byHand -> add(Segment.Words("ONLY BY HAND"))
            else -> add(Segment.NoAppRead)
        }
        if (unreadable.isNotEmpty()) {
            add(Segment.Words(". "))
            addAll(listed(unreadable, unreadable = true))
            add(
                Segment.Words(
                    if (unreadable.size == 1) " IS ON BUT PINGED CANNOT READ IT YET" else " ARE ON BUT PINGED CANNOT READ THEM YET",
                ),
            )
        }
    }

    /** [segments] as one string, for a screen that draws it plainly. */
    val text: String = segments.joinToString("") { it.text }

    /** `A`, `A AND B`, `A, B AND C`: no comma before the last name. */
    private fun listed(sources: List<NamedSource>, unreadable: Boolean): List<Segment> = buildList {
        sources.forEachIndexed { i, source ->
            when {
                i == 0 -> Unit
                i == sources.lastIndex -> add(Segment.Words(" AND "))
                else -> add(Segment.Words(", "))
            }
            add(Segment.App(source, unreadable))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SourcesLine && from == other.from && unreadable == other.unreadable && byHand == other.byHand

    override fun hashCode(): Int = (from.hashCode() * 31 + unreadable.hashCode()) * 31 + byHand.hashCode()

    override fun toString(): String = "SourcesLine($text)"

    companion object {
        /**
         * The line from what is already known, for [read] and for a test.
         *
         * @param sources every `capture_source` row, switched on or off.
         * @param rulePackages every package a rule reads: the pack's and the
         *   taught messages'.
         * @param paidFrom every package with a counted captured transaction in
         *   the month.
         * @param byHand the month has a counted manual entry.
         * @param packLabels each pack package's `label`, which names it ahead
         *   of its `capture_source` label.
         */
        fun of(
            sources: List<CaptureSource>,
            rulePackages: Set<String>,
            paidFrom: Set<String>,
            byHand: Boolean,
            packLabels: Map<String, String> = emptyMap(),
        ): SourcesLine {
            val labels = sources.associate { it.pkg to it.label }
            fun named(pkg: String): NamedSource {
                val label = sequenceOf(packLabels[pkg], labels[pkg]).firstOrNull { !it.isNullOrBlank() } ?: pkg
                return NamedSource(pkg, label.uppercase(Locale.ROOT))
            }
            val on = sources.filter { it.enabled }.map { it.pkg }
            val read = on.filter { it in rulePackages }
            return SourcesLine(
                from = (read + paidFrom).distinct().map(::named).sortedWith(ORDER),
                unreadable = (on - rulePackages).distinct().map(::named).sortedWith(ORDER),
                byHand = byHand,
            )
        }

        /**
         * The line for [month], read through [db], which a screen's read
         * passes so it is of that read's snapshot. [pack] is the loaded parse
         * pack, `Graph.parsePack()`. Blocking, like the DAOs it calls.
         *
         * Three small statements: `capture_source` and the taught packages
         * hold one row per app, and `TxnDao.monthSources` walks the month in
         * `index_txn_local_date_occurred_at`, as the month's totals do.
         * Measured on the Pixel 10a emulator over `SourcesLineTimingTest`'s
         * 20,000-row ledger: 0.65ms median, 0.97ms longest of 50, beside
         * 0.51ms for that month's `monthTotals`.
         */
        fun read(db: PingedDatabase, pack: ParsePack, month: YearMonth): SourcesLine {
            val range = LocalDates.monthRange(month)
            val paid = db.txnDao().monthSources(range.start, range.endInclusive)
            return of(
                sources = db.captureSourceDao().all(),
                rulePackages = pack.packages.mapTo(HashSet()) { it.pkg } + db.userTemplateRuleDao().packages(),
                paidFrom = paid.mapNotNullTo(HashSet()) { if (it.byHand) null else it.sourcePackage },
                byHand = paid.any { it.byHand },
                packLabels = pack.packages.associate { it.pkg to it.label },
            )
        }

        private val ORDER = compareBy<NamedSource>({ it.name }, { it.pkg })
    }
}
