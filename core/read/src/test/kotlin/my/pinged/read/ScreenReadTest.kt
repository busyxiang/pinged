package my.pinged.read

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /** Questions are numbers; an answer names its question and the order its body ran in. */
    private val read = ScreenRead<Int, String>(scope, runner, "The test cannot be read", initial = 1) { _, question ->
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
        assertFalse(state.unavailable)
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
        assertTrue("A refused read did not raise unavailable", state.unavailable)
        assertEquals("A refused read dropped the last answer", "q1#1", state.answer)
        assertFalse("A refused read is not in flight", state.rereading)

        runner.refusing = false
        read.refresh()
        assertFalse("A successful read did not clear unavailable", state.unavailable)
        assertEquals("q1#2", state.answer)
    }

    /** A ledger replacement's refusal is its own result: never [ReadState.unavailable], and never both. */
    @Test fun aReadRefusedByAReplacementIsReplacingNotUnavailableAndTheNextOutcomeDecidesBetweenThem() {
        read.refresh()

        runner.replacing = true
        read.refresh()
        assertTrue("A read refused by a replacement did not raise replacing", state.replacing)
        assertFalse("A read refused by a replacement was called unavailable", state.unavailable)
        assertEquals("A read refused by a replacement dropped the last answer", "q1#1", state.answer)

        runner.replacing = false
        runner.refusing = true
        read.refresh()
        assertTrue("A refusal after a replacement did not raise unavailable", state.unavailable)
        assertFalse("A refusal after a replacement left replacing up", state.replacing)

        runner.refusing = false
        runner.replacing = true
        read.refresh()
        assertTrue("A replacement after a refusal did not raise replacing", state.replacing)
        assertFalse("A replacement after a refusal left unavailable up", state.unavailable)

        runner.replacing = false
        read.refresh()
        assertFalse("A successful read did not clear replacing", state.replacing)
        assertFalse(state.unavailable)
        assertEquals("The successful read's answer is not the one held", "q1#2", state.answer)
    }

    @Test fun aWriteRefusedByAReplacementIsReplacingAndItsReadBackDecidesWhatStays() {
        runner.holding = true
        read.refresh()
        runner.calls[0].release()

        val written = read.write("The test cannot be written") { }
        runner.calls[1].replace()
        assertTrue("A write refused by a replacement did not raise replacing", state.replacing)
        assertFalse("A write refused by a replacement was called unavailable", state.unavailable)

        runner.calls[2].replace()
        assertEquals(Outcome.Replacing, runBlocking { written.await() })
        assertTrue(state.replacing)
        assertFalse(state.unavailable)
    }

    @Test fun aStepRefusedByAReplacementEndsAStagedReadAsReplacing() {
        val staged = ScreenRead.fixedStaged(scope, runner, "The test cannot be read", Dispatchers.Unconfined) {
            transaction { }
            "never"
        }
        runner.replacing = true

        staged.refresh()

        assertTrue("A step refused by a replacement did not raise replacing", staged.state.value.replacing)
        assertFalse("A step refused by a replacement was called unavailable", staged.state.value.unavailable)
    }

    @Test fun aRefusedReadOfANewQuestionIsNotRereadingAndKeepsTheOldQuestionsAnswer() {
        read.refresh()

        runner.refusing = true
        read.ask(2)

        assertTrue(state.unavailable)
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

        assertTrue("A refused write did not raise unavailable", state.unavailable)
        assertEquals("A refused write dropped the last answer", "q1#1", state.answer)
        assertEquals("A refused write was not read back", 3, runner.calls.size)
        assertFalse(written.isCompleted)

        runner.calls[2].release()
        assertEquals(Outcome.Refused, runBlocking { written.await() })
        assertFalse("A successful read-back did not clear unavailable", state.unavailable)
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

        assertTrue("A refused step did not raise unavailable", staged.state.value.unavailable)
        assertEquals("A step ran after the one that was refused", emptyList<String>(), steps)
        assertEquals("The read asked the runner again after a refusal", 1, runner.calls.size)
        assertNull("A refused read published an answer", staged.state.value.answered)

        runner.refusing = false
        staged.refresh()
        assertFalse("The next read that landed did not clear unavailable", staged.state.value.unavailable)
        assertEquals(Answered(Unit, "never"), staged.state.value.answered)
    }
}
