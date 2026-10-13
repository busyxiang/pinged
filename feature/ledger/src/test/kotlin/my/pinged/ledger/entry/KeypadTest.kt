package my.pinged.ledger.entry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The entry screen's amount rule (#97, #99): digits fill in from the right as
 * sen, backspace drops the last one, and nothing grows the amount past the
 * parser's ceiling. A JVM test, because the rule is `Long` arithmetic on what a
 * text field reported; `ManualEntryScreenTest` drives the same rule through
 * the field.
 */
class KeypadTest {

    @Test fun digitsFillInFromTheRightAsSen() {
        val sen = "1250".fold(0L) { sen, d -> Keypad.keyed(sen, Keypad.shown(sen), Keypad.shown(sen) + d) }
        assertEquals("1, 2, 5, 0 is not RM12.50", 1_250L, sen)
    }

    @Test fun backspaceDropsTheLastDigit() {
        assertEquals("Backspace did not drop the last digit", 125L, Keypad.keyed(1_250L, "1250", "125"))
        assertEquals("Backspace from one digit did not reach zero", 0L, Keypad.keyed(5L, "5", ""))
    }

    @Test fun aDecimalSeparatorNeverReachesTheAmount() {
        // A comma-decimal keyboard's separator, then a digit, as an IME would
        // commit them: neither "12,5" nor "12.5" may read as anything but 125.
        assertEquals("A comma moved the amount", 12L, Keypad.keyed(12L, "12", "12,"))
        assertEquals("A full stop moved the amount", 12L, Keypad.keyed(12L, "12", "12."))
        assertEquals("A digit after a separator was not a digit", 125L, Keypad.keyed(12L, "12", "12,5"))
    }

    @Test fun aDigitPastTheCeilingIsIgnored() {
        // RM1,000,000.00 itself is accepted, as `Amount.toSen` accepts it.
        val ceiling = 100_000_000L
        assertEquals("RM1,000,000.00 was refused", ceiling, Keypad.keyed(10_000_000L, "10000000", "100000000"))
        assertEquals("A digit grew the amount past RM1,000,000", ceiling, Keypad.keyed(ceiling, "100000000", "1000000000"))
        assertEquals("A digit grew RM999,999.99 past the ceiling", 99_999_999L, Keypad.keyed(99_999_999L, "99999999", "999999999"))
    }

    @Test fun zeroIsShownAsNothingToType() {
        assertEquals("Zero drew a digit the user has to delete first", "", Keypad.shown(0L))
        assertEquals("A leading zero counted as a digit", 0L, Keypad.keyed(0L, "", "0"))
    }

    @Test fun aChangeThatIsNeitherTypingNorErasingLeavesTheAmount() {
        // A paste or a replaced selection: not a keypad, so not guessed at.
        assertEquals("A replacement moved the amount", 1_250L, Keypad.keyed(1_250L, "1250", "99"))
    }
}
