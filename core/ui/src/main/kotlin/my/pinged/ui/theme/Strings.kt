package my.pinged.ui.theme

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

/**
 * What a screen says in [CANNOT_READ_YOUR_DATA]'s place when its read was
 * turned away by a ledger replacement -- a restore or a delete-everything the
 * user started (#328). Nothing is wrong with the ledger, so the unreadable line
 * would be false; and what the screen held is the ledger going away, so it
 * draws none of it. Here for [CANNOT_READ_YOUR_DATA]'s reason: every screen
 * that reads the ledger, and its tests, say it. The dot is [Separator]'s,
 * escaped for ASCII.
 */
const val REPLACING_YOUR_DATA: String = "REPLACING YOUR DATA \u00B7 NOTHING CAN BE SHOWN UNTIL IT FINISHES"

/**
 * The damaged ledger's one way to a file: the recovery notice's action, which
 * Spending's backup reminder leads to on a damaged ledger. Here for
 * [CANNOT_READ_YOUR_DATA]'s reason: the settings screen and `:app`'s tests
 * both say it.
 */
const val RESCUE: String = "Rescue what can still be read"

/** The settings row that writes a backup; see [RESCUE]. */
const val EXPORT_EVERYTHING: String = "Export everything"

/**
 * A value not read yet: the em-dash `design/Loading*.dc.html` draw in its
 * place. Never 0, NEVER or NONE, which a reader takes for an answer (#237).
 * Here for [CANNOT_READ_YOUR_DATA]'s reason, and an escape because source
 * files here are ASCII.
 */
const val NOT_READ_YET: String = "\u2014"
