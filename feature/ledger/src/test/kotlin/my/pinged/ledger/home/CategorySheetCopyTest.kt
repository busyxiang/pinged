package my.pinged.ledger.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the category sheet says before a save (#155): the reach of a rename or
 * a new icon, and why an in-use category has no Delete. Plain JVM: both are
 * strings of counts.
 */
class CategorySheetCopyTest {
    @Test fun theReachNamesWhatChangesOnHowManyRows() {
        assertEquals("412 TRANSACTIONS WILL SHOW THE NEW NAME", reachLine(412, renamed = true, reIconed = false))
        assertEquals("1 TRANSACTION WILL SHOW THE NEW ICON", reachLine(1, renamed = false, reIconed = true))
        assertEquals("1,234 TRANSACTIONS WILL SHOW THE NEW NAME AND ICON", reachLine(1_234, renamed = true, reIconed = true))
    }

    @Test fun thereIsNoReachWithNothingChangedOrNothingFiled() {
        assertNull(reachLine(412, renamed = false, reIconed = false))
        assertNull(reachLine(0, renamed = true, reIconed = true))
    }

    @Test fun anInUseCategoryIsToldWhatUsesIt() {
        assertEquals("USED BY 412 TRANSACTIONS, SO IT CANNOT BE DELETED", inUseLine(412, 0))
        assertEquals("USED BY 1 LEARNED MERCHANT, SO IT CANNOT BE DELETED", inUseLine(0, 1))
        assertEquals("USED BY 1 TRANSACTION AND 3 LEARNED MERCHANTS, SO IT CANNOT BE DELETED", inUseLine(1, 3))
        assertNull(inUseLine(0, 0))
    }
}
