package my.pinged.data.dao

import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState

/** One row as a gateway Merge found it: what `TxnDao.unmergeGateway` puts back. */
data class MergedRow(val id: Long, val state: TxnState, val reason: PendingReason?, val categoryId: Long)

/**
 * What a gateway Merge wrote (§7.2, #255): [dismissed] `REJECTED`, [kept]
 * `COMMITTED`, and [kept]'s category set to [carriedCategory] when that is
 * not null. `TxnDao.unmergeGateway` takes all of it back.
 */
data class GatewayMerge(val dismissed: MergedRow, val kept: MergedRow, val carriedCategory: Long?)
