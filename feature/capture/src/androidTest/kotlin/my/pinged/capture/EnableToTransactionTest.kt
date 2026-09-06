package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.sources.SourcesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The milestone's whole claim, in one test: **the user enables their bank, and
 * the next notification from it becomes a transaction.**
 *
 * Two committed suites prove the halves -- `:feature:ledger`'s
 * `SourcesAllowListTest` takes a tap on the real screen down to a
 * `capture_source` row, and this module's `FreshInstallTest` takes a
 * notification to a committed transaction -- and nothing joined them. The join
 * is where the claim lives: for the whole milestone `insertIfNew` and
 * `setEnabled` had no caller anywhere, so the allow-list was empty on every real
 * install while several hundred green tests wrote their own rows in `@Before`.
 *
 * **Why this file is here and not in `:feature:ledger` or `:app`.** The chain
 * needs [CaptureIngest], which is `internal` to this module, and the allow-list
 * writer, which lives in `:feature:ledger`.
 *
 *  - `:app` cannot host it: Kotlin `internal` is per-module, so an `:app`
 *    androidTest suite cannot see [CaptureIngest] either, and it would need an
 *    androidTest source set, a manifest and a runner it does not have. Nor
 *    could it post a real notification instead -- a test APK can only post as
 *    itself, and the pack is keyed by package.
 *  - Widening [CaptureIngest] to public would put this module's private
 *    stage-one entry point into every module's compile surface, for ever, to
 *    buy one test.
 *  - An `androidTestImplementation` edge back onto `:feature:ledger` costs one
 *    line, changes nothing that ships, and runs the direction the dependency
 *    already runs at runtime.
 *
 * **Where this picks the chain up.** [SourcesViewModel.setEnabled] is "the
 * allow-list write, and the only one in the app", and the row toggle is a
 * one-line delegation to it. Driving `SourcesScreen` from here would mean
 * applying the Compose compiler plugin to a module that draws nothing, and
 * `SourcesAllowListTest` already covers the tap.
 */
@RunWith(AndroidJUnit4::class)
class EnableToTransactionTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sources = Databases.captureSourceDao(context)
    private val captures = Databases.rawCaptureDao(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Unique per run: this database is shared with the rest of the APK and survives it. */
    private val marker = "ZZJOIN" + System.nanoTime()
    private val base = System.currentTimeMillis()

    private val bank = ParseFixtures.TNG

    /**
     * The state a fresh install is in, and the state the shipped app was stuck
     * in: the pack has rules for this bank, and the user has not enabled it.
     *
     * This is a direct DAO write and it is setup, not the step under test --
     * every other class in this APK enables `TNG` in its own `@Before`, so the
     * shared row may already be on. Undoing that here is the only way to have
     * something for the toggle to do.
     */
    @Before fun theUserHasNotEnabledTheirBankYet() {
        sources.setEnabled(bank, false)
        cancelStageTwo()
    }

    /**
     * Leaves the row enabled, which is what every other class in this APK sets
     * up for itself anyway, so nothing depends on the order this one ran in.
     */
    @After fun leaveTheAllowListUsable() {
        CaptureFixtures.allowList(context, bank, true)
        cancelStageTwo()
        scope.cancel()
    }

    @Test
    fun enablingASourceIsWhatTurnsTheNextNotificationIntoATransaction() {
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val text = "Payment of $rm to foodpanda KLCC successful"

        // 1. Before consent. The identical notification, discarded at the gate.
        //
        // Without this the test would pass against an app whose allow-list was
        // never consulted, which is a different bug in the same place.
        val beforeConsent = runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = notification(text, id = 8_101, tag = "$marker-before", at = base - 9_000L),
                arrival = Arrival.POSTED,
                now = base - 9_000L,
            )
        }
        assertNull(
            "A notification from a source the user has not enabled was stored. " +
                "The allow-list is spec 11.1's default-deny consent gate.",
            beforeConsent,
        )
        assertEquals(
            "Text from a source the user has not enabled reached the database",
            emptyList<String>(),
            CaptureFixtures.textInDatabase(context, marker),
        )

        // 2. The user enables the source. Production's only allow-list write.
        val viewModel = SourcesViewModel(context, scope)
        viewModel.refresh()
        awaitLoaded(viewModel)
        viewModel.setEnabled(bank, true)

        assertNotNull(
            "The allow-list does not have $bank enabled after the screen's only " +
                "write ran. A null row below means insertIfNew never ran, and a " +
                "row with enabled=false means setEnabled updated nothing -- the " +
                "shape of failure that leaves a user certain they enabled their " +
                "bank. Row is now: ${sources.byPackage(bank)}",
            CaptureFixtures.waitForValue { sources.byPackage(bank)?.takeIf { it.enabled } },
        )
        assertTrue(
            "The allow-list and enabled() must agree. The listener gate reads " +
                "byPackage(pkg)?.enabled, not this list, so a row visible to one " +
                "and not the other means the settings screen and the gate " +
                "disagree about what is being captured",
            sources.enabled().any { it.pkg == bank },
        )

        // 3. The next notification from that source, through the same call the
        //    bound listener makes.
        val postedAt = base - 4_000L
        val captureId = runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = notification(text, id = 8_102, tag = "$marker-after", at = postedAt),
                arrival = Arrival.POSTED,
                now = postedAt,
            )
        }
        assertNotNull(
            "The same notification that was correctly discarded in step 1 was " +
                "still discarded after the user enabled the source. Enabling " +
                "did not reach the gate.",
            captureId,
        )

        // 4. Stage two, constructed the way WorkManager constructs it.
        assertEquals(
            "Stage two did not succeed, so the row is durable and unparsed and " +
                "the ledger stays empty with nothing anywhere saying why",
            ListenableWorker.Result.success(),
            TestListenableWorkerBuilder<ParseWorker>(context).build().startWork().get(),
        )

        assertEquals(
            "The capture never left NEW, which is a silently lost transaction",
            ParseStatus.MATCHED,
            captures.byId(captureId!!).parseStatus,
        )

        // 5. The transaction.
        val txn = ParseFixtures.txnForCapture(context, captureId)
        assertNotNull("The capture produced no transaction", txn)
        requireNotNull(txn)
        assertEquals(sen, txn.amountSen)
        assertEquals(TxnState.COMMITTED, txn.state)
        assertNull("A committed row must carry no review reason", txn.pendingReason)
        assertEquals(bank, txn.sourcePackage)
        // Spec 5.4: preserved as parsed, and the cleanup lands on the display
        // column. The lower-case brand is not decoration -- it is the one shape
        // `Merchant.display(Merchant.clean(raw))` destroyed.
        assertEquals("foodpanda KLCC", txn.merchantRaw)
        assertEquals("foodpanda KLCC", txn.merchantDisplay)
        assertTrue("txn.category_id is not a real row", txn.categoryId > 0)
    }

    /**
     * The screen offers this source for enabling in the first place.
     *
     * Spec 9.6's lower list is the only route to a bank the pack knows about but
     * `PackageManager` will not confirm -- which, without the package-visibility
     * permission this app will not ship, is every bank on API 30+ outside
     * `<queries>`. A source that cannot be found on the screen cannot be enabled,
     * and the test above would be proving the chain from a step no user can reach.
     */
    @Test
    fun theSourceIsOfferedOnTheScreenBeforeItIsEnabled() {
        runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = notification(
                    "Payment of RM1.00 to $marker successful",
                    id = 8_103,
                    tag = "$marker-seen",
                    at = base - 9_000L,
                ),
                arrival = Arrival.POSTED,
                now = base - 9_000L,
            )
        }

        val viewModel = SourcesViewModel(context, scope)
        viewModel.refresh()
        val state = awaitLoaded(viewModel)
        assertTrue(
            "The pack has rules for $bank and the device has heard from it, but " +
                "neither list on the allow-list screen offers it. Nothing else " +
                "in the app can enable a source. Screen was: $state",
            (state.suggested + state.seenNotCaptured).any { it.pkg == bank },
        )
    }

    private fun notification(text: String, id: Int, tag: String, at: Long) =
        CaptureFixtures.posted(
            CaptureFixtures.notification(
                context,
                title = "Touch 'n Go $marker",
                text = text,
            ),
            pkg = bank,
            id = id,
            tag = tag,
            postTime = at,
        )

    private fun cancelStageTwo() {
        // These tests drive the worker by hand; anything the listener really
        // scheduled must not wake up and write to the shared database.
        CaptureFixtures.cancelStageTwo(context)
    }

    private fun awaitLoaded(viewModel: SourcesViewModel): SourcesState {
        CaptureFixtures.waitForValue { viewModel.state.value.takeIf { it.loaded } }
            ?: throw AssertionError("The allow-list screen never finished its first read")
        return viewModel.state.value
    }

}
