package my.pinged.ledger

import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Seed
import my.pinged.ledger.R
import my.pinged.ledger.home.categoryIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fourteen category icons, and the two things a generated file can get
 * wrong that nothing else would notice.
 *
 * These drawables are converted from Lucide's SVGs by a script (see
 * `docs/licenses/lucide.md`), so the failure to guard against is not a wrong
 * *picture* -- a test cannot see that -- but a file that does not **inflate**
 * at all. `pathData` is parsed lazily when the drawable is first loaded, so a
 * malformed path is not a build error: it is a crash, or a blank, on whichever
 * screen first draws that category. Only actually loading each one finds it,
 * which is why this is instrumented and not a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class CategoryIconTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everySeededIconKeyResolvesToADrawableThatInflates() {
        val keys = Seed.categories().map { it.iconKey }
        assertEquals("The seed no longer holds fourteen categories", 14, keys.size)

        for (key in keys) {
            val id = categoryIcon(key)
            assertNotNull(
                "No bundled drawable for the seeded icon_key '$key', so that " +
                    "category draws its name with no icon beside it",
                id,
            )
            assertNotNull(
                "R.drawable for '$key' would not inflate. The generated " +
                    "pathData is parsed on first load, so this is the only " +
                    "place a malformed path is caught rather than crashing " +
                    "the screen that first draws this category",
                ContextCompat.getDrawable(context, id!!),
            )
        }
    }

    /**
     * The two marks that are not a category's, and so are not reached by the
     * seed walk above. They are generated from upstream the same way and can
     * fail to inflate the same way.
     */
    @Test fun theRowMarksThatAreNotCategoryIconsAlsoInflate() {
        for ((name, id) in listOf(
            "ic_row_not_counted" to R.drawable.ic_row_not_counted,
            "ic_row_uncategorized" to R.drawable.ic_row_uncategorized,
        )) {
            assertNotNull(
                "$name would not inflate, so the row that draws it shows a " +
                    "blank slot or crashes",
                ContextCompat.getDrawable(context, id),
            )
        }
    }

    /**
     * A mapping typo cannot be seen by the test above: two keys pointing at one
     * drawable resolves and inflates, and the only symptom is Groceries wearing
     * Transport's picture.
     */
    @Test fun thefourteenKeysResolveToFourteenDifferentDrawables() {
        val ids = Seed.categories().map { it.iconKey }.mapNotNull(::categoryIcon)

        assertEquals(
            "Two icon_keys share a drawable, so one category is drawn with " +
                "another's icon: $ids",
            ids.size,
            ids.toSet().size,
        )
    }

    /**
     * §4 says only bundled icons can be offered, and §9.6 asks for icons "where
     * they can be resolved". Null is how the screen is told to draw the name
     * alone rather than a stand-in that would disagree with it.
     */
    @Test fun aKeyThisBuildDoesNotBundleResolvesToNoIcon() {
        assertNull(
            "An unbundled icon_key resolved to a drawable, so some category " +
                "would be drawn with a picture that is not its own",
            categoryIcon("definitely-not-a-bundled-lucide-name"),
        )
    }
}
