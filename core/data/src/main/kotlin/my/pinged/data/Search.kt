package my.pinged.data

import androidx.sqlite.db.SimpleSQLiteQuery
import my.pinged.data.dao.TxnDao
import java.util.Locale

/**
 * The search field's text as an FTS4 `MATCH` expression (spec 15.2).
 *
 * The expression goes to FTS4's query parser, which reads quotes, `*`, `-`,
 * brackets and the capitalised `OR`/`AND`/`NOT`/`NEAR` as syntax: an
 * unbalanced quote throws, and a stray operator silently asks another
 * question. So nothing the user typed reaches it but runs of letters and
 * digits, lowercased, each followed by the one `*` this builds.
 *
 * Words split where a code point is neither a letter nor a digit, by
 * `Character.isLetterOrDigit` rather than a regex class, for the reason the
 * repository's CLAUDE.md gives against `\w`. That is close to `unicode61`'s
 * own token rule, and where the two differ the tokenizer splits the term
 * again at match time, so a disagreement can only add a split, never join
 * two words.
 */
object SearchMatch {
    /**
     * Each word of [query] as a prefix term, all of them required; null when
     * there is no word to match, so the caller leaves the `MATCH` out.
     */
    fun of(query: String): String? {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        query.lowercase(Locale.ROOT).codePoints().forEach { cp ->
            if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "$it*" }
    }
}

/**
 * What a search is narrowed to besides its words (spec 9.1, 15.2), each null
 * when it does not narrow. Every bound is inclusive.
 *
 * @property from the first `local_date`, and [to] the last.
 * @property sourcePackage the app a capture came from; a manual entry has none
 *   and so never matches one.
 * @property minSen the smallest `amount_sen`, and [maxSen] the largest, both
 *   in ringgit: either one set narrows the search to `MYR` rows, since a
 *   bound in sen means nothing against another currency's.
 */
data class SearchFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val categoryId: Long? = null,
    val sourcePackage: String? = null,
    val minSen: Long? = null,
    val maxSen: Long? = null,
)

/**
 * A search's money in one currency: every row it found that the feed would
 * draw ([rowCount]), those §8 counts ([countedCount]), the counted ones that
 * are money out rather than a refund ([paymentCount]), and the counted rows'
 * signed net (§15.3).
 */
data class SearchTotal(
    val currency: String,
    val rowCount: Int,
    val countedCount: Int,
    val paymentCount: Int,
    val netSen: Long,
)

/**
 * The SQL behind `TxnDao.searchRows` and `TxnDao.searchTotals`.
 *
 * Built per search rather than written once with `(:x IS NULL OR col = :x)`
 * for each filter, because that form can seek no index: SQLite plans the
 * statement before it sees the bindings. Built, only the filters set are in
 * the `WHERE`, each an ordinary predicate the planner can take an index for
 * (spec 15.2); `QueryPlanTest` pins the month search's plan. Every value is
 * bound, never spliced.
 *
 * The words are a subquery on `txn_fts` answering `rowid`s, so the rows and
 * every other column still come from `txn`.
 */
object SearchSql {
    /** The feed's rows that match, newest first in the feed's order, at most [limit]. */
    fun rows(text: String, filter: SearchFilter, limit: Int): SimpleSQLiteQuery {
        val (where, args) = where(text, filter)
        return SimpleSQLiteQuery(
            TxnDao.FEED_ROWS + where + TxnDao.FEED_ORDER + " LIMIT ?",
            (args + limit).toTypedArray(),
        )
    }

    /**
     * Per currency: the rows [rows] would find without its limit, and of them
     * those §8 counts, summed as every total is.
     */
    fun totals(text: String, filter: SearchFilter): SimpleSQLiteQuery {
        val (where, args) = where(text, filter)
        return SimpleSQLiteQuery(
            """
            SELECT txn.currency AS currency, COUNT(*) AS rowCount,
                   SUM(${TxnDao.COUNTED_ROW}) AS countedCount,
                   SUM(${TxnDao.COUNTED_ROW} AND txn.direction = 'EXPENSE') AS paymentCount,
                   SUM(CASE WHEN ${TxnDao.COUNTED_ROW} THEN
                       CASE WHEN txn.direction = 'EXPENSE' THEN txn.amount_sen ELSE -txn.amount_sen END
                       ELSE 0 END) AS netSen
            FROM txn
            WHERE txn.state != 'REJECTED'
            """ + where + " GROUP BY txn.currency",
            args.toTypedArray(),
        )
    }

    /** The currency an amount bound is in. */
    private const val RINGGIT = "MYR"

    private fun where(text: String, filter: SearchFilter): Pair<String, List<Any>> {
        val sql = StringBuilder()
        val args = mutableListOf<Any>()
        fun and(clause: String, arg: Any) {
            sql.append("AND ").append(clause).append(' ')
            args += arg
        }
        SearchMatch.of(text)?.let { and("txn.id IN (SELECT rowid FROM txn_fts WHERE txn_fts MATCH ?)", it) }
        filter.from?.let { and("txn.local_date >= ?", it.yyyymmdd) }
        filter.to?.let { and("txn.local_date <= ?", it.yyyymmdd) }
        filter.categoryId?.let { and("txn.category_id = ?", it) }
        filter.sourcePackage?.let { and("txn.source_package = ?", it) }
        if (filter.minSen != null || filter.maxSen != null) and("txn.currency = ?", RINGGIT)
        filter.minSen?.let { and("txn.amount_sen >= ?", it) }
        filter.maxSen?.let { and("txn.amount_sen <= ?", it) }
        return sql.toString() to args
    }
}
