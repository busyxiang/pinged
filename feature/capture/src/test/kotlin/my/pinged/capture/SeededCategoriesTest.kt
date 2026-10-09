package my.pinged.capture

import my.pinged.data.Seed
import my.pinged.parse.SeededCategories
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `:core:parse` cannot see `Seed`, so the dictionary validates category names
 * against its own copy of them. This is the one place the two are compared.
 */
class SeededCategoriesTest {
    @Test fun `the dictionary's category names are the seeded ones`() {
        assertEquals(Seed.categories().map { it.name }, SeededCategories.NAMES.toList())
    }
}
