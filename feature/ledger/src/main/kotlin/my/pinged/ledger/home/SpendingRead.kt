package my.pinged.ledger.home

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import my.pinged.charts.MonthsRead
import my.pinged.charts.monthsRead
import my.pinged.data.CaptureRecord
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.NotInTotalLine
import my.pinged.read.ReadRunner
import my.pinged.read.ScreenRead
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spending's two one-shot reads, through `:core:read`'s [ScreenRead] (#299,
 * #296): the month's summary, and the `Months` sheet's months. The feed is not
 * one of them: it is a `PagingSource`, and `LedgerViewModel` keeps it.
 *
 * **The summary's question is the month on screen**, [read]'s `question`: the
 * one [pick] chose, else the clock's. A month picked while another is read
 * publishes nothing of the one replaced, and a write the feed reports meanwhile
 * reads the month asked, not whichever `YearMonth.now()` names by then: both are
 * the module's rule about a question that has changed.
 *
 * **Following the clock is [followClock]'s**, since the module re-reads the
 * question it holds and a screen left open across a month's end holds the old
 * one.
 *
 * [chooser] is the category chooser's half of the read, called inside the read's
 * transaction so it is of one snapshot with the rows it marks. [today] is asked
 * as each read begins and each time the month is worked out.
 *
 * Built on the holder's scope, whose thread the module's calls must be made on.
 */
internal class SpendingRead(
    scope: CoroutineScope,
    runner: ReadRunner,
    private val handle: SavedStateHandle,
    private val chooser: (PingedDatabase) -> ChooserRead,
    private val today: () -> LocalDate = LocalDate::now,
) {
    /**
     * The month Spending's header picked (#192), or null to follow the clock:
     * a screen left open across a month's end reads the new month on its next
     * resume, as it did before there was a picker. Picking the current month
     * is null too, for the same reason. Kept in [handle] under
     * [PICKED_MONTH] as `2026-09`.
     */
    private val _picked = MutableStateFlow(handle.get<String>(PICKED_MONTH)?.let(YearMonth::parse))
    val picked: StateFlow<YearMonth?> = _picked.asStateFlow()

    /** The month on screen: the one picked, else the clock's. */
    fun shownMonth(): YearMonth = _picked.value ?: YearMonth.from(today())

    /**
     * The month's summary and the chooser's half, published in one piece: see
     * [LedgerRead] for what a snapshot mixing two reads costs. The screen reads
     * it again on resume, on `Databases.rewrites` and when [FeedWrites] says the
     * feed saw a write, through `rememberReadState`.
     */
    val read: ScreenRead<YearMonth, LedgerRead> = ScreenRead(
        scope = scope,
        runner = runner,
        what = "The ledger cannot be read",
        initial = shownMonth(),
    ) { db, month -> readLedger(db, month, chooser(db), today()) }

    /**
     * The `Months` sheet's read, as Charts' is: the question is the opening,
     * numbered by [openMonths], so a sheet opening is a new question and keeps
     * the last opening's months under `UPDATING` while it is read (#285).
     */
    val months: ScreenRead<Int, MonthsRead> = ScreenRead(
        scope = scope,
        runner = runner,
        what = "The months cannot be read",
        initial = 0,
    ) { db, _ -> monthsRead(db, today()) }

    private var openings = 0

    /**
     * The `Months` sheet has opened: read it as of now, as a new question
     * whatever the last opening's answer was, so its rows are as of that moment.
     */
    fun openMonths(): Job = months.ask(++openings)

    /**
     * Show [month] (#192): kept in [handle] so a process death comes back to
     * it, then read. [thisMonth] is a parameter so a test can name the month
     * that counts as now.
     */
    fun pick(month: YearMonth, thisMonth: YearMonth = YearMonth.from(today())): Job {
        _picked.value = month.takeIf { it != thisMonth }
        handle[PICKED_MONTH] = _picked.value?.toString()
        return read.ask(month)
    }

    /**
     * Ask the month the clock names now, **if** nothing is picked and it is not
     * the one already asked. What the resume does that [ScreenRead.refresh]
     * cannot: it reads the question held, which a month's end has left behind.
     * A no-op otherwise, so it is safe beside the resume's own read in either
     * order: the old month's read is then dropped for this one, and a month
     * already asked is not read twice.
     */
    fun followClock(): Job? {
        val shown = shownMonth()
        return if (shown != read.question.value) read.ask(shown) else null
    }

    internal companion object {
        /** The [SavedStateHandle] key [picked] is kept under. */
        const val PICKED_MONTH = "pickedMonth"
    }
}

/**
 * One read of [month] and the chooser's half, all through [db] and so inside
 * the transaction the module reads in (`ReadRunner.read`): the summary's
 * figures and the chooser's categories are one snapshot. The transaction holds
 * Room's one connection, so a capture waits for it: measured on emulator-5554
 * over 20,000 constructed rows across two years, 2.0ms median and 3.8ms at the
 * longest of 40 (the chooser's three statements included) before the capture
 * record, which adds 1.1 to 1.3ms for the month (`CaptureRecord.read`), under stage two's
 * longest single capture (66ms, `Databases.reset`).
 *
 * The month-trust rule is the one Charts and `Months` grey on too (#81), so the
 * three cannot disagree. It never counts today: the first foreground of a day
 * writes today's row launched, not awaited, so this read can run before it
 * lands.
 */
@VisibleForTesting
internal fun readLedger(db: PingedDatabase, month: YearMonth, chooser: ChooserRead, today: LocalDate): LedgerRead {
    val range = LocalDates.monthRange(month)
    val txns = db.txnDao()
    val record = CaptureRecord.read(db, today, month.atDay(1)..month.atEndOfMonth())

    return LedgerRead(
        summary = MonthSummary(
            month = month,
            totals = txns.monthTotals(range.start, range.endInclusive),
            top = txns.monthTopNamed(range.start, range.endInclusive, limit = LedgerViewModel.TOP_CATEGORIES),
            trustworthy = !record.month(month).untrusted,
            toSort = txns.toSortCount(range.start, range.endInclusive),
        ),
        categories = chooser.categories,
        uncategorizedId = chooser.uncategorizedId,
        filer = chooser.filer,
        awaitingReview = txns.awaitingReviewCount(),
        awaitingReviewInMonth = txns.notInTotal(range.start, range.endInclusive)
            .filter { it.line == NotInTotalLine.PENDING }
            .sumOf { it.txnCount },
    )
}

/**
 * Spec 15.4: the Paging feed's *write seen* signal, as a trigger for
 * [SpendingRead.read]: [count] moves when the summary should be read again,
 * and `LedgerScreen` hands it to `rememberReadState` as one more reason to read.
 * A capture updates the total without re-reading the list (#125).
 *
 * `LeasedFeed` calls [saw] when its observer reports a write, and [observing]
 * each time it has installed its `txn` observer, which is **after** any commit
 * that invalidated the source before it and **before** the read of its first
 * page. So the read it brings starts after every write the previous observer
 * reported, and a write after it invalidates the source and brings the next:
 * no commit falls between two reads. The feed is what is observing, so nothing
 * here holds a lease or a tracker of its own, and a screen nobody is collecting
 * (Paging's `LAZY` collection) never gets here.
 *
 * **Only when a write was seen.** A source replaced because the database was
 * (a restore, or a connection replaced for damage) installs an observer too,
 * and reading there makes a month on damaged pages read, meet the damage,
 * replace the connection and read again for as long as the screen is up
 * (`DamagedFeedTest` measures two quiet seconds).
 *
 * Called from the feed's IO threads; [count] is a `StateFlow`, so the
 * composable that collects it does the reading on its own.
 */
internal class FeedWrites {
    private val seen = AtomicBoolean()
    private val _count = MutableStateFlow(0)

    /** Moves each time a write was seen and the feed is observing again; its value means nothing. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** The feed's observer reported a write. */
    fun saw() {
        seen.set(true)
    }

    /** The feed has installed an observer: the summary is read again if a write was seen, once. */
    fun observing() {
        if (seen.compareAndSet(true, false)) _count.update { it + 1 }
    }
}
