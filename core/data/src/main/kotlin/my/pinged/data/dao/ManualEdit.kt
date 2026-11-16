package my.pinged.data.dao

import my.pinged.parse.Direction

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
    /** The new `direction` (#355): money received, or spent. */
    val direction: Direction? = null,
    /** The bill it pays back (spec 7.4, 9.4): a new one, or none. */
    val bill: Bill? = null,
) {
    /** The three merchant columns, each null for no merchant. */
    data class Merchant(val raw: String?, val display: String?, val key: String?)

    /** The note, a group of its own because null is a value it can be set to. */
    data class Note(val text: String?)

    /** `category_id`, and `user_edited`: true for a one-off, false for rule-filed. */
    data class Filing(val categoryId: Long, val userEdited: Boolean)

    /** `bill_txn_id`: the bill [id] pays back, or none for null, which makes it plain money received. */
    data class Bill(val id: Long?)
}

/**
 * A guard refused part of a write of several statements, thrown inside its
 * transaction so the statements before it roll back: `TxnDao.editManualEntry`
 * and `TxnDao.insertManual` answer it as "nothing written".
 */
internal class RefusedWriteException : IllegalStateException("A guard refused part of the write; nothing was written.")
