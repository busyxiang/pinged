package my.pinged.ledger.entry

import my.pinged.data.Seed
import my.pinged.data.entity.Category

/** The seed's categories with ids, Uncategorized dropped, in seed order: what `rankedByUse` hands manual entry on a fresh install. */
internal val SEED_CHIPS: List<Category> = Seed.categories()
    .mapIndexed { i, c -> c.copy(id = i + 1L) }
    .filter { it.name != Seed.UNCATEGORIZED }
