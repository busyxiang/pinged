package my.pinged.ledger.theme

/**
 * What the app says when it cannot open its own database (spec 11.1).
 *
 * It is the line that stops a reader concluding they have spent nothing when
 * the truth is that their ledger could not be read, and two screens and three
 * instrumented suites say it -- so a screen and its test spelling it
 * differently is a test that passes over a screen saying something else. Same
 * reason as [Separator], which records the failure mode in full.
 *
 * In `theme` rather than in either screen because neither of the two packages
 * that draw it may depend on the other.
 *
 * Uppercased in the string, not by a text transform, so what a screen reader
 * says is what is drawn -- and is what the tests match on exactly.
 */
const val CANNOT_READ_YOUR_DATA: String = "CANNOT READ YOUR DATA"
