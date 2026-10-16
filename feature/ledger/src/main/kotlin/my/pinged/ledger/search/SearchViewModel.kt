package my.pinged.ledger.search

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.pinged.capture.CaptureStorage
import my.pinged.charts.MonthsRead
import my.pinged.charts.monthsRead
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.SearchFilter
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.SourceApp
import my.pinged.data.entity.Category
import java.time.LocalDate
import java.time.YearMonth

/**
 * Search's holder (spec 9.1, 15.2; `design/Search.dc.html`): the words, the
 * four filters, and what the last read found for them.
 *
 * **The words are debounced, the filters are not.** Spec 15.2 puts the
 * debounce ahead of the index: a read per keystroke is a transaction per
 * keystroke on Room's one connection, which a capture then waits behind. A
 * chip is one tap and reads at once.
 *
 * The words and filters live in the entry's [SavedStateHandle], so a search
 * comes back after a process death; what was found does not, and is read
 * again.
 */
class SearchViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    private val _query = MutableStateFlow(handle.get<String>(QUERY).orEmpty())

    /** The field's text as typed, before the debounce. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _choice = MutableStateFlow(restoreChoice())

    /** The four filters. */
    val choice: StateFlow<SearchChoice> = _choice.asStateFlow()

    private val _read = MutableStateFlow<SearchRead?>(null)

    /** The last read that answers the current words and filters, or null before one lands. */
    internal val read: StateFlow<SearchRead?> = _read.asStateFlow()

    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    private val _months = MutableStateFlow<MonthsRead?>(null)

    /** The month filter's sheet, read when it opens. */
    val months: StateFlow<MonthsRead?> = _months.asStateFlow()

    private val _apps = MutableStateFlow<List<SourceApp>?>(null)

    /** The app filter's sheet, read when it opens. */
    val apps: StateFlow<List<SourceApp>?> = _apps.asStateFlow()

    /**
     * Held across one whole read, so a later read is also a later write of
     * [read]; and a read that finishes after the question changed publishes
     * nothing, since a newer one is coming.
     */
    private val reading = Mutex()

    init {
        // The first value is left to the screen's resume, which reads at once.
        // The read runs in `collectLatest` itself, so a newer question
        // cancels it while it waits for the lease or the IO dispatcher.
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(_query.debounce(DEBOUNCE_MS), _choice) { q, c -> q to c }
                .drop(1)
                .collectLatest { read() }
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

    private fun restoreChoice(): SearchChoice {
        val month = handle.get<String>(MONTH)
        return SearchChoice(
            month = when (month) {
                null -> YearMonth.now()
                ANY -> null
                else -> YearMonth.parse(month)
            },
            categoryId = handle.get<Long>(CATEGORY),
            app = handle.get<String>(APP)?.let { SourceApp(it, handle.get<String>(APP_LABEL)) },
            minSen = handle.get<Long>(MIN),
            maxSen = handle.get<Long>(MAX),
        )
    }

    /**
     * Read the current words and filters: the rows, at most [ROW_LIMIT] of
     * them, their totals from SQL, and the categories the rows and chip are
     * named from, in one transaction so the total is over the rows drawn.
     *
     * The transaction holds Room's one connection, so a capture waits for
     * it. `SearchTimingTest` measured it on emulator-5554 over 20,000
     * constructed rows across two years, median of 40 after 10 warm-ups: a
     * month with no words 3.3ms (5.1ms longest), every month with no words
     * 25.5ms, a month with "grab" 34.0ms, every month with "grab" 37.1ms
     * (38.3ms longest). What the words cost is nearly the same whatever the
     * span, so a month does not make a search by words cheap.
     */
    fun refresh(): Job = viewModelScope.launch { read() }

    private suspend fun read() {
        reading.withLock {
            val text = _query.value
            val choice = _choice.value
            CaptureStorage.guarded(
                context,
                what = "The search cannot be read",
                unavailable = { _storageUnavailable.value = true },
                damageStopsCapture = false,
            ) {
                val read = Databases.inOneTransaction(context) { db ->
                    val txns = db.txnDao()
                    val filter = choice.filter()
                    SearchRead(
                        text = text,
                        choice = choice,
                        rows = txns.searchRows(text, filter, ROW_LIMIT),
                        totals = txns.searchTotals(text, filter),
                        categories = db.categoryDao().all(),
                    )
                }
                if (text == _query.value && choice == _choice.value) _read.value = read
                _storageUnavailable.value = false
            }
        }
    }

    fun readMonths(today: LocalDate = LocalDate.now()): Job = viewModelScope.launch {
        CaptureStorage.guarded(
            context,
            what = "The months cannot be read",
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            _months.value = Databases.inOneTransaction(context) { db -> monthsRead(db, today) }
        }
    }

    fun readApps(): Job = viewModelScope.launch {
        CaptureStorage.guarded(
            context,
            what = "The apps cannot be read",
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            _apps.value = Databases.leasing { Databases.txnDao(context).sourceApps() }
        }
    }

    internal companion object {
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

/** What one [SearchViewModel.refresh] read, and the question it answers. */
internal data class SearchRead(
    val text: String,
    val choice: SearchChoice,
    val rows: List<FeedRow>,
    val totals: List<SearchTotal>,
    val categories: List<Category>,
)
