package my.pinged.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the search field's text becomes in `txn_fts MATCH ?` (spec 15.2). The
 * string is handed to FTS4's query parser, so anything the user types that
 * the parser reads as syntax -- a quote, a `-`, an `OR` -- changes the
 * question or throws. Expected values are written out by hand.
 */
class SearchMatchTest {

    @Test fun aWordMatchesAsAPrefix() {
        assertEquals("grab*", SearchMatch.of("grab"))
    }

    @Test fun everyWordIsRequired() {
        assertEquals("grab* food*", SearchMatch.of("Grab  Food"))
    }

    @Test fun nothingToMatchIsNull() {
        assertNull(SearchMatch.of(""))
        assertNull(SearchMatch.of("   \t"))
        assertNull(SearchMatch.of("\"*-()'"))
    }

    @Test fun punctuationSeparatesWordsAndNeverReachesTheParser() {
        // An unbalanced quote is a parse error in FTS4, `-` is NOT in the
        // enhanced syntax, and a `*` typed mid-word would be a second prefix.
        assertEquals("touch* n* go*", SearchMatch.of("Touch 'n Go"))
        assertEquals("7* eleven*", SearchMatch.of("7-Eleven"))
        assertEquals("a* b*", SearchMatch.of("\"a*(b)"))
    }

    @Test fun operatorWordsAreSearchedForNotObeyed() {
        // FTS4 reads OR, AND, NOT and NEAR as operators only in capitals.
        assertEquals("kopi* or* teh*", SearchMatch.of("kopi OR teh"))
        assertEquals("not* near*", SearchMatch.of("NOT NEAR"))
    }

    @Test fun lettersAndDigitsOutsideAsciiAreWords() {
        assertEquals("café* 99speedmart*", SearchMatch.of("Café 99Speedmart"))
        assertEquals("麦当劳*", SearchMatch.of("麦当劳"))
        // A no-break space separates as a space does.
        assertEquals("kfc* ss2*", SearchMatch.of("KFC SS2"))
    }
}
