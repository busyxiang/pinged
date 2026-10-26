package my.pinged.ledger.home

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.pinged.capture.Graph
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.readLearnedRules
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.CategoryNameTakenException
import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.RetroPreview
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.Category
import my.pinged.parse.Filer
import my.pinged.parse.LearnedRules.Snapshot
import my.pinged.parse.SameShop
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.ScreenRead
import java.time.YearMonth

/**
 * What a ledger row lets the user do, for any screen that draws one (#69): the
 * category chooser's read, its save -- teaching or one-off -- and spec 6.4's
 * merchant sheet.
 *
 * Owned by a view model, which hands over its own [scope], the [runner] every
 * screen reads the ledger through, and [screen]: the screen's own read, which
 * every write here is followed by (#296, decision 4). Nothing in a one-shot
 * read is invalidated by a write, so without that re-read a total keeps
 * naming the category the money just left. The re-read runs in the write's
 * own turn, so a resume read queued behind the write publishes nothing older
 * than it.
 *
 * **Every database touch is guarded** by [runner], which opens, leases and
 * turns a database that cannot be opened into a refusal: `Databases` throws
 * from the *open* in §11.1's state, [scope] is a `viewModelScope` nothing in
 * which catches, and an unguarded write would be a process kill on a tap.
 * `MainThreadRefreshTest` records the mechanism. A refusal raises the screen's
 * own `unavailable`, through [screen].
 *
 * **The guard covers the open, not the statement.** A statement that fails
 * afterwards -- `SQLiteFullException` on a full disk -- is outside
 * [DatabaseUnavailableException] and still escapes [scope]: a catch-all here
 * would swallow programming errors it cannot tell from a full disk. A category
 * deleted in Settings between the chooser being drawn and its save (#155) is
 * not left to the foreign key: [assignCategory] asks in the transaction that
 * writes.
 *
 * [context] is a function so the holder can keep reading its `Application`
 * through a getter: a `Context` field is what lint's `StaticFieldLeak` fails
 * the build on (see `SourcesViewModel`).
 *
 * A view model hands it to its screen whole, and the screen hands each
 * `*ScreenContent` only the halves that screen offers: [CategoryActions] and
 * [MerchantActions] (#307).
 */
class RowActions(
    private val context: () -> Context,
    private val scope: CoroutineScope,
    private val runner: ReadRunner,
    private val screen: RowScreen,
) : CategoryActions, MerchantActions {
    /**
     * Spec 6.4's merchant sheet's read: its question is the opening, or null
     * while no sheet is open. A tap on another row, or a close, changes the
     * question, so a read for the old one publishes nothing.
     */
    private val merchant = ScreenRead<MerchantOpening?, MerchantSheetState?>(
        scope = scope,
        runner = runner,
        what = "The merchant could not be read",
        initial = null,
    ) { db, opening ->
        opening?.let { readMerchantSheet(db.merchantIdentityDao(), db.txnDao(), it.ownKey, it.on) }
    }

    private val _merchantSheet = MutableStateFlow<OpenMerchantSheet?>(null)

    /** Spec 6.4's merchant sheet, while one is open; see [openMerchant]. */
    override val merchantSheet: StateFlow<OpenMerchantSheet?> = _merchantSheet.asStateFlow()

    /**
     * What the sheet draws now: the opening the question names, and the read
     * only if it answered that opening. The module publishes an answer to a
     * question that has changed meanwhile as nothing, and an older opening's
     * answer, held until the next lands, is not this one's.
     */
    private fun publish() {
        val opening = merchant.question.value
        _merchantSheet.value = opening?.let { open ->
            OpenMerchantSheet(open, merchant.state.value.answered?.takeIf { it.question == open }?.answer)
        }
    }

    /**
     * Everything the chooser needs beside the row it opens on.
     *
     * Blocking, so it may only be called from inside a read's body, which is
     * where the holder's own read calls it, beside the rows the chooser marks.
     * Of one snapshot with them, because every screen's read is one
     * transaction (`ReadRunner.read`).
     */
    fun readChooser(): ChooserRead {
        // Read on every refresh: Settings edits the table (#155), and a rename
        // has to reach the rows and the chooser on the next one. Three small
        // statements on a table of sixteen-odd rows, beside the ledger's
        // aggregate reads.
        val categories = Databases.categoryDao(context())
        val all = categories.all()
        return ChooserRead(
            categories = all,
            uncategorizedId = categories.uncategorizedIdOrNull(),
            // The pack is the shipped one and the seeded ids are read now, so
            // the marking is the filing stage two makes. The rules are read on
            // every refresh as well: a teaching save changes them, and the next
            // chooser has to start from the rule just written.
            filer = Filer(
                Graph.parsePack().dictionary,
                categories.seededIds(),
                readLearnedRules(Databases.merchantRuleDao(context()), Databases.merchantIdentityDao(context())),
            ),
        )
    }

    /**
     * Move one transaction to [categoryId] (§9.1's chip), then read the screen
     * again; the `Job` completes after that re-read.
     *
     * [teach] is the chooser's "Always call this" switch: on writes the
     * merchant's learned rule and leaves the row `user_edited = 0`
     * (`MerchantRuleDao.teach`); off, or a row with no merchant key, is a
     * one-off (`TxnDao.setCategory`, `user_edited = 1`).
     *
     * The re-read follows a refusal too: it is what says whether the database
     * is still unreadable.
     *
     * **`setCategory` returning 0 is ignored on purpose.** It means the row is
     * gone, and the honest answer is the re-read, which redraws from what is
     * actually in the database rather than reporting a failure about a row
     * the user can no longer see.
     */
    override fun assignCategory(txnId: Long, categoryId: Long, teach: Boolean): Job =
        screen.write("The category could not be assigned") { db ->
            val at = System.currentTimeMillis()
            // The check and the write in one transaction, so a delete from
            // Settings cannot land between them: deleted since the chooser
            // drew it, there is nothing to file under, and the re-read
            // draws the chooser's new list. Inside `whileLive`, as every other
            // transaction opened outside `Databases` is: on an instance `reset`
            // has closed, `runInTransaction` would reopen the file outside the
            // gate (ruling R42).
            Databases.whileLive(db) {
                db.runInTransaction(Runnable {
                    if (!db.categoryDao().exists(categoryId)) return@Runnable
                    // A teaching save writes the merchant's learned rule and
                    // leaves the row filed by it. It answers false for a row
                    // with no merchant key, which is then saved as the one-off
                    // the sheet said it would be.
                    //
                    // `teachAndFix` also moves the merchant's past payments
                    // (#49), in this same transaction.
                    val taught = teach && db.merchantRuleDao().teachAndFix(txnId, categoryId, at).taught
                    if (!taught) db.txnDao().setCategory(txnId, categoryId, at)
                })
            }
        }

    /**
     * Spec 4's "add", from the chooser's New category tile (#155): the new
     * row's id, or null where it was not written -- the database could not be
     * opened, or the name was taken since the sheet checked it. The screen is
     * read again before this returns, which is how the new tile appears.
     */
    override suspend fun addCategory(name: String, iconKey: String): Long? {
        val written = screen.write("The category could not be added") { db ->
            try {
                db.categoryDao().add(name, iconKey)
            } catch (_: CategoryNameTakenException) {
                null
            }
        }.await()
        return (written as? Outcome.Done)?.value
    }

    /**
     * What teaching [categoryId] on the row [txnId] would also fix (#49), for the
     * chooser's count line, or null when the ledger could not be read: not a
     * count of 0, which the chooser would draw as a fact (#237). The save
     * re-checks inside its own transaction either way.
     */
    override suspend fun retroPreview(txnId: Long, categoryId: Long): RetroPreview? =
        (runner.read("The past payments could not be counted") { db -> db.merchantRuleDao().retroPreview(txnId, categoryId) } as? Outcome.Done)
            ?.value

    /**
     * Open spec 6.4's sheet for the merchant [opening]'s row belongs to.
     *
     * The sheet opens on the tap, from what the row already showed (#284),
     * and the read follows. One read of everything the sheet shows, published
     * whole, so a sheet never draws a name from one read beside a list from
     * another. A key that no longer resolves to any transaction, or a ledger
     * that cannot be opened, closes the sheet as soon as the read says so; a
     * refusal also has the screen read again, so it says why.
     *
     * A read lands only on the opening it was made for: a sheet closed, or
     * opened from another row, while it ran stays as the user left it. That is
     * the module's question, not a check made here.
     */
    override fun openMerchant(opening: MerchantOpening): Job {
        val read = merchant.ask(opening)
        publish()
        return scope.launch {
            read.join()
            val state = merchant.state.value
            // Only an answer to the opening still asked for says it is gone: a
            // read dropped for another question left its refusal or answer behind.
            if (merchant.question.value == opening) {
                val gone = state.unavailable || state.answered?.let { it.question == opening && it.answer == null } == true
                if (gone) merchant.setQuestion(null)
                if (state.unavailable) screen.refused()
            }
            publish()
        }
    }

    override fun closeMerchant() {
        merchant.setQuestion(null)
        publish()
    }

    /** Spec 6.4's rename, of the merchant the open sheet is about. */
    override fun renameMerchant(name: String): Job = writeMerchant("The merchant could not be renamed") { dao, sheet ->
        dao.rename(sheet.identityKey, name, sheet.derivedName)
    }

    /**
     * Spec 6.4's merge of the open sheet's merchant into [targetKey], under the
     * name the sheet showed for it.
     */
    override fun mergeMerchant(targetKey: String): Job = writeMerchant("The merchants could not be merged") { dao, sheet ->
        val target = sheet.others.firstOrNull { it.identityKey == targetKey } ?: return@writeMerchant
        dao.merge(source = sheet.ownKey, target = targetKey, targetName = target.displayName ?: targetKey)
    }

    /** Undo the merge of [key]. */
    override fun separateMerchant(key: String): Job = writeMerchant("The merchants could not be separated") { dao, _ ->
        dao.separate(key)
    }

    /**
     * One write against the open sheet, which then closes, and the screen is
     * read again: its rows name the merchant, and a screen whose feed observes
     * both of spec 6.4's tables re-reads nothing for it.
     */
    private fun writeMerchant(
        what: String,
        write: (MerchantIdentityDao, MerchantSheetState) -> Unit,
    ): Job {
        // Nothing to write against before the read: the sheet offers no write
        // until then.
        val sheet = _merchantSheet.value?.read ?: return Job().apply { complete() }
        closeMerchant()
        return screen.write(what) { db -> write(db.merchantIdentityDao(), sheet) }
    }
}

/**
 * The category chooser's half of [RowActions], which `RowCategoryChooser`
 * calls. Search is handed only this: its rows open no merchant sheet.
 */
interface CategoryActions {
    /** File one transaction under [categoryId], teaching its merchant when [teach]; see [RowActions.assignCategory]. */
    fun assignCategory(txnId: Long, categoryId: Long, teach: Boolean): Job

    /** What teaching would also fix, or null when it could not be counted; see [RowActions.retroPreview]. */
    suspend fun retroPreview(txnId: Long, categoryId: Long): RetroPreview?

    /** The New category tile's write: the new id, or null where none was written; see [RowActions.addCategory]. */
    suspend fun addCategory(name: String, iconKey: String): Long?
}

/**
 * What a screen hands [RowActions] of its own read (#296): the write that is
 * followed by a re-read of it, and the way to ask it to look again.
 * `ScreenRead.rowScreen` is the one implementation a joined screen uses.
 */
interface RowScreen {
    /**
     * Run [body] as a guarded write and read the screen again in the same
     * turn; completes after that re-read. See `ScreenRead.write`.
     */
    fun <R> write(what: String, body: suspend (PingedDatabase) -> R): Deferred<Outcome<R>>

    /** A read [RowActions] made was refused: the screen reads again, so it can say so itself. */
    fun refused()
}

/** [this] as what [RowActions] writes through: its writes read it back, and a refusal makes it read again. */
fun ScreenRead<*, *>.rowScreen(): RowScreen = object : RowScreen {
    override fun <R> write(what: String, body: suspend (PingedDatabase) -> R) = this@rowScreen.write(what, body)

    override fun refused() {
        refresh()
    }
}

/**
 * What [RowActions.readChooser] read: the chooser's half of a holder's read.
 *
 * The fields mean what `LedgerRead`'s fields of the same names mean, and are
 * published together for the reason given there.
 */
data class ChooserRead(
    val categories: List<Category> = emptyList(),
    val uncategorizedId: Long? = null,
    val filer: Filer<Snapshot> = Filer.EMPTY,
)

/**
 * What spec 6.4's sheet shows for [ownKey], or null when no transaction carries
 * it any more. Blocking; called inside `CaptureStorage.guarded`.
 *
 * Suggestions are judged against [ownKey], the string the row the user pressed
 * actually carries, not against the merchant it may already be merged into.
 */
@VisibleForTesting
internal fun readMerchantSheet(dao: MerchantIdentityDao, txns: TxnDao, ownKey: String, on: LocalDate): MerchantSheetState? {
    val identity = dao.canonicalOf(ownKey) ?: ownKey
    val month = YearMonth.from(LocalDates.calendarDay(on))
    val range = LocalDates.monthRange(month)
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
        month = month,
        // Charts' own read, so the header and that month's WHO GOT IT row
        // cannot disagree about this merchant.
        monthTotals = txns.merchantTotals(range.start, range.endInclusive, limit = Int.MAX_VALUE)
            .filter { it.identityKey == identity },
    )
}
