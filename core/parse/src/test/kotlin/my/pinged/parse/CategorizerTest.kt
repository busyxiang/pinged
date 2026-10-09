package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one seam of spec #45's testing decisions: `Categorizer.file(key, learned)`.
 * Everything here goes through it and nothing reads the matcher's internals.
 */
class CategorizerTest {

    private fun entry(text: String, category: String, mode: MatchMode = MatchMode.PREFIX) =
        DictionaryEntry(text = text, category = category, mode = mode)

    private fun categorizer(vararg entries: DictionaryEntry) = Categorizer(entries.toList())

    private fun filed(c: Categorizer, key: String?) = c.file(key, learned = null)

    // ---- one mode at a time ---------------------------------------------

    @Test fun `exact files only the whole key`() {
        val c = categorizer(entry("DIGI", "Telco & internet", MatchMode.EXACT))
        assertEquals(Filing("Telco & internet", FilingSource.DICTIONARY), filed(c, "DIGI"))
        assertEquals(Filing("Telco & internet", FilingSource.DICTIONARY), filed(c, "digi"))
        assertEquals(Filing(null, FilingSource.NONE), filed(c, "DIGI TELEC"))
        assertEquals(Filing(null, FilingSource.NONE), filed(c, "MY DIGI"))
    }

    @Test fun `prefix files the start of the key and not the middle`() {
        val c = categorizer(entry("DIGI", "Telco & internet", MatchMode.PREFIX))
        assertEquals("Telco & internet", filed(c, "DIGI TELEC").categoryName)
        assertEquals("Telco & internet", filed(c, "DIGI").categoryName)
        assertEquals(FilingSource.NONE, filed(c, "MY DIGI TELEC").source)
    }

    @Test fun `contains files a mention anywhere in the key`() {
        val c = categorizer(entry("RKL", "Transport", MatchMode.CONTAINS))
        assertEquals("Transport", filed(c, "123-MY-RKL-KL SENTRAL").categoryName)
        assertEquals("Transport", filed(c, "RKL").categoryName)
        assertEquals(FilingSource.NONE, filed(c, "123-MY-ABC-KL SENTRAL").source)
    }

    @Test fun `an entry with no mode is a prefix entry`() {
        assertEquals(MatchMode.PREFIX, DictionaryEntry(text = "DIGI", category = "Telco & internet").mode)
    }

    // ---- the shared boundary table ---------------------------------------

    @Test fun `the boundary table holds on the JVM`() {
        val failures = BoundaryTable.rows().mapNotNull { row ->
            val c = Categorizer(listOf(DictionaryEntry(row.entry, "Shopping", row.mode)))
            val got = c.file(row.key, learned = null).source
            val want = if (row.matches) FilingSource.DICTIONARY else FilingSource.NONE
            if (got == want) null else "${row.describe()}: expected $want, got $got"
        }
        assertEquals(emptyList<String>(), failures)
        assertTrue("the table is not being read", BoundaryTable.rows().size >= 38)
    }

    // ---- longest match, then mode, then pack order -----------------------

    @Test fun `the longest match wins across modes`() {
        val c = categorizer(
            entry("GRAB", "Transport", MatchMode.PREFIX),
            entry("GRAB EC", "Shopping", MatchMode.CONTAINS),
        )
        assertEquals("Shopping", filed(c, "GRAB EC KUALA LUMPUR").categoryName)
        assertEquals("Transport", filed(c, "GRAB KUALA LUMPUR").categoryName)
    }

    @Test fun `the longest match wins whichever way the pack lists them`() {
        val longFirst = categorizer(
            entry("GRAB EC", "Shopping"),
            entry("GRAB", "Transport"),
        )
        assertEquals("Shopping", filed(longFirst, "GRAB EC KL").categoryName)
    }

    @Test fun `a length tie goes to the more specific mode, then to pack order`() {
        // Both entries are three letters and both match "AAB ABB": one as a
        // prefix and one as a mention further along.
        val mentionListedFirst = categorizer(
            entry("ABB", "Shopping", MatchMode.CONTAINS),
            entry("AAB", "Transport", MatchMode.PREFIX),
        )
        assertEquals("Transport", filed(mentionListedFirst, "AAB ABB").categoryName)

        // Equal in length and in mode: the pack's order decides, both ways.
        val first = categorizer(
            entry("AAB", "Shopping", MatchMode.CONTAINS),
            entry("ABB", "Transport", MatchMode.CONTAINS),
        )
        val second = categorizer(
            entry("ABB", "Transport", MatchMode.CONTAINS),
            entry("AAB", "Shopping", MatchMode.CONTAINS),
        )
        assertEquals("Shopping", filed(first, "AAB ABB").categoryName)
        assertEquals("Transport", filed(second, "AAB ABB").categoryName)
    }

    @Test fun `exact outranks prefix and contains at the same length`() {
        val c = categorizer(
            entry("ABC", "Shopping", MatchMode.CONTAINS),
            entry("ABC", "Transport", MatchMode.PREFIX),
            entry("ABC", "Health", MatchMode.EXACT),
        )
        assertEquals("Health", filed(c, "ABC").categoryName)
    }

    // ---- learned, and no hit ---------------------------------------------

    @Test fun `no hit and no learned rule files nothing`() {
        val c = categorizer(entry("DIGI", "Telco & internet"))
        assertEquals(Filing(null, FilingSource.NONE), c.file("SOMEWHERE ELSE", learned = null))
        assertEquals(Filing(null, FilingSource.NONE), c.file(null, learned = null))
        assertEquals(Filing(null, FilingSource.NONE), Categorizer(emptyList()).file("DIGI", learned = null))
    }

    @Test fun `a learned rule outranks a dictionary hit`() {
        val c = categorizer(entry("DIGI", "Telco & internet"))
        assertEquals(
            Filing("Entertainment", FilingSource.LEARNED),
            c.file("DIGI TELEC", LearnedRule("Entertainment")),
        )
    }

    @Test fun `a learned rule outranks a longer dictionary hit`() {
        val c = categorizer(
            entry("DIGI", "Telco & internet"),
            entry("DIGI TELEC", "Bills & utilities", MatchMode.EXACT),
        )
        assertEquals("Bills & utilities", filed(c, "DIGI TELEC").categoryName)
        assertEquals(
            Filing("Entertainment", FilingSource.LEARNED),
            c.file("DIGI TELEC", LearnedRule("Entertainment")),
        )
    }

    @Test fun `a learned rule files a key the dictionary has never seen`() {
        val c = categorizer(entry("DIGI", "Telco & internet"))
        assertEquals(
            Filing("Health", FilingSource.LEARNED),
            c.file("A CLINIC", LearnedRule("Health")),
        )
    }
}
