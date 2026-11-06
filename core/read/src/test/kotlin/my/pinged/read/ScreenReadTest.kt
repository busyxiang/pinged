package my.pinged.read

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import my.pinged.read.test.ScriptedRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ScreenRead]'s contract (#296) through its public surface and a runner
 * the test drives: what a screen is given, in what order, and when.
 *
 * The scope is `Dispatchers.Unconfined` on the test's own thread, so a call
 * runs until it suspends on the runner and every assertion below is made at a
 * point the test chose: [ScriptedRunner] holds a call until it is released.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenReadTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val runner = ScriptedRunner(ApplicationProvider.getApplicationContext())

    /** Each body's question, in the order the bodies ran. */
    private val ran = ArrayList<Int>()

    /** The ledger replacement claimed, as `LedgerReplacements.current` is in production. */
    private val claim = MutableStateFlow<LedgerReplacement?>(null)

    /** Questions are numbers; an answer names its question and the order its body ran in. */
    private val read = ScreenRead<Int, String>(scope, runner, "The test cannot be read", initial = 1, replacement = claim) { _, question ->
        ran += question
        "q$question#${ran.size}"
    }

    private val state get() = read.state.value

    @After fun end() {
        scope.cancel()
    }

    @Test fun aReadIsNotReadYetUntilItsFirstAnswerLandsAndIsNeverRereadingBeforeThen() {
        runner.holding = true

        read.refresh()
        assertTrue("A read in flight with nothing to show was not 'not read yet'", state.notReadYet)
        assertFalse("The first read was called a re-read, with no answer to dim", state.rereading)

        runner.calls.single().release()
        assertEquals(Answered(1, "q1#1"), state.answered)
        assertFalse(state.rereading)
        assertEquals(LedgerAccess.Readable, state.ledger)
    }

    @Test fun aLaterReadCannotLandBeforeAnEarlierOneAndSoNeverUnderIt() {
        runner.holding = true

        read.refresh()
        read.refresh()
        assertEquals("The later read ran while the earlier was held: it could land first", 1, runner.calls.size)

        runner.calls[0].release()
        assertEquals("The earlier read's answer was not published first", "q1#1", state.answer)
        assertEquals("The later read did not start when the earlier ended", 2, runner.calls.size)

        runner.calls[1].release()
        assertEquals("The later read's answer is not the one left on screen", "q1#2", state.answer)
    }

    @Test fun anAnswerWhoseQuestionChangedWhileItRanPublishesNothing() {
        runner.holding = true

        read.refresh()
        read.ask(2)
        runner.calls[0].release()

        assertEquals("The first read's body did not run: this would pass for a read that never ran", listOf(1), ran)
        assertNull("An answer to a question already left behind was published", state.answered)

        runner.calls[1].release()
        assertEquals(Answered(2, "q2#2"), state.answered)
    }

    @Test fun aReadWaitingItsTurnForAQuestionLeftBehindIsNeverRun() {
        runner.holding = true

        read.refresh()
        read.ask(2)
        read.ask(3)
        runner.calls[0].release()
        runner.calls[1].release()

        assertEquals("A read for a question nobody wants any more was run", listOf(1, 3), ran)
        assertEquals(Answered(3, "q3#2"), state.answered)
    }

    @Test fun readingTheSameQuestionAgainNeverSetsRereadingAndAskingAnotherDoesUntilItsAnswerLands() {
        runner.holding = true
        read.refresh()
        runner.calls[0].release()

        read.refresh()
        assertEquals("A resume's read of the same question was called a re-read", false, state.rereading)
        assertEquals("The last answer was not kept while the same question was read again", "q1#1", state.answer)
        runner.calls[1].release()

        read.ask(2)
        assertTrue("A new question's read in flight was not rereading", state.rereading)
        assertEquals("The last answer was not kept while another question was read", "q1#2", state.answer)
        runner.calls[2].release()

        assertFalse("A landed answer was still rereading", state.rereading)
        assertEquals(Answered(2, "q2#3"), state.answered)
    }

    @Test fun aSheetOpenedAgainIsANewQuestionSoItsReadIsRereading() {
        val openings = ScreenRead<Int, String>(scope, runner, "The months cannot be read", initial = 0) { _, opening ->
            "opening $opening"
        }
        runner.holding = true
        openings.ask(1)
        runner.calls[0].release()
        assertFalse(openings.state.value.rereading)

        openings.ask(2)

        assertTrue("A reopened sheet's read was not rereading", openings.state.value.rereading)
        assertEquals("opening 1", openings.state.value.answer)
    }

    @Test fun aRefusedReadRaisesUnavailableAndKeepsTheLastAnswerAndTheNextSuccessClearsIt() {
        read.refresh()
        assertEquals("q1#1", state.answer)

        runner.refusing = true
        read.refresh()
        assertEquals("A refused read was not unreadable", LedgerAccess.Unreadable, state.ledger)
        assertEquals("A refused read dropped the last answer", "q1#1", state.answer)
        assertFalse("A refused read is not in flight", state.rereading)

        runner.refusing = false
        read.refresh()
        assertEquals("A successful read did not clear unreadable", LedgerAccess.Readable, state.ledger)
        assertEquals("q1#2", state.answer)
    }

    /** A ledger replacement's refusal is its own result: never [LedgerAccess.Unreadable], and the next outcome decides between them. */
    @Test fun aReadRefusedByAReplacementIsBeingReplacedNotUnreadableAndTheNextOutcomeDecidesBetweenThem() {
        read.refresh()

        runner.replacing = true
        read.refresh()
        assertEquals("A read refused by a replacement was not being replaced", LedgerAccess.BeingReplaced, state.ledger)
        assertEquals("A read refused by a replacement dropped the last answer", "q1#1", state.answer)

        runner.replacing = false
        runner.refusing = true
        read.refresh()
        assertEquals("A refusal after a replacement was not unreadable", LedgerAccess.Unreadable, state.ledger)

        runner.refusing = false
        runner.replacing = true
        read.refresh()
        assertEquals("A replacement after a refusal was not being replaced", LedgerAccess.BeingReplaced, state.ledger)

        runner.replacing = false
        read.refresh()
        assertEquals("A successful read did not clear being replaced", LedgerAccess.Readable, state.ledger)
        assertEquals("The successful read's answer is not the one held", "q1#2", state.answer)
    }

    @Test fun aWriteRefusedByAReplacementIsBeingReplacedAndItsReadBackDecidesWhatStays() {
        runner.holding = true
        read.refresh()
        runner.calls[0].release()

        val written = read.write("The test cannot be written") { }
        runner.calls[1].replace()
        assertEquals("A write refused by a replacement was not being replaced", LedgerAccess.BeingReplaced, state.ledger)

        runner.calls[2].replace()
        assertEquals(Outcome.Replacing, runBlocking { written.await() })
        assertEquals(LedgerAccess.BeingReplaced, state.ledger)
    }

    @Test fun aStepRefusedByAReplacementEndsAStagedReadAsBeingReplaced() {
        val staged = ScreenRead.fixedStaged(scope, runner, "The test cannot be read", Dispatchers.Unconfined) {
            transaction { }
            "never"
        }
        runner.replacing = true

        staged.refresh()

        assertEquals("A step refused by a replacement was not being replaced", LedgerAccess.BeingReplaced, staged.state.value.ledger)
    }

    @Test fun aRefusedReadOfANewQuestionIsNotRereadingAndKeepsTheOldQuestionsAnswer() {
        read.refresh()

        runner.refusing = true
        read.ask(2)

        assertEquals(LedgerAccess.Unreadable, state.ledger)
        assertFalse("A refused read is no longer in flight", state.rereading)
        assertEquals("The answer held is the old question's", Answered(1, "q1#1"), state.answered)
    }

    @Test fun aWriteIsReadBackInItsOwnTurnBeforeAnyReadAskedAfterIt() {
        runner.holding = true
        read.refresh()
        val wrote = ArrayList<String>()
        val written = read.write("The test cannot be written") { wrote += "w"; 7 }
        read.refresh()

        runner.calls[0].release()
        assertEquals("The write did not follow the read before it", listOf(false, true), runner.calls.map { it.write })
        assertEquals("The write ran before it was released", emptyList<String>(), wrote)

        runner.calls[1].release()
        assertEquals(listOf("w"), wrote)
        assertEquals("The read-back did not start in the write's turn", 3, runner.calls.size)
        assertFalse("The read-back was a write", runner.calls[2].write)
        assertFalse("The write completed before its read-back landed", written.isCompleted)

        runner.calls[2].release()
        assertEquals(Outcome.Done(7), runBlocking { written.await() })
        assertEquals("The read asked after the write began before the write's read-back landed", 4, runner.calls.size)
    }

    @Test fun aWriteAsksTheRunnerForOneTransactionOnlyWhenItIsToldTo() {
        runner.holding = true
        read.refresh()
        runner.calls[0].release()

        read.write("A plain write") { }
        runner.calls[1].release()
        runner.calls[2].release()
        read.write("A write that checks and then writes", oneTransaction = true) { }
        runner.calls[3].release()

        assertEquals(
            "The write was not asked for in one transaction exactly when it said so: " +
                "the first read, a plain write and its read-back, then the write that asked and its read-back",
            listOf(false, false, false, true, false),
            runner.calls.map { it.oneTransaction },
        )
    }

    @Test fun aRefusedWriteRaisesUnavailableAndIsStillReadBack() {
        runner.holding = true
        read.refresh()
        runner.calls[0].release()

        val written = read.write("The test cannot be written") { }
        runner.calls[1].refuse()

        assertEquals("A refused write was not unreadable", LedgerAccess.Unreadable, state.ledger)
        assertEquals("A refused write dropped the last answer", "q1#1", state.answer)
        assertEquals("A refused write was not read back", 3, runner.calls.size)
        assertFalse(written.isCompleted)

        runner.calls[2].release()
        assertEquals(Outcome.Refused, runBlocking { written.await() })
        assertEquals("A successful read-back did not clear unreadable", LedgerAccess.Readable, state.ledger)
    }

    /** A sheet closed: nothing is read for it, and what was on its way publishes nothing. */
    @Test fun settingTheQuestionReadsNothingAndDropsWhatWasInFlightOrWaitingForTheOldOne() {
        runner.holding = true
        read.refresh()
        read.refresh()

        read.setQuestion(2)
        runner.calls[0].release()

        assertEquals("The read already running did not run its body", listOf(1), ran)
        assertEquals("A read for the question left behind was run after it was left", 1, runner.calls.size)
        assertNull("An answer to the question left behind was published", state.answered)
        assertFalse("Setting a question with nothing asked of it was rereading", state.rereading)
        assertEquals(2, read.question.value)

        read.refresh()
        runner.calls[1].release()
        assertEquals("The question set was not the one read next", Answered(2, "q2#2"), state.answered)
    }

    @Test fun aReadWithNoQuestionAsksUnitAndNeverRereads() {
        val fixed = ScreenRead.fixed(scope, runner, "The test cannot be read") { "fixed" }
        runner.holding = true

        fixed.refresh()
        runner.calls[0].release()
        fixed.refresh()

        assertFalse(fixed.state.value.rereading)
        assertEquals(Answered(Unit, "fixed"), fixed.state.value.answered)
    }

    /**
     * What a staged read does between its steps is its own, with nothing of the
     * ledger held, and the answer is one: published when the last step is done.
     */
    @Test fun aStagedReadRunsItsStepsInOrderWithItsOwnWorkBetweenThemAndPublishesOnceAtTheEnd() {
        val steps = ArrayList<String>()
        val staged = ScreenRead.fixedStaged(scope, runner, "The test cannot be read", Dispatchers.Unconfined) {
            statements { steps += "statements" }
            steps += "between"
            val seen = transaction { steps += "transaction"; 3 }
            "answer $seen"
        }
        runner.holding = true

        staged.refresh()
        assertEquals("The first step did not wait for the runner", emptyList<String>(), steps)
        runner.calls[0].release()
        assertEquals("The work between the steps did not run once the first step was done", listOf("statements", "between"), steps)
        assertEquals("The answer landed before the last step", true, staged.state.value.notReadYet)
        assertEquals("The steps were not a statement, then a transaction", listOf(true, false), runner.calls.map { it.write })

        runner.calls[1].release()
        assertEquals(listOf("statements", "between", "transaction"), steps)
        assertEquals(Answered(Unit, "answer 3"), staged.state.value.answered)
    }

    @Test fun aRefusedStepEndsAStagedReadAsUnavailableAndRunsNothingAfterIt() {
        val steps = ArrayList<String>()
        val staged = ScreenRead.fixedStaged(scope, runner, "The test cannot be read", Dispatchers.Unconfined) {
            statements { steps += "statements" }
            steps += "between"
            transaction { steps += "transaction" }
            "never"
        }
        runner.refusing = true

        staged.refresh()

        assertEquals("A refused step was not unreadable", LedgerAccess.Unreadable, staged.state.value.ledger)
        assertEquals("A step ran after the one that was refused", emptyList<String>(), steps)
        assertEquals("The read asked the runner again after a refusal", 1, runner.calls.size)
        assertNull("A refused read published an answer", staged.state.value.answered)

        runner.refusing = false
        staged.refresh()
        assertEquals("The next read that landed did not clear unreadable", LedgerAccess.Readable, staged.state.value.ledger)
        assertEquals(Answered(Unit, "never"), staged.state.value.answered)
    }

    /**
     * The claim (#376): a write asked for while a restore or a delete is
     * claimed would land in the ledger about to be replaced, before its gate
     * is up. Refused before the runner, it raises nothing either: the user
     * started the operation, and the screen already says so.
     */
    @Test fun aWriteDuringTheClaimReachesNoRunnerEmitsNothingCompletesReplacingAndIsNotReadBack() {
        read.refresh()
        claim.value = LedgerReplacement.RESTORE
        val seen = ArrayList<ReadState<Int, String>>()
        scope.launch { read.state.collect { seen += it } }
        val calls = runner.calls.size
        var ran = false

        val written = read.write("The test cannot be written") { ran = true }

        assertEquals(Outcome.Replacing, runBlocking { written.await() })
        assertFalse("The write's body ran during the claim", ran)
        assertEquals("The write asked the runner for something during the claim, a read-back among it", calls, runner.calls.size)
        assertEquals("A write refused by the claim emitted state", 1, seen.size)
    }

    @Test fun aClaimThatLandsBetweenAskingForAWriteAndItsTurnStillRefusesIt() {
        runner.holding = true
        read.refresh()
        val written = read.write("The test cannot be written") { }

        claim.value = LedgerReplacement.DELETE
        runner.calls[0].release()

        assertEquals("The write waiting its turn reached the runner once the claim was up", 1, runner.calls.size)
        assertEquals(Outcome.Replacing, runBlocking { written.await() })
    }

    @Test fun aLookupRunsInTheTurnAndReadsNothingBack() {
        runner.holding = true
        read.refresh()
        val found = read.lookup("The test cannot be looked up") { 7 }
        assertEquals("The lookup ran before the read asked before it ended", 1, runner.calls.size)

        runner.calls[0].release()
        runner.calls[1].release()

        assertEquals(Outcome.Done(7), runBlocking { found.await() })
        assertFalse("A lookup is a read, not a write", runner.calls[1].write)
        assertEquals("A lookup was read back", 2, runner.calls.size)
    }

    /** Its caller is told; the screen's answer, which was not what was refused, stands. */
    @Test fun aLookupTheRunnerRefusesPublishesNothing() {
        read.refresh()
        runner.refusing = true

        val found = read.lookup("The test cannot be looked up") { 7 }

        assertEquals(Outcome.Refused, runBlocking { found.await() })
        assertEquals("A refused lookup was published as the screen's own refusal", LedgerAccess.Readable, state.ledger)
    }

    /** What a lookup returns feeds a write, so it is refused as a write is. */
    @Test fun aLookupDuringTheClaimReachesNoRunnerAndCompletesReplacing() {
        read.refresh()
        claim.value = LedgerReplacement.RESTORE
        val calls = runner.calls.size

        val found = read.lookup("The test cannot be looked up") { 7 }

        assertEquals(Outcome.Replacing, runBlocking { found.await() })
        assertEquals("The lookup asked the runner during the claim", calls, runner.calls.size)
    }

    @Test fun aReadDuringTheClaimLandsAsWithholding() {
        claim.value = LedgerReplacement.DELETE

        read.refresh()

        assertEquals("A read during the claim was refused", "q1#1", state.answer)
        assertEquals(LedgerAccess.Withholding(LedgerReplacement.DELETE), state.ledger)
    }

    @Test fun theClaimIsPublishedAsItIsRaisedAndAsItClears() {
        read.refresh()

        claim.value = LedgerReplacement.RESTORE
        assertEquals("The claim did not reach the screen until it read again", LedgerAccess.Withholding(LedgerReplacement.RESTORE), state.ledger)

        claim.value = null
        assertEquals("The claim's end did not reach the screen until it read again", LedgerAccess.Readable, state.ledger)
    }

    @Test fun unreadableTakesPriorityOverWithholding() {
        claim.value = LedgerReplacement.RESTORE
        runner.refusing = true

        read.refresh()

        assertEquals("A screen that cannot read said it was withholding", LedgerAccess.Unreadable, state.ledger)
    }

    @Test fun beingReplacedLastsPastTheClaimsEndUntilAReadLands() {
        claim.value = LedgerReplacement.DELETE
        runner.replacing = true
        read.refresh()
        assertEquals("A read the gate turned away was withholding, with nothing of the answer to hold", LedgerAccess.BeingReplaced, state.ledger)

        claim.value = null
        assertEquals("Being replaced ended with the claim, before any read said the ledger was back", LedgerAccess.BeingReplaced, state.ledger)

        runner.replacing = false
        read.refresh()
        assertEquals(LedgerAccess.Readable, state.ledger)
    }

    @Test fun aWriteAskedAfterTheClaimClearsRuns() {
        read.refresh()
        claim.value = LedgerReplacement.RESTORE
        read.write("The test cannot be written") { }
        claim.value = null
        var ran = false

        val written = read.write("The test cannot be written") { ran = true; 3 }

        assertEquals(Outcome.Done(3), runBlocking { written.await() })
        assertTrue("The write asked after the claim cleared did not run", ran)
    }
}
