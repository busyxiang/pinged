package my.pinged.ledger.home

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.pinged.capture.CaptureStorage
import my.pinged.capture.Graph
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.RetroPreview
import my.pinged.parse.SameShop
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.Category
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
 * that met damage is replaced; `LedgerScreen`'s resume effect re-runs
 * [refresh] on `Databases.rewrites`, which does not.
 */
class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    /** What the last successful [refresh] read, published in one piece. */
    private val _read = MutableStateFlow(LedgerRead())
    val read: StateFlow<LedgerRead> = _read.asStateFlow()

    /**
     * Not folded into [read], because it has a different lifetime: it is
     * written by `CaptureStorage.guarded`'s failure callback on a path that
     * produced no read at all, and again from [assignCategory], which reads
     * nothing.
     */
    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    /** Spec 6.4's merchant sheet, while one is open; see [openMerchant]. */
    private val _merchantSheet = MutableStateFlow<MerchantSheetState?>(null)
    val merchantSheet: StateFlow<MerchantSheetState?> = _merchantSheet.asStateFlow()

    /**
     * The `category` table, read once per database this holder reads from.
     *
     * **Safe only while nothing writes the table**: §4's editor is out and the
     * one write on this screen is `txn.category_id`. Read per refresh these are
     * two of the six statements a chip tap issues, re-reading fourteen rows to
     * move one row between them. A restore does replace the table, with the
     * backup's rows, which is why [Catalogue] carries the generation it was
     * read under and [readCatalogueOnce] reads again when that has moved.
     *
     * **Null means "not read yet", never "read and empty".** A refresh that
     * cannot open the database throws out of `all()` before the assignment, so
     * a failed first refresh memoizes nothing and the next one tries again;
     * that is why a single nullable field holds both values rather than two,
     * since a resolved `uncategorizedId` is legitimately null.
     *
     * `@Volatile` because two refreshes can overlap -- the resume effect and a
     * chip tap both launch on `viewModelScope` and both suspend into
     * `Dispatchers.IO`. The worst a stale read costs is one extra pass over
     * fourteen rows, never a wrong answer.
     */
    @Volatile private var catalogue: Catalogue? = null

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
            (bound?.db?.let { LeasedFeed(it.txnDao().feed(), it.invalidationTracker) } ?: ReopeningFeed(
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
                paging.insertSeparators { before, after -> dayHeaderBetween(before, after) }
            }
            .cachedIn(viewModelScope)

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
     * **The aggregates are not re-read here.** `LedgerScreen`'s resume effect
     * is keyed on `Databases.rewrites` and calls [refresh], which reads
     * through `Databases` afresh every time; a screen coming back is a resume
     * in any case. A holder refreshing for itself would open the database for
     * a screen nobody is looking at, and every holder left alive would do it
     * at once.
     *
     * [bound] is left alone if it was already opened under the current
     * generation -- a [ReopeningFeed] that finished between the reset and
     * this collector running bound the new database, and invalidating it
     * would only read the first page twice.
     *
     * [catalogue] needs nothing here: [readCatalogueOnce] compares its
     * generation itself.
     */
    private fun rebind() {
        val stale = bound
        if (stale != null && stale.generation != Databases.generation.value) {
            bound = null
            feedSource?.invalidate()
        }
    }

    /**
     * Re-read the aggregates for the current month.
     *
     * Also the app's database probe from this screen's side: `guarded` is what
     * opens the database and therefore what records or clears the storage flag
     * the banner reads. It is **not** the app's only probe --
     * `MainActivity.onResume` keeps its own, because the banner must not depend
     * on which destination happens to be composed.
     *
     * [now] is a parameter so a test can name the month it built a fixture for
     * instead of arranging today.
     *
     * **Nothing queues two refreshes**, unlike `SourcesViewModel`, whose
     * `enqueue`/`tail` chain records why it needs one. The single write here is
     * the whole answer to one read, so overlapping refreshes could only leave
     * the older answer on top until the newer lands.
     *
     * **[assignCategory]'s escape is this method's too.** `guarded` catches
     * the open; a statement that fails afterwards -- a `SQLiteFullException`
     * on a full disk -- is outside [DatabaseUnavailableException] and escapes
     * `viewModelScope`. Left uncovered for the reason recorded there.
     */
    fun refresh(now: YearMonth = YearMonth.now()): Job = viewModelScope.launch {
        val month = LocalDates.monthRange(now)

        // No `withContext(Dispatchers.IO)` of its own: `guarded` dispatches its
        // whole body, which is what `GuardedDispatchTest` pins.
        // Damage on the month's own pages is this screen's to draw, not a
        // capture stopping: see `CaptureStorage.guarded`.
        CaptureStorage.guarded(
            context,
            what = "The ledger cannot be read",
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            val txns = Databases.txnDao(context)
            val daySubtotals = txns.dayTotals(month.start, month.endInclusive)
                .groupBy({ it.localDate }, { CurrencyTotal(it.currency, it.netSen) })

            // Elapsed, not the whole month: days that have not happened yet
            // are not gaps, and counting them would grey out every month
            // total until its last day.
            //
            // Today can still be a false positive on the first foreground of
            // a day: a process bound across midnight writes today's row from
            // that foreground, launched and not awaited, so this read may run
            // first. Erring toward the warning is the safe direction.
            // `CaptureDayDao.boundDayCount` carries the same note.
            val elapsed = if (YearMonth.now() == now) java.time.LocalDate.now().dayOfMonth
            else now.lengthOfMonth()
            val bound = Databases.captureDayDao(context)
                .boundDayCount(month.start, month.endInclusive)

            val catalogue = readCatalogueOnce()
            // Every refresh, unlike the catalogue: a teaching save changes it,
            // and the next chooser has to start from the rule just written.
            val learned = Databases.merchantRuleDao(context).learnedRules()
                .associate { it.pattern to it.categoryId }
            val merged = Databases.merchantIdentityDao(context).mergedIdentities().toSet()
            val summary = MonthSummary(
                month = now,
                totals = txns.monthTotals(month.start, month.endInclusive),
                top = txns.monthByCategory(month.start, month.endInclusive, limit = TOP_CATEGORIES),
                trustworthy = bound >= elapsed,
            )

            // One assignment, at the end, built from locals: see [LedgerRead]
            // for what a snapshot mixing two reads costs.
            _read.value = LedgerRead(
                daySubtotals = daySubtotals,
                summary = summary,
                categories = catalogue.categories,
                uncategorizedId = catalogue.uncategorizedId,
                learnedRules = learned,
                mergedIdentities = merged,
                dictionaryFiling = catalogue.dictionaryFiling,
            )
            _storageUnavailable.value = false
        }
    }

    /**
     * [catalogue], filled in on the first refresh that gets to the database.
     *
     * All fourteen categories, not a lookup per line: the table is seeded and
     * tiny, so three point lookups cost more than one walk.
     * `uncategorizedIdOrNull` beside it is one indexed point lookup, measured
     * at under a millisecond next to the four aggregate reads around it -- so
     * [catalogue] is a memo about statement count, not about a slow read.
     *
     * Blocking, like every `CategoryDao` method here, so it may only be called
     * from inside a `CaptureStorage.guarded` block.
     */
    private fun readCatalogueOnce(): Catalogue {
        // Read before the query, so a reset landing during it leaves the
        // older number on the result and the next refresh reads again.
        val generation = Databases.generation.value
        return catalogue?.takeIf { it.generation == generation } ?: run {
            val categories = Databases.categoryDao(context)
            val all = categories.all()
            Catalogue(
                categories = all,
                uncategorizedId = categories.uncategorizedIdOrNull(),
                // The pack is the shipped one and the categories are this
                // generation's, so the marking is the filing stage two makes.
                dictionaryFiling = dictionaryCategoryIds(
                    Graph.parsePack().dictionary,
                    all.associate { it.name to it.id },
                ),
                generation = generation,
            ).also { catalogue = it }
        }
    }

    /**
     * Move one transaction to [categoryId] and re-read the month (§9.1's chip).
     *
     * [teach] is the chooser's "Always call this" switch: on writes the
     * merchant's learned rule and leaves the row `user_edited = 0`
     * (`MerchantRuleDao.teach`); off, or a row with no merchant key, is a
     * one-off (`TxnDao.setCategory`, `user_edited = 1`).
     *
     * **The refresh is not tidiness.** The feed invalidates itself -- Room
     * invalidates the `PagingSource` on the `UPDATE`, so the row redraws on its
     * own -- but the three aggregates are separate queries nothing invalidates,
     * so without this the top three keeps naming the category the money just
     * left.
     *
     * [now] is a parameter for the same reason [refresh]'s is, and it is load
     * bearing rather than symmetric: a test that assigns on a fixture dated in
     * a fixed month would otherwise re-read *today's* month afterwards and see
     * the summary go empty.
     *
     * **`setCategory` returning 0 is ignored on purpose.** It means the row is
     * gone, and the honest answer is the [refresh] below: it redraws from what
     * is actually in the database rather than reporting a failure about a row
     * the user can no longer see.
     *
     * Guarded like [refresh], and not because the write is likely to fail:
     * `Databases.txnDao` throws from the *open* in §11.1's state, this runs on
     * `viewModelScope`, and nothing in that scope catches -- so an unguarded
     * write here is a process kill on a tap. `MainThreadRefreshTest` records
     * the mechanism.
     *
     * **The guard covers the open, not the `UPDATE`.** A statement that fails
     * afterwards -- `SQLiteFullException` on a full disk, or a foreign key
     * violation if [categoryId] were deleted between the sheet being drawn and
     * the tap -- is outside [DatabaseUnavailableException] and still escapes
     * `viewModelScope`. Left uncovered on purpose: nothing in this milestone
     * can delete a category (§4's editor is out), and a catch-all here would
     * swallow programming errors it cannot tell from a full disk. §4 is where
     * this stops being hypothetical and where the handling belongs.
     */
    fun assignCategory(
        txnId: Long,
        categoryId: Long,
        teach: Boolean = false,
        now: YearMonth = YearMonth.now(),
    ): Job = viewModelScope.launch {
        CaptureStorage.guarded<Unit>(
            context,
            what = "The category could not be assigned",
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            val at = System.currentTimeMillis()
            // A teaching save writes the merchant's learned rule and leaves the
            // row filed by it. It answers false for a row with no merchant key,
            // which is then saved as the one-off the sheet said it would be.
            //
            // `teachAndFix` also moves the merchant's past payments (#49), under
            // the lease `guarded` holds, in the one transaction that wrote the rule.
            val taught = teach && Databases.merchantRuleDao(context).teachAndFix(txnId, categoryId, at).taught
            if (!taught) Databases.txnDao(context).setCategory(txnId, categoryId, at)
        }
        refresh(now).join()
    }

    /**
     * What teaching [categoryId] on the row [txnId] would also fix (#49), for the
     * chooser's count line. A read, guarded like [assignCategory]: an unreadable
     * ledger answers "nothing", which draws as a count of 0 and is corrected by
     * the save, which re-checks inside its own transaction.
     */
    suspend fun retroPreview(txnId: Long, categoryId: Long): RetroPreview =
        CaptureStorage.guarded(
            context,
            what = "The past payments could not be counted",
            unavailable = { RetroPreview(0, emptyList()) },
            damageStopsCapture = false,
        ) { Databases.merchantRuleDao(context).retroPreview(txnId, categoryId) }

    /**
     * Open spec 6.4's sheet for the merchant a row's [ownKey] belongs to.
     *
     * One read of everything the sheet shows, published whole, so a sheet
     * never draws a name from one read beside a list from another. A key that
     * no longer resolves to any transaction opens nothing.
     *
     * Guarded like [assignCategory], for the same reason and with the same
     * uncovered remainder.
     */
    fun openMerchant(ownKey: String): Job = viewModelScope.launch {
        CaptureStorage.guarded(
            context,
            what = "The merchant could not be read",
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            _merchantSheet.value = readMerchantSheet(Databases.merchantIdentityDao(context), ownKey)
        }
    }

    fun closeMerchant() {
        _merchantSheet.value = null
    }

    /** Spec 6.4's rename, of the merchant the open sheet is about. */
    fun renameMerchant(name: String): Job = writeMerchant("The merchant could not be renamed") { dao, sheet ->
        dao.rename(sheet.identityKey, name, sheet.derivedName)
    }

    /**
     * Spec 6.4's merge of the open sheet's merchant into [targetKey], under the
     * name the sheet showed for it.
     */
    fun mergeMerchant(targetKey: String): Job = writeMerchant("The merchants could not be merged") { dao, sheet ->
        val target = sheet.others.firstOrNull { it.identityKey == targetKey } ?: return@writeMerchant
        dao.merge(source = sheet.ownKey, target = targetKey, targetName = target.displayName ?: targetKey)
    }

    /** Undo the merge of [key]. */
    fun separateMerchant(key: String): Job = writeMerchant("The merchants could not be separated") { dao, _ ->
        dao.separate(key)
    }

    /**
     * One write against the open sheet, which then closes.
     *
     * Nothing is refreshed: the feed observes both of spec 6.4's tables
     * (`LeasedFeed`), and no aggregate [refresh] reads depends on which
     * merchant a row belongs to.
     */
    private fun writeMerchant(
        what: String,
        write: (MerchantIdentityDao, MerchantSheetState) -> Unit,
    ): Job = viewModelScope.launch {
        val sheet = _merchantSheet.value ?: return@launch
        _merchantSheet.value = null
        CaptureStorage.guarded<Unit>(
            context,
            what = what,
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            write(Databases.merchantIdentityDao(context), sheet)
        }
    }

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
    }
}

/**
 * [LedgerViewModel.catalogue]'s two values, so one nullable field can hold
 * both -- see there for why two fields could not -- beside the
 * `Databases.generation` they were read under.
 */
private class Catalogue(
    val categories: List<Category>,
    val uncategorizedId: Long?,
    val dictionaryFiling: (String?) -> Long?,
    val generation: Long,
)

/**
 * What spec 6.4's sheet shows for [ownKey], or null when no transaction carries
 * it any more. Blocking; called inside `CaptureStorage.guarded`.
 *
 * Suggestions are judged against [ownKey], the string the row the user pressed
 * actually carries, not against the merchant it may already be merged into.
 */
@VisibleForTesting
internal fun readMerchantSheet(dao: MerchantIdentityDao, ownKey: String): MerchantSheetState? {
    val identity = dao.canonicalOf(ownKey) ?: ownKey
    val choices = dao.choices()
    val self = choices.firstOrNull { it.identityKey == identity } ?: return null
    val others = SameShop.suggestionsFirst(ownKey, choices.filter { it.identityKey != identity }) { it.identityKey }
    return MerchantSheetState(
        ownKey = ownKey,
        identityKey = identity,
        name = self.displayName ?: identity,
        derivedName = self.derivedName,
        txnCount = self.txnCount,
        mergedInto = if (identity != ownKey) self.displayName ?: identity else null,
        members = if (identity == ownKey) dao.membersOf(identity) else emptyList(),
        others = others,
        suggested = others.filter { SameShop.likely(ownKey, it.identityKey) }.mapTo(HashSet()) { it.identityKey },
        ruleCategory = self.ruleCategory,
        noOwnRule = dao.ownRuleCategory(ownKey) == null,
    )
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
 * Everything else is Room's, and invalidation runs both ways, so Room's
 * tracker invalidating its source reaches the `Pager` through this one.
 */
@VisibleForTesting
internal class LeasedFeed(
    private val room: PagingSource<Int, FeedRow>,
    private val tracker: InvalidationTracker,
) : PagingSource<Int, FeedRow>() {
    // Every table `TxnDao.FEED_SQL` reads, so a rename or a merge (spec 6.4)
    // redraws the rows it names, for the same reason a capture does.
    private val observer = object : InvalidationTracker.Observer(FEED_TABLES) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }
    private val observing = AtomicBoolean()

    init {
        room.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(room::invalidate)
        registerInvalidatedCallback { untracking.launch { stopObserving() } }
    }

    override val jumpingSupported: Boolean get() = room.jumpingSupported
    override val keyReuseSupported: Boolean get() = room.keyReuseSupported

    // On IO, because the release is where a retired instance is closed, and
    // Paging calls this from whatever its collector runs on.
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FeedRow> =
        withContext(Dispatchers.IO) {
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
                    if (invalid) stopObserving()
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
