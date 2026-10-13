package my.pinged.ledger.entry

import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate

/**
 * The entry screen's draft in its `SavedStateHandle` (#108), so a process
 * death keeps everything typed: the amount, the day, the merchant, the note,
 * the chip and how it was chosen, and the entry being edited.
 *
 * One key per field, each a `Long` or a `String`, which a `Bundle` holds on
 * every API level. The chip's [ChosenBy] is stored by name: it decides
 * whether the entry saves as a one-off or rule-filed, so a restored draft
 * that lost it would save a pre-selection as something the user tapped.
 */
internal object SavedDraft {
    private const val AMOUNT = "draft.amountSen"
    private const val DAY = "draft.day"
    private const val MERCHANT = "draft.merchant"
    private const val NOTE = "draft.note"
    private const val CATEGORY = "draft.categoryId"
    private const val CHOSEN_BY = "draft.chosenBy"
    private const val EDIT_TARGET = "draft.editTarget"
    private const val EDIT_CREATED_AT = "draft.editCreatedAt"

    /** Write [draft] over whatever [handle] held. */
    fun write(handle: SavedStateHandle, draft: ManualDraft) {
        handle[AMOUNT] = draft.amountSen
        handle[DAY] = draft.day?.toString()
        handle[MERCHANT] = draft.merchant
        handle[NOTE] = draft.note
        handle[CATEGORY] = draft.category?.categoryId
        handle[CHOSEN_BY] = draft.category?.by?.name
    }

    /** The draft [handle] holds, or null when nothing was ever written to it. */
    fun read(handle: SavedStateHandle): ManualDraft? {
        val amount = handle.get<Long>(AMOUNT) ?: return null
        val categoryId = handle.get<Long>(CATEGORY)
        val by = handle.get<String>(CHOSEN_BY)?.let(ChosenBy::valueOf)
        return ManualDraft(
            amountSen = amount,
            day = handle.get<String>(DAY)?.let(LocalDate::parse),
            merchant = handle.get<String>(MERCHANT).orEmpty(),
            note = handle.get<String>(NOTE).orEmpty(),
            category = if (categoryId != null && by != null) CategoryChoice(categoryId, by) else null,
        )
    }

    /**
     * The entry being edited: what [handle] kept, else [target] from the
     * navigation key, which is then kept. Null for a new entry. Its
     * `created_at` is kept with its id, so a draft restored after a
     * delete-everything or a restore gave the id to another payment cannot
     * be written over that payment ([EditTarget]).
     */
    fun editTarget(handle: SavedStateHandle, target: EditTarget?): EditTarget? {
        if (handle.contains(EDIT_TARGET)) {
            val id = handle.get<Long>(EDIT_TARGET) ?: return null
            return EditTarget(id, handle.get<Long>(EDIT_CREATED_AT) ?: return null)
        }
        handle[EDIT_TARGET] = target?.id
        handle[EDIT_CREATED_AT] = target?.createdAt
        return target
    }
}
