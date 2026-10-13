package my.pinged.data.dao

/**
 * What a correction of a manual entry changes (#105): each group null when
 * unchanged, so `TxnDao.editManualEntry` names only the columns that moved.
 */
data class ManualEdit(
    val amountSen: Long? = null,
    /** The new `occurred_at`; `local_date` is derived from it. */
    val occurredAt: Long? = null,
    val merchant: Merchant? = null,
    val note: Note? = null,
    val filing: Filing? = null,
) {
    /** The three merchant columns, each null for no merchant. */
    data class Merchant(val raw: String?, val display: String?, val key: String?)

    /** The note, a group of its own because null is a value it can be set to. */
    data class Note(val text: String?)

    /** `category_id`, and `user_edited`: true for a one-off, false for rule-filed. */
    data class Filing(val categoryId: Long, val userEdited: Boolean)
}
