package my.pinged.ledger.home

import my.pinged.ledger.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which glyph a row's leading slot holds.
 *
 * A JVM test, because [rowMark] is a rule over two inputs and `R.drawable`
 * constants are plain ints -- nothing here needs a device. That the files those
 * ints name actually inflate is `CategoryIconTest`'s subject, on a device,
 * because only loading them can answer it.
 *
 * Asserted here rather than through the screen because the icon is decorative:
 * it carries no `contentDescription` on purpose, so a composition offers
 * nothing to assert on but a picture.
 */
class RowMarkRuleTest {

    @Test fun aRowThatCountsGetsItsOwnCategorysIconAndNoAccent() {
        val mark = rowMark(excluded = false, uncategorized = false, iconKey = "utensils")

        assertEquals(
            "A committed row did not get the icon its category names, so the " +
                "slot says nothing about what the row is",
            R.drawable.ic_category_utensils,
            mark?.icon,
        )
        assertEquals(
            "An ordinary row was drawn in the accent, which the artboard " +
                "reserves for the uncategorized row and its chip",
            false,
            mark?.accent,
        )
    }

    @Test fun anUncategorizedRowGetsTheAccentedAddMark() {
        val mark = rowMark(excluded = false, uncategorized = true, iconKey = "circle-dashed")

        assertEquals(R.drawable.ic_row_uncategorized, mark?.icon)
        assertEquals(
            "The add mark was not drawn in the accent, so it does not pair " +
                "with the chip underneath it",
            true,
            mark?.accent,
        )
    }

    /**
     * The precedence that matters. An excluded row can also be uncategorized,
     * and `TxnRow` withholds its chip -- so an accent "add a category" mark
     * would point at a control that is not on the row.
     */
    @Test fun anExcludedRowThatIsAlsoUncategorizedReadsAsNotCounted() {
        val mark = rowMark(excluded = true, uncategorized = true, iconKey = "circle-dashed")

        assertEquals(
            "An excluded row was marked as needing a category, but its chip " +
                "is withheld and it is in no total",
            R.drawable.ic_row_not_counted,
            mark?.icon,
        )
        assertEquals(false, mark?.accent)
    }

    /**
     * The rule §7.3 forces. An excluded row keeps whatever `category_id` it
     * happens to carry, and that category is in no total -- so drawing its icon
     * would label the row with a fact about nothing.
     */
    @Test fun anExcludedRowGetsTheNotCountedMarkAndNotItsCategorysIcon() {
        val mark = rowMark(excluded = true, uncategorized = false, iconKey = "utensils")

        assertEquals(
            "An excluded row drew something other than the not-counted mark",
            R.drawable.ic_row_not_counted,
            mark?.icon,
        )
    }

    /**
     * The excluded rule does not depend on the row having a resolvable category
     * at all -- an excluded row with no icon key is still not counted, and the
     * slot still has to say so.
     */
    @Test fun anExcludedRowWithNoIconKeyStillGetsTheNotCountedMark() {
        assertEquals(
            R.drawable.ic_row_not_counted,
            rowMark(excluded = true, uncategorized = false, iconKey = null)?.icon,
        )
    }

    @Test fun aKeyThisBuildDoesNotBundleLeavesTheSlotEmpty() {
        assertNull(
            "An unbundled icon_key resolved to some drawable, so a row would " +
                "be marked with a picture that is not its own",
            rowMark(excluded = false, uncategorized = false, iconKey = "definitely-not-bundled"),
        )
    }

    @Test fun aRowWithNoCategoryAtAllLeavesTheSlotEmpty() {
        assertNull(rowMark(excluded = false, uncategorized = false, iconKey = null))
    }
}
