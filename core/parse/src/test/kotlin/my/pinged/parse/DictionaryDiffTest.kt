package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryDiffTest {
    private val digi = DictionaryEntry("DIGI", "Telco & internet")
    private val my50 = DictionaryEntry("MY50", "Transport")

    @Test fun `entries only in the incoming dictionary are added`() {
        val diff = DictionaryDiff.between(installed = listOf(digi), incoming = listOf(digi, my50))
        assertEquals(listOf(my50), diff.added)
        assertEquals(emptyList<DictionaryEntry>(), diff.removed)
        assertTrue(diff.changed.isEmpty())
    }

    @Test fun `entries only in the installed dictionary are removed`() {
        val diff = DictionaryDiff.between(installed = listOf(digi, my50), incoming = listOf(my50))
        assertEquals(listOf(digi), diff.removed)
        assertTrue(diff.added.isEmpty())
    }

    @Test fun `an entry whose category or mode moved is changed, not added and removed`() {
        val recategorised = digi.copy(category = "Bills & utilities")
        val widened = my50.copy(mode = MatchMode.CONTAINS)
        val diff = DictionaryDiff.between(listOf(digi, my50), listOf(recategorised, widened))
        assertEquals(
            listOf(DictionaryDiff.Change(digi, recategorised), DictionaryDiff.Change(my50, widened)),
            diff.changed,
        )
        assertTrue(diff.added.isEmpty() && diff.removed.isEmpty())
    }

    @Test fun `a change of case alone is the same entry`() {
        val diff = DictionaryDiff.between(listOf(digi), listOf(digi.copy(text = "Digi")))
        assertTrue(diff.isEmpty)
    }

    @Test fun `an unchanged dictionary has an empty diff`() {
        assertTrue(DictionaryDiff.between(listOf(digi, my50), listOf(my50, digi)).isEmpty)
    }

    @Test fun `the preview lists every entry and says it affects new payments only`() {
        val diff = DictionaryDiff.between(
            installed = listOf(digi, my50),
            incoming = listOf(digi.copy(category = "Bills & utilities"), DictionaryEntry("RKL", "Transport", MatchMode.CONTAINS)),
        )
        val lines = diff.preview()
        assertTrue(lines.toString(), lines.contains("Added: RKL (contains) -> Transport"))
        assertTrue(lines.toString(), lines.contains("Changed: DIGI (prefix) Telco & internet -> Bills & utilities"))
        assertTrue(lines.toString(), lines.contains("Removed: MY50 (prefix) -> Transport"))
        assertEquals(DictionaryDiff.NEW_PAYMENTS_ONLY, lines.last())
        assertEquals("These changes affect new payments only. Payments already filed keep their category.", lines.last())
    }

    @Test fun `an empty diff still carries the wording`() {
        val lines = DictionaryDiff.between(listOf(digi), listOf(digi)).preview()
        assertEquals(listOf("No dictionary changes."), lines)
    }
}
