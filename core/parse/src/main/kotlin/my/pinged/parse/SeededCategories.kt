package my.pinged.parse

/**
 * The names of the fourteen categories the app seeds, which a dictionary entry
 * may name (spec #45: an entry names its category by seeded name).
 *
 * Restated here because `Seed.categories()` is in `:core:data`, which depends
 * on this module and not the other way round. `SeededCategoriesTest` in
 * `:feature:capture` compares the two lists, so a category renamed in `Seed`
 * without this list fails there and not as a dictionary that silently stops
 * filing.
 */
object SeededCategories {
    val NAMES: Set<String> = linkedSetOf(
        "Food & Drinks",
        "Groceries",
        "Transport",
        "Petrol & tolls",
        "Bills & utilities",
        "Telco & internet",
        "Shopping",
        "Health",
        "Education",
        "Family",
        "Religious & zakat",
        "Government & fees",
        "Entertainment",
        "Uncategorized",
    )
}
