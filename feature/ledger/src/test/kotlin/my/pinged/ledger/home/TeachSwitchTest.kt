package my.pinged.ledger.home

import my.pinged.parse.DictionaryEntry
import my.pinged.parse.MatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #48: the "Always call this" switch's starting position, derived from how the
 * row was filed. Ids are arbitrary; each test names the state it stands for.
 */
class TeachSwitchTest {
    private val uncategorized = 1L
    private val food = 2L
    private val transport = 3L

    private fun default(
        categoryId: Long,
        userEdited: Boolean = false,
        hasKey: Boolean = true,
        learned: Long? = null,
        dictionary: Long? = null,
    ) = teachSwitchDefault(
        hasKey = hasKey,
        categoryId = categoryId,
        userEdited = userEdited,
        uncategorizedId = uncategorized,
        learnedCategoryId = learned,
        dictionaryCategoryId = dictionary,
    )

    @Test fun anUncategorizedRowStartsOn() = assertEquals(true, default(categoryId = uncategorized))

    /** Uncategorized is its own branch: the dictionary could name it, and that must not read as "filed by a shipped rule". */
    @Test fun anUncategorizedRowStartsOnEvenIfTheDictionaryNamesUncategorized() =
        assertEquals(true, default(categoryId = uncategorized, dictionary = uncategorized))

    @Test fun aOneOffStartsOn() = assertEquals(true, default(categoryId = food, userEdited = true))

    @Test fun aOneOffStartsOnEvenWhereItsCategoryIsTheDictionarys() =
        assertEquals(true, default(categoryId = food, userEdited = true, dictionary = food))

    @Test fun aRowFiledByTheUsersRuleStartsOn() = assertEquals(true, default(categoryId = food, learned = food))

    @Test fun aRowFiledByTheDictionaryStartsOff() = assertEquals(false, default(categoryId = food, dictionary = food))

    /** The learned rule outranks the dictionary in filing, so it does in marking. */
    @Test fun aRowBothTheRuleAndTheDictionaryWouldFileStartsOn() =
        assertEquals(true, default(categoryId = food, learned = food, dictionary = food))

    /**
     * Not the user's rule's category and not the dictionary's: a since-changed
     * entry or a since-changed rule filed it. #45 left the default open; it is
     * on, because the row is not filed by anything that is there now.
     */
    @Test fun anOldRuleRowStartsOn() =
        assertEquals(true, default(categoryId = food, learned = transport, dictionary = transport))

    @Test fun aRowWithNoCategoryMatchAndNoRulesStartsOn() = assertEquals(true, default(categoryId = food))

    @Test fun aRowWithNoMerchantKeyHasNoSwitch() {
        assertNull(default(categoryId = uncategorized, hasKey = false))
        assertNull(default(categoryId = food, userEdited = true, hasKey = false))
    }

    @Test fun theDictionaryMarksOnlyWhatItWouldFile() {
        val filing = dictionaryCategoryIds(
            dictionary = listOf(
                DictionaryEntry("DIGI", "Telco & internet"),
                DictionaryEntry("MY50", "Transport", MatchMode.PREFIX),
                DictionaryEntry("GONE", "Deleted category"),
            ),
            categoryIds = mapOf("Telco & internet" to 10L, "Transport" to transport),
        )

        assertEquals(10L, filing("DIGI PREPAID"))
        assertEquals(transport, filing("MY50 PASS"))
        assertNull("A key the dictionary does not know", filing("MR DIY"))
        assertNull("An entry whose category is gone files nothing", filing("GONE"))
        assertNull("No key, no dictionary hit", filing(null))
    }
}
