package my.pinged.ledger.home

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.pinged.capture.CaptureStorage
import my.pinged.capture.Graph
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.dao.CategoryNameTakenException
import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.RetroPreview
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.Category
import my.pinged.parse.SameShop
import java.time.YearMonth

/**
 * What a ledger row lets the user do, for any screen that draws one (#69): the
 * category chooser's read, its save -- teaching or one-off -- and spec 6.4's
 * merchant sheet.
 *
 * Owned by a view model, which hands over its own [scope] and is told through
 * [unavailable] when the database could not be opened, so the screen's banner
 * stays the holder's. Every database touch is inside `CaptureStorage.guarded`:
 * `Databases` throws from the *open* in §11.1's state, [scope] is a
 * `viewModelScope` nothing in which catches, and an unguarded write would be a
 * process kill on a tap. `MainThreadRefreshTest` records the mechanism.
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
 */
class RowActions(
    private val context: () -> Context,
    private val scope: CoroutineScope,
    private val unavailable: () -> Unit,
) {
    /** Spec 6.4's merchant sheet, while one is open; see [openMerchant]. */
    private val _merchantSheet = MutableStateFlow<MerchantSheetState?>(null)
    val merchantSheet: StateFlow<MerchantSheetState?> = _merchantSheet.asStateFlow()

    /**
     * Everything the chooser needs beside the row it opens on.
     *
     * Blocking, so it may only be called from inside a `CaptureStorage.guarded`
     * block, which is where the holder's own refresh calls it, beside the rows
     * the chooser marks. Of one snapshot with them only inside a transaction:
     * the `Day` screen's read is one (`Databases.inOneTransaction`), the
     * Ledger's is not.
     */
    fun readChooser(): ChooserRead {
        // Read on every refresh: Settings edits the table (#155), and a rename
        // has to reach the rows and the chooser on the next one. Three small
        // statements on a table of sixteen-odd rows, beside the ledger's
        // aggregate reads.
        val categories = Databases.categoryDao(context())
        val all = categories.all()
        // A teaching save changes it, and the next chooser has to start from
        // the rule just written.
        val learned = Databases.merchantRuleDao(context()).learnedRules()
            .associate { it.pattern to it.categoryId }
        val merged = Databases.merchantIdentityDao(context()).mergedIdentities().toSet()
        return ChooserRead(
            categories = all,
            uncategorizedId = categories.uncategorizedIdOrNull(),
            learnedRules = learned,
            mergedIdentities = merged,
            // The pack is the shipped one and the seeded ids are read now, so
            // the marking is the filing stage two makes.
            dictionaryFiling = dictionaryCategoryIds(Graph.parsePack().dictionary, categories.seededIds()),
        )
    }

    /**
     * Move one transaction to [categoryId] (§9.1's chip), then run [then].
     *
     * [teach] is the chooser's "Always call this" switch: on writes the
     * merchant's learned rule and leaves the row `user_edited = 0`
     * (`MerchantRuleDao.teach`); off, or a row with no merchant key, is a
     * one-off (`TxnDao.setCategory`, `user_edited = 1`).
     *
     * [then] is where the holder re-reads whatever aggregates it shows. The
     * rows redraw themselves -- Room invalidates on the `UPDATE` -- but nothing
     * invalidates a total, so without it a summary keeps naming the category
     * the money just left. It runs whether or not the write got the database.
     *
     * **`setCategory` returning 0 is ignored on purpose.** It means the row is
     * gone, and the honest answer is [then]'s re-read, which redraws from what
     * is actually in the database rather than reporting a failure about a row
     * the user can no longer see.
     */
    fun assignCategory(
        txnId: Long,
        categoryId: Long,
        teach: Boolean,
        then: suspend () -> Unit = {},
    ): Job = scope.launch {
        CaptureStorage.guarded<Unit>(
            context(),
            what = "The category could not be assigned",
            unavailable = { unavailable() },
            damageStopsCapture = false,
        ) {
            val at = System.currentTimeMillis()
            // The check and the write in one transaction, so a delete from
            // Settings cannot land between them: deleted since the chooser
            // drew it, there is nothing to file under, and [then]'s re-read
            // draws the chooser's new list.
            Databases.inOneTransaction(context()) { db ->
                if (!db.categoryDao().exists(categoryId)) return@inOneTransaction
                // A teaching save writes the merchant's learned rule and leaves
                // the row filed by it. It answers false for a row with no
                // merchant key, which is then saved as the one-off the sheet
                // said it would be.
                //
                // `teachAndFix` also moves the merchant's past payments (#49),
                // in this same transaction.
                val taught = teach && db.merchantRuleDao().teachAndFix(txnId, categoryId, at).taught
                if (!taught) db.txnDao().setCategory(txnId, categoryId, at)
            }
        }
        then()
    }

    /**
     * Spec 4's "add", from the chooser's New category tile (#155): the new
     * row's id, or null where it was not written -- the database could not be
     * opened, or the name was taken since the sheet checked it. [then] re-reads
     * the chooser, which is how the new tile appears.
     */
    suspend fun addCategory(name: String, iconKey: String, then: suspend () -> Unit = {}): Long? {
        val id = CaptureStorage.guarded<Long?>(
            context(),
            what = "The category could not be added",
            unavailable = { unavailable(); null },
            damageStopsCapture = false,
        ) {
            try {
                Databases.categoryDao(context()).add(name, iconKey)
            } catch (_: CategoryNameTakenException) {
                null
            }
        }
        then()
        return id
    }

    /**
     * What teaching [categoryId] on the row [txnId] would also fix (#49), for the
     * chooser's count line. An unreadable ledger answers "nothing", which draws
     * as a count of 0 and is corrected by the save, which re-checks inside its
     * own transaction.
     */
    suspend fun retroPreview(txnId: Long, categoryId: Long): RetroPreview =
        CaptureStorage.guarded(
            context(),
            what = "The past payments could not be counted",
            unavailable = { RetroPreview(0, emptyList()) },
            damageStopsCapture = false,
        ) { Databases.merchantRuleDao(context()).retroPreview(txnId, categoryId) }

    /**
     * Open spec 6.4's sheet for the merchant a row's [ownKey] belongs to.
     *
     * One read of everything the sheet shows, published whole, so a sheet
     * never draws a name from one read beside a list from another. A key that
     * no longer resolves to any transaction opens nothing. [on] is the row's
     * own `local_date`, whose month the header counts.
     */
    fun openMerchant(ownKey: String, on: LocalDate): Job = scope.launch {
        CaptureStorage.guarded(
            context(),
            what = "The merchant could not be read",
            unavailable = { unavailable() },
            damageStopsCapture = false,
        ) {
            _merchantSheet.value =
                readMerchantSheet(Databases.merchantIdentityDao(context()), Databases.txnDao(context()), ownKey, on)
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
     * Nothing is re-read: a feed of rows observes both of spec 6.4's tables
     * (`LeasedFeed`), and no total depends on which merchant a row belongs to.
     */
    private fun writeMerchant(
        what: String,
        write: (MerchantIdentityDao, MerchantSheetState) -> Unit,
    ): Job = scope.launch {
        val sheet = _merchantSheet.value ?: return@launch
        _merchantSheet.value = null
        CaptureStorage.guarded<Unit>(
            context(),
            what = what,
            unavailable = { unavailable() },
            damageStopsCapture = false,
        ) {
            write(Databases.merchantIdentityDao(context()), sheet)
        }
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
    val learnedRules: Map<String, Long> = emptyMap(),
    val mergedIdentities: Set<String> = emptySet(),
    val dictionaryFiling: (String?) -> Long? = LedgerRead.NO_DICTIONARY,
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
