package my.pinged.data

import my.pinged.data.entity.Category

object Seed {
    /**
     * Spec section 4. The sixteen categories, their order and their Lucide
     * `icon_key` values are fixed by the spec's table; only `Uncategorized` is
     * protected, because the confidence gate and the categorizer both resolve
     * to it by id and it must never be renamed or deleted out from under them.
     *
     * A row added here reaches a fresh install only. An existing install gets
     * it from [my.pinged.data.dao.CategoryDao.topUp], which inserts the names in
     * [TOPPED_UP] and nothing else: add the new name there too, and raise
     * [UNCATEGORIZED_SORT_ORDER] past it.
     */
    fun categories(): List<Category> = listOf(
        // Renamed from "Makan" by product decision. Only the display name
        // changed: the icon key and the sort order are unchanged, and this is
        // seed data, so it is rows and not schema -- schemas/1.json is
        // untouched by it.
        Category(name = "Food & Drinks", iconKey = "utensils", sortOrder = 0),
        Category(name = "Groceries", iconKey = "shopping-basket", sortOrder = 1),
        Category(name = "Transport", iconKey = "car", sortOrder = 2),
        Category(name = "Petrol & tolls", iconKey = "fuel", sortOrder = 3),
        Category(name = "Bills & utilities", iconKey = "zap", sortOrder = 4),
        Category(name = "Telco & internet", iconKey = "wifi", sortOrder = 5),
        Category(name = "Shopping", iconKey = "shopping-bag", sortOrder = 6),
        Category(name = "Health", iconKey = "heart-pulse", sortOrder = 7),
        Category(name = "Education", iconKey = "graduation-cap", sortOrder = 8),
        Category(name = "Family", iconKey = "users", sortOrder = 9),
        Category(name = "Religious & zakat", iconKey = "hand-heart", sortOrder = 10),
        Category(name = "Government & fees", iconKey = "landmark", sortOrder = 11),
        Category(name = "Entertainment", iconKey = "ticket", sortOrder = 12),
        // "Financing" is what an Islamic bank calls a loan. PTPTN, hire
        // purchase, housing and personal loans.
        Category(name = LOANS_AND_FINANCING, iconKey = "hand-coins", sortOrder = 13),
        // Every premium, medical and takaful included. Treatment is Health's.
        Category(name = INSURANCE_AND_TAKAFUL, iconKey = "shield", sortOrder = 14),
        Category(
            name = UNCATEGORIZED,
            iconKey = "circle-dashed",
            sortOrder = UNCATEGORIZED_SORT_ORDER,
            isProtected = true,
        ),
    )

    const val LOANS_AND_FINANCING = "Loans & financing"
    const val INSURANCE_AND_TAKAFUL = "Insurance & takaful"

    /**
     * The seeded names [my.pinged.data.dao.CategoryDao.topUp] adds to a table
     * that was seeded before they existed. Not every seeded name: a missing
     * one is as likely to be a category the user deleted (spec 4), and
     * re-inserting it would undo that.
     */
    val TOPPED_UP: Set<String> = setOf(LOANS_AND_FINANCING, INSURANCE_AND_TAKAFUL)

    /**
     * Uncategorized's place, after every other seeded category. It is also the
     * top-up's marker: a table whose Uncategorized sits lower was seeded, or
     * restored from a backup, before [TOPPED_UP] existed. It travels inside a
     * backup with the row, which no flag outside the table would.
     */
    const val UNCATEGORIZED_SORT_ORDER = 15

    /**
     * The one seeded name that is referenced from SQL
     * ([my.pinged.data.dao.CategoryDao.uncategorizedIdOrNull]). Named here so the
     * literal exists in exactly one place.
     */
    const val UNCATEGORIZED = "Uncategorized"
}
