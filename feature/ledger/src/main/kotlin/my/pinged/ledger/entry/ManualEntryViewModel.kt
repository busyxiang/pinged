package my.pinged.ledger.entry

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.pinged.data.entity.Category
import my.pinged.ledger.settings.Operation
import my.pinged.parse.Amount

/**
 * Everything the entry screen draws: the draft, and why Save may be off.
 *
 * [saved] is true once the draft is in the ledger, which is the screen's cue
 * to close; [saving] while a save is in flight, so a second tap on Save
 * before the screen closes cannot write the payment twice.
 */
data class ManualEntryState(
    val draft: ManualDraft = ManualDraft(),
    /** The category chips, most used first; see `ManualEntries.categories`. */
    val categories: List<Category> = emptyList(),
    /** A restore or delete-everything running; nothing is saved meanwhile (`ManualEntries`). */
    val busy: Operation? = null,
    /** The ledger would not open on the last save. */
    val storageUnavailable: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** The day the date chip's picker treats as today, the latest it offers: `ManualEntries.today`. */
    val today: LocalDate = LocalDate.now(),
    /**
     * The captured payment this draft may repeat, found on Save (#104):
     * drawn above Save with Add anyway and Cancel until one is tapped or the
     * draft changes.
     */
    val nearby: NearbyCapture? = null,
    /**
     * The manual entry being corrected (#105), or null for a new one: a
     * plain id, the `ManualEntry` key's, so it can be kept wherever the
     * draft is. Save reads *Save changes* while it is set.
     */
    val editing: Long? = null,
    /**
     * [editing]'s entry as stored, null until it is read: what Delete's
     * question names (#106), not the draft, which may have been retyped.
     */
    val loaded: ManualDraft? = null,
    /** The entry was deleted (#106): the screen's cue to close, as [saved] is. */
    val deleted: Boolean = false,
    /**
     * [editing]'s entry is no longer there -- deleted elsewhere, or replaced
     * by a delete-everything or a restore that may have given its id to
     * another payment: the screen's cue to close, as [deleted] is, with
     * nothing written ([EditTarget]).
     */
    val gone: Boolean = false,
) {
    /**
     * Spec 9.4: an amount above RM0.00, and nothing in the way of writing it:
     * no restore or delete running, and a ledger that opens (#108). A ledger
     * that would not open comes back through a restore or a delete, after
     * which `ManualEntries` reads the chips again and clears the flag. Off
     * while [nearby] asks its question: Add anyway is the save then.
     */
    val canSave: Boolean get() = draft.amountSen > 0 && writable && nearby == null && (editing == null || loaded != null)

    /** [nearby]'s Add anyway: Save's conditions, the question aside. */
    val canAddAnyway: Boolean get() = nearby != null && writable

    /**
     * Delete (#106): an entry being corrected, read, and nothing in the way
     * of writing; refused, as Save is, while a restore or delete runs.
     */
    val canDelete: Boolean get() = editing != null && loaded != null && writable && !deleted

    private val writable: Boolean get() = busy == null && !storageUnavailable && !saving && !saved && !gone
}

/**
 * The entry screen's holder: owns a [ManualEntries] on its own scope, and the
 * draft, which survives a configuration change here and a process death in
 * [handle] ([SavedDraft], #108). Built in `:app` with the navigation entry's
 * own `createSavedStateHandle()`.
 *
 * [target] is the navigation key's manual entry to correct (#105), or null
 * for a new one; see [editing]. A correction's draft is loaded once
 * ([ManualEntries.load]) and Save writes it back with `ManualEntries.update`.
 * It is read again whenever a delete-everything or a restore ends, and the
 * screen closes ([ManualEntryState.gone]) if the entry is no
 * longer there: either may have given its id to another payment.
 *
 * [preselection] is `ManualEntries.preselection` in production; a parameter so a
 * screen test can answer it when it chooses. [clock] is `ManualEntries`', the
 * one today is read from.
 */
class ManualEntryViewModel internal constructor(
    app: Application,
    private val handle: SavedStateHandle,
    target: EditTarget?,
    clock: () -> Long = System::currentTimeMillis,
    preselection: ((merchant: String) -> Deferred<Long?>)?,
) : AndroidViewModel(app) {

    constructor(app: Application, handle: SavedStateHandle) : this(app, handle, null, preselection = null)

    constructor(app: Application, handle: SavedStateHandle, editing: EditTarget?) : this(app, handle, editing, preselection = null)

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    private val entries = ManualEntries(context = { context }, scope = viewModelScope, clock = clock)

    private val lookup: (String) -> Deferred<Long?> = preselection ?: entries::preselection

    /**
     * The draft a process death left in [handle], if any. When there is one,
     * it is what the user last saw, so a correction starts from it: the
     * stored row is still read, for [loaded], but not over the draft.
     */
    private val restoredDraft: ManualDraft? = SavedDraft.read(handle)

    /**
     * The manual entry being corrected, null for a new one: what [handle]
     * kept, else the navigation key's [target].
     */
    internal val editing: EditTarget? = SavedDraft.editTarget(handle, target)

    private val draft = MutableStateFlow(restoredDraft ?: ManualDraft())
    private val progress = MutableStateFlow(Progress.EDITING)

    /**
     * [editing]'s draft as loaded, null until it is: what Save compares
     * against to decide whether to look for a nearby capture again, what the
     * merchant lookup leaves alone, and what Delete's question names.
     */
    private val loaded = MutableStateFlow<ManualDraft?>(null)

    /**
     * The warning Save raised, with the draft it was raised for: drawn only
     * while the draft is still that one, so an edit after it withdraws it
     * and the next Save asks again about what is now on screen.
     */
    private val warned = MutableStateFlow<Warned?>(null)

    /** The draft, and the warning standing over it, if any. */
    private val nearbyDraft = combine(draft, warned) { draft, warned -> draft to warned?.takeIf { it.draft == draft }?.nearby }

    val state: StateFlow<ManualEntryState> =
        combine(nearbyDraft, entries.categories, entries.busy, entries.storageUnavailable, combine(progress, loaded, entries.today, ::Triple)) { (draft, nearby), categories, busy, unavailable, (progress, loaded, today) ->
            ManualEntryState(
                draft = draft,
                categories = categories,
                busy = busy,
                storageUnavailable = unavailable,
                saving = progress == Progress.SAVING,
                saved = progress == Progress.SAVED,
                nearby = nearby,
                editing = editing?.id,
                loaded = loaded,
                deleted = progress == Progress.DELETED,
                gone = progress == Progress.GONE,
                today = today,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ManualEntryState(editing = editing?.id, today = entries.today.value))

    init {
        // Every draft the screen holds, whoever set it, is the one a process
        // death keeps. On the main thread, as every edit is, so the write
        // lands before the Activity's state is saved. A correction's only
        // once its row is read or a draft was restored: the empty draft before
        // the read, kept, would come back as a restored draft and block the
        // row's read, reopening the edit blank.
        viewModelScope.launch {
            draft.filter { editing == null || restoredDraft != null || loaded.value != null }
                .collect { SavedDraft.write(handle, it) }
        }
        // Spec 9.4's pre-selection (#102), asked again whenever the merchant
        // changes, and when a restore or delete ends: one asked during it is
        // refused, and the restored rules may file the merchant differently.
        // `collectLatest`, so only the newest merchant's answer is waited on;
        // [ManualDraft.preselected] drops a late one anyway.
        viewModelScope.launch {
            combine(draft.map { it.merchant }.distinctUntilChanged(), entries.busy) { merchant, busy ->
                merchant.takeIf { busy == null }
            }.filterNotNull().collectLatest { merchant ->
                if (editing != null) {
                    // Edit mode asks only about a merchant the user typed:
                    // the stored one's category is what the row is filed
                    // under, which a lookup must not replace before Save --
                    // an amount fixed alone leaves the category alone.
                    val stored = loaded.value ?: return@collectLatest
                    if (merchant == stored.merchant) {
                        draft.update { if (it.category?.by == ChosenBy.TAPPED) it else it.copy(category = stored.category) }
                        return@collectLatest
                    }
                }
                val categoryId = lookup(merchant).await()
                draft.update { it.preselected(merchant, categoryId) }
            }
        }
        // A chip the ledger no longer has -- chosen before a restore or a
        // delete-everything replaced the categories -- is dropped once the
        // chips are read again, so the screen never shows a selection with no
        // chip; `ManualEntries` refuses to write such an id meanwhile. Not
        // while the chips are unread: an empty list is no answer.
        viewModelScope.launch {
            combine(draft, entries.categories) { draft, categories ->
                draft.category?.categoryId?.takeIf { id -> categories.isNotEmpty() && categories.none { it.id == id } }
            }.filterNotNull().collect { gone ->
                draft.update { if (it.category?.categoryId == gone) it.copy(category = null) else it }
            }
        }
        if (editing != null) {
            // Read once nothing is in the way, and again whenever a restore
            // or a delete-everything ends: it may have refused the first read,
            // and it may have replaced the ledger, taking the entry or giving
            // its id to another payment. Their end, not `Databases.rewrites`:
            // they are the only production replacements, and the test
            // fixtures' ungated `Databases.reset` moves `rewrites` too, where
            // a read can meet the file mid-delete ("no such table: txn"),
            // which `CaptureStorage` rethrows out of this scope.
            viewModelScope.launch {
                var replacing = false
                entries.busy.collect { busy ->
                    if (busy != null) {
                        replacing = true
                    } else if (loaded.value == null || replacing) {
                        replacing = false
                        read(editing)
                    }
                }
            }
        }
    }

    /**
     * Read [target]'s entry: the first read fills the draft, unless a process
     * death left one; a later one only refreshes [loaded], which Delete's
     * question names, and keeps what the user typed. An entry no longer
     * there closes the screen. A refused read waits for the next try.
     */
    private suspend fun read(target: EditTarget) {
        when (val read = entries.load(target.id, target.createdAt).await()) {
            null -> Unit
            Loaded.Gone -> progress.compareAndSet(Progress.EDITING, Progress.GONE)
            is Loaded.Entry -> {
                val first = loaded.value == null
                loaded.value = read.draft
                if (first && restoredDraft == null) draft.value = read.draft
            }
        }
    }

    /** The amount the field now holds, in sen; see [Keypad]. */
    fun amount(sen: Long) {
        draft.update { it.copy(amountSen = sen.coerceIn(0L, Amount.CEILING_SEN)) }
    }

    /** A tap on [categoryId]'s chip; see [ManualDraft.tapped]. */
    fun tapCategory(categoryId: Long) {
        draft.update { it.tapped(categoryId) }
    }

    /** The merchant field's text, as typed; see [ManualDraft.merchant]. */
    fun merchant(text: String) {
        draft.update { it.copy(merchant = text) }
    }

    /** The note field's text, as typed; see [ManualDraft.note]. */
    fun note(text: String) {
        draft.update { it.copy(note = text) }
    }

    /**
     * The date chip's pick: today is `Today`, a past day backdates the entry,
     * a future day is ignored ([dated]).
     */
    fun day(picked: LocalDate) {
        draft.update { it.dated(picked, entries.today()) }
    }

    /**
     * Save the draft, once: a tap while a save is in flight, or after one
     * landed, writes nothing. A save that wrote nothing -- refused during a
     * restore or a delete, or a ledger that would not open -- leaves the
     * draft as it was, to save again.
     *
     * When Pinged already has a capture of the same amount near the entry's
     * time (#104, `ManualEntries.nearbyCapture`), nothing is written: the
     * screen names it, and [addAnyway] or [keepEditing] answers. An edit
     * asks again only when its amount or date changed (#105), and never
     * about itself.
     */
    fun save(): Job = viewModelScope.launch {
        if (!progress.compareAndSet(Progress.EDITING, Progress.SAVING)) return@launch
        val asked = draft.value
        val stored = loaded.value
        val moved = stored == null || asked.amountSen != stored.amountSen || asked.day != stored.day
        val nearby = if (moved) entries.nearbyCapture(asked, editing?.id).await() else null
        if (nearby != null) {
            warned.value = Warned(asked, nearby)
            progress.value = Progress.EDITING
            return@launch
        }
        write(asked)
    }

    /** Add anyway (#104): save the draft the warning was about, as it is. */
    fun addAnyway(): Job = viewModelScope.launch {
        if (!progress.compareAndSet(Progress.EDITING, Progress.SAVING)) return@launch
        write(draft.value)
    }

    /** Cancel on the warning (#104): back to the draft, filled in, unsaved. */
    fun keepEditing() {
        warned.value = null
    }

    private suspend fun write(asked: ManualDraft) {
        val written = if (editing != null) entries.update(editing, asked).await() else entries.save(asked).await() != null
        progress.value = if (written) Progress.SAVED else Progress.EDITING
        // A correction refused because its entry is gone closes the screen
        // rather than offering a Save that can never land.
        if (!written && editing != null) read(editing)
    }

    /**
     * Delete the entry being corrected (#106), once Delete's question is
     * answered: the screen closes when it is gone, as it does when it was
     * already gone (`ManualEntries.delete`). Nothing for a new entry,
     * while a save or delete is in flight, or after either landed; a delete
     * that removed nothing -- refused during a restore or a delete, or a
     * ledger that would not open -- leaves the screen as it was.
     */
    fun delete(): Job = viewModelScope.launch {
        val target = editing ?: return@launch
        if (!progress.compareAndSet(Progress.EDITING, Progress.SAVING)) return@launch
        progress.value = if (entries.delete(target).await()) Progress.DELETED else Progress.EDITING
    }

    /** [Progress.SAVING] covers a delete in flight too: either one is a write. */
    private enum class Progress { EDITING, SAVING, SAVED, DELETED, GONE }

    private data class Warned(val draft: ManualDraft, val nearby: NearbyCapture)
}
