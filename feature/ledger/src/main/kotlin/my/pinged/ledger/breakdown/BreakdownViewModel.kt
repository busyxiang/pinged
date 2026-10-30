package my.pinged.ledger.breakdown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import my.pinged.capture.GuardedRunner
import my.pinged.data.CaptureRecord
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.home.rowScreen
import my.pinged.read.ScreenRead
import my.pinged.ui.RINGGIT
import java.time.LocalDate
import java.time.YearMonth

/**
 * Breakdown's holder (#342; CONTEXT.md, Breakdown): one category's month by
 * merchant, read whole through `:core:read`'s [ScreenRead].
 *
 * [categoryId] and [month] are the whole of what it is built from -- the
 * `Breakdown` key carries nothing else -- so a holder rebuilt after a process
 * death reads afresh and shows nothing stale.
 *
 * **The question is the set of lines expanded**, kept in [handle] under
 * [EXPANDED] so it survives a process death too, and the read fetches the
 * rows behind each. A line gone from a read is dropped from the set when that
 * read lands, so it does not come back expanded if the line does.
 *
 * **Every write is followed by a re-read**, as Day's is: the lines are not a
 * `PagingSource`, and nothing invalidates them. A row re-filed to another
 * category leaves its line's figure and count on that re-read. [today] is
 * asked as each read begins.
 */
class BreakdownViewModel(
    app: Application,
    val categoryId: Long,
    val month: YearMonth,
    private val handle: SavedStateHandle,
    private val today: () -> LocalDate = LocalDate::now,
) : AndroidViewModel(app) {

    private val runner = GuardedRunner(app)

    /**
     * The month's read. `BreakdownScreen` reads it again on resume and on
     * `Databases.rewrites` through `rememberReadState`.
     */
    internal val read: ScreenRead<Set<LineKey>, BreakdownRead> = ScreenRead(
        scope = viewModelScope,
        runner = runner,
        what = "The breakdown cannot be read",
        initial = handle.get<ArrayList<String>>(EXPANDED).orEmpty().mapTo(LinkedHashSet(), ::LineKey),
    ) { db, expanded -> readBreakdown(db, expanded, today()) }

    /** The row's category and merchant actions, shared with the Ledger; the month is read again after each write. */
    val rows = RowActions(
        scope = viewModelScope,
        runner = runner,
        screen = read.rowScreen(),
    )

    init {
        read.state.onEach { state ->
            val answered = state.answered ?: return@onEach
            // Only for the set it answered: a toggle since is the user's newer word.
            if (answered.question != read.question.value) return@onEach
            // The read fetched rows for exactly the expanded lines it found.
            val kept = answered.question.filterTo(LinkedHashSet()) { it in answered.answer.rows }
            if (kept != answered.question) {
                save(kept)
                read.setQuestion(kept)
            }
        }.launchIn(viewModelScope)
    }

    /** Expand [key]'s line, or collapse it if it is expanded, and read the month for it. */
    fun toggle(key: LineKey): Job {
        val now = read.question.value
        val next = if (key in now) now - key else now + key
        save(next)
        return read.ask(next)
    }

    private fun save(expanded: Set<LineKey>) {
        handle[EXPANDED] = expanded.mapTo(ArrayList()) { it.saved }
    }

    /**
     * The category, its lines, the kept-out aggregate and its first held row,
     * the month's capture record, the rows behind each line in [expanded] that
     * exists, and the chooser's half, all through [db] in one transaction, so
     * the header and the lines beneath it are one snapshot.
     */
    private fun readBreakdown(db: PingedDatabase, expanded: Set<LineKey>, today: LocalDate): BreakdownRead {
        val txns = db.txnDao()
        val range = LocalDates.monthRange(month)
        // Through [db]: the same instance and the same snapshot. Its list of
        // categories is also how a deleted or merged category is told.
        val chooser = rows.readChooser(db)
        val lines = txns.breakdownLines(categoryId, range.start, range.endInclusive)
        val live = lines.filter { it.currency == RINGGIT }.mapTo(HashSet()) { LineKey.of(it.identityKey) }
        return BreakdownRead(
            category = chooser.categories.firstOrNull { it.id == categoryId },
            month = month,
            today = today,
            notCaptured = CaptureRecord.read(db, today, month.atDay(1)..month.atEndOfMonth()).month(month).notCaptured,
            lines = lines,
            keptOut = txns.breakdownKeptOut(categoryId, range.start, range.endInclusive),
            firstHeld = txns.breakdownFirstHeld(categoryId, range.start, range.endInclusive, RINGGIT),
            rows = expanded.filter { it in live }.associateWith {
                txns.breakdownRows(categoryId, range.start, range.endInclusive, it.identity, RINGGIT)
            },
            chooser = chooser,
        )
    }

    internal companion object {
        /** [handle]'s key for the expanded lines, as [LineKey.saved] strings. */
        const val EXPANDED = "expanded"
    }
}
