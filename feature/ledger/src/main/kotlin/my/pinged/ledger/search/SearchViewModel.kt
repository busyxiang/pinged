package my.pinged.ledger.search

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import my.pinged.capture.GuardedRunner
import my.pinged.charts.MonthsRead
import my.pinged.charts.monthsRead
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.SearchFilter
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.SourceApp
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.CategoryActions
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.home.rowScreen
import my.pinged.ledger.settings.ExportSink
import my.pinged.ledger.settings.Transfers
import my.pinged.read.ScreenRead
import java.time.LocalDate
import java.time.YearMonth

/**
 * Search's holder (spec 9.1, 15.2; `design/Search.dc.html`): the words, the
 * four filters, and what the last read found for them.
 *
 * **The words are debounced, the filters are not.** Spec 15.2 puts the
 * debounce ahead of the index: a read per keystroke is a transaction per
 * keystroke on Room's one connection, which a capture then waits behind. A
 * chip is one tap and reads at once. The debounce is the holder's, and only
 * what survives it is asked of [read], whose question is [SearchQuestion].
 *
 * The words and filters live in the entry's [SavedStateHandle], so a search
 * comes back after a process death; what was found does not, and is read
 * again. [start] is where a search opened from `N TO SORT` or Charts'
 * Uncategorized bar begins (#273); see [restoredChoice].
 *
 * A result row offers the ledger's category chooser, and a save re-reads the
 * search through [read], so a row sorted out of an Uncategorized search leaves
 * it at once.
 */
class SearchViewModel(
    app: Application,
    private val handle: SavedStateHandle,
    start: SearchStart? = null,
    private val today: () -> LocalDate = LocalDate::now,
) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    private val runner = GuardedRunner(app)

    private val _query = MutableStateFlow(handle.get<String>(QUERY).orEmpty())

    /** The field's text as typed, before the debounce. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _choice = MutableStateFlow(restoredChoice(handle, start))

    /** The four filters. */
    val choice: StateFlow<SearchChoice> = _choice.asStateFlow()

    /**
     * What the last read found for the words and filters asked of it; its
     * `rereading` says that others are being read. `SearchScreen` reads it
     * again on resume and on `Databases.rewrites` through `rememberReadState`.
     *
     * The words asked of it lag [query] by the debounce, and the screen says
     * `UPDATING` for that gap as well as for the read.
     *
     * The transaction holds Room's one connection, so a capture waits for
     * it. `SearchTimingTest` measured it on emulator-5554 over 20,000
     * constructed rows across two years, median of 40 after 10 warm-ups: a
     * month with no words 3.3ms (5.1ms longest), every month with no words
     * 25.5ms, a month with "grab" 34.0ms, every month with "grab" 37.1ms
     * (38.3ms longest). What the words cost is nearly the same whatever the
     * span, so a month does not make a search by words cheap.
     */
    internal val read: ScreenRead<SearchQuestion, SearchRead> = ScreenRead(
        scope = viewModelScope,
        runner = runner,
        what = "The search cannot be read",
        initial = SearchQuestion(_query.value, _choice.value),
    ) { db, question -> readSearch(db, question) }

    /**
     * Private, so the screen is handed [chooser] and never the merchant half:
     * a result row opens no merchant sheet.
     */
    private val rows = RowActions(
        scope = viewModelScope,
        runner = runner,
        screen = read.rowScreen(),
    )

    /** The result rows' category chooser, shared with the Ledger; the search is read again after each write. */
    val chooser: CategoryActions = rows

    /**
     * The month filter's sheet. Each opening is a new question, numbered from
     * [openMonths], so the last opening's months stay drawn under `UPDATING`
     * until this one lands (#285).
     */
    val months: ScreenRead<Int, MonthsRead> = ScreenRead(
        scope = viewModelScope,
        runner = runner,
        what = "The months cannot be read",
        initial = 0,
    ) { db, _ -> monthsRead(db, today()) }

    /** The app filter's sheet, read when it opens; see [openApps]. */
    val apps: ScreenRead<Int, List<SourceApp>> = ScreenRead(
        scope = viewModelScope,
        runner = runner,
        what = "The apps cannot be read",
        initial = 0,
    ) { db, _ -> db.txnDao().sourceApps() }

    private var openings = 0
    private var appOpenings = 0

    init {
        // A question the screen already shows the answer to is not read
        // again: that is the opening one, which the screen's resume reads at
        // once. Not `drop(1)`: words typed within DEBOUNCE_MS of opening
        // swallow the opening empty words, so the first question here can be
        // the typed one, and dropping it left the screen on the opening read
        // (SearchScreenTest.wordsTypedWhileTheFirstReadIsDebouncedAreRead).
        // [ScreenRead.ask] never blocks, and the read for a question that
        // changes meanwhile publishes nothing, so no `collectLatest`.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(_query.debounce(DEBOUNCE_MS), _choice) { q, c -> SearchQuestion(q, c) }
                .filter { it != read.question.value }
                .collect { read.ask(it) }
        }
    }

    fun setQuery(text: String) {
        _query.value = text
        handle[QUERY] = text
    }

    fun setMonth(month: YearMonth?) = choose(_choice.value.copy(month = month))

    fun setCategory(categoryId: Long?) = choose(_choice.value.copy(categoryId = categoryId))

    fun setApp(app: SourceApp?) = choose(_choice.value.copy(app = app))

    /** [minSen] and [maxSen] as the amount sheet parsed them; null leaves that side open. */
    fun setAmount(minSen: Long?, maxSen: Long?) = choose(_choice.value.copy(minSen = minSen, maxSen = maxSen))

    private fun choose(next: SearchChoice) {
        _choice.value = next
        handle[MONTH] = next.month?.toString() ?: ANY
        handle[CATEGORY] = next.categoryId
        handle[APP] = next.app?.sourcePackage
        handle[APP_LABEL] = next.app?.sourceLabel
        handle[MIN] = next.minSen
        handle[MAX] = next.maxSen
    }

    /**
     * The rows of [question], at most [ROW_LIMIT] of them, their totals from
     * SQL, and the categories the rows and chip are named from, through [db]
     * in the one transaction [read] runs, so the total is over the rows drawn.
     */
    private fun readSearch(db: PingedDatabase, question: SearchQuestion): SearchRead {
        val txns = db.txnDao()
        val filter = question.choice.filter()
        return SearchRead(
            text = question.text,
            choice = question.choice,
            rows = txns.searchRows(question.text, filter, ROW_LIMIT),
            totals = txns.searchTotals(question.text, filter),
            // Through [db], as Day's is.
            chooser = rows.readChooser(db),
        )
    }

    /**
     * "Export these N rows" (#201): every row the read on screen found -- its
     * words and filters, with no [ROW_LIMIT] -- written as spec 12's CSV by
     * [Transfers.spreadsheet]. The read's question rather than the field's,
     * so the file holds the rows whose count the user tapped.
     *
     * This holder's scope is the requester, as `SettingsViewModel`'s is for
     * its own: leaving search abandons the export and deletes the document.
     * The [Job] ends once the export has let go of it.
     */
    fun exportTo(sink: ExportSink): Job {
        val found = read.state.value.answer
        return viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            if (found == null) {
                // Nothing on screen to export; the picker's document is let go.
                runCatching { sink.delete() }
                return@launch
            }
            val pending = Transfers.spreadsheet(context, sink, found.text, found.choice.filter(), coroutineContext.job)
            val answer = try {
                pending.await()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { pending.await() }
                throw cancelled
            }
            when (answer) {
                is Transfers.Answer.Settled -> _exported.value = answer.outcome.serial
                is Transfers.Answer.Refused -> Log.i(TAG, "Export refused while ${answer.busy} runs")
                Transfers.Answer.Abandoned -> Unit
            }
        }
    }

    private val _exported = MutableStateFlow<Long?>(null)

    /**
     * The [Outcome.serial][my.pinged.ledger.settings.Outcome.serial] of this
     * search's last settled export: the export sheet draws that result, and
     * not a spreadsheet the Settings sheet made.
     */
    val exported: StateFlow<Long?> = _exported.asStateFlow()

    /** See [Transfers.cancelExport]: the export sheet's Cancel. */
    fun cancelExport() = Transfers.cancelExport()

    /**
     * The month filter's sheet has opened: read it as of now, as a new
     * question whatever the last opening's answer was.
     */
    fun openMonths(): Job = months.ask(++openings)

    /** The app filter's sheet has opened: read the apps, as a new question for the same reason. */
    fun openApps(): Job = apps.ask(++appOpenings)

    internal companion object {
        const val TAG = "PingedSearch"

        /**
         * Long enough that a word typed at speed is one read, short enough
         * that a pause reads as an answer. Not measured against a typist.
         */
        const val DEBOUNCE_MS = 300L

        /**
         * The most rows one search draws. The totals are SQL's over every
         * row found, so a search past this says how many it is not showing
         * rather than summing what it drew.
         */
        const val ROW_LIMIT = 200

        const val QUERY = "searchQuery"
        const val MONTH = "searchMonth"
        const val CATEGORY = "searchCategory"
        const val APP = "searchApp"
        const val APP_LABEL = "searchAppLabel"
        const val MIN = "searchMin"
        const val MAX = "searchMax"

        /** [MONTH]'s value for no month, which an absent key cannot say: absent is the current one. */
        const val ANY = "any"
    }
}

/**
 * Where a search begins when it is opened for something (#273): [month] and
 * the category [categoryId], every other filter open.
 */
data class SearchStart(val month: YearMonth, val categoryId: Long)

/**
 * The filters a search opens on: those saved in [handle] once the user has
 * chosen any, since every choice saves the month; else [start]; else [now]
 * and nothing else. So a search opened for a month and category comes back
 * to them after a process death until the user changes a filter, and to the
 * user's own filters after that.
 */
internal fun restoredChoice(handle: SavedStateHandle, start: SearchStart?, now: YearMonth = YearMonth.now()): SearchChoice {
    val month = handle.get<String>(SearchViewModel.MONTH)
    if (month == null && start != null) return SearchChoice(month = start.month, categoryId = start.categoryId)
    return SearchChoice(
        month = when (month) {
            null -> now
            SearchViewModel.ANY -> null
            else -> YearMonth.parse(month)
        },
        categoryId = handle.get<Long>(SearchViewModel.CATEGORY),
        app = handle.get<String>(SearchViewModel.APP)?.let { SourceApp(it, handle.get<String>(SearchViewModel.APP_LABEL)) },
        minSen = handle.get<Long>(SearchViewModel.MIN),
        maxSen = handle.get<Long>(SearchViewModel.MAX),
    )
}

/**
 * The four filters (`design/Search.dc.html`'s chips), each null when it does
 * not narrow. [month] starts at the current one, as the artboard draws it.
 */
data class SearchChoice(
    val month: YearMonth? = YearMonth.now(),
    val categoryId: Long? = null,
    val app: SourceApp? = null,
    val minSen: Long? = null,
    val maxSen: Long? = null,
) {
    fun filter(): SearchFilter = SearchFilter(
        from = month?.let { LocalDates.of(it.atDay(1)) },
        to = month?.let { LocalDates.of(it.atEndOfMonth()) },
        categoryId = categoryId,
        sourcePackage = app?.sourcePackage,
        minSen = minSen,
        maxSen = maxSen,
    )
}

/**
 * What [SearchViewModel.read] asks: the words and the filters. Two searches
 * are the same question when both are equal.
 */
internal data class SearchQuestion(val text: String, val choice: SearchChoice)

/**
 * What one read of [SearchViewModel.read] found, and the question it answers.
 * [chooser] is the category chooser's half, read in the same transaction,
 * and its categories are what the rows and the category chip are named from.
 */
internal data class SearchRead(
    val text: String,
    val choice: SearchChoice,
    val rows: List<FeedRow>,
    val totals: List<SearchTotal>,
    val chooser: ChooserRead,
) {
    val categories: List<Category> get() = chooser.categories
}
