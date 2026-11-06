package my.pinged.ledger.entry

import androidx.lifecycle.SavedStateHandle
import my.pinged.parse.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/** The draft's direction survives a process death with the other fields (#355, #108). */
class SavedDraftDirectionTest {
    @Test fun receivedComesBackReceived() {
        val handle = SavedStateHandle()
        SavedDraft.write(handle, ManualDraft(amountSen = 500L, direction = Direction.REFUND))

        assertEquals(Direction.REFUND, SavedDraft.read(handle)!!.direction)
    }

    @Test fun aDraftSavedBeforeTheControlReadsAsPaid() {
        val handle = SavedStateHandle(mapOf("draft.amountSen" to 500L))

        assertEquals(Direction.EXPENSE, SavedDraft.read(handle)!!.direction)
    }
}
