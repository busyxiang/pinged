package my.pinged.capture

import my.pinged.data.DuplicateWindows
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState

/**
 * [PairCandidates] over a list, deciding what `TxnDao.findDuplicateSuspects`'s
 * SQL decides and in its index's order. `PairCandidatesContractTest` runs it
 * beside [RoomPairCandidates] over the same rows on the device.
 */
internal class InMemoryPairCandidates(private val rows: List<Row>) : PairCandidates {
    /**
     * A transaction in the ledger and [ruleId], the `matched_rule_id` its
     * capture recorded: null for a manual entry, or a capture no rule read.
     */
    data class Row(val txn: Txn, val ruleId: String?)

    override fun near(query: PairQuery): List<Txn> {
        val window = DuplicateWindows.layerTwo(query.occurredAt)
        return rows
            .filter { (txn, ruleId) ->
                txn.amountSen == query.amountSen &&
                    txn.occurredAt in window &&
                    txn.direction == query.direction &&
                    // SQL's `!=` against a NULL rule is NULL, which no row passes.
                    (txn.sourcePackage == null || txn.sourcePackage != query.sourcePackage || (ruleId != null && ruleId != query.ruleId)) &&
                    txn.state != TxnState.REJECTED
            }
            .sortedWith(compareBy({ it.txn.occurredAt }, { it.txn.id }))
            .map { it.txn }
    }
}

/** [StandingAnswers] over the answers given, each the rule, app and counterparty key the inbox's Always stores. */
internal class InMemoryStandingAnswers(private val answers: Set<Answer>) : StandingAnswers {
    data class Answer(val ruleId: String, val sourcePackage: String, val merchantKey: String?)

    override fun answered(ruleId: String, sourcePackage: String, merchantKey: String?): Boolean =
        Answer(ruleId, sourcePackage, merchantKey) in answers
}
