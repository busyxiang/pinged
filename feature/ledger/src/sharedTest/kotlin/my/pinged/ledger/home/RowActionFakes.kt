package my.pinged.ledger.home

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.dao.RetroPreview
import my.pinged.read.LedgerReplacement

/** A finished `Job`: what a fake's write returns, having written nothing. */
private fun done(): Job = Job().apply { complete() }

/**
 * The chooser's half for a screen test: records each save in [assigned],
 * answers the count line from [preview] and the New category tile from [add],
 * and is replacing the ledger while [busy] holds an operation.
 */
internal class FakeCategoryActions(
    private val preview: suspend (txnId: Long, categoryId: Long) -> RetroPreview? = { _, _ -> RetroPreview(0, emptyList()) },
    private val add: suspend (name: String, iconKey: String) -> Long? = { _, _ -> null },
) : CategoryActions {
    override val busy = MutableStateFlow<LedgerReplacement?>(null)

    /** Each save, as (txnId, categoryId, teach), in the order made. */
    val assigned = mutableListOf<Triple<Long, Long, Boolean>>()

    override fun assignCategory(txnId: Long, categoryId: Long, teach: Boolean): Job {
        assigned += Triple(txnId, categoryId, teach)
        return done()
    }

    override suspend fun retroPreview(txnId: Long, categoryId: Long): RetroPreview? = preview(txnId, categoryId)

    override suspend fun addCategory(name: String, iconKey: String): Long? = add(name, iconKey)
}

/**
 * The merchant sheet's half for a screen test: [sheet] stays drawn whatever
 * is tapped, each call is recorded, and the ledger is being replaced while
 * [busy] holds an operation. With [publishesSheet], an opening also publishes a sheet
 * read for it, named as the row named it, so a test can see that the screen
 * draws the sheet it asked for.
 */
internal class FakeMerchantActions(sheet: OpenMerchantSheet? = null, private val publishesSheet: Boolean = false) : MerchantActions {
    override val merchantSheet = MutableStateFlow(sheet)

    override val busy = MutableStateFlow<LedgerReplacement?>(null)

    val opened = mutableListOf<MerchantOpening>()
    val renamed = mutableListOf<String>()
    val merged = mutableListOf<String>()
    val separated = mutableListOf<String>()
    var closed = 0
        private set

    override fun openMerchant(opening: MerchantOpening): Job {
        opened += opening
        if (publishesSheet) {
            merchantSheet.value = OpenMerchantSheet(
                opening,
                MerchantSheetState(
                    ownKey = opening.ownKey,
                    identityKey = opening.ownKey,
                    name = opening.name,
                    derivedName = opening.name,
                    txnCount = 1,
                    mergedInto = null,
                    members = emptyList(),
                    others = emptyList(),
                    suggested = emptySet(),
                ),
            )
        }
        return done()
    }

    override fun closeMerchant() {
        closed++
    }

    override fun renameMerchant(name: String): Job {
        renamed += name
        return done()
    }

    override fun mergeMerchant(targetKey: String): Job {
        merged += targetKey
        return done()
    }

    override fun separateMerchant(key: String): Job {
        separated += key
        return done()
    }
}

/**
 * Where a screen's rows lead, for a screen test: each row opened, by id,
 * whichever screen [RowOpenings.open] chose for it -- `RowOpeningsTest` is
 * where that choice is asserted -- and each review asked for.
 */
internal class RecordingOpenings {
    val opened = mutableListOf<Long>()
    val reviewed = mutableListOf<Long?>()
    val openings = RowOpenings(openEntry = { opened += it.id }, openDetail = { opened += it.id }, openReview = { reviewed += it })
}
