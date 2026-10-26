package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictionary matcher alone: `Categorizer.categoryOf(key)`. The order it is
 * tried in against a learned rule is `FilerTest`'s.
 */
class CategorizerTest {

    private fun entry(text: String, category: String, mode: MatchMode = MatchMode.PREFIX) =
        DictionaryEntry(text = text, category = category, mode = mode)

    private fun categorizer(vararg entries: DictionaryEntry) = Categorizer(entries.toList())

    private fun filed(c: Categorizer, key: String?) = c.categoryOf(key)

    // ---- one mode at a time ---------------------------------------------

    @Test fun `exact files only the whole key`() {
        val c = categorizer(entry("DIGI", "Telco & internet", MatchMode.EXACT))
        assertEquals("Telco & internet", filed(c, "DIGI"))
        assertEquals("Telco & internet", filed(c, "digi"))
        assertNull(filed(c, "DIGI TELEC"))
        assertNull(filed(c, "MY DIGI"))
    }

    @Test fun `prefix files the start of the key and not the middle`() {
        val c = categorizer(entry("DIGI", "Telco & internet", MatchMode.PREFIX))
        assertEquals("Telco & internet", filed(c, "DIGI TELEC"))
        assertEquals("Telco & internet", filed(c, "DIGI"))
        assertNull(filed(c, "MY DIGI TELEC"))
    }

    @Test fun `contains files a mention anywhere in the key`() {
        val c = categorizer(entry("RKL", "Transport", MatchMode.CONTAINS))
        assertEquals("Transport", filed(c, "123-MY-RKL-KL SENTRAL"))
        assertEquals("Transport", filed(c, "RKL"))
        assertNull(filed(c, "123-MY-ABC-KL SENTRAL"))
    }

    @Test fun `an entry with no mode is a prefix entry`() {
        assertEquals(MatchMode.PREFIX, DictionaryEntry(text = "DIGI", category = "Telco & internet").mode)
    }

    // ---- the shared boundary table ---------------------------------------

    @Test fun `the boundary table holds on the JVM`() {
        val failures = BoundaryTable.rows().mapNotNull { row ->
            val c = Categorizer(listOf(DictionaryEntry(row.entry, "Shopping", row.mode)))
            val got = c.categoryOf(row.key)
            val want = if (row.matches) "Shopping" else null
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
        assertEquals("Shopping", filed(c, "GRAB EC KUALA LUMPUR"))
        assertEquals("Transport", filed(c, "GRAB KUALA LUMPUR"))
    }

    @Test fun `the longest match wins whichever way the pack lists them`() {
        val longFirst = categorizer(
            entry("GRAB EC", "Shopping"),
            entry("GRAB", "Transport"),
        )
        assertEquals("Shopping", filed(longFirst, "GRAB EC KL"))
    }

    @Test fun `a length tie goes to the more specific mode, then to pack order`() {
        // Both entries are three letters and both match "AAB ABB": one as a
        // prefix and one as a mention further along.
        val mentionListedFirst = categorizer(
            entry("ABB", "Shopping", MatchMode.CONTAINS),
            entry("AAB", "Transport", MatchMode.PREFIX),
        )
        assertEquals("Transport", filed(mentionListedFirst, "AAB ABB"))

        // Equal in length and in mode: the pack's order decides, both ways.
        val first = categorizer(
            entry("AAB", "Shopping", MatchMode.CONTAINS),
            entry("ABB", "Transport", MatchMode.CONTAINS),
        )
        val second = categorizer(
            entry("ABB", "Transport", MatchMode.CONTAINS),
            entry("AAB", "Shopping", MatchMode.CONTAINS),
        )
        assertEquals("Shopping", filed(first, "AAB ABB"))
        assertEquals("Transport", filed(second, "AAB ABB"))
    }

    @Test fun `exact outranks prefix and contains at the same length`() {
        val c = categorizer(
            entry("ABC", "Shopping", MatchMode.CONTAINS),
            entry("ABC", "Transport", MatchMode.PREFIX),
            entry("ABC", "Health", MatchMode.EXACT),
        )
        assertEquals("Health", filed(c, "ABC"))
    }

    // ---- no hit ----------------------------------------------------------

    @Test fun `no hit files nothing`() {
        val c = categorizer(entry("DIGI", "Telco & internet"))
        assertNull(c.categoryOf("SOMEWHERE ELSE"))
        assertNull(c.categoryOf(null))
        assertNull(Categorizer(emptyList()).categoryOf("DIGI"))
    }
}
