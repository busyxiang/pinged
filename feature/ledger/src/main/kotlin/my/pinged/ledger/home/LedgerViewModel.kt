package my.pinged.ledger.home

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.room.InvalidationTracker
import androidx.paging.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.pinged.capture.GuardedRunner
import my.pinged.charts.MonthsRead
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.RetroPreview
import my.pinged.data.dao.TxnDao
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.ScreenRead
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Home's state (§9.1). The feed and the aggregates are separate reads, and
 * deliberately so.
 *
 * The feed is a `PagingSource` and the subtotals are not derived from it: a page
 * boundary can fall mid-day, so a subtotal summed from loaded rows is short by
 * whatever is on the next page (§15.4, §9.1).
 *
 * `cachedIn(viewModelScope)` needs a scope that outlives a configuration
 * change, or every rotation re-queries from page one.
 *
 * **Both reads follow a replaced database.** Spec 11.3's delete and 11.2's
 * restore close the database this holder read from and replace it, and the
 * holder outlives both on `MainActivity`'s back stack. [rebind] is what the
 * feed does, on `Databases.generation`, which also moves when a connection
 * that met damage is replaced; `LedgerScreen` reads [read] again on
 * `Databases.rewrites`, through `rememberReadState`, which does not.
 */
class LedgerViewModel(
    app: Application,
    /** Where [picked] survives a process death, as Charts' month does: the entry's own. */
    private val handle: SavedStateHandle,
) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    /**
     * Opens, leases and guards every read and write this screen makes. The
     * summary's read goes through it with [beforeSummary] in front.
     */
    private val runner = GuardedRunner(app)

    /**
     * Spending's two one-shot reads, through `:core:read`: the month's summary
     * and the `Months` sheet. See [SpendingRead]; the screen reads [read]
     * through `rememberReadState`, which is also what raises and clears the
     * storage banner (`ReadState.unavailable`).
     */
    private val spending: SpendingRead = SpendingRead(
        scope = viewModelScope,
        runner = object : ReadRunner by runner {
            override suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T> {
                beforeSummary()
                return runner.read(what, body)
            }
        },
        handle = handle,
        chooser = { rows.readChooser() },
    )

    /** The month's summary, and the category chooser's half; see [SpendingRead.read]. */
    val read: ScreenRead<YearMonth, LedgerRead> get() = spending.read

    /** The `Months` sheet's read; see [SpendingRead.months] and [openMonths]. */
    val months: ScreenRead<Int, MonthsRead> get() = spending.months

    /**
     * The feed's *write seen* signal, one more reason for [read] to read
     * again: `LedgerScreen` hands it to `rememberReadState`. See [FeedWrites].
     */
    internal val feedWrites = FeedWrites()

    /**
     * The row's category and merchant actions, shared with any other screen
     * that draws ledger rows. Every write is read back through [read] in its
     * own turn, and a refusal raises the banner through it.
     */
    private val rows: RowActions = RowActions(
        context = { context },
        scope = viewModelScope,
        runner = runner,
        screen = spending.read.rowScreen(),
    )

    /** The month Spending's header picked, or null to follow the clock; see [SpendingRead.picked]. */
    val picked: StateFlow<YearMonth?> get() = spending.picked

    /** Spec 6.4's merchant sheet, while one is open; see [openMerchant]. */
    val merchantSheet: StateFlow<OpenMerchantSheet?> = rows.merchantSheet

    /**
     * The DAO once the database has opened, with the `Databases.generation`
     * it was opened under, and the reason `AppViewModelFactory` is allowed to
     * be synchronous.
     *
     * Written and read only from `viewModelScope`, which is
     * `Dispatchers.Main.immediate`: `Pager` invokes the factory inside the
     * collection `cachedIn` runs there, [ReopeningFeed] hands the DAO back on
     * the same dispatcher after its `withContext(IO)` returns, and [rebind]
     * runs there too.
     */
    private var bound: Bound? = null

    /**
     * The `PagingSource` the factory last handed out, so [rebind] can
     * invalidate it. Room's own would never be invalidated otherwise: its
     * observer is registered with the instance a reset closed. Main-thread
     * only, as [bound] is.
     */
    private var feedSource: PagingSource<Int, FeedRow>? = null

    /**
     * Tests only: a suspension point between [ReopeningFeed]'s open and its
     * hand-back, where a reset has to land for the refusal in [items] to be
     * observable at all.
     */
    @VisibleForTesting
    internal var afterOpen: suspend () -> Unit = {}

    /**
     * Tests only: runs before each page of the feed is read, outside the
     * lease, so a test can make new rows land after the screen has acted on
     * the old ones -- CI's emulator does that on its own.
     */
    @VisibleForTesting
    internal var beforePage: suspend () -> Unit = {}

    /**
     * Tests only: runs before each read of the month's summary, outside the
     * lease, so a test can hold a picked month's read in flight (#285).
     * Holding the database instead also stalls the main thread: a pick
     * under `withTheDatabaseHeld` never let Compose go idle in 30s.
     */
    @VisibleForTesting
    internal var beforeSummary: suspend () -> Unit = {}

    /**
     * The rows, newest day first, with a day header above the first row of each
     * day.
     *
     * `enablePlaceholders = false`: a placeholder row would draw an
     * amount-shaped blank in a list of money, and §8's honesty rules make an
     * unknown number worse than a shorter list.
     *
     * **The database is opened inside [ReopeningFeed.load], not here.** The
     * factory runs on `Dispatchers.Main.immediate`, so an open in it would be
     * a cold SQLCipher open on the main thread (`OpenTest` measures it); in
     * spec 11.1's state it would be worse than slow, because Room answers a
     * first main-thread open with an `IllegalStateException` from
     * `assertNotMainThread` -- outside the [DatabaseUnavailableException]
     * family, so outside every catch in this file. Measured against a first
     * page overwritten with rubbish: the throw arrives from
     * `CategoryDao_Impl.seedIfEmpty`, before SQLCipher is asked to decrypt
     * anything, so `DatabaseFactory.build`'s translation never runs. `load` is
     * `suspend` and takes the open to `Dispatchers.IO` itself.
     *
     * It is also what makes the failure **recoverable**: a full disk or a
     * transient IO error at the wrong moment must not leave `loadState.refresh`
     * an error for the life of the holder while `refresh` and
     * `MainActivity.onResume` re-probe and clear the banner -- the screen would
     * read CANNOT READ YOUR DATA under a banner saying capture was fine.
     * `LedgerScreen`'s resume effect calls `retry()`, which re-enters the open.
     *
     * **What that covers is failures up to the first successful open of each
     * database.** Once [bound] is set the factory hands back Room's own
     * source and [ReopeningFeed] is out of the path. Nothing is lost by it:
     * `CommonLimitOffsetImpl.load` in room-paging 2.8.4 catches `Exception`
     * and answers `LoadResult.Error` -- read off that artifact's bytecode
     * rather than assumed -- so a later failure still reaches
     * `loadState.refresh` and `retry()` still re-runs the query, through Room
     * instead of through this class.
     *
     * **The one thing that defeats both is a [TxnDao] whose database was
     * closed underneath it**, and spec 11.3's delete and 11.2's restore both
     * do that, in production, while this holder is alive on the back stack --
     * as does `CaptureStorage.guarded` replacing a connection that met damage.
     * The source then keeps drawing its cached pages, `retry()` has nothing
     * failed to retry, and Room's tracker on the new instance never reaches
     * it. [rebind] clears [bound] and invalidates the source, which puts
     * [ReopeningFeed] back in the path against the new database.
     *
     * **An open that straddles a reset is refused.** [ReopeningFeed]'s open
     * reads the generation before it asks for the DAO, and [bound] only takes
     * it if the generation has not moved since. Otherwise an open racing a
     * reset could bind the instance that reset closed after [rebind] had
     * already run for it, and nothing would announce that instance again. A
     * refused open answers `LoadResult.Invalid` all the same, so the factory
     * asks again.
     */
    val items: Flow<PagingData<LedgerItem>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            (bound?.db?.let {
                LeasedFeed(
                    it.txnDao().feed(feedThrough()),
                    it.invalidationTracker,
                    onWrite = feedWrites::saw,
                    onObserving = feedWrites::observing,
                    beforeLoad = { beforePage() },
                )
            } ?: ReopeningFeed(
                open = {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val generation = Databases.generation.value
                            Bound(Databases.shared(context), generation)
                                .also { afterOpen() }
                        }
                    }
                },
                onOpened = { opened ->
                    if (opened.generation == Databases.generation.value) bound = opened
                },
            )).also { feedSource = it }
        }
            .flow
            .map { paging -> paging.map { LedgerItem.Row(it.txn, it.identityKey, it.displayName) as LedgerItem } }
            .map { paging ->
                paging.insertSeparators { before, after ->
                    dayHeaderBetween(before, after)?.let { it.copy(subtotal = subtotalOf(it.date)) }
                }
            }
            .cachedIn(viewModelScope)

    /**
     * The last day the feed draws: the picked month's end, so the rows start
     * at the month the summary above them names and run on into older ones,
     * or every day when nothing is picked. Read when the factory runs; [pick]
     * invalidates the source so it runs again.
     */
    private fun feedThrough(): LocalDate =
        picked.value?.let { LocalDates.of(it.atEndOfMonth()) } ?: TxnDao.EVERY_LATER_DAY

    /**
     * One day's subtotal for its heading, from SQL (§15.3), or null.
     *
     * Per heading, because the feed pages through all of history and any
     * month-wide read would miss the days outside it. It runs when the
     * separator is made, so a write that invalidates the feed re-reads it with
     * the rows, and no refresh has to remember it.
     *
     * Under [Databases.leasing] for the reason [LeasedFeed] records, and on IO
     * because the separator runs on the collector's dispatcher. A failure --
     * a database being deleted, or damaged -- draws no number: the rows' own
     * load reports it, and a subtotal is not worth taking the list down for.
     */
    private suspend fun subtotalOf(date: LocalDate): List<CurrencyTotal>? =
        withContext(Dispatchers.IO) {
            runCatching {
                Databases.leasing { Databases.txnDao(context).dayTotals(date, date) }
            }.getOrNull()
        }?.map { CurrencyTotal(it.currency, it.netSen) }?.ifEmpty { null }

    init {
        // `dropWhile` rather than `drop(1)`: the value read here is what this
        // holder was built against, and a reset landing before the collector
        // subscribes must still count as a change.
        val builtAgainst = Databases.generation.value
        viewModelScope.launch {
            Databases.generation.dropWhile { it == builtAgainst }.collect { rebind() }
        }
    }

    /**
     * The database was replaced: drop the feed's hold on the old one.
     *
     * **Unprompted, and whether or not the screen is composed.** The screen
     * may be showing -- a user who backs out of settings while a restore runs
     * is on it when the restore lands -- and then nothing else would redraw
     * the rows: no resume comes, and Room's tracker cannot see the new
     * instance. When the screen is not composed the invalidation opens
     * nothing: Paging starts a new generation's first load only for a
     * subscriber (`cachedIn`'s `CachedPageEventFlow` launches its collection
     * `LAZY`, read off paging-common 3.5.1's bytecode), so the open happens
     * when the user comes back to the ledger.
     *
     * **The aggregates are not re-read here.** `rememberReadState` reads
     * [read] again on `Databases.rewrites`, through `Databases` afresh every
     * time; a screen coming back is a resume in any case. A holder re-reading
     * for itself would open the database for a screen nobody is looking at,
     * and every holder left alive would do it at once.
     *
     * [bound] is left alone if it was already opened under the current
     * generation -- a [ReopeningFeed] that finished between the reset and
     * this collector running bound the new database, and invalidating it
     * would only read the first page twice.
     *
     * [rows]' category memo needs nothing here: it compares its generation
     * itself.
     */
    private fun rebind() {
        val stale = bound
        if (stale != null && stale.generation != Databases.generation.value) {
            bound = null
            feedSource?.invalidate()
        }
    }

    /**
     * The month on screen is the module's question: [pick] changes it, and
     * `LedgerScreen`'s resume asks [followClock] for the clock's, so there is no
     * second copy of it here for a feed's write to read.
     */
    fun followClock(): Job? = spending.followClock()

    /**
     * Show [month] (#192): the summary reads it and the feed starts at its
     * last day ([feedThrough]).
     *
     * Main thread only, as [feedSource] is. [today] is a parameter so a test
     * can name the month that counts as now.
     */
    fun pick(month: YearMonth, today: YearMonth = YearMonth.now()): Job {
        val read = spending.pick(month, today)
        feedSource?.invalidate()
        return read
    }

    /** The `Months` sheet has opened; see [SpendingRead.openMonths]. */
    fun openMonths(): Job = spending.openMonths()

    /**
     * Move one transaction to [categoryId] and read the month again (§9.1's chip);
     * see [RowActions.assignCategory].
     *
     * **The re-read is not tidiness.** The aggregates are separate queries that
     * only the feed's `txn` observer re-reads (see [FeedWrites]), and an
     * observer is not guaranteed to fire before the chip's own read of them;
     * without it the top three can keep naming the category the money just left.
     * It runs in the write's own turn, for the month asked.
     */
    fun assignCategory(txnId: Long, categoryId: Long, teach: Boolean = false): Job =
        rows.assignCategory(txnId, categoryId, teach)

    /** See [RowActions.retroPreview]. */
    suspend fun retroPreview(txnId: Long, categoryId: Long): RetroPreview? = rows.retroPreview(txnId, categoryId)

    /** See [RowActions.addCategory]; the month, and so the chooser, is read again after. */
    suspend fun addCategory(name: String, iconKey: String): Long? = rows.addCategory(name, iconKey)

    /** See [RowActions.openMerchant]. */
    fun openMerchant(opening: MerchantOpening): Job = rows.openMerchant(opening)

    fun closeMerchant() = rows.closeMerchant()

    fun renameMerchant(name: String): Job = rows.renameMerchant(name)

    fun mergeMerchant(targetKey: String): Job = rows.mergeMerchant(targetKey)

    fun separateMerchant(key: String): Job = rows.separateMerchant(key)

    companion object {
        /** §9.1 says three. The query takes it as a parameter so the two cannot disagree. */
        const val TOP_CATEGORIES = 3

        /**
         * Rows per page. Paging's default initial load is three pages, so the
         * first read is 120 rows -- more than a phone screen holds, which is
         * what `LedgerScreenTest`'s page-boundary fixture has to out-grow to
         * put a day boundary on the far side of a page.
         */
        const val PAGE_SIZE = 40

        /** The [SavedStateHandle] key [picked] is kept under. */
        const val PICKED_MONTH = SpendingRead.PICKED_MONTH
    }
}

/** [LedgerViewModel.bound]: a database, and the `Databases.generation` it came from. */
private class Bound(val db: PingedDatabase, val generation: Long)

/**
 * Room's `PagingSource`, with each page read under `Databases.leasing`, and
 * invalidated by every write to `txn` after its first page is read.
 *
 * **Room's first page is a transaction** -- `CommonLimitOffsetImpl.initialLoad`
 * in room-paging 2.8.4 runs it in `withTransaction(DEFERRED)`, read off the
 * artifact's bytecode -- and an instance closed during one keeps its write
 * lock for the life of the process (`Databases.leasing` has the
 * measurement). A connection this feed's read poisons is retired by the next
 * guarded caller while the feed may still be reading through it; the lease
 * is what keeps it open until the read is done.
 *
 * **Room's own source cannot be trusted to see a write that follows its
 * first page.** Its constructor launches the coroutine that subscribes it to
 * the tracker, and a write committed before that coroutine runs is never
 * replayed to it: the captured payment stays off the ledger until the next
 * write. On a CI emulator with its four cores loaded that was 8 to 20 of
 * every 100 inserts (issue #16). So this feed registers its own observer
 * before the first read, through `addObserver`, which installs the triggers
 * before it returns (room-runtime 2.8.4's bytecode), so no write after the
 * read can go unseen. Pinned by `FeedInvalidationTest`.
 *
 * The observer is removed on invalidation, on IO: `removeObserver` blocks on
 * a trigger sync, and `LedgerViewModel.rebind` invalidates from the main
 * thread. A sync on an instance since closed is skipped by Room's
 * `CloseBarrier`, and one that fails leaves triggers installed, which costs
 * a log row per write and nothing else, so neither is reported.
 *
 * [onWrite] runs when the observer reports a write, or Room's source does
 * first, before the invalidation it causes, and [onObserving] once the observer is installed and before the
 * first page is read: between them a write is never between two reads of
 * whatever else the owner keeps beside the feed.
 *
 * Everything else is Room's, and invalidation runs both ways, so Room's
 * tracker invalidating its source reaches the `Pager` through this one.
 */
@VisibleForTesting
internal class LeasedFeed(
    private val room: PagingSource<Int, FeedRow>,
    private val tracker: InvalidationTracker,
    private val onWrite: () -> Unit = {},
    private val onObserving: () -> Unit = {},
    private val beforeLoad: suspend () -> Unit = {},
) : PagingSource<Int, FeedRow>() {
    // Every table `TxnDao.FEED_SQL` reads, so a rename or a merge (spec 6.4)
    // redraws the rows it names, for the same reason a capture does.
    private val observer = object : InvalidationTracker.Observer(FEED_TABLES) {
        override fun onInvalidated(tables: Set<String>) {
            onWrite()
            invalidate()
        }
    }
    private val observing = AtomicBoolean()

    init {
        // Room's source invalidating while this one is still valid is Room
        // hearing a write before the observer did: the same commit reaches
        // both, and the invalidation removes the observer before the tracker
        // gets to it. So it is a write too, or the owner would not re-read
        // (1 to 6 of every 50 writes with four busy loops in the guest). An
        // invalidation this feed starts -- a rebind, a replaced connection --
        // finds it already invalid and reports nothing.
        room.registerInvalidatedCallback {
            if (!invalid) onWrite()
            invalidate()
        }
        registerInvalidatedCallback(room::invalidate)
        registerInvalidatedCallback { untracking.launch { stopObserving() } }
    }

    override val jumpingSupported: Boolean get() = room.jumpingSupported
    override val keyReuseSupported: Boolean get() = room.keyReuseSupported

    // On IO, because the release is where a retired instance is closed, and
    // Paging calls this from whatever its collector runs on.
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FeedRow> =
        withContext(Dispatchers.IO) {
            beforeLoad()
            Databases.leasing {
                if (observing.compareAndSet(false, true)) {
                    val failed = runCatching { tracker.addObserver(observer) }.exceptionOrNull()
                    if (failed != null) {
                        // Room has the observer before the sync that threw, and a
                        // second `addObserver` of it would not sync again; a retry
                        // has to start clean.
                        stopObserving()
                        observing.set(false)
                        return@withContext LoadResult.Error(failed)
                    }
                    // Invalidated while adding: the callback's removal may have
                    // run first and found nothing to remove.
                    if (invalid) stopObserving() else onObserving()
                }
                room.load(params)
            }
        }

    override fun getRefreshKey(state: PagingState<Int, FeedRow>): Int? = room.getRefreshKey(state)

    private fun stopObserving() {
        runCatching { tracker.removeObserver(observer) }
    }

    internal companion object {
        private val untracking = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val FEED_TABLES = arrayOf("txn", "merchant_alias", "merchant_name")
    }
}

/**
 * The stand-in that opens the database, and reports through Paging rather
 * than throwing when it cannot.
 *
 * [open] runs on `Dispatchers.IO` and `load` is `suspend`, which is the whole
 * reason the open lives here instead of in the `Pager`'s factory -- see
 * [LedgerViewModel.items].
 *
 * On success it hands the DAO back through [onOpened] and answers
 * `LoadResult.Invalid`, Paging's own "discard me and ask the factory again":
 * the factory then returns Room's real `PagingSource`, which is the one that
 * registers for invalidation and so the one that makes a captured payment
 * appear. Returning the page itself instead would load once and never update.
 * The loop terminates because [onOpened] has run by then, and has kept the
 * DAO unless a reset landed during the open -- which happens a bounded number
 * of times, once per reset.
 *
 * On failure it answers `LoadResult.Error`, which leaves `loadState.refresh`
 * an error for `LedgerScreen` to draw as "cannot be read". Any failure, not
 * only [DatabaseUnavailableException]: whatever the open threw is the reason
 * there is no feed. A `retry()` re-enters this `load`, so a failure that has
 * passed does not outlive the holder.
 *
 * `getRefreshKey` returns null because there is no loaded page to key off and
 * the only sensible retry is from the top.
 */
private class ReopeningFeed(
    private val open: suspend () -> Result<Bound>,
    private val onOpened: (Bound) -> Unit,
) : PagingSource<Int, FeedRow>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FeedRow> =
        open().fold(
            { dao -> onOpened(dao); LoadResult.Invalid() },
            { failure -> LoadResult.Error(failure) },
        )

    override fun getRefreshKey(state: PagingState<Int, FeedRow>): Int? = null
}
