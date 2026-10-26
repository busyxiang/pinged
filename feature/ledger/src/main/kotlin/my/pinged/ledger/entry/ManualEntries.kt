package my.pinged.ledger.entry

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import my.pinged.capture.Graph
import my.pinged.capture.MerchantColumns
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.ManualEdit
import my.pinged.data.entity.Category
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.LedgerWrites
import my.pinged.ledger.home.dictionaryCategoryIds
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.TransferState
import my.pinged.ledger.settings.Transfers
import my.pinged.parse.Amount
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.MerchantNormalization

/**
 * Spec 9.4's manual entry: a payment Pinged did not see, typed in by hand
 * (#97). The only thing in the feature that touches the database.
 *
 * **A manual entry is a `txn` row with `raw_capture_id IS NULL`** (spec 4),
 * and `source_package` and `source_label` null with it. It needs no schema of
 * its own, and what already reads such a row reads it rightly: it is never
 * evidence of a captured day (`CaptureRecord`) and never moves
 * the history start (`CaptureRecord.historyStart`), so typing in cash cannot
 * hide a day Pinged was not watching.
 *
 * Owned by a view model, which hands over its [scope]; every database touch
 * inside `LedgerWrites.guarded`, which leases the instance and turns a database
 * that will not open into [storageUnavailable] rather than a process kill on a
 * tap. (`ReviewInbox`, whose busy state is the same, writes through
 * `:core:read` instead.)
 *
 * **During a restore or a delete-everything nothing is saved** ([busy]). A
 * save asked for then is refused on the spot, not queued behind the gate:
 * queued, it would hold a lease past the gate, which `CLAUDE.md`'s lease rules
 * forbid, and it would land in a ledger the user has not seen. The draft is
 * the screen's to keep, so the entry is only delayed. Asked before the guard
 * ([LedgerWrites.replacing] says why).
 *
 * [transfers] is a parameter so the busy state has one source in production,
 * `Transfers.state`; [clock] so a test can name the moment an entry is dated.
 * [normalization] is the parser pack's, the one stage two cleans a captured
 * merchant with, so a typed merchant and a captured one land on one key.
 */
class ManualEntries(
    private val context: () -> Context,
    private val scope: CoroutineScope,
    transfers: StateFlow<TransferState> = Transfers.state,
    private val clock: () -> Long = System::currentTimeMillis,
    private val normalization: () -> MerchantNormalization = { Graph.ruleMatcher().merchantNormalization },
) {
    private val writes = LedgerWrites(context, scope, transfers)

    /** The database would not open on the last write. */
    val storageUnavailable: StateFlow<Boolean> = writes.storageUnavailable

    /** The restore or delete-everything running, while one is; see the class KDoc. */
    val busy: StateFlow<Operation?> = writes.busy

    /**
     * Spec 9.4's category chips, most used first ([rank]); empty until a read
     * lands.
     */
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    /**
     * Today by [clock] in the system zone, which a save is dated by: the date
     * chip's `Today` and its picker's latest day (#103). Moves at the
     * midnight [clock] names with the screen open; looked at again at least
     * every [TODAY_RECHECK_MS] besides, so a clock or zone changed by hand is
     * followed within that.
     */
    val today: StateFlow<LocalDate> = flow {
        while (true) {
            // One read. A day from one and a midnight from a later one skip
            // the midnight that passed between them, until the recheck.
            val now = clock()
            val day = dayOf(now)
            emit(day)
            val midnight = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            delay((midnight - now).coerceIn(1L, TODAY_RECHECK_MS))
        }
    }.stateIn(scope, SharingStarted.Eagerly, today())

    /** Today, by [clock], in the system zone. */
    internal fun today(): LocalDate = dayOf(clock())

    private fun dayOf(moment: Long): LocalDate = Instant.ofEpochMilli(moment).atZone(ZoneId.systemDefault()).toLocalDate()

    init {
        // Ranked on arrival, and again when a restore or delete ends: a rank
        // asked for during one is refused, as a save is, and the chips would
        // otherwise stay empty until the screen was reopened.
        scope.launch { busy.collect { if (it == null) rank() } }
    }

    /**
     * Read [categories] again: every category but Uncategorized, by how many
     * counted rows each has over the [RANKING_DAYS] days ending today, then
     * `sort_order` (`CategoryDao.rankedByUse`, #100). Refused, like [save],
     * while a restore or delete runs.
     */
    fun rank(): Job = scope.launch {
        if (writes.replacing) return@launch
        writes.guarded("The categories cannot be read", unavailable = Unit) {
            val today = LocalDates.calendarDay(LocalDates.of(clock()))
            _categories.value = Databases.categoryDao(context()).rankedByUse(
                from = LocalDates.of(today.minusDays(RANKING_DAYS - 1L)),
                to = LocalDates.of(today),
            )
            writes.opened()
        }
    }

    /**
     * Spec 9.4's pre-selection (#102): the category [merchant], as typed, is
     * already filed under ([ruleFiling]), or null for a merchant nothing
     * files, for text that cleans to no merchant, and when it could not be
     * asked: refused, like [save], while a restore or delete runs, or a
     * ledger that will not open.
     */
    fun preselection(merchant: String): Deferred<Long?> = scope.async {
        // Outside the lease, for the reason [save] gives.
        val key = withContext(Dispatchers.IO) { MerchantColumns.of(merchant, normalization()).key }
            ?: return@async null
        if (writes.replacing) return@async null
        writes.guarded("The merchant's category cannot be read", unavailable = null) {
            ruleFiling(key).also { writes.opened() }
        }
    }

    /**
     * The category a rule files merchant [key] under, in stage two's order
     * (`ParsePass`): the learned rule for its identity, which resolves
     * through a merge (`MerchantRuleDao.learnedCategoryFor`), then the
     * shipped dictionary; null for neither. The one lookup both the entry
     * screen's pre-selection and an edit's re-filing (#105) make.
     *
     * Blocking, so only inside a `LedgerWrites.guarded` block, which leases
     * the instance.
     */
    internal fun ruleFiling(key: String): Long? =
        Databases.merchantRuleDao(context()).learnedCategoryFor(key) ?: dictionaryFiling()(key)

    /**
     * The dictionary's filing by `category.id`, read on every lookup: Settings
     * renames and deletes categories (#155) without moving
     * `Databases.generation`, so a memo keyed on it would offer a deleted
     * category's id, which the save then refuses without a word. Through
     * `seed_name`, so a renamed seeded category keeps its merchants, as stage
     * two files them.
     */
    private fun dictionaryFiling(): (String?) -> Long? =
        dictionaryCategoryIds(Graph.parsePack().dictionary, Databases.categoryDao(context()).seededIds())

    /**
     * Write [draft] as a new manual entry, dated now or on its past day at
     * now's time of day ([EntryDate.occurredAt]), and answer its id; null
     * when nothing was written: an amount of RM0.00 or past
     * [Amount.CEILING_SEN], a future day, a restore or delete running, a
     * ledger that will not open, or a chip whose category is gone -- one
     * chosen before a restore or a delete-everything replaced the categories,
     * which `txn`'s foreign key would otherwise throw on.
     *
     * What is written (spec 9.4, #99): `EXPENSE`, `HIGH`, `COMMITTED`, no
     * pending reason, not excluded. Filed under the draft's [CategoryChoice]
     * (#100): a chip the user tapped is a one-off, `user_edited = 1`, which
     * no rule moves again; a pre-selection left alone is rule-filed,
     * `user_edited = 0`, so teaching its merchant later moves it with the
     * rest (§6.3, #102); no chip is `Uncategorized` with `user_edited = 0`,
     * so a later teaching can file it. §7.1's confidence
     * gate is not asked: it would hold an entry with no merchant as
     * `MERCHANT_MISSING`, and a person typing a payment in is the review.
     *
     * The typed merchant becomes the three merchant columns exactly as a
     * capture's string does ([MerchantColumns], #101): raw as typed, display
     * and key cleaned by the pack, all three null for text that cleans to
     * nothing. So the entry joins its merchant identity like any other row,
     * and a typed `Kopitiam` is the captured `KOPITIAM` with no merge. The
     * note is stored trimmed, or null when blank.
     */
    fun save(draft: ManualDraft): Deferred<Long?> = scope.async {
        // Outside the lease: the first call builds the pack's matcher, which
        // compiles every pattern, and a lease must not outlast a gate. Before
        // [admitted], so its busy check stays next to the guard.
        val merchant = withContext(Dispatchers.IO) { MerchantColumns.of(draft.merchant, normalization()) }
        val now = admitted(draft) ?: return@async null
        val zone = ZoneId.systemDefault()
        writes.guarded("The payment could not be saved", unavailable = null) {
            val chosen = draft.category
            val categories = Databases.categoryDao(context())
            if (chosen != null && !categories.exists(chosen.categoryId)) return@guarded null
            Databases.txnDao(context()).insert(
                Txn(
                    rawCaptureId = null,
                    amountSen = draft.amountSen,
                    direction = Direction.EXPENSE,
                    occurredAt = EntryDate.occurredAt(draft.day, now, zone),
                    merchantRaw = merchant.raw,
                    merchantDisplay = merchant.display,
                    merchantKey = merchant.key,
                    categoryId = chosen?.categoryId ?: categories.requireUncategorizedId(),
                    sourcePackage = null,
                    sourceLabel = null,
                    confidence = Confidence.HIGH,
                    state = TxnState.COMMITTED,
                    note = draft.note.trim().ifEmpty { null },
                    userEdited = chosen?.by?.userEdited ?: false,
                    createdAt = now,
                    updatedAt = now,
                ),
            ).also { writes.opened() }
        }
    }

    /**
     * Manual entry [id] as the entry screen's edit mode opens it (#105):
     * every field filled in, the category as the chip it was saved by
     * ([shownChoice]), and the day null when it is today, as a new draft's
     * is; with the [EditTarget] that [update] and [delete] take.
     *
     * [Loaded.Gone] when [id] names no manual entry -- a captured row, which
     * has no edit screen (spec 9.4), or a row that is gone -- and when it
     * names one not created at [createdAt]: an entry read before a
     * delete-everything or a restore, whose id now names another payment.
     * Null when it cannot be read: a restore or delete running, or a ledger
     * that will not open.
     */
    fun load(id: Long, createdAt: Long? = null): Deferred<Loaded?> = scope.async {
        if (writes.replacing) return@async null
        val today = today()
        writes.guarded("The payment could not be read", unavailable = null) {
            val stored = Databases.txnDao(context()).byId(id)
                ?.takeIf { it.rawCaptureId == null && (createdAt == null || it.createdAt == createdAt) }
            val loaded = stored?.let {
                Loaded.Entry(
                    EditTarget(it.id, it.createdAt),
                    ManualDraft(
                        amountSen = it.amountSen,
                        day = LocalDates.calendarDay(it.localDate).takeUnless { day -> day == today },
                        merchant = it.merchantRaw.orEmpty(),
                        note = it.note.orEmpty(),
                        category = shownChoice(it, Databases.categoryDao(context()).requireUncategorizedId()),
                    ),
                )
            } ?: Loaded.Gone
            loaded.also { writes.opened() }
        }
    }

    /**
     * Correct manual entry [target] to [draft] (#105, spec 9.4), and answer
     * whether it was written; false on the same refusals as [save] -- a
     * category that is gone among them -- for a captured row, whose id
     * `TxnDao.editManualEntry` refuses in SQL, and for an entry no longer
     * there, [target]'s id now naming another payment among them.
     *
     * Only what changed is written, with `updated_at`:
     * - the amount;
     * - the date, when [draft]'s day is not the stored one, dated as [save]
     *   dates; an unchanged day keeps its `occurred_at` ([editedAt]);
     * - the three merchant columns, built as [save] builds them;
     * - the note;
     * - the category, by [refiled]: a merchant whose key changed on a
     *   rule-filed row is filed again by [ruleFiling], or Uncategorized; a
     *   one-off keeps its category; a chip tapped in this edit is a one-off.
     *   An amount, date or note alone never touches `category_id` or
     *   `user_edited`.
     *
     * A draft that changes nothing writes nothing and answers true for
     * [target]'s manual entry, so the screen closes as after a save.
     */
    fun update(target: EditTarget, draft: ManualDraft): Deferred<Boolean> = scope.async {
        // Outside the lease, and before [admitted], for the reasons [save] gives.
        val merchant = withContext(Dispatchers.IO) { MerchantColumns.of(draft.merchant, normalization()) }
        val now = admitted(draft) ?: return@async false
        val zone = ZoneId.systemDefault()
        writes.guarded("The payment could not be corrected", unavailable = false) {
            val dao = Databases.txnDao(context())
            val stored = dao.byId(target.id)?.takeIf { it.createdAt == target.createdAt } ?: return@guarded false
            val uncategorized = Databases.categoryDao(context()).requireUncategorizedId()
            val note = draft.note.trim().ifEmpty { null }
            val columns = ManualEdit.Merchant(merchant.raw, merchant.display, merchant.key)
            val edit = ManualEdit(
                amountSen = draft.amountSen.takeIf { it != stored.amountSen },
                occurredAt = editedAt(stored, draft, now, zone).takeIf { it != stored.occurredAt },
                merchant = columns.takeIf { it != ManualEdit.Merchant(stored.merchantRaw, stored.merchantDisplay, stored.merchantKey) },
                note = ManualEdit.Note(note).takeIf { note != stored.note },
                filing = refiled(stored, draft.category, merchant.key != stored.merchantKey, uncategorized) {
                    merchant.key?.let(::ruleFiling) ?: uncategorized
                }?.takeIf { it != ManualEdit.Filing(stored.categoryId, stored.userEdited) },
            )
            if (edit.filing?.let { Databases.categoryDao(context()).exists(it.categoryId) } == false) return@guarded false
            val written = if (edit == ManualEdit()) stored.rawCaptureId == null else dao.editManualEntry(target.id, target.createdAt, edit, now)
            written.also { writes.opened() }
        }
    }

    /**
     * Delete manual entry [target] (#106, spec 9.4), and answer whether it is
     * gone: true too for an entry already gone -- deleted from another
     * screen, or replaced by a delete-everything or a restore that left its
     * id to another payment, which is untouched -- since that is what the
     * user asked for, so the screen closes rather than staying open over
     * nothing. False for a captured row, whose id
     * `TxnDao.deleteManualEntry` refuses in SQL, a restore or delete
     * running, and a ledger that will not open. There is no undo: putting
     * the row back would give it a new id and a new `created_at`.
     */
    fun delete(target: EditTarget): Deferred<Boolean> = scope.async {
        if (writes.replacing) return@async false
        writes.guarded("The payment could not be deleted", unavailable = false) {
            val dao = Databases.txnDao(context())
            val gone = dao.deleteManualEntry(target.id, target.createdAt) == 1 ||
                dao.byId(target.id)?.createdAt != target.createdAt
            gone.also { writes.opened() }
        }
    }

    /**
     * The moment a write of [draft] is dated, or null where [save], [update]
     * and [nearbyCapture] all refuse it before the guard: an amount of RM0.00
     * or past [Amount.CEILING_SEN]; a restore or delete running
     * ([LedgerWrites.replacing]); or a future day, which spec 9.4 forbids --
     * the chip cannot offer one, but a draft kept past a clock moving back
     * could still hold one.
     */
    private fun admitted(draft: ManualDraft): Long? {
        if (draft.amountSen !in 1..Amount.CEILING_SEN || writes.replacing) return null
        val now = clock()
        return now.takeUnless { draft.day?.isAfter(dayOf(now)) == true }
    }

    /**
     * A captured payment [draft] may repeat (#104, spec 9.4), which Save
     * names before writing; null when there is none, or when it cannot be
     * asked: a draft [save] would refuse, a restore or delete running, or a
     * ledger that will not open, where [save] then refuses as well.
     *
     * Near is layer 2's window around the moment [save] would date the
     * draft, or the whole of its day when [ManualDraft.backdated], whose time
     * of day is made up (`TxnDao.nearbyCapture`). [editing] is the entry
     * being corrected, never its own match; null for a new one.
     */
    fun nearbyCapture(draft: ManualDraft, editing: Long?): Deferred<NearbyCapture?> = scope.async {
        val now = admitted(draft) ?: return@async null
        val zone = ZoneId.systemDefault()
        writes.guarded("Pinged could not look for the same payment", unavailable = null) {
            val dao = Databases.txnDao(context())
            // An edit is dated as [update] would date it: on its unchanged
            // day, that is when it was paid, not now.
            val edited = editing?.let(dao::byId)
            val at = edited?.let { editedAt(it, draft, now, zone) } ?: EntryDate.occurredAt(draft.day, now, zone)
            dao
                .nearbyCapture(editing, draft.amountSen, at, wholeDay = draft.backdated)
                ?.let(NearbyCapture::of)
                .also { writes.opened() }
        }
    }
}

/**
 * The chip a stored row's category shows as when edit mode opens (#105):
 * none for Uncategorized, which is never a chip; a one-off as
 * [ChosenBy.TAPPED]; a rule-filed category as [ChosenBy.PRESELECTED].
 */
internal fun shownChoice(stored: Txn, uncategorizedId: Long): CategoryChoice? =
    if (stored.categoryId == uncategorizedId) {
        null
    } else {
        CategoryChoice(stored.categoryId, ChosenBy.of(stored.userEdited))
    }

/**
 * How an edit files [stored] (spec 9.4's "On update"), given the chip
 * [chosen] on Save and whether the merchant's key changed; null leaves the
 * category as it is. [ruleFiling] is the new key's filing, Uncategorized
 * for none.
 *
 * - A chip tapped in this edit is a one-off; the one-off edit mode opened
 *   with, still selected, keeps its category whatever the merchant became.
 * - Otherwise the row is rule-filed, or its one-off chip was deselected:
 *   a new key files it again, by rule.
 * - With the key unchanged, a deselected chip is Uncategorized,
 *   `user_edited = 0`, as a new entry with no chip saves. A pre-selection
 *   is the merchant's, not the user's, so it is no reason to write.
 */
internal fun refiled(
    stored: Txn,
    chosen: CategoryChoice?,
    keyChanged: Boolean,
    uncategorizedId: Long,
    ruleFiling: () -> Long,
): ManualEdit.Filing? {
    val shown = shownChoice(stored, uncategorizedId)
    return when {
        chosen?.by == ChosenBy.TAPPED -> ManualEdit.Filing(chosen.categoryId, userEdited = true).takeUnless { chosen == shown }
        keyChanged -> ManualEdit.Filing(ruleFiling(), userEdited = false)
        chosen == null && shown != null -> ManualEdit.Filing(uncategorizedId, userEdited = false)
        else -> null
    }
}

/**
 * When an edit of [stored] to [draft] is dated: unchanged while the draft's
 * day is the stored day, else as [ManualEntries.save] dates a new entry, now
 * or the past day at now's time of day.
 */
internal fun editedAt(stored: Txn, draft: ManualDraft, now: Long, zone: ZoneId): Long {
    val day = draft.day ?: Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (day == LocalDates.calendarDay(stored.localDate)) stored.occurredAt else EntryDate.occurredAt(draft.day, now, zone)
}

/**
 * The manual entry an edit screen corrects (#105): its id, and the
 * `created_at` that tells it from a later row given the same id. A
 * delete-everything restarts ids and a restore brings in another ledger's,
 * so the id alone may name another payment by Save; `created_at` is written
 * once and kept by a restore, so the same payment brought back still
 * matches. See `TxnDao.editManualEntry`.
 */
data class EditTarget(val id: Long, val createdAt: Long)

/** What [ManualEntries.load] found. */
sealed interface Loaded {
    /** The manual entry, as the draft the edit screen opens with. */
    data class Entry(val target: EditTarget, val draft: ManualDraft) : Loaded

    /** No such manual entry: see [ManualEntries.load]. */
    data object Gone : Loaded
}

/**
 * What the entry screen holds before Save: the amount, in sen (see [Keypad]),
 * the date chip's [day], the merchant and note as typed, and the category
 * chip selected, if any; a draft is the one thing a save is handed.
 *
 * [day] is null for `Today`, dated the moment Save is tapped, and otherwise a
 * past day chosen on the chip ([dated]); see [backdated].
 */
data class ManualDraft(
    val amountSen: Long = 0L,
    val day: LocalDate? = null,
    /** As typed; [ManualEntries.save] derives the merchant columns from it. */
    val merchant: String = "",
    /** As typed; stored trimmed, or null when blank. */
    val note: String = "",
    /** The chip selected; null saves as Uncategorized. */
    val category: CategoryChoice? = null,
) {
    /**
     * The draft once the pre-selection lookup for [forMerchant] answered
     * [categoryId] ([ManualEntries.preselection]): that category as
     * [ChosenBy.PRESELECTED], or no selection for null. Unchanged when the
     * selection is a chip the user tapped, which no pre-selection replaces
     * (spec 9.4), or when [forMerchant] is no longer what is typed, so a
     * slow answer cannot land over a newer one.
     */
    fun preselected(forMerchant: String, categoryId: Long?): ManualDraft = when {
        forMerchant != merchant || category?.by == ChosenBy.TAPPED -> this
        else -> copy(category = categoryId?.let { CategoryChoice(it, ChosenBy.PRESELECTED) })
    }

    /**
     * The draft after a tap on [categoryId]'s chip: the selected chip
     * deselects, any other is selected as [ChosenBy.TAPPED], whatever was
     * selected before and however.
     */
    fun tapped(categoryId: Long): ManualDraft =
        copy(category = if (category?.categoryId == categoryId) null else CategoryChoice(categoryId, ChosenBy.TAPPED))

    /**
     * The draft once [categoryId] is picked in the chooser behind More
     * (#272): selected as [ChosenBy.TAPPED] even when it already was, since
     * the chooser's Save picks rather than toggles.
     */
    fun chosen(categoryId: Long): ManualDraft = copy(category = CategoryChoice(categoryId, ChosenBy.TAPPED))

    /**
     * The entry is dated on a past day, so its time of day is made up (#103):
     * it is the time Save was tapped, not the time it was paid. The nearby-capture
     * check (#104) reads that as "anywhere on that day" rather than "within
     * 10 minutes".
     */
    val backdated: Boolean get() = day != null
}

/**
 * The captured payment Save warns about (#104): enough to say *Pinged
 * already has RM12.50 from Touch 'n Go eWallet at 12:41*, and whether it is
 * still awaiting review.
 */
data class NearbyCapture(
    val amountSen: Long,
    /** The source's label, or its package when it has none. */
    val source: String,
    val occurredAt: Long,
    val awaitingReview: Boolean,
) {
    internal companion object {
        fun of(txn: Txn) = NearbyCapture(
            amountSen = txn.amountSen,
            source = txn.sourceLabel ?: txn.sourcePackage.orEmpty(),
            occurredAt = txn.occurredAt,
            awaitingReview = txn.state == TxnState.PENDING,
        )
    }
}

/** A category chip selected on the entry screen, and [by] what. */
data class CategoryChoice(val categoryId: Long, val by: ChosenBy)

/**
 * How a [CategoryChoice] came to be selected, which decides how it saves:
 * [userEdited] is the row's `user_edited` (spec 9.4's "Saved as"), and [of]
 * reads one back for edit mode, so the two directions cannot disagree.
 */
enum class ChosenBy(val userEdited: Boolean) {
    /** The user tapped the chip: a one-off, `user_edited = 1`. */
    TAPPED(userEdited = true),

    /**
     * Filed by the typed merchant's rule and left alone (#102): rule-filed,
     * `user_edited = 0`. Replaced or removed as the merchant changes.
     */
    PRESELECTED(userEdited = false),
    ;

    companion object {
        /** The choice a row stored with [userEdited] shows as. */
        fun of(userEdited: Boolean): ChosenBy = entries.single { it.userEdited == userEdited }
    }
}

/** How often [ManualEntries.today] looks at the clock at least: once a minute. */
private const val TODAY_RECHECK_MS = 60_000L

/** How far back spec 9.4's "most used" looks, today included. */
internal const val RANKING_DAYS = 90
