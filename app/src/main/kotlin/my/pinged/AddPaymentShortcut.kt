package my.pinged

import android.content.Intent

/**
 * Spec 9.4's **Add payment** launcher shortcut (#107), declared statically in
 * `res/xml/shortcuts.xml` from the launcher activity's `<meta-data>`.
 *
 * The XML cannot name these constants, so the two are held together by
 * `AddPaymentShortcutTest`, which launches the intent the system parsed out of
 * that file rather than one built from here.
 */
internal object AddPaymentShortcut {
    /** `shortcutId` in `shortcuts.xml`. */
    const val ID = "add_payment"

    /** The extra `shortcuts.xml`'s intent carries, and its one value. */
    const val EXTRA_OPEN = "my.pinged.extra.OPEN"
    const val ADD_PAYMENT = "add_payment"

    /**
     * Whether [intent] is the shortcut's.
     *
     * A string compared, not a boolean: `<extra android:value="true">` is
     * typed by aapt, and a value that compiled as a string would read false
     * from `getBooleanExtra` without any error.
     */
    fun opens(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_OPEN) == ADD_PAYMENT
}
