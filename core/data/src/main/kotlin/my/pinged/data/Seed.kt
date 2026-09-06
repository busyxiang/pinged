package my.pinged.data

import my.pinged.data.entity.Category

object Seed {
    /**
     * Spec section 4. The fourteen categories, their order and their Lucide
     * `icon_key` values are fixed by the spec's table; only `Uncategorized` is
     * protected, because the confidence gate and the categorizer both resolve
     * to it by id and it must never be renamed or deleted out from under them.
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
        Category(
            name = UNCATEGORIZED,
            iconKey = "circle-dashed",
            sortOrder = 13,
            isProtected = true,
        ),
    )

    /**
     * The one seeded name that is referenced from SQL
     * ([my.pinged.data.dao.CategoryDao.uncategorizedIdOrNull]). Named here so the
     * literal exists in exactly one place.
     */
    const val UNCATEGORIZED = "Uncategorized"
}
