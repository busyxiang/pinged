package my.pinged

import my.pinged.data.Databases
import my.pinged.ui.theme.HeldBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #280: what `core:data` says holds the database, as the status line names
 * it. The background integrity check is never blamed on an export (#238).
 */
class HeldByTest {
    @Test fun anExportIsAnExport() = assertEquals(HeldBy.Export, heldBy(Databases.Holder.Export))

    @Test fun theIntegrityCheckIsTheCheck() = assertEquals(HeldBy.IntegrityCheck, heldBy(Databases.Holder.IntegrityCheck))

    @Test fun nothingHoldingItIsNothing() = assertNull(heldBy(null))
}
