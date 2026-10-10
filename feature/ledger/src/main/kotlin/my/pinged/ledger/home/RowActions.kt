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
import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.parse.SameShop

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
 * afterwards -- `SQLiteFullException` on a full disk, or a foreign key
 * violation if a category were deleted between the sheet being drawn and the
 * tap -- is outside [DatabaseUnavailableException] and still escapes [scope].
 * Left uncovered on purpose: nothing in this milestone can delete a category
 * (§4's editor is out), and a catch-all here would swallow programming errors
 * it cannot tell from a full disk. §4 is where this stops being hypothetical
 * and where the handling belongs.
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
     * The `category` table, read once per database this holder reads from.
     *
     * **Safe only while nothing writes the table**: §4's editor is out and the
     * one write a row makes is `txn.category_id`. Read per refresh these are
     * two of the six statements a chip tap issues, re-reading fourteen rows to
     * move one row between them. A restore does replace the table, with the
     * backup's rows, which is why [Catalogue] carries the generation it was
     * read under and [readCatalogueOnce] reads again when that has moved.
     *
     * **Null means "not read yet", never "read and empty".** A read that
     * cannot open the database throws out of `all()` before the assignment, so
     * a failed first read memoizes nothing and the next one tries again; that
     * is why a single nullable field holds both values rather than two, since
     * a resolved `uncategorizedId` is legitimately null.
     *
     * `@Volatile` because two reads can overlap -- a resume and a chip tap both
     * launch on the holder's scope and both suspend into `Dispatchers.IO`. The
     * worst a stale read costs is one extra pass over fourteen rows, never a
     * wrong answer.
     */
    @Volatile private var catalogue: Catalogue? = null

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
        val catalogue = readCatalogueOnce()
        // Every read, unlike the catalogue: a teaching save changes it, and the
        // next chooser has to start from the rule just written.
        val learned = Databases.merchantRuleDao(context()).learnedRules()
            .associate { it.pattern to it.categoryId }
        val merged = Databases.merchantIdentityDao(context()).mergedIdentities().toSet()
        return ChooserRead(
            categories = catalogue.categories,
            uncategorizedId = catalogue.uncategorizedId,
            learnedRules = learned,
            mergedIdentities = merged,
            dictionaryFiling = catalogue.dictionaryFiling,
        )
    }

    /**
     * [catalogue], filled in on the first read that gets to the database.
     *
     * All fourteen categories, not a lookup per line: the table is seeded and
     * tiny, so three point lookups cost more than one walk.
     * `uncategorizedIdOrNull` beside it is one indexed point lookup, measured
     * at under a millisecond next to the ledger's four aggregate reads -- so
     * [catalogue] is a memo about statement count, not about a slow read.
     */
    private fun readCatalogueOnce(): Catalogue {
        // Read before the query, so a reset landing during it leaves the
        // older number on the result and the next read reads again.
        val generation = Databases.generation.value
        return catalogue?.takeIf { it.generation == generation } ?: run {
            val categories = Databases.categoryDao(context())
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
            // A teaching save writes the merchant's learned rule and leaves the
            // row filed by it. It answers false for a row with no merchant key,
            // which is then saved as the one-off the sheet said it would be.
            //
            // `teachAndFix` also moves the merchant's past payments (#49), under
            // the lease `guarded` holds, in the one transaction that wrote the rule.
            val taught = teach && Databases.merchantRuleDao(context()).teachAndFix(txnId, categoryId, at).taught
            if (!taught) Databases.txnDao(context()).setCategory(txnId, categoryId, at)
        }
        then()
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
     * no longer resolves to any transaction opens nothing.
     */
    fun openMerchant(ownKey: String): Job = scope.launch {
        CaptureStorage.guarded(
            context(),
            what = "The merchant could not be read",
            unavailable = { unavailable() },
            damageStopsCapture = false,
        ) {
            _merchantSheet.value = readMerchantSheet(Databases.merchantIdentityDao(context()), ownKey)
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
 * [RowActions.catalogue]'s two values, so one nullable field can hold both --
 * see there for why two fields could not -- beside the `Databases.generation`
 * they were read under.
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
