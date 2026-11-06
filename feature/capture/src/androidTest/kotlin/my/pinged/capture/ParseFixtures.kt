package my.pinged.capture

import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import android.content.Context
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.RawCapture
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.RuleMatcher

/**
 * Shared setup for the stage-two instrumented tests.
 *
 * Like `CaptureFixtures`, these run against the real encrypted database the
 * app ships. Nothing is substituted except the pack and the clock, and only
 * where a test needs a rule the bundled pack does not contain.
 *
 * **The database is not deleted between tests, deliberately.** It is shared
 * with every other test in this APK and it survives the run, so isolation
 * comes from each test using its own `sbn_key`, its own amount and its own
 * marker text rather than from a clean slate. Deleting the file under
 * `Graph`'s open handle would be worse than untidy: the handle stays open, the
 * next statement writes to a deleted inode, and the failure surfaces somewhere
 * else entirely.
 */
internal object ParseFixtures {

    /** Two packages the bundled pack actually has rules for. */
    const val TNG = "my.com.tngdigital.ewallet"
    const val MAE = "com.maybank2u.life"

    /** A package the bundled pack knows nothing about. */
    const val SYNTHETIC = "com.example.syntheticbank"

    /**
     * **This does not seed the categories, and must not start again.**
     *
     * It used to, guarded on `countAll() == 0`, and that one line is why this
     * suite was green against an app that could never write a transaction:
     * `Seed.categories()` had no production caller at all, so the shipped APK
     * left `category` empty and `ParseWorker` retried for ever on
     * `requireUncategorizedId`. `DatabaseFactory.build` seeds now, which is
     * what these tests should have been exercising all along; `FreshInstallTest`
     * is the test that fails if it stops.
     *
     * The allow-list rows below stay, and the distinction is the point: the
     * allow-list is default-deny user consent (spec 11.1) that a settings
     * screen writes, so a test standing in for the user is honest. The seeded
     * categories are an app invariant no user action creates.
     */
    fun prepare(context: Context) {
        Databases.captureSourceDao(context).let { sources ->
            listOf(TNG, MAE, SYNTHETIC).forEach { CaptureFixtures.allowList(context, it, true) }
            sources.setLabel(TNG, "Touch 'n Go eWallet")
            sources.setLabel(MAE, "Maybank MAE")
        }

        // Anything the listener scheduled for real must not run against the
        // database while these tests drive the worker by hand.
        CaptureFixtures.cancelStageTwo(context)
    }

    /**
     * Inserts a `raw_capture` row the way stage one would have.
     *
     * `content_hash` is not hand-written: it goes through the same
     * [NotificationFields.normalizedForHash] and [ContentHash.of] the listener
     * uses, so a test cannot accidentally give two identical notifications
     * different identities -- which would make every duplicate assertion here
     * pass for the wrong reason.
     *
     * [text] is nullable and [bigText] exists because a notification with only
     * an expanded body is a real shape and a rule keys on it: the bundled MAE
     * pack scopes its main rule to `text` and falls back to the concatenation
     * when there is none. Without both, that fallback is unreachable from a
     * test.
     */
    fun insertCapture(
        context: Context,
        text: String?,
        title: String? = null,
        pkg: String = TNG,
        sbnKey: String,
        postedAt: Long,
        whenMillis: Long? = null,
        arrival: Arrival = Arrival.POSTED,
        bigText: String? = null,
    ): Long {
        val fields = NotificationFields(
            sourcePackage = pkg,
            postedAt = postedAt,
            whenMillis = whenMillis,
            sbnKey = sbnKey,
            notifId = 1,
            notifTag = null,
            userHandle = 0,
            channelId = null,
            flags = 0,
            title = title,
            text = text,
            bigText = bigText,
            subText = null,
        )
        return Databases.rawCaptureDao(context).insert(
            RawCapture(
                sourcePackage = fields.sourcePackage,
                postedAt = fields.postedAt,
                whenMillis = fields.whenMillis,
                capturedAt = fields.postedAt,
                sbnKey = fields.sbnKey,
                notifId = fields.notifId,
                notifTag = fields.notifTag,
                userHandle = fields.userHandle,
                channelId = fields.channelId,
                flags = fields.flags,
                arrival = arrival,
                title = fields.title,
                text = fields.text,
                bigText = fields.bigText,
                subText = fields.subText,
                extrasJson = null,
                contentHash = ContentHash.of(
                    fields.sourcePackage,
                    fields.userHandle,
                    fields.normalizedForHash,
                ),
            ),
        )
    }

    /** A corpus fixture's package, title and notification body. */
    data class Corpus(val pkg: String, val title: String?, val body: String)

    /**
     * `core/parse`'s fixture [name], read as `:core:parse`'s own corpus test
     * reads it: a header block, a blank line, then the body.
     */
    fun corpus(name: String): Corpus {
        val text = requireNotNull(ParseFixtures::class.java.getResource("/fixtures/$name")) {
            "no fixture named '$name' on the androidTest classpath"
        }.readText().replace("\r\n", "\n")
        val (header, body) = text.split("\n\n", limit = 2)
        val h = header.lineSequence()
            .filter { it.contains(':') && !it.startsWith("#") }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return Corpus(pkg = requireNotNull(h["package"]), title = h["title"], body = body.trim())
    }

    /** Inserts corpus fixture [name] as a capture, as [insertCapture] does. */
    fun insertCorpus(context: Context, name: String, sbnKey: String, postedAt: Long): Long {
        val fixture = corpus(name)
        return insertCapture(
            context, text = fixture.body, title = fixture.title, pkg = fixture.pkg,
            sbnKey = sbnKey, postedAt = postedAt,
        )
    }

    /**
     * A [ParsePass] over the real database, built by [ParsePass.over] as
     * `ParseWorker.drain` builds one: the bundled dictionary, the taught rules
     * and the check limit the device has stored, so a test that says nothing
     * about them is testing what ships. A suite that sets the limit resets it
     * in its `@After`.
     *
     * [dictionary], [categoryIds] and [sourceLabel] each replace
     * what would be read when given, and nothing is merged into them.
     */
    fun pass(
        context: Context,
        matcher: RuleMatcher = Graph.ruleMatcher(),
        maxRows: Int = ParsePass.MAX_ROWS_PER_RUN,
        onCaptureFinished: (Long) -> Unit = {},
        progress: ParsePass.Progress = ParsePass.Progress(),
        sourceLabel: ((String) -> String?)? = null,
        dictionary: List<my.pinged.parse.DictionaryEntry>? = null,
        categoryIds: Map<String, Long>? = null,
    ): ParsePass = ParsePass.over(
        db = Databases.shared(context),
        matcher = matcher,
        checkLimitSen = runBlocking { CheckLimit.sen(context) },
        progress = progress,
        maxRows = maxRows,
        onCaptureFinished = onCaptureFinished,
        dictionary = dictionary,
        categoryIds = categoryIds,
        sourceLabel = sourceLabel,
    )

    /**
     * A transaction for [captureId] written **without** marking the capture,
     * which is precisely the state a process killed between the two writes
     * used to leave behind.
     *
     * `commitCapture` is one `@Transaction`, so stage two can no longer reach
     * that interleaving at all -- which is why it has to be manufactured to be
     * tested. It is still reachable in the field: any row left this way by a
     * build that predates `commitCapture`, or by raw SQL, is on the user's
     * disk now.
     */
    fun orphanTransaction(context: Context, captureId: Long, amountSen: Long): Long {
        val at = System.currentTimeMillis()
        return Databases.txnDao(context).insert(
            my.pinged.data.entity.Txn(
                rawCaptureId = captureId,
                amountSen = amountSen,
                direction = Direction.EXPENSE,
                occurredAt = at,
                localDate = my.pinged.data.LocalDates.of(at),
                merchantRaw = null,
                merchantDisplay = null,
                merchantKey = null,
                categoryId = Databases.categoryDao(context).requireUncategorizedId(),
                sourcePackage = TNG,
                sourceLabel = null,
                confidence = Confidence.HIGH,
                state = my.pinged.data.entity.TxnState.COMMITTED,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    /**
     * An amount unique to this run, as sen and as the `RMx.yy` string a
     * notification would carry.
     *
     * Layer 2's first clause is an identical `amount_sen` within ten minutes from a
     * different package, and this database survives the run -- so a fixed amount
     * would let a transaction from the *previous* run satisfy that clause and turn
     * a committed row into a review item. Every value is well under spec 7.1's
     * default check limit, RM500.
     */
    fun uniqueAmount(): Pair<Long, String> {
        val sen = 10_000L + (System.nanoTime() % 30_000L)
        return sen to String.format(java.util.Locale.ROOT, "RM%d.%02d", sen / 100, sen % 100)
    }

    /**
     * Stage two through the real worker. Hoisted because two classes drive it
     * and the incantation breaks together when the test artifact changes.
     */
    fun runWorker(context: Context): androidx.work.ListenableWorker.Result =
        androidx.work.testing.TestListenableWorkerBuilder<ParseWorker>(context)
            .build().startWork().get()

    /**
     * The transaction a capture produced, or null.
     *
     * `recent()` would be shorter and wrong: it orders by `occurred_at`, and these
     * tests deliberately post captures minutes in the past, so a row from an
     * earlier run can sort ahead of the row under test.
     */
    fun txnForCapture(context: Context, captureId: Long): my.pinged.data.entity.Txn? {
        val id = Databases.rawCaptureDao(context).txnIdForCapture(captureId) ?: return null
        return Databases.txnDao(context).byId(id)
    }
}
