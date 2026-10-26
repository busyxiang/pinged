package my.pinged.parse

import my.pinged.parse.LearnedRules.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one seam of #303: `Filer.file(key)`, through a [Snapshot] port. The
 * matcher's own table is `CategorizerTest`'s; what is here is the order, and
 * which key each half is asked under.
 */
class FilerTest {
    private val telco = 10L
    private val transport = 11L
    private val food = 12L
    private val health = 13L

    private val dictionary = listOf(
        DictionaryEntry("DIGI", "Telco & internet"),
        DictionaryEntry("MY50", "Transport"),
        DictionaryEntry("GONE", "Deleted category"),
    )
    private val seeded = mapOf("Telco & internet" to telco, "Transport" to transport)

    private fun filer(
        rules: Map<String, Long> = emptyMap(),
        aliases: Map<String, String> = emptyMap(),
        dictionary: List<DictionaryEntry> = this.dictionary,
        seeded: Map<String, Long> = this.seeded,
    ) = Filer(dictionary, seeded, Snapshot(rules, aliases))

    @Test fun `a learned rule beats the dictionary and both halves are kept`() {
        val filing = filer(rules = mapOf("DIGI TELEC" to food)).file("DIGI TELEC")
        assertEquals(Filing(learned = food, dictionary = telco), filing)
        assertEquals(food, filing.categoryId)
        assertEquals(FilingSource.LEARNED, filing.source)
    }

    @Test fun `a learned rule beats a longer dictionary entry`() {
        val f = filer(
            rules = mapOf("DIGI TELEC" to food),
            dictionary = dictionary + DictionaryEntry("DIGI TELEC", "Transport", MatchMode.EXACT),
        )
        assertEquals(Filing(learned = food, dictionary = transport), f.file("DIGI TELEC"))
        assertEquals(food, f.file("DIGI TELEC").categoryId)
    }

    @Test fun `a rule on a renamed or user-made category still wins`() {
        // The rule is an id, and a rule's category need be nobody's seed_name.
        val userMade = 99L
        val filing = filer(rules = mapOf("MY50 PASS" to userMade)).file("MY50 PASS")
        assertEquals(userMade, filing.categoryId)
        assertEquals(FilingSource.LEARNED, filing.source)
    }

    @Test fun `the dictionary files a key nothing was taught for`() {
        val filing = filer().file("DIGI PREPAID")
        assertEquals(Filing(learned = null, dictionary = telco), filing)
        assertEquals(FilingSource.DICTIONARY, filing.source)
    }

    @Test fun `an entry whose seeded category is gone falls through`() {
        assertEquals(Filing.NONE, filer().file("GONE"))
        // ...to a shorter entry, not to the gone category.
        val f = filer(dictionary = dictionary + DictionaryEntry("GONE FISHING", "Deleted category"))
        assertEquals(Filing.NONE, f.file("GONE FISHING"))
        val shorter = filer(dictionary = dictionary + DictionaryEntry("GONE", "Transport", MatchMode.CONTAINS) +
            DictionaryEntry("GONE FISHING", "Deleted category"))
        assertEquals(transport, shorter.file("GONE FISHING").dictionary)
    }

    @Test fun `no key and an unknown key give NONE`() {
        val f = filer(rules = mapOf("A CLINIC" to health))
        assertEquals(Filing.NONE, f.file(null))
        assertEquals(Filing.NONE, f.file("MR DIY"))
        assertNull(f.file("MR DIY").categoryId)
        assertEquals(FilingSource.NONE, f.file("MR DIY").source)
    }

    @Test fun `a rule files a key the dictionary has never seen`() {
        assertEquals(Filing(learned = health, dictionary = null), filer(rules = mapOf("A CLINIC" to health)).file("A CLINIC"))
    }

    @Test fun `an alias reads its identity's rule`() {
        val f = filer(rules = mapOf("TARGET" to food), aliases = mapOf("SOURCE" to "TARGET"))
        assertEquals(food, f.file("SOURCE").learned)
        assertEquals(food, f.file("TARGET").learned)
    }

    @Test fun `a dormant rule on a merged-away key is not read`() {
        val f = filer(rules = mapOf("SOURCE" to health), aliases = mapOf("SOURCE" to "TARGET"))
        assertNull("the identity is TARGET, which has no rule", f.file("SOURCE").learned)
        assertNull(f.file("TARGET").learned)
    }

    @Test fun `the dictionary matches the key the payment arrived under, not its identity's`() {
        val f = filer(aliases = mapOf("DIGI PREPAID" to "SOMEWHERE ELSE"))
        assertEquals(telco, f.file("DIGI PREPAID").dictionary)
        assertNull("the identity's key is not the dictionary's question", f.file("SOMEWHERE ELSE").dictionary)
    }

    @Test fun `two filers over an unchanged ledger are equal`() {
        assertEquals(
            filer(rules = mapOf("A" to food), aliases = mapOf("B" to "A")),
            filer(rules = mapOf("A" to food), aliases = mapOf("B" to "A")),
        )
    }

    @Test fun `a snapshot's merged identities come from its aliases`() {
        assertEquals(setOf("A", "C"), Snapshot(aliases = mapOf("B" to "A", "D" to "C", "E" to "A")).mergedIdentities)
    }
}
