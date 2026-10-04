package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 11.3's unmentioned hazard: the listener shares this process and calls
 * `Databases.shared` on the next notification, rebuilding the database halfway
 * through its removal.
 */
@RunWith(AndroidJUnit4::class)
class DeleteGateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromAnEmptyDatabase() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun captureCannotOpenTheDatabaseWhileItIsBeingDeleted() {
        Databases.whileDeleting {
            assertThrows(DatabaseBeingDeletedException::class.java) {
                Databases.shared(context)
            }
        }
    }

    @Test fun theGateLiftsAfterwards() {
        Databases.whileDeleting { }
        Databases.shared(context) // does not throw
    }

    /** A gate left up by a throwing block is capture dead for the process. */
    @Test fun theGateLiftsEvenWhenTheDeleteFails() {
        runCatching { Databases.whileDeleting { error("delete blew up") } }
        Databases.shared(context) // does not throw
    }

    /**
     * **A gate raised inside another does not lift it.** A restore holds the
     * gate across its wipe and its import, and the wipe raises its own. The
     * wipe's `finally` lowering the restore's gate would leave the whole
     * import running with the listener, the worker and the resume probe free
     * to open the file it is writing.
     */
    @Test fun anInnerGateLiftingLeavesTheOuterOneUp() {
        Databases.whileDeleting {
            Databases.whileDeleting { }
            assertThrows(
                "The inner gate lifted the outer one: the database opened while " +
                    "the outer holder was still inside it",
                DatabaseBeingDeletedException::class.java,
            ) {
                Databases.shared(context)
            }
        }
        Databases.shared(context) // and the outer one does lift
    }

    /**
     * **An open that passed the gate before it rose is refused once it has
     * the lock.** `shared` reads the gate unlocked, then waits for the lock to
     * build. Refused only at the unlocked check, a gate rising and closing the
     * handle in between would leave it building a fresh instance on the file
     * about to be deleted -- one that survives the delete, and that every
     * holder then rebinds onto.
     *
     * Deterministic, not a race: this thread holds `Databases`' monitor --
     * the one `shared` builds under -- until the opener is `BLOCKED` on it,
     * which is past the unlocked check, then raises the gate and `wait`s,
     * which releases the monitor to the opener with the gate up.
     */
    @Test fun anOpenWaitingForTheLockWhenTheGateRisesIsRefused() {
        Databases.reset()
        var outcome: Result<PingedDatabase>? = null
        val opener = Thread { outcome = runCatching { Databases.shared(context) } }
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val monitor = Databases as java.lang.Object
        try {
            synchronized(Databases) {
                opener.start()
                val deadline = System.currentTimeMillis() + TIMEOUT
                while (opener.state != Thread.State.BLOCKED) {
                    check(System.currentTimeMillis() < deadline) {
                        "the opener never reached the lock: ${opener.state}, $outcome"
                    }
                    Thread.sleep(1)
                }
                Databases.whileDeleting {
                    while (opener.isAlive && System.currentTimeMillis() < deadline) monitor.wait(20)
                }
            }
            opener.join(TIMEOUT)
            assertTrue(
                "An open that passed the gate before it rose, and took the lock " +
                    "after, was served ${outcome?.getOrNull()} with the gate up",
                outcome?.exceptionOrNull() is DatabaseBeingDeletedException,
            )
        } finally {
            Databases.reset()
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
