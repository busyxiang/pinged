package my.pinged.ledger.home

import my.pinged.data.dao.FeedRow
import my.pinged.ledger.entry.EditTarget

/**
 * Where a ledger row leads, for every screen that lists rows (#377): its body
 * to [open], and its awaiting-review badge, or a screen's *M awaiting
 * review*, to [openReview], the review inbox focused on that row. `:app` builds one and hands the same
 * value to each of them.
 *
 * [openEntry] is spec 9.4's edit screen (#105), [openDetail] a captured row's
 * detail screen (#153), offered whatever the row's state: what Pinged read is
 * worth seeing on a row awaiting review too, and the screen offers only the answers the
 * row can take. Which of the two a row gets is decided here and nowhere else.
 */
class RowOpenings(
    val openEntry: (EditTarget) -> Unit,
    val openDetail: (EditTarget) -> Unit,
    val openReview: (focus: Long?) -> Unit,
) {
    /** [row]'s own screen: a manual entry's edit screen, a captured row's detail. */
    fun open(row: FeedRow) {
        val target = EditTarget(row.txn.id, row.txn.createdAt)
        if (row.isManual) openEntry(target) else openDetail(target)
    }

    /** What [open] does with [row], as the accessibility action names it. */
    internal fun label(row: FeedRow): String = if (row.isManual) EDIT_ENTRY else OPEN_DETAIL

    /** `raw_capture_id IS NULL` is what a manual entry is (spec 4), whatever its source columns hold. */
    private val FeedRow.isManual: Boolean get() = txn.rawCaptureId == null

    companion object {
        /** Opens nothing: for a test or preview that is not about where a row leads. */
        val None = RowOpenings(openEntry = {}, openDetail = {}, openReview = {})
    }
}
