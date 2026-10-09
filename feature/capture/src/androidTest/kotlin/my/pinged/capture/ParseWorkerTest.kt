package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import my.pinged.data.IntegrityStore
import my.pinged.data.LocalDates
import my.pinged.data.Seed
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage two through the real worker, against the real encrypted database and
 * the pack that ships.
 *
 * The dedup layers and the confidence gate get their own files; this one is
 * about the worker itself -- that it is constructible the way WorkManager
 * constructs it, that it finds the bundled pack, that it drains the queue, and
 * that running it twice does not create money.
 */
@RunWith(AndroidJUnit4::class)
class ParseWorkerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val txns = Databases.txnDao(context)

    /** Unique per test method, so a re-run of this suite cannot collide with itself. */
    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
    }

    private fun runWorker(): ListenableWorker.Result = ParseFixtures.runWorker(context)

    /**
     * The whole job, once, on a payload stage one would actually have written.
     *
     * Deliberately not a hand-built [my.pinged.data.entity.RawCapture]: a real
     * `Notification` with the amount in `EXTRA_TEXT`, wrapped in a real
     * `StatusBarNotification`, put through [CaptureIngest] -- the allow-list gate,
     * `getCharSequence` extraction, `sbn.postTime`, `content_hash` -- and only then
     * through the worker.
     */
    @Test
    fun aRealNotificationBecomesACommittedTransactionOfTheRightSen() {
        val postedAt = base - 5_000L
        val captureId = runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = CaptureFixtures.posted(
                    CaptureFixtures.notification(
                        context,
                        title = "Touch 'n Go $marker",
                        text = "Payment of RM32.00 to 99 SPEEDMART successful",
                    ),
                    pkg = ParseFixtures.TNG,
                    id = 4_242,
                    tag = marker,
                    postTime = postedAt,
                ),
                arrival = Arrival.POSTED,
                now = postedAt,
            )
        }
        assertNotNull("Stage one stored nothing, so there is no stage two to test", captureId)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val capture = captures.byId(captureId!!)
        assertEquals(ParseStatus.MATCHED, capture.parseStatus)
        assertEquals("tng-payment-v1", capture.matchedRuleId)
        assertEquals(Graph.ruleMatcher().packVersion, capture.packVersion)
        assertNull(capture.duplicateOfId)

        val txn = ParseFixtures.txnForCapture(context, captureId)
        assertNotNull("The capture produced no transaction", txn)
        requireNotNull(txn)

        assertEquals("RM32.00 must be 3200 sen and nothing else", 3_200L, txn.amountSen)
        assertEquals("MYR", txn.currency)
        assertEquals(captureId, txn.rawCaptureId)
        // Spec 5.4: merchant_raw is preserved as parsed; the cleanup lands on
        // merchant_display.
        assertEquals("99 SPEEDMART", txn.merchantRaw)
        assertEquals("99 Speedmart", txn.merchantDisplay)
        assertEquals(postedAt, txn.occurredAt)
        assertEquals(LocalDates.of(postedAt), txn.localDate)
        assertEquals(ParseFixtures.TNG, txn.sourcePackage)
        assertEquals("Touch 'n Go eWallet", txn.sourceLabel)
        assertEquals(TxnState.COMMITTED, txn.state)
        assertNull("A committed row must carry no review reason", txn.pendingReason)
        assertEquals(false, txn.isExcluded)
        assertNull(txn.exclusionReason)
    }

    /**
     * A merchant that capitalised itself keeps its capitalisation, through the
     * production composition rather than a direct call to the title-caser.
     *
     * Spec 5.4 title-cases "only when the raw string is entirely uppercase", and
     * that guard could not fail to pass while [ParsePass] called
     * `Merchant.display(Merchant.clean(raw))` -- `clean` uppercases, because spec
     * 6.1 keys learned rules off its output. `foodpanda KLCC` was stored as
     * `Foodpanda Klcc`.
     *
     * Asserting against `Merchant.displayFor` directly would prove nothing: the old
     * `display` was also correct when called on the raw string, and the bug was
     * entirely in what [ParsePass] handed it.
     */
    @Test
    fun aMerchantThatCapitalisedItselfKeepsItsCapitalisation() {
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val postedAt = base - 6_000L
        val captureId = runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = CaptureFixtures.posted(
                    CaptureFixtures.notification(
                        context,
                        title = "Touch 'n Go $marker",
                        text = "Payment of $rm to foodpanda KLCC successful",
                    ),
                    pkg = ParseFixtures.TNG,
                    id = 4_243,
                    tag = "$marker-mixed",
                    postTime = postedAt,
                ),
                arrival = Arrival.POSTED,
                now = postedAt,
            )
        }
        assertNotNull("Stage one stored nothing, so there is no stage two to test", captureId)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val txn = ParseFixtures.txnForCapture(context, captureId!!)
        assertNotNull("The capture produced no transaction", txn)
        requireNotNull(txn)

        assertEquals(sen, txn.amountSen)
        // Spec 5.4 and spec 4: merchant_raw is preserved exactly as parsed.
        assertEquals("foodpanda KLCC", txn.merchantRaw)
        assertEquals(
            "The ledger shows a name the notification never said. This is the " +
                "value ParsePass writes, so a display path that only preserves " +
                "case when called directly does not fix it.",
            "foodpanda KLCC",
            txn.merchantDisplay,
        )
    }

    /**
     * The bundled pack is reached as a classpath resource, not as an asset.
     *
     * If this fails, nothing else in stage two works and it fails in the most
     * misleading way available: every capture is recorded `UNMATCHED`, which
     * reads as a rule gap on spec 5.6's authoring screen rather than as a
     * packaging mistake.
     */
    @Test
    fun theBundledPackIsOnTheClasspath() {
        val matcher = Graph.ruleMatcher()
        assertTrue("pack_version should be a real pack version", matcher.packVersion > 0)
        assertTrue(
            "The bundled pack must have rules for the packages it names",
            matcher.match(
                ParseFixtures.TNG,
                title = null,
                text = "Payment of RM1.00 to A SHOP successful",
                bigText = null,
            ) is my.pinged.parse.MatchOutcome.Matched,
        )
    }

    /**
     * The invariant that matters most here: a capture stuck at `NEW` is a
     * silently lost transaction, which is the failure the two-stage design
     * exists to prevent.
     *
     * The three payloads cover the three ways a capture can end without a
     * transaction, so none of them may leave the queue occupied.
     */
    @Test
    fun everyCaptureLeavesTheNewState() {
        val matched = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Payment of RM1.11 to A SHOP successful",
            sbnKey = "$marker-a",
            postedAt = base - 900_000L,
        )
        val rejected = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "TAC 123456 for RM250.00 transfer. Do not share.",
            sbnKey = "$marker-b",
            postedAt = base - 800_000L,
        )
        val unmatched = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Your FD of RM5,000.00 has matured.",
            sbnKey = "$marker-c",
            postedAt = base - 700_000L,
        )

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(ParseStatus.MATCHED, captures.byId(matched).parseStatus)
        assertEquals(ParseStatus.REJECTED, captures.byId(rejected).parseStatus)
        assertEquals("tng-otp", captures.byId(rejected).rejectedByRuleId)
        assertEquals(ParseStatus.UNMATCHED, captures.byId(unmatched).parseStatus)

        assertNull(
            "A rejected capture must produce no transaction",
            captures.txnIdForCapture(rejected),
        )
        assertNull(
            "An unmatched capture must produce no transaction",
            captures.txnIdForCapture(unmatched),
        )
        assertEquals(
            "The queue must be empty after a successful run",
            emptyList<Long>(),
            captures.claimNext(200).map { it.id },
        )
    }

    /**
     * Running the worker twice must not create money.
     *
     * The transaction id is asserted rather than a count, because that is the
     * thing that must not change: a second row for the same capture is what
     * the unique index on `txn.raw_capture_id` refuses, and a *replacement*
     * row would keep the count right while losing whatever the user had done
     * to the first one.
     */
    @Test
    fun aRerunDoesNotDoubleCount() {
        val id = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Payment of RM2.22 to RERUN SHOP successful",
            sbnKey = "$marker-rerun",
            postedAt = base - 600_000L,
        )

        runWorker()
        val first = captures.txnIdForCapture(id)
        assertNotNull("The first run must produce a transaction", first)
        val before = txns.countAll()

        runWorker()

        assertEquals(first, captures.txnIdForCapture(id))
        assertEquals("The second run must write nothing", before, txns.countAll())
        assertEquals(ParseStatus.MATCHED, captures.byId(id).parseStatus)
    }

    /**
     * `notification.when` displaces `sbn.postTime` only when it is plausible, and
     * "plausible" is one-sided. The plan's sketch used
     * `abs(when - postTime) < 7 days`, which accepts a `when` a week in the
     * *future* -- dating a transaction into a month that has not happened, the same
     * class of bug as spec 4's zero `when` dating one to 1970.
     */
    @Test
    fun aFutureWhenIsIgnoredAndAModestlyEarlierOneIsTrusted() {
        val postedAt = base - 400_000L

        val trusted = ParseFixtures.insertCapture(
            context,
            title = "$marker-when-ok",
            text = "Payment of RM3.33 to WHEN OK successful",
            sbnKey = "$marker-when-ok",
            postedAt = postedAt,
            whenMillis = postedAt - 2 * 60 * 60 * 1000L,
        )
        val future = ParseFixtures.insertCapture(
            context,
            title = "$marker-when-future",
            text = "Payment of RM3.34 to WHEN FUTURE successful",
            sbnKey = "$marker-when-future",
            postedAt = postedAt,
            whenMillis = postedAt + 2 * 60 * 60 * 1000L,
        )
        val ancient = ParseFixtures.insertCapture(
            context,
            title = "$marker-when-old",
            text = "Payment of RM3.35 to WHEN OLD successful",
            sbnKey = "$marker-when-old",
            postedAt = postedAt,
            whenMillis = postedAt - 5 * 24 * 60 * 60 * 1000L,
        )

        runWorker()

        assertEquals(postedAt - 2 * 60 * 60 * 1000L, occurredAt(trusted))
        assertEquals("A `when` in the future must not date the transaction", postedAt, occurredAt(future))
        assertEquals("A `when` days earlier must not date the transaction", postedAt, occurredAt(ancient))
    }

    /**
     * Task 13's headline behaviour, and the only production caller of it: this
     * worker. Commenting that one line out leaves every other test in this
     * module green, so without this the weekly check could stop shipping and
     * nothing would say so.
     *
     * The timestamp rather than the verdict, because the database these tests
     * share is healthy and `damaged` is false either way. That the seam's
     * default is the real pragma and not a stub is
     * [PeriodicIntegrityTest.theDefaultCheckIsTheRealPragma].
     */
    @Test
    fun aRunChecksTheDatabaseForDamage() = runBlocking {
        IntegrityStore.forget(context)
        assertEquals("nothing should have been checked yet", 0L, IntegrityStore.lastCheckAt(context))

        runWorker()

        assertNotEquals(
            "stage two ran without checking the database, and no other caller ever does",
            0L,
            IntegrityStore.lastCheckAt(context),
        )
    }

    /**
     * **The placement, not just the call.** The check sits before the drain
     * because a database damaged enough to make the drain throw is exactly the
     * one whose verdict has to reach the settings screen, and moved after
     * `ParsePass(...).run()` it never runs on one. Measured on this tree: with
     * the call moved there, this case is the only one of the module's 100 that
     * fails.
     *
     * The drain is made to throw by the failure `doWork`'s own catch already
     * names -- an absent seed. `ParsePass`'s `uncategorizedId` argument is
     * `requireUncategorizedId()`, the first statement after the check, and
     * spec 7.1 leaves it nowhere to file an unknown merchant without it. The
     * name goes back in a `finally`: this database is shared with every other
     * class in this APK.
     *
     * **An undrained capture is what says the drain threw, and `retry()` alone
     * does not.** `retry()` is also `doWork`'s ordinary answer for
     * `summary.remaining || swept.more`, so on its own it rules out
     * `success()` and nothing else -- a drain that finished normally with work
     * left over satisfies it just as well. The capture below is posted a
     * minute into the past, so `claimNext`'s `ORDER BY posted_at ASC` puts it
     * in the first batch of the 2,000 a run takes: any drain that ran at all
     * reached it. Still `NEW` afterwards means [ParsePass] was never
     * constructed, which is the case this test is named for.
     *
     * **It is a capture the bundled pack cannot match, and that is not
     * incidental.** A capture whose *parse* fails also stays `NEW` -- ParsePass
     * says so where it catches, because an unfinished row is the honest record
     * -- so a probe that needed the seed would still look undrained on the day
     * `requireUncategorizedId` gains a fallback: the drain would run, fail this
     * row on the bad id, count it in `summary.failed`, and answer `retry()` for
     * the ordinary reason. An unmatched capture is marked `UNMATCHED` by a
     * `mark` that never touches the category, so it leaves `NEW` on any drain
     * that runs at all. Measured on this tree: with `requireUncategorizedId`
     * replaced by `uncategorizedIdOrNull() ?: 1L` and a second capture behind
     * this one to keep `remaining` true, the two assertions this test had
     * before still pass and the status assertion is the only one that fails.
     */
    @Test
    fun aRunWhoseDrainThrowsStillRecordsACheck() = runBlocking {
        IntegrityStore.forget(context)
        val renamed = "Uncategorized $marker"
        val captureId = ParseFixtures.insertCapture(
            context,
            text = "Nothing in the bundled pack matches this, $marker",
            pkg = ParseFixtures.SYNTHETIC,
            sbnKey = "drain-throws-$marker",
            postedAt = base - 60_000L,
        )
        val (result, statusAfter) = try {
            renameCategory(from = Seed.UNCATEGORIZED, to = renamed)
            runWorker() to captures.byId(captureId).parseStatus
        } finally {
            renameCategory(from = renamed, to = Seed.UNCATEGORIZED)
            // This database is shared with every other class in this APK, and
            // a row left at `NEW` with an old `posted_at` sorts ahead of
            // theirs -- `ParseInterruptionTest` stops its pass after a fixed
            // number of captures and would be stopping on this one.
            deleteCapture(captureId)
        }

        assertEquals(
            "the drain finished, so nothing here is about a run that threw",
            ListenableWorker.Result.retry(),
            result,
        )
        assertEquals(
            "the drain reached the queue and parsed it, so the retry above is the " +
                "ordinary `remaining || swept.more` answer and this case proves nothing",
            ParseStatus.NEW,
            statusAfter,
        )
        assertNotEquals(
            "a run whose drain threw recorded no check, so the damage that made it " +
                "throw is the damage the settings screen never hears about",
            0L,
            IntegrityStore.lastCheckAt(context),
        )
    }

    private fun categoryId(name: String): Long =
        Databases.categoryDao(context).all().first { it.name == name }.id

    /** A TnG travel-pass capture, which the bundled dictionary files by its `MY50` entry. */
    private fun insertMy50Capture(postedAt: Long, rm: String, sbnKey: String): Long =
        ParseFixtures.insertCapture(
            context = context,
            title = "Payment successful",
            text = "Travel Pass: You have paid $rm for your My50 Pass. View your updated pass details now.",
            sbnKey = sbnKey,
            postedAt = postedAt,
        )

    /**
     * #46: a capture from a dictionary brand commits under the dictionary's
     * category, through the real worker and the pack that ships. The expected
     * id is read off the seeded `Transport` row, so a worker that wrote
     * Uncategorized, or any other category, fails here.
     */
    @Test
    fun aCaptureFromADictionaryBrandCommitsWithTheDictionarysCategory() {
        val (_, rm) = ParseFixtures.uniqueAmount()
        val captureId = insertMy50Capture(base - 7_000L, rm, "$marker-my50")

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, captureId))
        assertEquals("MY50 PASS", txn.merchantKey)
        assertEquals(TxnState.COMMITTED, txn.state)
        assertEquals(categoryId("Transport"), txn.categoryId)
        assertNotEquals(Databases.categoryDao(context).requireUncategorizedId(), txn.categoryId)
    }

    /** #46: a merchant the dictionary does not know is committed Uncategorized. */
    @Test
    fun aCaptureWithNoDictionaryHitCommitsUncategorized() {
        val (_, rm) = ParseFixtures.uniqueAmount()
        val captureId = ParseFixtures.insertCapture(
            context = context,
            title = "Touch 'n Go $marker",
            text = "Payment of $rm to foodpanda KLCC successful",
            sbnKey = "$marker-nohit",
            postedAt = base - 8_000L,
        )

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, captureId))
        assertEquals(Databases.categoryDao(context).requireUncategorizedId(), txn.categoryId)
    }

    /**
     * #46: an entry whose category the user deleted is skipped, so its
     * merchants fall through to Uncategorized rather than to an id that is
     * gone (which the foreign key would refuse, stranding the capture at NEW).
     *
     * A pass of its own over a dictionary the test writes, naming a category
     * the test creates, because deleting a *seeded* category would fail on a
     * phone that holds real transactions in it. The control entry names a
     * seeded category that stays, so a pass that ignored the dictionary
     * altogether cannot satisfy both halves.
     */
    @Test
    fun anEntryWhoseCategoryWasDeletedFallsThroughToUncategorized() {
        val categories = Databases.categoryDao(context)
        val doomed = "ZZ doomed $marker"
        categories.insertAll(listOf(my.pinged.data.entity.Category(name = doomed, iconKey = "circle-dashed", sortOrder = 99)))
        categories.deleteIfUnused(categoryId(doomed))

        val (_, kept) = ParseFixtures.uniqueAmount()
        val keptKey = "ZZKEPT$marker"
        val goneKey = "ZZGONE$marker"
        val keptCapture = ParseFixtures.insertCapture(
            context = context, title = "Touch 'n Go", sbnKey = "$marker-kept", postedAt = base - 9_000L,
            text = "Payment of $kept to $keptKey successful",
        )
        val goneCapture = ParseFixtures.insertCapture(
            context = context, title = "Touch 'n Go", sbnKey = "$marker-gone", postedAt = base - 9_500L,
            text = "Payment of ${ParseFixtures.uniqueAmount().second} to $goneKey successful",
        )

        ParseFixtures.pass(
            context,
            dictionary = listOf(
                my.pinged.parse.DictionaryEntry(keptKey, "Shopping", my.pinged.parse.MatchMode.EXACT),
                my.pinged.parse.DictionaryEntry(goneKey, doomed, my.pinged.parse.MatchMode.EXACT),
            ),
        ).run()

        assertEquals(
            "the control entry did not file, so the dictionary was never consulted",
            categoryId("Shopping"),
            requireNotNull(ParseFixtures.txnForCapture(context, keptCapture)).categoryId,
        )
        assertEquals(
            categories.requireUncategorizedId(),
            requireNotNull(ParseFixtures.txnForCapture(context, goneCapture)).categoryId,
        )
    }

    /** A learned rule as the teaching save writes it; a test's own, so no row is shared. */
    private fun learn(identity: String, category: String) {
        Databases.merchantRuleDao(context).insert(
            my.pinged.data.entity.MerchantRule(
                matchType = my.pinged.data.entity.MatchType.EXACT,
                pattern = identity,
                merchantDisplay = identity,
                categoryId = categoryId(category),
                origin = my.pinged.data.entity.RuleOrigin.LEARNED,
                priority = 100,
            ),
        )
    }

    private fun forget(identity: String) {
        Databases.shared(context).openHelper.writableDatabase
            .execSQL("DELETE FROM merchant_rule WHERE pattern = ?", arrayOf(identity))
    }

    /**
     * #48: a learned rule outranks the dictionary, through the real worker and
     * the pack that ships. `MY50 PASS` is a dictionary hit (Transport, the
     * test above); the rule says Shopping. The rule is removed afterwards
     * because the database outlives the test and `MY50 PASS` is shared with it.
     */
    @Test
    fun aLearnedRuleBeatsTheDictionary() {
        learn("MY50 PASS", "Shopping")
        try {
            val (_, rm) = ParseFixtures.uniqueAmount()
            val captureId = insertMy50Capture(base - 6_000L, rm, "$marker-my50-learned")

            assertEquals(ListenableWorker.Result.success(), runWorker())

            val txn = requireNotNull(ParseFixtures.txnForCapture(context, captureId))
            assertEquals("MY50 PASS", txn.merchantKey)
            assertEquals(categoryId("Shopping"), txn.categoryId)
            assertNotEquals("The dictionary answered over the rule", categoryId("Transport"), txn.categoryId)
        } finally {
            forget("MY50 PASS")
        }
    }

    /**
     * #48: a rule on a canonical key files a capture that arrives under a key
     * merged into it, and the capture's own key is left as it came. A pass of
     * its own over keys the test writes, so no shared merchant is touched; the
     * control capture is the canonical key's own, which the rule files directly.
     */
    @Test
    fun aRuleOnAnAliasResolvedIdentityFilesTheMergedKeysCapture() {
        val canonical = "ZZCANON$marker"
        val merged = "ZZMERGED$marker"
        Databases.merchantIdentityDao(context).insertAlias(
            my.pinged.data.entity.MerchantAlias(merchantKey = merged, canonicalKey = canonical),
        )
        learn(canonical, "Health")
        val viaMerged = ParseFixtures.insertCapture(
            context = context, title = "Touch 'n Go", sbnKey = "$marker-merged", postedAt = base - 10_000L,
            text = "Payment of ${ParseFixtures.uniqueAmount().second} to $merged successful",
        )
        val direct = ParseFixtures.insertCapture(
            context = context, title = "Touch 'n Go", sbnKey = "$marker-canon", postedAt = base - 10_500L,
            text = "Payment of ${ParseFixtures.uniqueAmount().second} to $canonical successful",
        )

        ParseFixtures.pass(context).run()

        assertEquals(
            "The canonical key's own capture was not filed by its rule",
            categoryId("Health"),
            requireNotNull(ParseFixtures.txnForCapture(context, direct)).categoryId,
        )
        val txn = requireNotNull(ParseFixtures.txnForCapture(context, viaMerged))
        assertEquals("The merged key kept its own key", merged, txn.merchantKey)
        assertEquals(categoryId("Health"), txn.categoryId)
    }

    /**
     * The order is learned, then dictionary, then none. A pass whose name map
     * does not hold the rule's category (the map is a snapshot, taken when the
     * pass is built) still files by the rule: the dictionary, which says
     * Shopping here, must not answer over a rule the user taught.
     */
    @Test
    fun aLearnedRuleWhoseCategoryIsMissingFromThePassSnapshotStillBeatsTheDictionary() {
        val key = "ZZSNAP$marker"
        learn(key, "Health")
        val everything = Databases.categoryDao(context).all().associate { it.name to it.id }
        val captureId = ParseFixtures.insertCapture(
            context = context, title = "Touch 'n Go", sbnKey = "$marker-snap", postedAt = base - 11_000L,
            text = "Payment of ${ParseFixtures.uniqueAmount().second} to $key successful",
        )

        ParseFixtures.pass(
            context,
            dictionary = listOf(my.pinged.parse.DictionaryEntry(key, "Shopping", my.pinged.parse.MatchMode.EXACT)),
            categoryIds = everything - "Health",
        ).run()

        assertEquals(categoryId("Health"), requireNotNull(ParseFixtures.txnForCapture(context, captureId)).categoryId)
    }

    private fun renameCategory(from: String, to: String) {
        Databases.shared(context).openHelper.writableDatabase
            .execSQL("UPDATE category SET name = ? WHERE name = ?", arrayOf(to, from))
    }

    private fun deleteCapture(id: Long) {
        Databases.shared(context).openHelper.writableDatabase
            .execSQL("DELETE FROM raw_capture WHERE id = ?", arrayOf<Any>(id))
    }

    private fun occurredAt(captureId: Long): Long {
        val txn = ParseFixtures.txnForCapture(context, captureId)
        assertNotNull("Capture $captureId produced no transaction", txn)
        return requireNotNull(txn).occurredAt
    }
}
