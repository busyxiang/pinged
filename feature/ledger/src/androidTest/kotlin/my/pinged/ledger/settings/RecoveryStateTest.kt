package my.pinged.ledger.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.concurrent.atomic.AtomicInteger
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.RESCUE
import my.pinged.ledger.transfer.ImportReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The states with no artboard, met by a user who is already alarmed.
 *
 * `DatabaseUnreadableException` covers "a corrupt file, a full disk or a
 * storage error". The middle one is transient, so that state must not lead
 * with an irreversible action, and must never say the data is gone.
 */
class RecoveryStateTest {
    @get:Rule val compose = createComposeRule()

    private fun show(storage: Storage) =
        compose.setContent { PingedTheme { RecoveryNotice(SettingsState(storage = storage, loaded = true), onRescue = {}) } }

    @Test fun anUnreadableLedgerIsToldToWaitRatherThanToStartOver() {
        show(Storage.UNREADABLE)
        compose.onNodeWithText("check your phone's storage", substring = true).assertIsDisplayed()
    }

    /** A full disk lands here. Saying the key is gone would be a lie about a recoverable state. */
    @Test fun anUnreadableLedgerIsNotToldItsKeyIsGone() {
        show(Storage.UNREADABLE)
        compose.onNodeWithText("key", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    /** The key is gone for good; there is nothing to wait for. */
    @Test fun aKeylessLedgerIsToldPlainlyThatItCannotComeBack() {
        show(Storage.KEY_GONE)
        compose.onNodeWithText("device-to-device", substring = true).assertIsDisplayed()
    }

    @Test fun aDamagedLedgerIsToldSomeOfItCanStillBeRead() {
        show(Storage.DAMAGED)
        compose.onNodeWithText("SOME OF THIS CAN STILL BE READ", substring = true).assertIsDisplayed()
    }

    /**
     * **Section 6's urged action is a real control**, its role in semantics,
     * and the tap reaches the caller: a rescue drawn as text that does
     * nothing is no rescue (R35).
     */
    @Test fun aDamagedLedgerIsOfferedTheRescueAsAButtonThatReachesItsCaller() {
        val taps = AtomicInteger(0)
        compose.setContent {
            PingedTheme { RecoveryNotice(SettingsState(storage = Storage.DAMAGED, loaded = true), onRescue = { taps.incrementAndGet() }) }
        }
        compose.onNodeWithText(RESCUE)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals("the rescue did not reach its caller", 1, taps.get())
    }

    /** A file that will not open has nothing to rescue from; its notice offers none. */
    @Test fun anUnreadableLedgerIsNotOfferedTheRescue() {
        show(Storage.UNREADABLE)
        compose.onNodeWithText(CANNOT_READ_YOUR_DATA, substring = true).assertIsDisplayed()
        compose.onNodeWithText(RESCUE).assertDoesNotExist()
    }

    /**
     * A restore from here wipes the file before it imports, and the notice is
     * not redrawn until the import finishes. For that whole time "the
     * database is intact" and "nothing here has been deleted" are false.
     */
    @Test fun aKeylessLedgerSaysNothingWhileItIsBeingReplaced() {
        compose.setContent {
            PingedTheme {
                RecoveryNotice(
                    SettingsState(storage = Storage.KEY_GONE, running = TransferJob.Running(Operation.RESTORE, 0), loaded = true),
                    onRescue = {},
                )
            }
        }
        compose.onNodeWithText("has been deleted", substring = true).assertDoesNotExist()
    }

    /**
     * After this process deleted the ledger -- a restore that lost it, or a
     * delete -- neither reassurance is drawn in either state that carries
     * one. `RecoveryNoticeTruthTest` drives the unreadable case end to end;
     * key-gone after a loss is not provoked there, since the wipe that loses
     * the ledger takes the key file with it.
     */
    @Test fun noStateClaimsNothingWasDeletedAfterThisProcessDeletedIt() {
        val lost = Outcome(1, Operation.RESTORE, TransferJob.Failed("gone", ledgerLost = true))
        val deleted = Outcome(1, Operation.DELETE, TransferJob.Deleted)
        listOf(Storage.KEY_GONE, Storage.UNREADABLE).forEach { storage ->
            listOf(lost, deleted).forEach { wipe ->
                val body = recoveryCopy(
                    SettingsState(storage = storage, lastWipe = wipe, storageAsOf = 1, loaded = true),
                )?.second
                assertTrue("$storage after $wipe drew nothing at all", body != null)
                assertFalse(
                    "$storage after $wipe still claims nothing was deleted: $body",
                    body!!.contains("has been deleted") || body.contains("is intact"),
                )
            }
        }
    }

    /**
     * **Nor while a lost ledger is unacknowledged, whatever came after it.**
     * `Transfers` keeps the loss past a lesser outcome, and a restore that
     * succeeded after it would otherwise let "Nothing has been deleted"
     * stand beside "YOUR LEDGER IS GONE".
     */
    @Test fun anUnacknowledgedLossWithholdsTheClaimEvenAfterARestore() {
        val lost = Outcome(1, Operation.RESTORE, TransferJob.Failed("gone", ledgerLost = true))
        val restored = Outcome(2, Operation.RESTORE, TransferJob.Restored(ImportReport(1, null, 0, 0, 0, 0, 1, 0, 0, 0)))
        val body = recoveryCopy(
            SettingsState(storage = Storage.UNREADABLE, loss = lost, lastWipe = restored, storageAsOf = 2, loaded = true),
        )?.second
        assertTrue("precondition: nothing drawn at all", body != null)
        assertFalse("Beside an unacknowledged lost ledger: $body", body!!.contains("has been deleted"))
    }

    /** Healthy is the absence of bad news; a notice there would invent some. */
    @Test fun aHealthyLedgerDrawsNoNotice() {
        show(Storage.HEALTHY)
        compose.onNodeWithText(CANNOT_READ_YOUR_DATA, substring = true).assertDoesNotExist()
        compose.onNodeWithText("DAMAGED", substring = true).assertDoesNotExist()
    }
}
