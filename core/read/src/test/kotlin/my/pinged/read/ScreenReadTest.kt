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

    @Test fun aReadWithNoQuestionAsksUnitAndNeverRereads() {
        val fixed = ScreenRead.fixed(scope, runner, "The test cannot be read") { "fixed" }
        runner.holding = true

        fixed.refresh()
        runner.calls[0].release()
        fixed.refresh()

        assertFalse(fixed.state.value.rereading)
        assertEquals(Answered(Unit, "fixed"), fixed.state.value.answered)
    }
}
