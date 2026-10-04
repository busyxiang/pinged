# Transfer and Recovery Milestone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the finished-but-unreachable `ExportJson` and `ImportJson` a user, and build spec §11.1's recovery paths so a damaged, key-less or deleted database has somewhere to go.

**Architecture:** A settings screen in `:feature:ledger` hosting export, restore, integrity check and delete, over three new pieces of plumbing: an integrity check in `:core:data`, a process-wide delete gate that reuses the existing `DatabaseUnavailableException` path, and a salvage read that tolerates unreadable pages. No new module, no schema change.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation 3, Room 2.8 over SQLCipher, AndroidX DataStore (Preferences), WorkManager, Storage Access Framework, JUnit4 + AndroidX instrumented tests.

**Spec:** [`docs/superpowers/specs/2026-09-18-pinged-transfer-milestone-design.md`](../specs/2026-09-18-pinged-transfer-milestone-design.md)

## Global Constraints

- **Schema v1 is frozen.** Nothing in this plan adds an entity, a column or an index. CI regenerates the schema and fails on drift.
- **Money is `Long` sen.** `BigDecimal` appears only in the string-to-sen step.
- **Never `\d`, `\w` or `\s` in a pattern.** `PackLoader` refuses `\d` at load.
- **No whole-row upserts on `capture_source` or `capture_day`.** Every mutation is a targeted UPDATE naming its own column.
- **Do not rename or move `PingedNotificationListener`.** The notification-access grant is keyed to its flattened `ComponentName`.
- **Commit messages are Conventional Commits.** `feat:` minor, `fix:` patch, `chore/docs/test/refactor/ci/build/perf:` no bump. A user-visible change must be `feat` or `fix`. Every commit message ends with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **A behaviour with no falsifying test has not landed.** Before claiming a fix works, revert it and confirm its own test fails for its own reason.
- **Measure rather than assume, and put the number in the comment.**
- **One KDoc per declaration.** Two `/** */` blocks in a row bind only the last.
- **Copy is drawn from the artboards** in `design/` where one exists: `Settings.dc.html`, `Export.dc.html`, `Wipe.dc.html`. Uppercase labels are uppercase in the string, not by text transform, so a screen reader says what is drawn.
- **Tests:** `./gradlew test` for JVM, `./gradlew connectedDebugAndroidTest` for instrumented (needs an emulator or phone). Instrumented tests for anything touching the database.

---

## File Structure

**`:core:data`**
- Create `core/data/src/main/kotlin/my/pinged/data/Integrity.kt` — runs `PRAGMA integrity_check` and classifies the answer.
- Create `core/data/src/main/kotlin/my/pinged/data/Wipe.kt` — deletes the database and its key behind the gate.
- Modify `core/data/src/main/kotlin/my/pinged/data/Databases.kt` — the delete gate; `reset` becomes production API.
- Modify `core/data/src/main/kotlin/my/pinged/data/DatabaseUnavailable.kt` — a third member of the family.

**`:feature:capture`**
- Create `feature/capture/src/main/kotlin/my/pinged/capture/CaptureCaches.kt` — public clear of everything this module memoizes.
- Modify `feature/capture/src/main/kotlin/my/pinged/capture/CaptureHealth.kt` — `forgetProcessMemo` gains a production caller.
- Modify `feature/capture/src/main/kotlin/my/pinged/capture/ParseWorker.kt` — the periodic integrity check.

**`:feature:ledger`**
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/TransferStore.kt` — when the last export and check happened.
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/Restore.kt` — import plus a restore's cache invalidation.
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/SalvageJson.kt` — the damage-tolerant read.
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsState.kt` — the state the screen renders.
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsViewModel.kt` — the state holder and every action.
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsScreen.kt` — the rows, from `Settings.dc.html`.
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/ExportSheet.kt` — from `Export.dc.html`, minus its CSV half.
- Create `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/WipeSheet.kt` — from `Wipe.dc.html`, with the typed confirmation.

**`:app`**
- Modify `app/src/main/kotlin/my/pinged/Destinations.kt` — a `Settings` destination.
- Modify `app/src/main/kotlin/my/pinged/MainActivity.kt` — the entry, the nudge branch, the recovery banner's button.

---

### Task 1: The integrity check

**Files:**
- Create: `core/data/src/main/kotlin/my/pinged/data/Integrity.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/IntegrityTest.kt`

**Interfaces:**
- Consumes: `PingedDatabase`, `DatabaseFactory` (existing).
- Produces: `Integrity.check(db: PingedDatabase): Integrity.Result`, with `Result.Ok` and `Result.Damaged(firstProblem: String)`. **Two cases, not three** — see the KDoc for why a pragma that throws is Damaged rather than a third state.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 11.1's corruption detection. Scheduling reasoning: design 2.1. */
@RunWith(AndroidJUnit4::class)
class IntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromAnEmptyDatabase() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun aHealthyDatabaseIsOk() {
        DatabaseFactory.build(context).useDb { db ->
            db.rawCaptureDao().insert(sampleCapture(hash = "healthy"))
            assertEquals(Integrity.Result.Ok, Integrity.check(db))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.IntegrityTest`
Expected: FAIL — `Unresolved reference 'Integrity'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package my.pinged.data

import android.database.sqlite.SQLiteException

/**
 * Spec 11.1's corruption check, and what separates a database worth salvaging
 * from one that is simply shut.
 *
 * **Not called at open**, which diverges from spec 11.1 deliberately. Measured
 * on an encrypted database, Pixel_10a emulator: 7ms at 1,000 captures, 121ms at
 * 10,000, 644ms at 50,000, against a 4ms open (`OpenTest`). `ParseWorker` runs
 * it weekly and the settings screen on demand.
 *
 * `integrity_check` and not `quick_check`: 121ms against 101ms at 10,000 rows,
 * and the cheaper pragma skips verifying index entries against table rows.
 */
object Integrity {
    /**
     * What the pragma said.
     *
     * **Two cases, and the absent third is the point.** This is only ever
     * called on a database that already opened, so "will not open" is decided
     * before we get here -- `DatabaseFactory.build` throws
     * `DatabaseKeyUnavailableException` or `DatabaseUnreadableException` and
     * the caller never reaches this. Anything that happens to the pragma
     * afterwards happened to a database that *is* open.
     */
    sealed interface Result {
        /** The pragma returned exactly "ok". */
        data object Ok : Result

        /**
         * Opened, and did not come back clean. Spec 11.1's "export whatever
         * still reads" state: one flipped byte left 2,200 of 5,000 captures
         * readable on a measured run.
         */
        data class Damaged(val firstProblem: String) : Result
    }

    /**
     * A pragma that throws is [Result.Damaged], not a third state.
     *
     * SQLCipher authenticates every page, so where the damaged byte lands
     * decides what this call does: on a page the check can walk past it
     * returns a problem list, and on one it cannot the pragma aborts with
     * `SQLiteException`. Both were observed on the same fixture at different
     * offsets. **The database is open either way, and rows before the damage
     * still read** -- so treating the abort as "unreadable" would withhold the
     * salvage offer from exactly the ledger that still has rows to give back.
     */
    fun check(db: PingedDatabase): Result = try {
        db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
            val lines = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            if (lines.singleOrNull() == "ok") Result.Ok else Result.Damaged(lines.firstOrNull().orEmpty())
        }
    } catch (thrown: SQLiteException) {
        Result.Damaged(thrown.message ?: "the integrity check could not complete")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.IntegrityTest`
Expected: PASS

- [ ] **Step 5: Add the falsifying test — a damaged database is not Ok**

Append to `IntegrityTest.kt`:

```kotlin
    /**
     * The state the whole recovery path exists for. Page 1 is left alone:
     * damaging it is indistinguishable from a wrong key, and the database then
     * refuses to open at all -- a different state.
     */
    @Test fun aDamagedDatabaseIsNotOk() {
        DatabaseFactory.build(context).useDb { db ->
            db.runInTransaction {
                repeat(10) { i ->
                    db.rawCaptureDao().insertAll(
                        List(500) { sampleCapture(hash = "d-${i * 500 + it}", postedAt = 1000L + i * 500 + it) },
                    )
                }
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        Databases.reset()
        damageMidFile(context.getDatabasePath(DatabaseFactory.NAME))

        DatabaseFactory.build(context).useDb { db ->
            val result = Integrity.check(db)
            assertTrue(
                "A database with a flipped byte reported $result, so nothing can " +
                    "ever reach the salvage path",
                result is Integrity.Result.Damaged,
            )
        }
    }
```

And add the helper to `core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt`:

```kotlin
/**
 * Flip one byte halfway into a database file.
 *
 * The offset matters, which is why this is not re-derived per test: page 1
 * holds the salt SQLCipher needs to open at all, so damaging it gives
 * `DatabaseUnreadableException` instead of a database that opens and fails.
 */
fun damageMidFile(file: java.io.File) {
    java.io.RandomAccessFile(file, "rw").use { raf ->
        val at = file.length() / 2
        raf.seek(at)
        val original = raf.readByte()
        raf.seek(at)
        raf.writeByte(original.toInt() xor 0xFF)
    }
}
```

Add `import org.junit.Assert.assertTrue` to the test file.

- [ ] **Step 6: Run both tests**

Run: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.IntegrityTest`
Expected: PASS, both.

- [ ] **Step 7: Falsify**

Change `check` to `return Result.Ok` unconditionally. Run the tests. Expected: `aDamagedDatabaseIsNotOk` FAILS with its own message about the salvage path. Restore the implementation.

- [ ] **Step 8: Commit**

```bash
git add core/data/src/main/kotlin/my/pinged/data/Integrity.kt core/data/src/androidTest/kotlin/my/pinged/data/IntegrityTest.kt core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt
git commit -m "feat: tell a damaged database from a shut one"
```

---

### Task 2: The delete gate

**Files:**
- Modify: `core/data/src/main/kotlin/my/pinged/data/DatabaseUnavailable.kt`
- Modify: `core/data/src/main/kotlin/my/pinged/data/Databases.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/DeleteGateTest.kt`

**Interfaces:**
- Consumes: `Databases.shared`, `Databases.reset`, `DatabaseUnavailableException` (existing).
- Produces: `DatabaseBeingDeletedException`, and `Databases.whileDeleting(block: () -> T): T`.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.DeleteGateTest`
Expected: FAIL — `Unresolved reference 'whileDeleting'`.

- [ ] **Step 3: Add the exception**

Append to `DatabaseUnavailable.kt`:

```kotlin
/**
 * Spec 11.3 is running and there is deliberately nowhere to put a capture.
 *
 * A member of this family rather than a type of its own, so that
 * `CaptureStorage.guarded` catches it with everything else: a notification
 * arriving mid-delete is dropped and the banner says capture stopped, which is
 * true and is what the user just asked for.
 */
class DatabaseBeingDeletedException(message: String) :
    DatabaseUnavailableException(message, null)
```

- [ ] **Step 4: Add the gate**

In `Databases.kt`, add the field and guard, and the new function:

```kotlin
    /**
     * Raised while spec 11.3's delete runs, so nothing reopens the file under it.
     *
     * `@Volatile` and not a lock: a reader arriving one instruction early gets a
     * database about to be deleted, which is what one arriving late gets too.
     */
    @Volatile private var deleting = false

    /**
     * Close the database, hold every other caller off, and run [block].
     *
     * Lifted in a `finally`: a gate left up by a throwing block is capture dead
     * for the life of the process, under a banner blaming a transfer that never
     * happened.
     */
    fun <T> whileDeleting(block: () -> T): T {
        deleting = true
        return try {
            reset()
            block()
        } finally {
            deleting = false
        }
    }
```

And at the top of `shared`:

```kotlin
    fun shared(context: Context): PingedDatabase {
        if (deleting) {
            throw DatabaseBeingDeletedException(
                "Pinged is deleting its database, so there is nowhere to put this " +
                    "right now. Capture resumes once the delete finishes.",
            )
        }
        return db ?: synchronized(this) {
            db ?: DatabaseFactory.build(context.applicationContext).also { db = it }
        }
    }
```

Update `reset`'s KDoc: it is no longer tests-only — `whileDeleting` calls it, and the close is the reason.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.DeleteGateTest`
Expected: PASS, all three.

- [ ] **Step 6: Falsify**

Remove the `finally` and lift the gate at the end of the `try` instead. Run. Expected: `theGateLiftsEvenWhenTheDeleteFails` FAILS. Restore.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/my/pinged/data/DatabaseUnavailable.kt core/data/src/main/kotlin/my/pinged/data/Databases.kt core/data/src/androidTest/kotlin/my/pinged/data/DeleteGateTest.kt
git commit -m "feat: stop capture reopening the database mid-delete"
```

---

### Task 3: Delete everything

**Files:**
- Create: `core/data/src/main/kotlin/my/pinged/data/Wipe.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/WipeTest.kt`

**Interfaces:**
- Consumes: `Databases.whileDeleting` (Task 2), `DatabaseKey.destroy`, `DatabaseFactory.NAME` (existing).
- Produces: `Wipe.everything(context: Context)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 11.3, asserted against the filesystem: a delete that reports success
 * having removed nothing passes any weaker test.
 */
@RunWith(AndroidJUnit4::class)
class WipeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startWithSomethingToDelete() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().insert(sampleCapture(hash = "doomed")) }
    }

    @Test fun nothingSurvives() {
        val main = context.getDatabasePath(DatabaseFactory.NAME)
        Wipe.everything(context)

        assertFalse("the database file is still there", main.exists())
        assertFalse("a -wal is still there", File("${main.path}-wal").exists())
        assertFalse("a -shm is still there", File("${main.path}-shm").exists())
        assertFalse("the wrapped key is still there", DatabaseKey.exists(context))
    }

    /** The app has to work afterwards, on a new key and an empty ledger. */
    @Test fun theAppOpensAgainAfterwards() {
        Wipe.everything(context)
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(0, db.rawCaptureDao().countAll())
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.WipeTest`
Expected: FAIL — `Unresolved reference 'Wipe'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package my.pinged.data

import android.content.Context

/**
 * Spec 11.3's delete everything.
 *
 * **The file goes, rather than `clearAllTables()` plus `VACUUM`.** Deleting it
 * makes the `VACUUM` moot and rotates the SQLCipher key in the same act. The
 * FTS shadow tables spec 11.3 names do not exist -- §15.2's search is not built.
 *
 * `CaptureCaches.clear` is the other half of a delete, and the caller makes
 * both: the DataStore is `internal` to `:feature:capture`.
 */
object Wipe {
    /**
     * Behind [Databases.whileDeleting], because the listener shares this
     * process and would otherwise rebuild the database between the file being
     * removed and the key being destroyed.
     */
    fun everything(context: Context) = Databases.whileDeleting {
        DatabaseKey.destroy(context)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.WipeTest`
Expected: PASS, both.

- [ ] **Step 5: Falsify**

Change `everything` to call `context.deleteDatabase(DatabaseFactory.NAME)` directly instead of `DatabaseKey.destroy`. Run. Expected: `nothingSurvives` FAILS on "the wrapped key is still there". Restore.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/my/pinged/data/Wipe.kt core/data/src/androidTest/kotlin/my/pinged/data/WipeTest.kt
git commit -m "feat: delete everything Pinged has recorded"
```

---

### Task 4: Clearing what capture memoizes

**Files:**
- Create: `feature/capture/src/main/kotlin/my/pinged/capture/CaptureCaches.kt`
- Modify: `feature/capture/src/main/kotlin/my/pinged/capture/CaptureHealth.kt` (KDoc on `forgetProcessMemo` only)
- Test: `feature/capture/src/androidTest/kotlin/my/pinged/capture/CaptureCachesTest.kt`

**Interfaces:**
- Consumes: `captureStore` (internal, same module), `CaptureHealth.forgetProcessMemo`, `Reparse` (existing).
- Produces: `CaptureCaches.clear(context: Context)` — a `suspend` function.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.entity.ParseStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gap `Reparse`'s KDoc records: the swept-pack mark does not travel in a
 * backup, so a restored backlog arrives stamped at old pack versions while the
 * mark says the current one is done. The v0.3.0 bug, by a second route.
 */
@RunWith(AndroidJUnit4::class)
class CaptureCachesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromAnEmptyDatabase() {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun clearingMakesTheNextSweepRunAgain() = runBlocking {
        val captures = Databases.rawCaptureDao(context)
        val packVersion = Graph.ruleMatcher().packVersion

        // A sweep that records the current version, so a second one is a no-op.
        Reparse.sweep(context, captures, packVersion)
        val second = Reparse.sweep(context, captures, packVersion)
        assertEquals("the mark did not take", 0, second.moved)

        captures.insert(
            my.pinged.data.entity.RawCapture(
                sourcePackage = "my.com.tngdigital.ewallet",
                postedAt = 1_000L, whenMillis = null, capturedAt = 1_000L,
                sbnKey = "restored", notifId = 1, notifTag = null, userHandle = 0,
                channelId = "txn", flags = 0, arrival = my.pinged.data.entity.Arrival.POSTED,
                title = null, text = "restored capture an old pack could not read",
                bigText = null, subText = null, extrasJson = null,
                contentHash = "restored", parseStatus = ParseStatus.UNMATCHED,
                packVersion = packVersion - 1,
            ),
        )

        CaptureCaches.clear(context)

        val afterClear = Reparse.sweep(context, captures, packVersion)
        assertEquals(
            "a restored capture stayed at its terminal status, so a restore " +
                "still lands a backlog nothing ever re-reads",
            1,
            afterClear.moved,
        )
    }
}
```

Check `RawCapture`'s constructor for the exact `packVersion` parameter name and position before running; adjust the literal to match the entity as declared.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:capture:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.capture.CaptureCachesTest`
Expected: FAIL — `Unresolved reference 'CaptureCaches'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package my.pinged.capture

import android.content.Context
import androidx.datastore.preferences.core.edit

/**
 * Everything this module remembers outside the database, dropped in one call.
 *
 * Two callers, both of which invalidate the database underneath it: spec 11.3's
 * delete and spec 11.2's restore. `Reparse`'s KDoc names the second as where
 * "the rest of a restore's cache invalidation will live".
 */
object CaptureCaches {
    /**
     * The whole store, not named keys. Every key in it describes a database
     * about to stop existing, and a named list is a list to forget to add to --
     * where the failure, a restored backlog never re-read, is silent.
     */
    suspend fun clear(context: Context) {
        context.captureStore.edit { it.clear() }
        CaptureHealth.forgetProcessMemo()
    }
}
```

- [ ] **Step 4: Update `forgetProcessMemo`'s KDoc**

It is no longer tests-only. Replace its KDoc's first line and drop `@VisibleForTesting`:

```kotlin
    /**
     * Forget every in-process memo, as a fresh process would.
     *
     * Two callers. [CaptureCaches.clear] needs it because clearing the durable
     * store while this process still holds `storageUnavailable = false` leaves
     * the two disagreeing until the process dies. Tests need it because the
     * throttles above are process-scoped by design, so the behaviour that
     * matters most -- what a *new* process does when it meets a flag an older
     * one wrote -- is otherwise unreachable from a suite that runs in one
     * process.
     */
    internal fun forgetProcessMemo() {
```

- [ ] **Step 5: Run the test**

Run: `./gradlew :feature:capture:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.capture.CaptureCachesTest`
Expected: PASS

- [ ] **Step 6: Falsify**

Make `clear` a no-op body. Run. Expected: FAIL on "a restored capture stayed at its terminal status". Restore.

- [ ] **Step 7: Commit**

```bash
git add feature/capture/src/main/kotlin/my/pinged/capture/CaptureCaches.kt feature/capture/src/main/kotlin/my/pinged/capture/CaptureHealth.kt feature/capture/src/androidTest/kotlin/my/pinged/capture/CaptureCachesTest.kt
git commit -m "feat: re-read a restored backlog instead of stranding it"
```

---

### Task 5: When the last export and check happened

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/TransferStore.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/TransferStoreTest.kt`

**Interfaces:**
- Consumes: AndroidX DataStore Preferences.
- Produces: `TransferStore.recordExport(context, at)`, `TransferStore.lastExportAt(context): Long`, `TransferStore.recordCheck(context, at, ok)`, `TransferStore.lastCheckAt(context): Long`, `TransferStore.damaged(context): Boolean`, `TransferStore.forget(context)` — all `suspend`, `0L` meaning never.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun forgetEverything() = runBlocking { TransferStore.forget(context) }

    @Test fun neverExportedReadsAsZero() = runBlocking {
        assertEquals(0L, TransferStore.lastExportAt(context))
    }

    @Test fun anExportIsRemembered() = runBlocking {
        TransferStore.recordExport(context, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, TransferStore.lastExportAt(context))
    }

    /** Two facts, not one: a check is not an export and must not read as one. */
    @Test fun aCheckDoesNotCountAsAnExport() = runBlocking {
        TransferStore.recordCheck(context, 1_700_000_000_000L, ok = true)
        assertEquals(0L, TransferStore.lastExportAt(context))
        assertEquals(1_700_000_000_000L, TransferStore.lastCheckAt(context))
    }

    /**
     * The verdict outlives the process, and has to. The check does not run at
     * open (design 2.1), so without a durable answer every cold start would
     * present a damaged database as healthy until something happened to look.
     */
    @Test fun aFailedCheckIsRememberedUntilOneSucceeds() = runBlocking {
        TransferStore.recordCheck(context, 1L, ok = false)
        assertTrue(TransferStore.damaged(context))

        TransferStore.recordCheck(context, 2L, ok = true)
        assertFalse("a clean check did not clear the damaged flag", TransferStore.damaged(context))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.transfer.TransferStoreTest`
Expected: FAIL — `Unresolved reference 'TransferStore'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package my.pinged.ledger.transfer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Its own file, not `capture_health`: that one is `internal` to
 * `:feature:capture` and is cleared wholesale when the database goes.
 */
internal val Context.transferStore by preferencesDataStore("transfer")

/**
 * When the user last took a copy of their data, and when it was last checked.
 *
 * Both default to `0L` for never, which the nudge (spec section 8) reads as
 * "overdue" -- the safe direction. A restore leaves them at never, because
 * `Backup`'s KDoc is explicit that nothing from DataStore travels in the file.
 */
object TransferStore {
    private val LAST_EXPORT_AT = longPreferencesKey("last_export_at")
    private val LAST_CHECK_AT = longPreferencesKey("last_check_at")

    /**
     * Set by a check that found problems, cleared by one that did not.
     *
     * Durable because the check does not run at open (design 2.1), so this is
     * the screen's only way to know between checks. Absent means "no bad news",
     * which is not "verified", and the screen says so.
     */
    private val DAMAGED = booleanPreferencesKey("damaged")

    suspend fun recordExport(context: Context, at: Long) {
        context.transferStore.edit { it[LAST_EXPORT_AT] = at }
    }

    suspend fun lastExportAt(context: Context): Long =
        context.transferStore.data.first()[LAST_EXPORT_AT] ?: 0L

    /** One edit for both facts, so a verdict can never disagree with its time. */
    suspend fun recordCheck(context: Context, at: Long, ok: Boolean) {
        context.transferStore.edit {
            it[LAST_CHECK_AT] = at
            if (ok) it.remove(DAMAGED) else it[DAMAGED] = true
        }
    }

    suspend fun lastCheckAt(context: Context): Long =
        context.transferStore.data.first()[LAST_CHECK_AT] ?: 0L

    suspend fun damaged(context: Context): Boolean =
        context.transferStore.data.first()[DAMAGED] ?: false

    /**
     * Tests, and spec 11.3's delete: a wiped app has never exported anything,
     * so leaving the timestamp behind would silence the nudge on an app with
     * nothing backed up.
     */
    suspend fun forget(context: Context) {
        context.transferStore.edit { it.clear() }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.transfer.TransferStoreTest`
Expected: PASS, all four.

- [ ] **Step 5: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/TransferStore.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/TransferStoreTest.kt
git commit -m "feat: remember when the last export and check happened"
```

---

### Task 6: Restore, with its cache invalidation

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/Restore.kt`
- Modify: `core/data/src/main/kotlin/my/pinged/data/DatabaseFactory.kt` (add `buildAt`)
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/RestoreTest.kt`

**`DatabaseFactory.buildAt(context, file)` does not exist yet.** Add it beside `build`, with the same
open-helper configuration and a caller-supplied file, and a KDoc saying its only caller is this
validation and why a probe database is the only honest validator. If factoring `build` cannot be done
in about twenty lines, stop and ask the controller rather than duplicating the configuration — a
probe that opens differently from the real database validates the wrong thing.

**Interfaces:**
- Consumes: `ImportJson.read` (existing), `CaptureCaches.clear` (Task 4), `Wipe.everything` (Task 3), `TransferStore.forget` (Task 5).
- Produces: `Restore.replaceEverything(context, input: InputStream, onProgress: (Int) -> Unit = {}, isCancelled: () -> Boolean = { false }): ImportReport`.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ImportJson.requireEmptyLedger` refuses a device holding anything, so a
 * restore onto a working phone is discard-then-import or nothing.
 */
@RunWith(AndroidJUnit4::class)
class RestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aBackupReplacesALedgerThatAlreadyHasRowsInIt() = runBlocking {
        val source = freshDatabase(context)
        val seeded = seedOneOfEverything(source)
        val bytes = exportBytes(source)
        source.close()

        // A device that has been capturing: ImportJson alone would refuse this.
        val busy = freshDatabase(context)
        busy.rawCaptureDao().insertAll(List(5) { bulkCapture(it) })
        busy.close()

        val report = Restore.replaceEverything(context, ByteArrayInputStream(bytes))

        assertTrue("nothing was restored", report.rows > 0)
        Databases.reset()
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(
                "the restored ledger does not match the file",
                seeded.captures.size,
                db.rawCaptureDao().countAll(),
            )
        }
    }
}
```

Add `import my.pinged.data.useDb` if the helper is not already resolvable from this package.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.transfer.RestoreTest`
Expected: FAIL — `Unresolved reference 'Restore'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package my.pinged.ledger.transfer

import android.content.Context
import java.io.InputStream
import my.pinged.capture.CaptureCaches
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.Wipe

/**
 * Spec 11.2's restore, as the single act it has to be.
 *
 * **Nothing is destroyed until the document is known to be a backup.** Staged
 * to the cache directory first, because SAF gives an `InputStream` that cannot
 * be rewound and the read happens twice: once to validate, once to write in.
 *
 * **Discard, then import, under one confirmation.** `ImportJson` refuses a
 * device holding any capture, transaction or learned rule -- both files number
 * their rows from one, so no merge rule avoids throwing something away -- and
 * in the key-gone state there is no database to write into at all. Importing
 * *over* never happens, so offering it as a peer action would be offering
 * something that always fails.
 *
 * The caller confirms before this runs.
 */
object Restore {
    suspend fun replaceEverything(
        context: Context,
        input: InputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ImportReport {
        val staged = File.createTempFile("restore", ".json", context.cacheDir)
        try {
            staged.outputStream().use { input.copyTo(it) }
            validate(context, staged)

            Wipe.everything(context)
            // Both stores, because both describe the ledger that just stopped
            // existing. `Reparse`'s KDoc names this as where a restore's cache
            // invalidation belongs: without it a restored backlog arrives
            // stamped at old pack versions and is never re-read.
            CaptureCaches.clear(context)
            TransferStore.forget(context)

            val db = DatabaseFactory.build(context)
            return try {
                staged.inputStream().use { ImportJson.read(db, it, onProgress, isCancelled) }
            } finally {
                // The process keeps its own handle; drop this one so
                // `Databases` rebuilds against the file the import just wrote.
                db.close()
                Databases.reset()
            }
        } finally {
            staged.delete()
        }
    }

    /**
     * Into a throwaway database, because that is the only honest test of
     * whether it restores: `ImportJson`'s guards -- the format version, the six
     * sections, `verifyReferences` -- need a real schema to run at all.
     */
    private fun validate(context: Context, staged: File) {
        val scratch = File(context.cacheDir, "restore-probe")
        scratch.deleteRecursively()
        scratch.mkdirs()
        val probe = DatabaseFactory.buildAt(context, File(scratch, "probe.db"))
        try {
            staged.inputStream().use { ImportJson.read(probe, it) }
        } finally {
            probe.close()
            scratch.deleteRecursively()
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.transfer.RestoreTest`
Expected: PASS

- [ ] **Step 5: Add the falsifying test for the cache invalidation**

This is the `v0.3.0` failure reproduced. Append to `RestoreTest.kt`:

```kotlin
    /**
     * Revert `CaptureCaches.clear` from `Restore` and this fails: the restored
     * captures stay at their terminal `parse_status` forever, which is exactly
     * what shipped in v0.2.1 and was found on a real phone.
     */
    @Test fun aRestoredBacklogIsReRead() = runBlocking {
        val packVersion = my.pinged.capture.Graph.ruleMatcher().packVersion

        // This device has already swept, so the mark says there is nothing to do.
        val here = freshDatabase(context)
        here.close()
        my.pinged.capture.Reparse.sweep(context, Databases.rawCaptureDao(context), packVersion)

        val source = freshDatabase(context)
        seedOneOfEverything(source)
        source.rawCaptureDao().insert(
            staleCapture(packVersion = packVersion - 1),
        )
        val bytes = exportBytes(source)
        source.close()

        Restore.replaceEverything(context, ByteArrayInputStream(bytes))

        val swept = my.pinged.capture.Reparse.sweep(
            context, Databases.rawCaptureDao(context), packVersion,
        )
        assertTrue(
            "the restored backlog was left at its terminal status, so a restore " +
                "lands captures nothing will ever re-read",
            swept.moved > 0,
        )
    }
```

Add `staleCapture` to `TransferFixtures.kt`:

```kotlin
/**
 * A capture an older pack could not read: revisitable, and stamped below the
 * current pack version, which is exactly what spec 5.5's sweep looks for.
 */
fun staleCapture(packVersion: Int) = RawCapture(
    sourcePackage = "my.com.tngdigital.ewallet",
    postedAt = 1_600_000_000_000L,
    whenMillis = null,
    capturedAt = 1_600_000_000_000L,
    sbnKey = "stale",
    notifId = 9_001,
    notifTag = null,
    userHandle = 0,
    channelId = "txn",
    flags = 0,
    arrival = Arrival.POSTED,
    title = "Touch 'n Go eWallet",
    text = "a wording the shipped pack could not read",
    bigText = null,
    subText = null,
    extrasJson = null,
    contentHash = "stale-capture",
    parseStatus = ParseStatus.UNMATCHED,
    packVersion = packVersion,
)
```

- [ ] **Step 6: Run both tests, then falsify**

Run the class. Expected: PASS, both. Then remove the `CaptureCaches.clear(context)` line from `Restore`. Expected: `aRestoredBacklogIsReRead` FAILS with its own message. Restore the line.

- [ ] **Step 7: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/Restore.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/RestoreTest.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/TransferFixtures.kt
git commit -m "feat: restore a backup onto a phone that is already capturing"
```

---

### Task 7: The state the settings screen renders

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsState.kt`
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsViewModel.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/SettingsStateTest.kt`

**Interfaces:**
- Consumes: `Integrity.check` (Task 1), `Wipe.everything` (Task 3), `CaptureCaches.clear` (Task 4), `TransferStore` (Task 5), `Restore.replaceEverything` (Task 6), `Databases`, `CaptureSourceDao` (existing). **Not `CaptureStorage.guarded`**: it collapses the whole `DatabaseUnavailableException` family into one branch, and this screen exists to tell `KEY_GONE` from `UNREADABLE`.
- Produces: `Storage` enum (`HEALTHY`, `DAMAGED`, `KEY_GONE`, `UNREADABLE`); `SettingsState`; `TransferJob` sealed interface; `SettingsViewModel` with `state: StateFlow<SettingsState>`, `refresh()`, `check()`, `restore(input: InputStream)`, `deleteEverything()`. Export lands in Task 10, where the document it writes to can be taken back.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.transfer.TransferStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 6's state table, asserted against the holder rather than the UI --
 * offering an action that cannot run is decided here, not in the drawing.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStateTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Before fun startClean() = runBlocking {
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        TransferStore.forget(app)
    }

    @Test fun aWorkingDatabaseWithNoBadNewsIsHealthy() = runBlocking {
        DatabaseFactory.build(app).useDb { }
        val model = SettingsViewModel(app)
        model.refresh()
        assertEquals(Storage.HEALTHY, model.state.value.storage)
    }

    /**
     * Healthy is the absence of bad news, and a recorded failure is bad news
     * that survives the process -- the check does not run at open.
     */
    @Test fun aRecordedFailureMakesItDamaged() = runBlocking {
        DatabaseFactory.build(app).useDb { }
        TransferStore.recordCheck(app, at = 1L, ok = false)
        val model = SettingsViewModel(app)
        model.refresh()
        assertEquals(Storage.DAMAGED, model.state.value.storage)
    }

    @Test fun checkingADamagedFileRecordsIt() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            db.runInTransaction {
                repeat(10) { i ->
                    db.rawCaptureDao().insertAll(
                        List(500) {
                            my.pinged.data.sampleCapture(
                                hash = "s-${i * 500 + it}", postedAt = 1000L + i * 500 + it,
                            )
                        },
                    )
                }
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        Databases.reset()
        damageMidFile(app.getDatabasePath(DatabaseFactory.NAME))

        val model = SettingsViewModel(app)
        model.check()
        assertEquals(Storage.DAMAGED, model.state.value.storage)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.SettingsStateTest`
Expected: FAIL — `Unresolved reference 'SettingsViewModel'`.

- [ ] **Step 3: Write `SettingsState.kt`**

```kotlin
package my.pinged.ledger.settings

import my.pinged.ledger.transfer.ImportReport

/**
 * Section 6's four states, which decide what the screen may offer.
 *
 * [HEALTHY] is **the absence of bad news, not a clean bill of health**: the
 * check does not run at open (design 2.1), so it says only that the database
 * opened and no check has failed. The screen states when it last looked.
 */
enum class Storage {
    HEALTHY,

    /** Opened, and a check found problems. Some rows still read (design 2.3). */
    DAMAGED,

    /** `DatabaseKeyUnavailableException`: intact file, destroyed key. */
    KEY_GONE,

    /**
     * `DatabaseUnreadableException`: "a corrupt file, a full disk or a storage
     * error". The middle one is transient, which is why this state's default
     * action is to wait rather than to delete anything.
     */
    UNREADABLE,
}

/** What one transfer action is doing, so every screen state has a drawing. */
sealed interface TransferJob {
    data object Idle : TransferJob

    /** [rows] is the running count both entry points report through `onProgress`. */
    data class Running(val what: String, val rows: Int) : TransferJob

    data class Exported(val rows: Int) : TransferJob

    data class Restored(val report: ImportReport) : TransferJob

    data class Checked(val ok: Boolean) : TransferJob

    /**
     * [corruption] is true when the database failed rather than the document --
     * the state that moves the screen to [Storage.DAMAGED] and offers salvage.
     */
    data class Failed(val message: String, val corruption: Boolean = false) : TransferJob
}

/**
 * Everything `Settings.dc.html` draws, plus the job running over it.
 *
 * [loaded] is false until the first read completes, so an empty screen is not a
 * lie -- as `SourcesState` does.
 */
data class SettingsState(
    val storage: Storage = Storage.HEALTHY,
    val sourcesOn: Int = 0,
    val lastExportAt: Long = 0L,
    val lastCheckAt: Long = 0L,
    val job: TransferJob = TransferJob.Idle,
    val loaded: Boolean = false,
)
```

- [ ] **Step 4: Write `SettingsViewModel.kt`**

```kotlin
package my.pinged.ledger.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.pinged.capture.CaptureCaches
import my.pinged.data.DatabaseKeyUnavailableException
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases
import my.pinged.data.Integrity
import my.pinged.data.Wipe
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ledger.transfer.ImportException
import my.pinged.ledger.transfer.Restore
import my.pinged.ledger.transfer.TransferStore

/**
 * Spec 9.5's screen, for the two sections this milestone builds.
 *
 * [viewModelScope] rather than WorkManager: surviving process death buys
 * nothing when a process death mid-export leaves a document that has to be
 * discarded anyway. This holder owns that discard.
 */
class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Reads what the rows display. **Runs no integrity check**: 121ms per
     * 10,000 captures (design 2.1) is not a cost to pay on arrival at a screen.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val storage = when {
            !opens() -> _state.value.storage
            TransferStore.damaged(app) -> Storage.DAMAGED
            else -> Storage.HEALTHY
        }
        _state.value = _state.value.copy(
            storage = storage,
            sourcesOn = if (storage == Storage.HEALTHY || storage == Storage.DAMAGED) {
                Databases.captureSourceDao(app).enabledCount()
            } else {
                0
            },
            lastExportAt = TransferStore.lastExportAt(app),
            lastCheckAt = TransferStore.lastCheckAt(app),
            loaded = true,
        )
    }

    /**
     * Files the two states that differ only by which exception came out, and
     * answers whether there is a database to ask anything else of.
     */
    private fun opens(): Boolean = try {
        Databases.shared(app)
        true
    } catch (thrown: DatabaseKeyUnavailableException) {
        _state.value = _state.value.copy(storage = Storage.KEY_GONE)
        false
    } catch (thrown: DatabaseUnavailableException) {
        _state.value = _state.value.copy(storage = Storage.UNREADABLE)
        false
    }

    fun check() = viewModelScope.launch(Dispatchers.IO) {
        _state.value = _state.value.copy(job = TransferJob.Running("Checking", 0))
        if (!opens()) {
            _state.value = _state.value.copy(job = TransferJob.Idle)
            return@launch
        }
        val result = Integrity.check(Databases.shared(app))
        val ok = result is Integrity.Result.Ok
        TransferStore.recordCheck(app, System.currentTimeMillis(), ok)
        _state.value = _state.value.copy(
            storage = if (ok) Storage.HEALTHY else Storage.DAMAGED,
            lastCheckAt = System.currentTimeMillis(),
            job = TransferJob.Checked(ok),
        )
    }

    fun restore(input: InputStream) = viewModelScope.launch(Dispatchers.IO) {
        _state.value = _state.value.copy(job = TransferJob.Running("Restoring", 0))
        try {
            val report = Restore.replaceEverything(
                app,
                input,
                onProgress = { done ->
                    _state.value = _state.value.copy(job = TransferJob.Running("Restoring", done))
                },
            )
            _state.value = _state.value.copy(job = TransferJob.Restored(report))
            refresh()
        } catch (thrown: ImportException) {
            // ImportJson reads inside one transaction, so this rolled back.
            _state.value = _state.value.copy(
                job = TransferJob.Failed("${thrown.message}\n\nNothing was imported."),
            )
            refresh()
        }
    }

    fun deleteEverything() = viewModelScope.launch(Dispatchers.IO) {
        _state.value = _state.value.copy(job = TransferJob.Running("Deleting", 0))
        Wipe.everything(app)
        CaptureCaches.clear(app)
        TransferStore.forget(app)
        _state.value = SettingsState(loaded = true)
        refresh()
    }
}
```

- [ ] **Step 5: Add this module's own `damageMidFile`**

`androidTest` source sets are **not** shared between Gradle modules, so Task 1's
helper in `:core:data` is not on `:feature:ledger`'s test classpath. Add a copy
to `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/TransferFixtures.kt`,
and make each copy's KDoc name the other so the duplication is visible rather
than discovered:

```kotlin
/**
 * Flip one byte halfway into a database file.
 *
 * A second copy of `:core:data`'s helper of the same name, because `androidTest`
 * source sets are not shared between modules and there is no test-fixtures
 * module here. Keep the two identical: the offset is the part that matters, and
 * a copy that drifts to page 1 silently tests a different state -- an open that
 * fails outright rather than a database that opens and fails its check.
 */
fun damageMidFile(file: java.io.File) {
    java.io.RandomAccessFile(file, "rw").use { raf ->
        val at = file.length() / 2
        raf.seek(at)
        val original = raf.readByte()
        raf.seek(at)
        raf.writeByte(original.toInt() xor 0xFF)
    }
}
```

The `SettingsStateTest` written in Step 1 resolves it from the same module with
no import.

- [ ] **Step 6: Add `enabledCount` to `CaptureSourceDao`**

The `4 ON` value the artboard draws. Add to `core/data/src/main/kotlin/my/pinged/data/dao/CaptureSourceDao.kt`:

```kotlin
    /**
     * The artboard's `4 ON`. A count and not the rows, because the settings row
     * shows a number and loading every allow-list row to call `.size` on it is
     * work the screen throws away.
     */
    @Query("SELECT COUNT(*) FROM capture_source WHERE enabled = 1")
    fun enabledCount(): Int
```

This adds a `@Query` only. Room's identity hash does not move for a new DAO method, so schema v1 stays frozen — confirm by running `./gradlew :core:data:test` and checking the schema JSON is unchanged in `git status`.

- [ ] **Step 7: Run the tests**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.SettingsStateTest`
Expected: PASS, all three.

- [ ] **Step 8: Falsify**

Make `refresh()` ignore `TransferStore.damaged` and always report `HEALTHY` when the database opens. Run. Expected: `aRecordedFailureMakesItDamaged` FAILS. Restore.

- [ ] **Step 9: Verify the schema did not move**

```bash
./gradlew :core:data:test
git status --porcelain core/data/schemas
```
Expected: no output from `git status` — the schema JSON is unchanged.

- [ ] **Step 10: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/settings core/data/src/main/kotlin/my/pinged/data/dao/CaptureSourceDao.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/TransferFixtures.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/SettingsStateTest.kt
git commit -m "feat: decide what the settings screen may offer"
```

---

### Task 8: The settings screen

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsScreen.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsViewModel`, `SettingsState`, `Storage` (Task 7); `theme` (`Paper`, `Card`, `Ink`, `Muted`, `Rule`, `Stamp`, `Display`, `Body`, `MonoLabel`, `Separator`, `CANNOT_READ_YOUR_DATA`).
- Produces: `SettingsScreen(viewModel, onBack, onOpenSources, onExport, onRestore, onDelete, modifier)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertDoesNotExist
import org.junit.Rule
import org.junit.Test

/**
 * The screen offers no row it cannot run: Export on a database that will not
 * open is a button whose only outcome is an error.
 */
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aHealthyLedgerOffersEverything() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, sourcesOn = 4, loaded = true),
                onOpenSources = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertIsDisplayed()
        compose.onNodeWithText("Check my data").assertIsDisplayed()
        compose.onNodeWithText("4 ON").assertIsDisplayed()
        compose.onNodeWithText("NEVER").assertIsDisplayed()
    }

    @Test fun aKeylessLedgerOffersNeitherExportNorCheck() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.KEY_GONE, loaded = true),
                onOpenSources = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertDoesNotExist()
        compose.onNodeWithText("Check my data").assertDoesNotExist()
        compose.onNodeWithText("Replace everything from a backup").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.SettingsScreenTest`
Expected: FAIL — `Unresolved reference 'SettingsRows'`.

- [ ] **Step 3: Write the screen**

```kotlin
package my.pinged.ledger.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.concurrent.TimeUnit
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Card
import my.pinged.ledger.theme.Display
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.Separator

/**
 * Spec 9.5, drawn from `design/Settings.dc.html`.
 *
 * **Only the rows with a screen behind them are here.** The artboard's six
 * others -- capture health, unread notifications, pack import, taught rules,
 * categories, the review threshold -- belong to milestones that do not exist,
 * and dead rows are worse than a short screen. `HOW THINGS GET SORTED` is
 * absent for the same reason and returns with the first row that fills it.
 *
 * No bottom navigation: the artboards draw a CHARTS tab that has no screen.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    // Once per foreground, for the reason `SourcesScreen` gives: the holder is
    // scoped to its NavEntry and survives a trip to another destination, so a
    // `LaunchedEffect` keyed on the holder would not re-read after a delete
    // performed from somewhere else.
    LifecycleResumeEffect(Unit) {
        viewModel.viewModelScope.launch { viewModel.refresh() }
        onPauseOrDispose { }
    }
    Column(modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        Header(onBack)
        SettingsRows(
            state = state,
            onOpenSources = onOpenSources,
            onExport = onExport,
            onRestore = onRestore,
            onCheck = viewModel::check,
            onDelete = onDelete,
        )
        Footer()
    }
}

/** The rows alone, so a test can drive section 6's states with no database. */
@Composable
internal fun SettingsRows(
    state: SettingsState,
    onOpenSources: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onCheck: () -> Unit,
    onDelete: () -> Unit,
) {
    val readable = state.storage == Storage.HEALTHY || state.storage == Storage.DAMAGED

    SectionLabel("CAPTURE")
    SettingsRow("Capture sources", value = "${state.sourcesOn} ON", onClick = onOpenSources)

    SectionLabel("YOUR DATA")
    if (readable) {
        SettingsRow(
            label = "Export everything",
            caption = "EVERY TRANSACTION AND THE NOTIFICATIONS BEHIND THEM",
            value = ago(state.lastExportAt, never = "NEVER"),
            onClick = onExport,
        )
    }
    SettingsRow(
        label = "Replace everything from a backup",
        caption = "DISCARDS WHAT IS HERE FIRST",
        onClick = onRestore,
    )
    if (readable) {
        SettingsRow(
            label = "Check my data",
            value = ago(state.lastCheckAt, never = "NEVER CHECKED"),
            onClick = onCheck,
        )
    }
    SettingsRow(label = "Delete everything", onClick = onDelete)
}

/**
 * How long ago, in the artboard's voice -- `LIVE, 2 MIN AGO` is the pattern.
 *
 * Coarse on purpose: the reader wants to know whether a copy is recent, and a
 * timestamp to the minute invites precision into what is a reminder.
 */
internal fun ago(at: Long, never: String, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return never
    val days = TimeUnit.MILLISECONDS.toDays(now - at)
    return when {
        days <= 0L -> "TODAY"
        days == 1L -> "YESTERDAY"
        days < 30L -> "$days DAYS AGO"
        else -> "OVER A MONTH AGO"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsRow(
    label: String,
    caption: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = Body, fontSize = 15.sp, color = Ink)
            if (caption != null) Text(caption, style = MonoLabel, color = Muted)
        }
        if (value != null) Text(value, style = MonoLabel, color = Muted)
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Back",
            style = MonoLabel,
            color = Muted,
            modifier = Modifier.clickable(role = Role.Button, onClick = onBack),
        )
        Text(
            "Settings",
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The artboard's footer. Both halves are true, and worth saying. */
@Composable
private fun Footer() {
    Text(
        "NO INTERNET PERMISSION" + Separator + "NOTHING LEAVES THIS PHONE",
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(20.dp),
    )
}
```

Add `import androidx.lifecycle.viewModelScope` and `import kotlinx.coroutines.launch`. If `viewModelScope` is not accessible from the composable, add a `fun load()` to `SettingsViewModel` that launches `refresh()` and call that instead — match whichever shape `SourcesScreen` already uses.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.SettingsScreenTest`
Expected: PASS, both.

- [ ] **Step 5: Falsify**

Remove the `if (readable)` guards so every row is always drawn. Run. Expected: `aKeylessLedgerOffersNeitherExportNorCheck` FAILS. Restore.

- [ ] **Step 6: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsScreen.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/SettingsScreenTest.kt
git commit -m "feat: give the app a settings screen"
```

---

### Task 9: Reaching the screen

**Files:**
- Modify: `app/src/main/kotlin/my/pinged/Destinations.kt`
- Modify: `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Test: `app/src/androidTest/kotlin/my/pinged/SettingsNavigationTest.kt`

**Interfaces:**
- Consumes: `SettingsScreen` (Task 8), `SettingsViewModel` (Task 7), `pushOnce`, `pop`, `AppViewModelFactory` (existing).
- Produces: a `Settings` `NavKey`; `LedgerScreen`'s `onOpenSources` re-pointed at it.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

/**
 * The top-bar control opens settings, with the allow-list a row inside it as
 * `Settings.dc.html` draws it. One control moves; no new navigation.
 */
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theTopBarControlReachesSettingsAndSettingsReachesSources() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Capture sources").assertIsDisplayed()
        compose.onNodeWithText("Capture sources").performClick()
        compose.onNodeWithText("Capture sources", substring = true).assertIsDisplayed()
    }
}
```

Read `LedgerScreen`'s `TopBar` first and match the control's existing `contentDescription`; change it from the allow-list wording to `Settings` in the same commit, so the test and the screen agree.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.SettingsNavigationTest`
Expected: FAIL — the settings rows are not reachable.

- [ ] **Step 3: Add the destination**

Append to `Destinations.kt`:

```kotlin
/**
 * Spec 9.5's settings, reached from the ledger's top-bar control — which opened
 * [Sources] until this milestone gave the allow-list a home inside settings, as
 * `design/Settings.dc.html` draws it.
 *
 * See [Ledger] for why all three are `@Serializable` objects registered nowhere.
 */
@Serializable data object Settings : NavKey
```

- [ ] **Step 4: Wire it in `MainActivity`**

Add the factory beside the other two:

```kotlin
        val settingsFactory = AppViewModelFactory(application, ::SettingsViewModel)
```

Re-point the ledger entry and add the settings entry:

```kotlin
                            entry<Ledger> {
                                val ledger: LedgerViewModel = viewModel(factory = ledgerFactory)
                                LedgerScreen(
                                    viewModel = ledger,
                                    onOpenSources = { backStack.pushOnce(Settings) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<Settings> {
                                val settings: SettingsViewModel = viewModel(factory = settingsFactory)
                                SettingsScreen(
                                    viewModel = settings,
                                    onBack = { backStack.pop() },
                                    onOpenSources = { backStack.pushOnce(Sources) },
                                    onExport = { /* Task 10 */ },
                                    onRestore = { /* Task 11 */ },
                                    onDelete = { /* Task 12 */ },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
```

`LedgerScreen`'s parameter keeps the name `onOpenSources` for now; renaming it to `onOpenSettings` is a separate mechanical commit and is done at the end of Task 12, once every callback it feeds exists.

- [ ] **Step 5: Run the test**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.SettingsNavigationTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/my/pinged/Destinations.kt app/src/main/kotlin/my/pinged/MainActivity.kt app/src/androidTest/kotlin/my/pinged/SettingsNavigationTest.kt
git commit -m "feat: reach settings from the ledger"
```

---

### Task 10: Export, and deleting a file that did not finish

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/ExportSheet.kt`
- Modify: `app/src/main/kotlin/my/pinged/MainActivity.kt` (the `onExport` callback)
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/ExportCleanupTest.kt`

**Interfaces:**
- Consumes: `TransferJob` and `SettingsState` (Task 7). Task 7 deliberately ships no export method; `exportTo` is new here, because the document it writes to is what has to be taken back on failure.
- Produces: `ExportSheet(state, onPick, onDismiss)`; `ExportSink` and `DocumentSink`; `SettingsViewModel.exportTo(sink: ExportSink, uri: Uri)`, which deletes the document when the write does not finish.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.settings

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ExportJson`'s KDoc: "a prefix of a JSON object is not a JSON document ...
 * the caller must delete the SAF document". A half-written file looks exactly
 * like a backup until the day it is needed.
 */
@RunWith(AndroidJUnit4::class)
class ExportCleanupTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Before fun startClean() {
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        DatabaseFactory.build(app).useDb { }
    }

    /**
     * A file URI rather than a document-provider one: the assertion is that the
     * caller removes what it wrote, and `DocumentFile`-backed deletion is the
     * platform's job, not this holder's.
     */
    @Test fun aFailedExportLeavesNoFileBehind() = runBlocking {
        val target = File(app.cacheDir, "half-written.json")
        val model = SettingsViewModel(app)

        model.exportTo(FailingSink(target), Uri.fromFile(target)).join()

        assertFalse(
            "a truncated export was left on disk, where it reads as a backup",
            target.exists(),
        )
    }
}
```

Write `FailingSink` in the same file: a tiny `ExportSink` implementation (see Step 3) whose `open()` returns an `OutputStream` that throws after the first 100 bytes, and whose `delete()` deletes the file.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.ExportCleanupTest`
Expected: FAIL — `Unresolved reference 'exportTo'`.

- [ ] **Step 3: Add the sink and the cleanup**

The holder must not import `ContentResolver` directly, or the failure path cannot be tested without a document provider. Add to `SettingsViewModel.kt`:

```kotlin
/**
 * Where an export goes, and how to take it back.
 *
 * An interface rather than a `ContentResolver` and a `Uri`: the behaviour worth
 * guarding is that a write which does not finish is *removed*, and a test
 * needing a document provider to assert that is a test nobody writes.
 */
interface ExportSink {
    fun open(): java.io.OutputStream

    /** Called when the write did not finish. Must leave nothing behind. */
    fun delete()
}

/** The real one: a SAF document the user picked. */
class DocumentSink(
    private val resolver: android.content.ContentResolver,
    private val uri: android.net.Uri,
) : ExportSink {
    override fun open(): java.io.OutputStream =
        resolver.openOutputStream(uri) ?: error("The document provider would not open $uri")

    override fun delete() {
        // Best effort: the document is on storage this app does not own, and a
        // provider that refuses is no reason to crash after a failed export.
        runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
    }
}
```

Replace `export(out: OutputStream)` with:

```kotlin
    /**
     * Writes the backup, and removes the document if it does not finish.
     *
     * Capture stalls for the write: measured at 334ms for 10,000 captures and
     * 1,243ms for 50,000. Progress and cancel, but no "capture is paused"
     * warning -- at a third of a second that reads as alarm, not information.
     */
    fun exportTo(sink: ExportSink, uri: android.net.Uri) = viewModelScope.launch(Dispatchers.IO) {
        _state.value = _state.value.copy(job = TransferJob.Running("Exporting", 0))
        try {
            val rows = sink.open().use { out ->
                ExportJson.write(
                    Databases.shared(app),
                    out,
                    onProgress = { done ->
                        _state.value = _state.value.copy(job = TransferJob.Running("Exporting", done))
                    },
                )
            }
            TransferStore.recordExport(app, System.currentTimeMillis())
            _state.value = _state.value.copy(
                lastExportAt = System.currentTimeMillis(),
                job = TransferJob.Exported(rows),
            )
        } catch (thrown: android.database.sqlite.SQLiteDatabaseCorruptException) {
            // The likeliest way damage is found, because the check is weekly.
            sink.delete()
            TransferStore.recordCheck(app, System.currentTimeMillis(), ok = false)
            _state.value = _state.value.copy(
                storage = Storage.DAMAGED,
                job = TransferJob.Failed(
                    "Pinged could not read all of your data, so this file was not " +
                        "finished and has been removed. Some of it can still be rescued.",
                    corruption = true,
                ),
            )
        } catch (thrown: Exception) {
            sink.delete()
            _state.value = _state.value.copy(
                job = TransferJob.Failed(thrown.message ?: "The export did not finish."),
            )
        }
    }
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.ExportCleanupTest`
Expected: PASS

- [ ] **Step 5: Falsify**

Remove the `sink.delete()` from the general `catch`. Run. Expected: FAIL on "a truncated export was left on disk". Restore.

- [ ] **Step 6: Write the sheet**

```kotlin
package my.pinged.ledger.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Separator

/**
 * `design/Export.dc.html`, minus the half that is CSV.
 *
 * **The artboard's range picker and exclusion toggles are absent because they
 * are wrong on this card, not only because CSV is deferred.** A JSON export
 * filtered to one month rebuilds nothing, and `ImportJson.verifyReferences`
 * refuses it outright -- the transactions it kept name captures the filter
 * removed. They return with CSV, on its side.
 */
@Composable
fun ExportSheet(state: SettingsState, onPick: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Everything", fontFamily = Body, fontSize = 20.sp, color = Ink)
        Text(
            "Transactions, the original notifications, your learned merchants and " +
                "categories. Enough to rebuild Pinged from scratch.",
            fontFamily = Body,
            fontSize = 14.sp,
            color = Muted,
            modifier = Modifier.padding(top = 8.dp),
        )
        when (val job = state.job) {
            is TransferJob.Running ->
                Text("${job.what}${Separator}${job.rows} ROWS", style = MonoLabel, color = Muted)
            is TransferJob.Exported ->
                Text("SAVED${Separator}${job.rows} ROWS", style = MonoLabel, color = Muted)
            is TransferJob.Failed ->
                Text(job.message, fontFamily = Body, fontSize = 14.sp, color = Ink)
            else -> Text("Export", style = MonoLabel, color = Ink, modifier = Modifier.padding(top = 16.dp))
        }
        Text(
            "YOU CHOOSE WHERE IT GOES" + Separator + "PINGED CANNOT UPLOAD IT ANYWHERE",
            style = MonoLabel,
            color = Muted,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
```

Wire `onPick` in `MainActivity` to a `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"))`, and on a non-null result call `settings.exportTo(DocumentSink(contentResolver, uri), uri)`.

- [ ] **Step 7: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/settings app/src/main/kotlin/my/pinged/MainActivity.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/ExportCleanupTest.kt
git commit -m "feat: export the ledger to a file the user chooses"
```

---

### Task 11: Replace everything from a backup

**Files:**
- Modify: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/RestoreStateTest.kt`

**Interfaces:**
- Consumes: `SettingsViewModel.restore` (Task 7), `Restore.replaceEverything` (Task 6).
- Produces: a confirmation before `restore` runs; `TransferJob.Restored` and `TransferJob.Failed` rendered.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refused import must say nothing was imported, and be telling the truth:
 * `ImportJson` reads inside one transaction, so a failure rolls back.
 */
@RunWith(AndroidJUnit4::class)
class RestoreStateTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Before fun startClean() {
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun aFileThatIsNotABackupChangesNothing() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            db.rawCaptureDao().insert(my.pinged.data.sampleCapture(hash = "mine"))
        }
        val model = SettingsViewModel(app)

        model.restore(ByteArrayInputStream("{\"not\":\"a backup\"}".toByteArray())).join()

        val job = model.state.value.job
        assertTrue("the failure was not reported: $job", job is TransferJob.Failed)
        assertTrue(
            "the message does not say nothing was imported",
            (job as TransferJob.Failed).message.contains("Nothing was imported"),
        )
        Databases.reset()
        DatabaseFactory.build(app).useDb { db ->
            assertEquals("the ledger was damaged by a refused import", 1, db.rawCaptureDao().countAll())
        }
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.RestoreStateTest`
Expected: PASS, because Task 6 already validates before destroying. Step 3 is what makes that pass meaningful.

- [ ] **Step 3: Prove the ordering is load-bearing**

`Restore.replaceEverything` already validates before it destroys — Task 6 built it that way, so the
test above should pass on the first run. **That is not evidence.** Prove the guard is what makes it
pass: temporarily move the `validate(context, staged)` call to *after* `Wipe.everything(context)`,
re-run `RestoreStateTest`, and confirm `aFileThatIsNotABackupChangesNothing` fails on its own
assertion — the ledger count is 0 because a file that was not a backup destroyed it. Then restore the
order. Record both outputs in your report.

For reference, the shape Task 6 built and which must not regress:

```kotlin
    /**
     * **Nothing is destroyed until the document is known to be a backup.**
     *
     * Staged to the cache directory first, because SAF gives an `InputStream`
     * that cannot be rewound and the read happens twice: once to validate, once
     * to write it in.
     */
    suspend fun replaceEverything(
        context: Context,
        input: InputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ImportReport {
        val staged = File.createTempFile("restore", ".json", context.cacheDir)
        try {
            staged.outputStream().use { input.copyTo(it) }
            validate(context, staged)

            Wipe.everything(context)
            CaptureCaches.clear(context)
            TransferStore.forget(context)

            val db = DatabaseFactory.build(context)
            return try {
                staged.inputStream().use { ImportJson.read(db, it, onProgress, isCancelled) }
            } finally {
                db.close()
                Databases.reset()
            }
        } finally {
            staged.delete()
        }
    }

    /**
     * Into a throwaway database, because that is the only honest test of
     * whether it restores: `ImportJson`'s guards -- the format version, the six
     * sections, `verifyReferences` -- need a real schema to run at all.
     */
    private fun validate(context: Context, staged: File) {
        val scratch = File(context.cacheDir, "restore-probe")
        scratch.deleteRecursively()
        scratch.mkdirs()
        val probe = DatabaseFactory.buildAt(context, File(scratch, "probe.db"))
        try {
            staged.inputStream().use { ImportJson.read(probe, it) }
        } finally {
            probe.close()
            scratch.deleteRecursively()
        }
    }
```

`DatabaseFactory.buildAt` was added in Task 6. Do not re-add it.

- [ ] **Step 4: Run the test**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.RestoreStateTest`
Expected: PASS

- [ ] **Step 5: Re-run Task 6's tests**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.transfer.RestoreTest`
Expected: PASS, both — the reordering must not have broken the restore itself.

- [ ] **Step 6: Add the confirmation to the screen**

`Replace everything from a backup` opens a confirmation naming what is destroyed before the SAF picker is shown — not after, so the user is never picking a file without knowing what it costs. Reuse `WipeSheet`'s shape from Task 12 once it exists; until then, a simple confirmation with the same words is fine.

Wire `onRestore` in `MainActivity` to `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` launched with `arrayOf("*/*")` — spec §12 says MIME filtering on `application/json` is unreliable across document providers, so the picker accepts anything and the content is validated after reading, which Step 3 now does.

- [ ] **Step 7: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/settings app/src/main/kotlin/my/pinged/MainActivity.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/RestoreStateTest.kt
git commit -m "feat: restore a backup from a file the user picks"
```

---

### Task 12: Delete everything, with the typed confirmation

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/WipeSheet.kt`
- Modify: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsState.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`; `core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt`; `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/WipeSheetTest.kt`

**Interfaces:**
- Consumes: `SettingsViewModel.deleteEverything` (Task 7), `Storage` (Task 7), `ReceiptSheet` (Task 11), `SettingsScreen`'s existing `onExport` (Task 10).
- Produces: `WipeSheet(counts: WipeCounts?, onConfirm, onExportFirst, onDismiss)`; `WipeCounts(txns, captures, merchants, months)`; `wipeCounts: WipeCounts? = null` on `SettingsState`, added here rather than in Task 7 since nothing before this reads it.

**Four corrections to what this task was first written as** — each is a ruling, recorded in the ledger as R22-R25, and the code below already carries them:

1. **The sheet is content-only, inside `ReceiptSheet`.** This task was drafted before Task 11's fix round extracted `ReceiptSheet`, so it hand-rolled its own `Column(fillMaxWidth().padding(20.dp))`. Nested inside `ReceiptSheet`'s identical wrapper that doubles the padding, and it is the fourth copy of chrome that Task 11's review existed to stop. `ExportSheet`'s KDoc states the same contract.
2. **"Export it first" is kept.** `design/Wipe.dc.html` draws it and the first draft silently dropped it. The one moment a user is about to destroy an unbacked-up ledger is exactly when the offer has value, and this milestone exists for that. It reuses `SettingsScreen`'s existing `onExport`.
3. **The rows follow the artboard**, not `"$label  $count"` in one `Text`: label, dotted leader, mono value, inside a bordered `WHAT GOES` card. Counts are grouped as drawn (`1,204`) with `String.format(Locale.ROOT, "%,d", n)`, the idiom `SourcesScreen.kt:568` and `LedgerScreen.kt:837` already use.
4. **`wipeCounts()` catches `SQLiteException`.** `storage == HEALTHY` is not proof the scan completes: `Storage.DAMAGED` comes from a persisted `TransferStore` flag, not a live check, so damage not yet found reads as HEALTHY -- `countEnabledSources`'s KDoc spells this out. Uncaught, it reaches `enqueue`'s bare `viewModelScope.launch` and kills the process on the one screen built to offer salvage.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/**
 * `design/Wipe.dc.html`, including its typed confirmation. The counts carry one
 * correction the artboard could not know -- see `countsAreOmittedRatherThanGuessed`.
 */
class WipeSheetTest {
    @get:Rule val compose = createComposeRule()

    /**
     * The field is found by `hasSetTextAction` rather than by its label text:
     * the label is drawn as its own `Text` above the rule (the artboard has no
     * box around the field), so `onNodeWithText("TYPE DELETE TO CONFIRM")`
     * would match the label and `performTextInput` would fail on a node that
     * takes no text. There is exactly one text field in the sheet.
     */
    @Test fun deleteIsRefusedUntilTheWordIsTyped() {
        compose.setContent {
            WipeSheetBody(WipeCounts(112, 1204, 38, 6), onConfirm = {}, onExportFirst = {}, onDismiss = {})
        }
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNodeWithText("Delete").assertIsEnabled()
    }

    /** Lowercase is not the word. Accepting it makes the friction decorative. */
    @Test fun theWordIsComparedExactly() {
        compose.setContent {
            WipeSheetBody(WipeCounts(112, 1204, 38, 6), onConfirm = {}, onExportFirst = {}, onDismiss = {})
        }
        compose.onNode(hasSetTextAction()).performTextInput("delete")
        compose.onNodeWithText("Delete").assertIsNotEnabled()
    }

    @Test fun theCountsAreTheOnesGiven() {
        compose.setContent {
            WipeSheetBody(WipeCounts(112, 1204, 38, 6), onConfirm = {}, onExportFirst = {}, onDismiss = {})
        }
        compose.onNodeWithText("Transactions").assertIsDisplayed()
        compose.onNodeWithText("1,204").assertIsDisplayed()
    }

    /**
     * `COUNT(*)` reported 5,000 rows on a database that could produce 2,200
     * (design 2.3), so on anything but a healthy ledger the itemisation would
     * overstate the loss. It is left out rather than guessed.
     */
    @Test fun countsAreOmittedRatherThanGuessed() {
        compose.setContent {
            WipeSheetBody(counts = null, onConfirm = {}, onExportFirst = {}, onDismiss = {})
        }
        compose.onNodeWithText("Transactions").assertDoesNotExist()
        compose.onNodeWithText("Pinged cannot count what it cannot read.").assertIsDisplayed()
    }
}
```

Add `import androidx.compose.ui.test.assertDoesNotExist`.

`WipeSheetBody` rather than `WipeSheet` is what the test drives: `WipeSheet` is
`ReceiptSheet` plus the body, and a `ModalBottomSheet` inside `createComposeRule`
animates in through a window of its own -- the body is what this suite is about.
`WipeSheet` itself is then three lines with nothing to assert that
`SettingsScreenTest` will not cover when it drives the row that opens it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.WipeSheetTest`
Expected: FAIL -- `Unresolved reference 'WipeSheetBody'`.

- [ ] **Step 3: Write the sheet**

`feature/ledger/src/main/kotlin/my/pinged/ledger/settings/WipeSheet.kt`. Shape it
against `design/Wipe.dc.html` and against `SettingsScreen`'s existing sheets, which
are the nearest precedent in this codebase; what follows fixes the parts an
implementer cannot read off either.

- `WipeCounts(txns, captures, merchants, months)`, a `data class`, with a KDoc
  saying why it is nullable: `COUNT(*)` is answered from an index without touching
  the damaged pages and reported 5,000 rows on a file that could produce 2,200
  (design 2.3). This is the last thing the user reads before agreeing, so a
  confident wrong number is worse than none.
- `WipeSheet(counts, onConfirm, onExportFirst, onDismiss)` = `ReceiptSheet { WipeSheetBody(...) }`.
- `WipeSheetBody` is `internal` and draws, in the artboard's order: `THIS CANNOT
  BE UNDONE` in `MonoLabel`/`Stamp`; the question in `Display` at 24.sp (the size
  `RestoreConfirmSheet` settled on, not the artboard's 30px, which was measured
  against a full-height sheet); the `WHAT GOES` card -- `Modifier.border(1.dp,
  Border)` -- or, when `counts` is null, `Pinged cannot count what it cannot
  read.`; the "no copy anywhere" paragraph in `Muted`; `Export it first`; the
  typed field; then `Keep it` and `Delete`.
- The artboard's closing sentence "Six months of history ends here." is dropped:
  it restates the `Months of history` row and cannot be written at all when
  `counts` is null, so a sheet that sometimes has a closing sentence and
  sometimes does not is worse than one that never does.
- **The field is this codebase's first text input.** Nothing in `app` or
  `feature` draws one today, so there is no idiom to match and the artboard
  decides: a `BasicTextField` in `Mono` at 17.sp over a 1.5.dp `Rule` underline,
  with the label a separate `MonoLabel` `Text` above it. `OutlinedTextField`
  brings Material3's box, floating label and focus tint into a letterpress
  screen -- a second visual idiom on one surface, which is the finding this
  milestone's reviews have already raised twice.
- `Delete` is disabled, not hidden, until `typed == CONFIRMATION`, and
  `private const val CONFIRMATION = "DELETE"` carries the KDoc: compared exactly,
  because accepting `delete` makes the friction decorative.
- `Export it first` and the two buttons are tap targets built the way
  `RestoreConfirmSheet` builds its pair -- `Modifier.clickable(role = Role.Button)`
  -- and `Delete`'s enabled state must reach semantics (`assertIsNotEnabled` reads
  it), so use `clickable(enabled = ...)`, whose disabled state sets it.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.WipeSheetTest`
Expected: PASS, all four.

- [ ] **Step 5: Falsify**

Change `typed == CONFIRMATION` to `true`. Run. Expected: `deleteIsRefusedUntilTheWordIsTyped` and `theWordIsComparedExactly` both FAIL on their own enabled-state assertion, not on a crash. Restore, and confirm they pass again.

- [ ] **Step 6: Supply the counts, and only when they are honest**

`SettingsState` gains `val wipeCounts: WipeCounts? = null`.

`SettingsViewModel` gains:

```kotlin
    /**
     * Null on anything but a healthy ledger, and null again if the scan does
     * not finish. See [WipeCounts] for why a count from a damaged database is
     * a confident lie, and [countEnabledSources] for why `HEALTHY` is not
     * proof the scan completes.
     *
     * Four queries on every resume, measured at <FILL IN>ms on a Pixel 10a
     * emulator over <FILL IN> captures.
     */
    private fun wipeCounts(): WipeCounts? {
        if (_state.value.storage != Storage.HEALTHY) return null
        return try {
            val db = Databases.shared(app)
            WipeCounts(
                txns = db.txnDao().countAll(),
                captures = db.rawCaptureDao().countAll(),
                merchants = db.merchantRuleDao().countAll(),
                months = db.txnDao().distinctMonthCount(),
            )
        } catch (thrown: SQLiteException) {
            null
        }
    }
```

Set `wipeCounts = wipeCounts()` in the same `_state.value.copy` that `refresh()`
already builds -- after `storage` is computed, since it reads it. Measure the
four queries on a seeded database and put the real number in the KDoc; if it is
over about 20ms, stop and report rather than accepting it, because `refresh()`
runs on every resume of this screen.

`distinctMonthCount()` does not exist. Add it to `TxnDao`:
`SELECT COUNT(DISTINCT substr(local_date, 1, 7)) FROM txn WHERE state != 'REJECTED'`,
with a KDoc saying it feeds the delete sheet's `Months of history` and nothing
else, and that the `(local_date, occurred_at)` index covers the scan. Confirm
`core/data/schemas/` is unchanged afterwards -- a `@Query` does not move Room's
identity hash, and `./gradlew :core:data:test` failing on schema drift would say
otherwise.

- [ ] **Step 7: Host the sheet**

`SettingsScreen` opens it from its own `Delete everything` row, the way it
already opens `RestoreConfirmSheet`: a `var confirmingWipe by remember`, set by
the row, cleared by `onDismiss`. `onConfirm` calls `viewModel.deleteEverything()`
and clears the flag; `onExportFirst` clears the flag and calls the existing
`onExport`, so the export sheet is not fighting this one for the screen.

`SettingsScreen`'s `onDelete` parameter goes: it was a placeholder for this task,
and the delete now runs through the holder this screen already owns, exactly as
`onCheck = viewModel::check` does. Drop it from the signature and from
`MainActivity`'s call, and fix any test that passes it.

- [ ] **Step 8: Rename `onOpenSources` on `LedgerScreen`**

Every callback now exists, so the misleading name goes. Rename the parameter to
`onOpenSettings` in `LedgerScreen`, `LedgerScreenContent`, `TopBar` and `Empty`,
and at its call site in `MainActivity`. Mechanical, no behaviour change.
`SettingsScreen`'s own `onOpenSources` is correctly named and stays.

- [ ] **Step 9: Run every test in the module**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
Expected: PASS

- [ ] **Step 10: The pre-commit gate**

Run: `./gradlew test lint :app:assembleDebug`
Expected: PASS, zero lint issues. A `@VisibleForTesting` or an unused import is
a CI failure on this branch, and the branch has already run four tasks deep in
the red once.

- [ ] **Step 11: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt app/src/main/kotlin/my/pinged/MainActivity.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/WipeSheetTest.kt
git commit -m "feat: delete everything, once the user has typed the word"
```

Name explicit paths; `git commit -a` on this branch has already swept another
session's fixture into an unrelated commit.

---

### Task 13: The periodic check

**Files:**
- Create: `core/data/src/main/kotlin/my/pinged/data/IntegrityStore.kt`
- Create: `feature/capture/src/main/kotlin/my/pinged/capture/PeriodicIntegrity.kt`
- Modify: `core/data/build.gradle.kts`, `core/data/src/main/kotlin/my/pinged/data/Wipe.kt`, `feature/capture/src/main/kotlin/my/pinged/capture/ParseWorker.kt`, `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/TransferStore.kt`, `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsViewModel.kt`
- Test: `feature/capture/src/androidTest/kotlin/my/pinged/capture/PeriodicIntegrityTest.kt`, plus whatever in `:feature:ledger`'s androidTest names the moved `TransferStore` members.

**Interfaces:**
- Consumes: `Integrity.check` (Task 1), `Databases.shared` (existing).
- Produces: `IntegrityStore.record(context, at, ok)`, `.lastCheckAt(context)`, `.damaged(context)`, `.forget(context)` in `:core:data`; `PeriodicIntegrity.checkIfDue(context, db, now, check): Boolean` in `:feature:capture`.

**The verdict has to cross the module boundary, and this task is where it does.**

As first drafted this task logged a damaged verdict and dropped it, on the
grounds that `:feature:capture` cannot see `:feature:ledger`'s `TransferStore`.
The dependency reads `:feature:ledger` → `:feature:capture` → `:core:data`, so
that much is true. The consequence was not acceptable: `Storage.DAMAGED` is read
from `TransferStore.damaged`, so a weekly check that found corruption would
change nothing a user can see, and the only paths left into `DAMAGED` would be
tapping **Check now** or having an export fail. A periodic check whose result
reaches nobody is 121ms a week spent on a log line, and Task 15's recovery
states would be nearly unreachable in practice.

So the check record moves to `:core:data`, which both modules already see and
which owns the database the record is about. `TransferStore` keeps
`lastExportAt`, which is genuinely a ledger concern, and gives up the three
check members. One store, two writers -- this worker and the settings screen's
**Check now** -- and one reader.

- [ ] **Step 1: Move the record into `:core:data`**

`core/data/build.gradle.kts` gains `implementation(libs.androidx.datastore.preferences)`, with a
comment saying why a Room module now has a preferences store: the integrity verdict is a fact about
this database that outlives any one process and has to be legible to both features.

`IntegrityStore.kt`, beside `Integrity.kt`. Lift `TransferStore`'s `LAST_CHECK_AT` and `DAMAGED`
verbatim -- including the KDoc explaining that absent means "no bad news", which is not "verified" --
into a `preferencesDataStore("integrity")`. Keep `recordCheck`'s one-edit-for-both-facts property and
the reason for it. Add `forget(context)`.

Then delete those three members from `TransferStore`, narrow its KDoc to the export
timestamp, and repoint `SettingsViewModel`'s `check()` and `refresh()` at `IntegrityStore`.
`Wipe.everything` must clear it too -- `deleteEverything` currently reaches `TransferStore.forget`
for the same reason, and a wiped device claiming it was checked last week, or worse that it was
damaged, is a lie about a database that no longer exists. Put the `IntegrityStore.forget` call where
`Wipe.everything` can guarantee it runs, not in the view model, since the worker is now a writer too.

- [ ] **Step 2: Write the failing test**

```kotlin
package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Integrity
import my.pinged.data.IntegrityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 121ms per 10,000 captures (design 2.1) is nothing weekly and a great deal per
 * notification. This is the throttle that keeps it the former, and the record
 * that makes the result reach a screen.
 */
@RunWith(AndroidJUnit4::class)
class PeriodicIntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startClean() = runBlocking {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        CaptureCaches.clear(context)
        IntegrityStore.forget(context)
    }

    @Test fun theFirstRunChecks() = runBlocking {
        val db = DatabaseFactory.build(context)
        assertTrue(PeriodicIntegrity.checkIfDue(context, db, now = 1_000L))
        assertEquals(1_000L, IntegrityStore.lastCheckAt(context))
        db.close()
    }

    @Test fun theNextNotificationDoesNotCheckAgain() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(context, db, now = 1_000L)
        assertFalse(
            "a check ran twice in a row, which is 121ms per notification burst",
            PeriodicIntegrity.checkIfDue(context, db, now = 2_000L),
        )
        db.close()
    }

    @Test fun aWeekLaterItChecksAgain() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(context, db, now = 1_000L)
        val aWeekOn = 1_000L + PeriodicIntegrity.EVERY_MILLIS + 1
        assertTrue(PeriodicIntegrity.checkIfDue(context, db, now = aWeekOn))
        db.close()
    }

    /**
     * A clock moved backwards -- a timezone-naive user correction, or a restored
     * system image -- must not mute the check until wall time catches up. Ahead
     * of the throttle, because `now - last` is negative and compares less than
     * any positive interval.
     */
    @Test fun aClockMovedBackwardsStillChecks() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(context, db, now = 10_000L)
        assertTrue(PeriodicIntegrity.checkIfDue(context, db, now = 5_000L))
        db.close()
    }

    /**
     * The point of the whole task: a verdict the settings screen can read.
     * [Integrity.check] is injected rather than corrupting a file here --
     * androidTest source sets are not shared between modules, so the real
     * damage helpers in `:core:data` and `:feature:ledger` are not on this
     * classpath, and a third copy to prove one boolean is written is not worth
     * it. That the real check is wired is what `theFirstRunChecks` covers.
     */
    @Test fun damageIsRecordedWhereTheScreenReadsIt() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(
            context,
            db,
            now = 1_000L,
            check = { Integrity.Result.Damaged("row 4 missing from index") },
        )
        assertTrue(IntegrityStore.damaged(context))
        db.close()
    }

    /** A later clean check clears it, or the screen never recovers. */
    @Test fun aCleanCheckClearsTheVerdict() = runBlocking {
        val db = DatabaseFactory.build(context)
        PeriodicIntegrity.checkIfDue(
            context, db, now = 1_000L,
            check = { Integrity.Result.Damaged("row 4 missing from index") },
        )
        PeriodicIntegrity.checkIfDue(context, db, now = 1_000L + PeriodicIntegrity.EVERY_MILLIS + 1)
        assertFalse(IntegrityStore.damaged(context))
        db.close()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :feature:capture:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.capture.PeriodicIntegrityTest`
Expected: FAIL -- `Unresolved reference 'PeriodicIntegrity'`.

- [ ] **Step 4: Write it**

```kotlin
package my.pinged.capture

import android.content.Context
import android.util.Log
import my.pinged.data.Integrity
import my.pinged.data.IntegrityStore
import my.pinged.data.PingedDatabase

/**
 * Spec 11.1's corruption detection, on the worker that already runs.
 *
 * **Not at open, and not per notification.** `integrity_check` costs 121ms at
 * 10,000 captures and 644ms at 50,000 against a 4ms open (design 2.1), and this
 * process starts per notification burst.
 *
 * **The cost lands on the listener, once a week.** Room 2.8 hands SQLCipher a
 * single connection (see the repository's CLAUDE.md), so for the duration of
 * the pragma every other caller waits -- including stage one's insert. A
 * listener callback blocked 121ms once a week is the price of noticing; at the
 * per-notification rate this replaced it would not be.
 */
internal object PeriodicIntegrity {
    /** A week. Corruption keeps no schedule; this bounds the cost of looking. */
    const val EVERY_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    private const val TAG = "PeriodicIntegrity"

    /**
     * Returns whether a check actually ran, which is what the tests assert on.
     *
     * @param check injected only so a test can produce a damaged verdict
     *   without a corrupted file; production has one caller and it takes the
     *   default.
     */
    suspend fun checkIfDue(
        context: Context,
        db: PingedDatabase,
        now: Long,
        check: (PingedDatabase) -> Integrity.Result = Integrity::check,
    ): Boolean {
        val last = IntegrityStore.lastCheckAt(context)
        // `now < last` is due, not throttled: a clock moved backwards would
        // otherwise mute the check until wall time caught up.
        if (last != 0L && now >= last && now - last < EVERY_MILLIS) return false

        val result = check(db)
        if (result is Integrity.Result.Damaged) {
            Log.e(TAG, "integrity_check reported: ${result.firstProblem}")
        }
        IntegrityStore.record(context, at = now, ok = result is Integrity.Result.Ok)
        return true
    }
}
```

- [ ] **Step 5: Call it from `ParseWorker`**

After the sweep and before the drain, inside the existing `CaptureStorage.guarded` body, which
already resolved the database and so has answered the open question this call would otherwise ask
again:

```kotlin
        PeriodicIntegrity.checkIfDue(app, Databases.shared(app), System.currentTimeMillis())
```

Before the drain rather than after, and the reason belongs in a comment: on a database damaged
enough that the drain throws, a check placed after it never runs, and the state this milestone
exists to surface would be the one state it could not reach.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :feature:capture:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.capture.PeriodicIntegrityTest`
Expected: PASS, all six.

- [ ] **Step 7: Falsify, three ways**

Each separately, restoring between: remove the `last != 0L && ...` guard, and
`theNextNotificationDoesNotCheckAgain` must fail with its own message. Drop `now >= last` from the
guard, and `aClockMovedBackwardsStillChecks` must fail. Change `ok = result is Integrity.Result.Ok`
to `ok = true`, and `damageIsRecordedWhereTheScreenReadsIt` must fail on its own assertion. Confirm
each fails for its own reason rather than on a crash, and that all six pass again afterwards.

- [ ] **Step 8: Run every affected suite**

Step 1 moved members out of `TransferStore`, so `:feature:ledger` is in scope whether or not it
looks it.

Run: `./gradlew :feature:capture:connectedDebugAndroidTest :feature:ledger:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
Expected: PASS

- [ ] **Step 9: The pre-commit gate**

Run: `./gradlew test lint :app:assembleDebug :app:processReleaseMainManifest`
Expected: PASS, zero lint issues.

- [ ] **Step 10: Commit**

Two commits, because they are two changes and the first is not user-visible:

```bash
git add core/data/build.gradle.kts core/data/src/main/kotlin/my/pinged/data/IntegrityStore.kt core/data/src/main/kotlin/my/pinged/data/Wipe.kt feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/TransferStore.kt feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsViewModel.kt
git commit -m "refactor: move the integrity verdict to the module that owns the database"

git add feature/capture/src/main/kotlin/my/pinged/capture/PeriodicIntegrity.kt feature/capture/src/main/kotlin/my/pinged/capture/ParseWorker.kt feature/capture/src/androidTest/kotlin/my/pinged/capture/PeriodicIntegrityTest.kt
git commit -m "feat: check the database for damage once a week"
```

Name explicit paths; `git commit -a` on this branch has already swept another session's fixture into
an unrelated commit.

---

### Task 14: The staleness nudge

**Files:**
- Modify: `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Test: `app/src/test/kotlin/my/pinged/ExportNudgeTest.kt`, `app/src/test/kotlin/my/pinged/BannerPriorityTest.kt`

**Interfaces:**
- Consumes: `TransferStore.lastExportAt` (Task 5), `CaptureBanner`, `TxnDao.countAll` (existing).
- Produces: `exportIsOverdue(txns, lastExportAt, now)`; `BannerKind`; `bannerFor(report, exportOverdue)`; a nudge branch rendered last.

**Three corrections to what this task was first drafted as.** Each is a ruling,
recorded in the ledger as R32-R34, and the steps below already carry them.

1. **The tests are JVM tests, not instrumented ones.** The drafted suite was a
   plain JUnit class with no `AndroidJUnit4` runner and no androidx.test import,
   filed under `app/src/androidTest/`. `app/src/test/` already exists
   (`BackStackRuleTest`), and a pure predicate tested there runs in CI's `test`
   task and needs no emulator at all.
2. **The priority order gets a falsifying test, which means it has to become a
   value.** Step 4's "after `current.looksDead`, so it can never appear while
   something is actually wrong" is the whole safety property of this task, and as
   a `when` inside a `private @Composable` reading a `StateFlow` nothing can
   reach it. Reordered by accident, a user whose listener is dead would read
   "NO BACKUP" instead of "CAPTURE STOPPED". The decision becomes
   `bannerFor(...)`, a pure function returning a `BannerKind`, and `GrantBanner`
   becomes a `when` over that kind supplying copy. Every unfalsified claim on
   this branch has eventually become a review finding.
3. **`countAll()` can throw where nothing catches it.** `onResume`'s
   `CaptureStorage.guarded` catches `DatabaseUnavailableException`; a
   `SQLiteException` from a scan that aborts mid-count is not one, and
   `lifecycleScope.launch` has no handler, so it kills the process. This is the
   fourth appearance of this exact hazard in this milestone -- see
   `SettingsViewModel.countEnabledSources`' KDoc, which documents it.

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/my/pinged/ExportNudgeTest.kt`:

```kotlin
package my.pinged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole defence against a user who never presses Export -- and it must
 * never fire on an app with nothing to lose.
 */
class ExportNudgeTest {
    private val now = 1_700_000_000_000L

    @Test fun anEmptyLedgerIsNeverNudged() {
        assertFalse(exportIsOverdue(txns = 0, lastExportAt = 0L, now = now))
    }

    @Test fun aLedgerWithRowsAndNoExportIsOverdue() {
        assertTrue(exportIsOverdue(txns = 12, lastExportAt = 0L, now = now))
    }

    @Test fun aRecentExportSilencesIt() {
        assertFalse(exportIsOverdue(txns = 12, lastExportAt = now - 1000, now = now))
    }

    @Test fun anExportOlderThanTheWindowDoesNot() {
        assertTrue(
            exportIsOverdue(txns = 12, lastExportAt = now - EXPORT_STALE_AFTER_MILLIS - 1, now = now),
        )
    }

    /** The boundary itself is not overdue; a strict `>` is what the code says. */
    @Test fun exactlyTheWindowIsNotYetOverdue() {
        assertFalse(
            exportIsOverdue(txns = 12, lastExportAt = now - EXPORT_STALE_AFTER_MILLIS, now = now),
        )
    }
}
```

The threshold is named and shared rather than restated as `31L * 24 * 60 * 60 * 1000`
in the test: a test carrying its own copy of the constant passes when the two drift.

`app/src/test/kotlin/my/pinged/BannerPriorityTest.kt` pins the order, and is the
reason `bannerFor` exists. Cover at least: a report with `storageUnavailable`
**and** an overdue export yields `STORAGE_UNAVAILABLE`; ungranted with an overdue
export yields `NOT_GRANTED`; `looksDead` with an overdue export yields `STOPPED`;
a healthy report with an overdue export yields `NUDGE_EXPORT`; and a healthy
report with a recent export yields `null`. The first three are the property --
the nudge never speaks over something that is actually wrong.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test`
Expected: FAIL -- `Unresolved reference 'exportIsOverdue'`, `'bannerFor'`.

- [ ] **Step 3: Write the predicate**

In `MainActivity.kt`, at file scope:

```kotlin
/**
 * **Thirty days is a guess**, with no evidence behind it, for a ledger that
 * gains roughly 850 captures a month (spec 15).
 */
internal const val EXPORT_STALE_AFTER_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * Whether to tell the user their data is not backed up anywhere.
 *
 * There has to be something to lose: a banner on an empty ledger is the app
 * inventing a problem.
 *
 * [txns] is `countAll()` and so includes `REJECTED` rows, unlike the delete
 * sheet's count, which excludes them to agree with the feed. The question is
 * different: the sheet itemises what the user would recognise losing, and this
 * asks only whether an export file would carry anything at all.
 */
internal fun exportIsOverdue(txns: Int, lastExportAt: Long, now: Long): Boolean {
    if (txns == 0) return false
    return now - lastExportAt > EXPORT_STALE_AFTER_MILLIS
}
```

- [ ] **Step 4: Make the priority order a value**

Extract `GrantBanner`'s `when` into a pure function beside the predicate:

```kotlin
internal enum class BannerKind { STORAGE_UNAVAILABLE, NOT_GRANTED, NOTHING_SEEN, STOPPED, NUDGE_EXPORT }

internal fun bannerFor(report: CaptureReport, exportOverdue: Boolean): BannerKind?
```

Carry the existing branch comments onto it -- particularly why `storageUnavailable`
is first and why `neverSeen` precedes `looksDead`, which are the two orderings the
existing code already argues for. `NUDGE_EXPORT` is last, and its own comment says
why: every other kind is a statement that capture is broken now, and this one is a
statement about the past. `GrantBanner` then becomes a `when` on the returned kind
that only supplies copy, so the decision and the drawing are separable and only one
of them needs a device to test.

- [ ] **Step 5: Render it**

```kotlin
            BannerKind.NUDGE_EXPORT -> CaptureBanner(
                label = "NO BACKUP" + Separator + "NOTHING SAVED ANYWHERE ELSE",
                body = "Pinged has no internet permission, so this phone holds the " +
                    "only copy of what it has recorded. If the phone is lost or " +
                    "reset, so is all of it.",
                actionLabel = "Export everything",
                onAction = { backStack.pushOnce(Settings) },
            )
```

The action navigates to Settings rather than opening the export sheet directly:
the sheet is hosted there, and a banner that opened a modal over whatever
destination the user was on would be the only control in the app that does that.

- [ ] **Step 6: Feed it**

Compute the flag in the same `onResume` block that fills `health`, reading
`TransferStore.lastExportAt(applicationContext)` and `Databases.txnDao(app).countAll()`
inside the existing `CaptureStorage.guarded` call, and publish it to a state holder
`GrantBanner` can read.

**Catch `SQLiteException` around the count and leave the flag false when it throws**
-- correction 3 above. A nudge is the least important thing on this screen and an
uncatchable crash is the most damaging; a database that cannot be counted is one the
user will hear about from the settings screen, not from this banner.

- [ ] **Step 7: Run the tests**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 8: Falsify, twice**

Separately, restoring between. Remove `if (txns == 0) return false`, and
`anEmptyLedgerIsNeverNudged` must fail on its own assertion. Move `NUDGE_EXPORT`
above `STOPPED` in `bannerFor`, and `BannerPriorityTest`'s ordering case must fail
on its own assertion. Confirm both pass again afterwards.

- [ ] **Step 9: Run every affected suite**

`GrantBanner` was restructured, so the existing banner tests are in scope.

Run: `./gradlew :app:connectedDebugAndroidTest :feature:ledger:connectedDebugAndroidTest`
Expected: PASS

- [ ] **Step 10: The pre-commit gate**

Run: `./gradlew test lint :app:assembleDebug :app:processReleaseMainManifest`
Expected: PASS, zero lint issues.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/kotlin/my/pinged/MainActivity.kt app/src/test/kotlin/my/pinged/ExportNudgeTest.kt app/src/test/kotlin/my/pinged/BannerPriorityTest.kt
git commit -m "feat: say so when nothing has been backed up in a month"
```

Name explicit paths; `git commit -a` on this branch has already swept another
session's fixture into an unrelated commit.

---

### Task 15: The recovery states

**Files:**
- Modify: `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/RecoveryStateTest.kt`, plus a test pinning the banner's new button (Step 5)

**Interfaces:**
- Consumes: `Storage` (Task 7), `CaptureBanner`, `CANNOT_READ_YOUR_DATA` (existing), `bannerFor`/`BannerKind` (Task 14).
- Produces: a `RecoveryNotice(storage)` composable; the `STORAGE_UNAVAILABLE` banner gains a button that navigates to settings, and loses a claim it cannot support.

**Three corrections to what this task was first drafted as.** Each is a ruling,
recorded in the ledger as R35-R37, and the steps below carry them.

1. **This task says; Task 17 offers.** The draft drew "Rescue what can still be
   read" as a `Text` with no click handler, because salvage does not exist until
   Task 17. A control that looks tappable and does nothing is a lie on the one
   screen a frightened user reads most carefully, and the draft's own KDoc draws
   the line: "Section 6's table decides what is *offered*; this is what is
   *said*." The action moves to Task 17, which can wire it. This task's DAMAGED
   test asserts the wording.
2. **The banner's copy claims a cause it does not know.** `CaptureStorage.guarded`
   catches the sealed `DatabaseUnavailableException` -- key gone, unreadable, *and*
   being deleted -- and records one boolean. So the `STORAGE_UNAVAILABLE` banner's
   "the key that unlocks it is gone from this phone" is shown on a full disk, and
   while the user's own Delete everything is running. Design section 6: "its copy
   never says the data is gone in a state where it may not be." The banner becomes
   cause-neutral, and the new button takes the user to the screen that *does* know
   which it is -- `SettingsViewModel.opens()` distinguishes them by exception type,
   and `RecoveryNotice` says the precise thing.
3. **Step 4's quoted KDoc no longer exists.** Task 14 moved `GrantBanner`'s `when`
   into `bannerFor` and rewrote the `storageUnavailable` comment ("each lives in
   settings behind its own confirmation"). The change targets the
   `BannerKind.STORAGE_UNAVAILABLE` arm of `GrantBanner`, and the reasoning to
   preserve is whatever that arm's comment says now -- read it, do not restore the
   old sentence.

**Left for Task 17, noted here so it is not lost:** design section 6's table gives
Damaged no Export -- salvage replaces it -- but `SettingsRows`' `readable = HEALTHY ||
DAMAGED` gate still offers Export in DAMAGED. That is correct *until* salvage exists,
since withdrawing Export first would leave a damaged ledger no way out; Task 17
swaps one for the other in the same commit.

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import my.pinged.ledger.theme.PingedTheme
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
        compose.setContent { PingedTheme { RecoveryNotice(storage) } }

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

    /** Healthy is the absence of bad news; a notice there would invent some. */
    @Test fun aHealthyLedgerDrawsNoNotice() {
        show(Storage.HEALTHY)
        compose.onNodeWithText(CANNOT_READ_YOUR_DATA, substring = true).assertDoesNotExist()
        compose.onNodeWithText("DAMAGED", substring = true).assertDoesNotExist()
    }
}
```

Add `import androidx.compose.ui.test.assertDoesNotExist` only if it is an extension in
this Compose version; `WipeSheetTest` found it is a member and an unused import is a
lint error on this branch.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.RecoveryStateTest`
Expected: FAIL -- `Unresolved reference 'RecoveryNotice'`.

- [ ] **Step 3: Write it**

Add to `SettingsScreen.kt`:

```kotlin
/**
 * What the screen says above the rows when the database is not healthy.
 *
 * Section 6's table decides what is *offered*; this is what is *said*. Nothing
 * in [Storage.UNREADABLE] may read as "your data is gone", and nothing there may
 * name the key: a full disk lands there and clears on its own.
 */
@Composable
internal fun RecoveryNotice(storage: Storage) {
    val (label, body) = when (storage) {
        Storage.HEALTHY -> return
        Storage.DAMAGED ->
            ("DAMAGED" + Separator + "SOME OF THIS CAN STILL BE READ") to
                "Part of Pinged's database is unreadable. What is still readable " +
                "can be written to a file and restored afterwards, and doing that " +
                "first loses the least."
        Storage.KEY_GONE ->
            (CANNOT_READ_YOUR_DATA + Separator + "THE KEY IS GONE") to
                "The database is intact and the key that unlocks it is not on this " +
                "phone. This happens after some device-to-device transfers, and " +
                "the key cannot be recovered -- Android does not let it leave the " +
                "phone it was made on. Nothing here has been deleted, and nothing " +
                "here can be read."
        Storage.UNREADABLE ->
            CANNOT_READ_YOUR_DATA to
                "Pinged could not open its database. This is sometimes a full disk " +
                "or a storage error rather than lost data, so check your phone's " +
                "storage and try again before replacing or deleting anything. " +
                "Nothing has been deleted."
    }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(label, style = MonoLabel, color = Stamp)
        Text(body, fontFamily = Body, fontSize = 14.sp, color = Ink, modifier = Modifier.padding(top = 8.dp))
    }
}
```

Call it from `SettingsScreen` above `SettingsRows`. Match the surrounding rows'
ground -- `Paper`, per `SettingsRow`'s KDoc -- rather than inventing a card.

- [ ] **Step 4: Make the banner honest, and give it a way forward**

In `GrantBanner`'s `BannerKind.STORAGE_UNAVAILABLE` arm:

- Rewrite the body so it says only what `guarded`'s boolean can support: Pinged
  cannot open its database, nothing new is being recorded, nothing already saved has
  been deleted. **No cause.** Not the key, not a transfer -- those belong to
  `KEY_GONE`, which only settings can tell apart from a full disk.
- Keep the label unless it names a cause too. `CANNOT OPEN YOUR DATA` does not.
- Add the button:

```kotlin
                actionLabel = "What can I do about this?",
                onAction = onOpenSettings,
```

  passed in the way Task 14 passed `onExportEverything`, since `backStack` lives in
  `onCreate`'s composition rather than on the Activity.

Update the arm's comment: the banner still does not *act*, it *navigates* -- the
destructive choices stay behind a screen that can explain them, and that screen is
also the only one that knows which failure this is.

- [ ] **Step 5: Pin the button**

`ExportNudgeBannerTest` (Task 14) already pins the nudge's copy and tap target end to
end. Pin this arm the same way if storage-unavailable can be provoked from `:app`'s
instrumentation -- the flag is recorded whenever `Databases.shared` throws, so removing
the key file from `noBackupFilesDir` and resetting `Databases` should reach it. If it
cannot be provoked, say so in the test file rather than leaving the arm silently
uncovered, and pin what can be pinned.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.settings.RecoveryStateTest`
Expected: PASS, all five.

- [ ] **Step 7: Falsify, three ways**

Separately, restoring between:
- `UNREADABLE`'s body replaced with `KEY_GONE`'s → `anUnreadableLedgerIsToldToWaitRatherThanToStartOver` **and** `anUnreadableLedgerIsNotToldItsKeyIsGone` must fail.
- `Storage.HEALTHY -> return` replaced with the `DAMAGED` pair → `aHealthyLedgerDrawsNoNotice` must fail.
- The banner button's `onAction` wired to `::openListenerSettings` → Step 5's test must fail (or, if Step 5 could not be pinned, say so here).

Each on its own assertion, not a crash.

- [ ] **Step 8: Run every affected suite, then the gate**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
Then: `./gradlew test lint :app:assembleDebug :app:processReleaseMainManifest`
Expected: PASS, zero lint issues. If either count moves, update CLAUDE.md's Commands
section to the number you measured.

- [ ] **Step 9: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/settings/SettingsScreen.kt app/src/main/kotlin/my/pinged/MainActivity.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/settings/RecoveryStateTest.kt
git commit -m "feat: tell the user what to do when the ledger will not open"
```

Add Step 5's test file and CLAUDE.md to the `git add` if they changed. Name explicit
paths; `git commit -a` on this branch has already swept another session's fixture
into an unrelated commit.

---

### Task 16: Reading past damage

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/RowReader.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/RowReaderTest.kt`, `RowReaderDamageTest.kt`

**Interfaces:**
- Consumes: `damageMidFile` (`TransferFixtures.kt`, this module's androidTest).
- Produces: `RowReader` with `page(after, limit, idOf, fetch)`; `RowReader.Strict`; `RowReader.Salvaging` reporting what it could not read in a form that is true (correction 3). Task 17 consumes it; its section is rewritten after this lands.

**Why this is its own task:** the narrowing is the only genuinely new algorithm in the milestone.

**Four corrections to the draft below, which predates them.** Recorded as R43-R46.
The draft's code is kept as a starting point, not as the answer.

1. **The draft does not terminate on real damage.** Its fake returns an empty page
   past id 100 *without throwing*. Real damage need not: design 2.3's walk stopped at
   a `SQLiteDatabaseCorruptException` with 2,200 of 5,000 read, and nobody tried past
   it. If the damaged page sits on the table B-tree's rightmost path, every keyset
   seek beyond the last good id may throw, including the one that would have
   answered "nothing left", and `Salvaging` then steps `cursor++` forever. The walk
   needs an upper bound on ids that does not come from the damaged path, and it must
   stop there. Where that bound can come from is to be **measured on a damaged file**,
   not assumed. One candidate worth trying: a secondary index lives on its own pages,
   which is exactly why `COUNT(*)` answered 5,000 off an index on a file that read
   2,200 (design 2.3), so an index scan may still yield `max(id)` -- or every id that
   exists.
2. **The classifier has to be measured too.** The draft steps over
   `SQLiteDatabaseCorruptException` and any message containing "malformed". Record
   what a keyset seek into a damaged page actually throws on SQLCipher here. Just as
   important is what must **not** be stepped over: a disk I/O error or a full disk is
   transient, and stepping over it silently drops rows that would have read. A closed
   connection is `android.database.SQLException` code 21, which is not a
   `SQLiteException` subclass (measured in `596e195`), so the draft already rethrows
   it. Confirm that rather than rely on it.
3. **`skipped` counts ids stepped over, not rows lost.** Ids can have gaps, so the
   number is an **upper bound** on rows lost, and Task 17 must not present it as a
   count. Name and document it as what it is. If correction 1's index yields the real
   set of ids, the loss can be counted exactly. That is better, and worth its cost if
   the measurement supports it.
4. **The algorithm is proven on real damage here, not deferred to Task 17.** Its
   termination, its classifier and its bound all depend on how real damage behaves,
   so a fake can only confirm the logic the implementer already believed. Add
   `RowReaderDamageTest`: seed a realistic ledger, damage it with `damageMidFile`,
   and assert all of the following:
   - salvage terminates;
   - it recovers at least as many rows as the strict walk reached before throwing;
   - it never returns a row twice;
   - its reported loss figure holds as a true statement against the seeded total.

   Also damage the rightmost path, or explain why that shape cannot occur. Measure
   the wall time at 10,000 and 50,000 captures and put the numbers in the KDoc.

   **Halving versus stepping:** keep a scheme that never skips a readable row. A
   gallop that jumps a damaged range can jump a readable island between two damaged
   pages, and losing readable rows is the one thing salvage exists to prevent.

**The fake-based test below must also gain a case with id gaps** and a case where the
damage runs to the end of the table and every fetch past it throws. The draft's fake
hid both.


- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.ledger.transfer

import android.database.sqlite.SQLiteDatabaseCorruptException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One byte of damage left 2,200 of 5,000 captures readable (design 2.3), and
 * `ExportJson` recovers none of them -- the first unreadable page throws and
 * takes the file with it. This is the walk that keeps going.
 */
class RowReaderTest {
    /** Rows 40..59 are behind the damaged page; everything else reads. */
    private fun damagedFetch(after: Long, limit: Int): List<Long> {
        val rows = (after + 1..after + limit).toList()
        if (rows.any { it in 40L..59L }) throw SQLiteDatabaseCorruptException()
        return rows.filter { it <= 100L }
    }

    @Test fun strictGivesUpAtTheFirstDamage() {
        val reader = RowReader.Strict
        assertEquals(listOf(1L, 2L, 3L), reader.page(0, 3, { it }, ::damagedFetch))
        try {
            reader.page(39, 3, { it }, ::damagedFetch)
            throw AssertionError("Strict swallowed a corruption it should have rethrown")
        } catch (expected: SQLiteDatabaseCorruptException) {
            // what ExportJson relies on
        }
    }

    @Test fun salvagingSkipsTheUnreadableRowsAndKeepsWalking() {
        val reader = RowReader.Salvaging()
        val seen = mutableListOf<Long>()
        var after = 0L
        while (true) {
            val page = reader.page(after, 10, { it }, ::damagedFetch)
            if (page.isEmpty()) break
            seen += page
            after = page.last()
        }
        assertEquals("rows behind the damage were kept", emptyList<Long>(), seen.filter { it in 40L..59L })
        assertEquals("readable rows were lost", 80, seen.size)
        assertEquals("the loss was not counted", 20, reader.skipped)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.transfer.RowReaderTest`
Expected: FAIL — `Unresolved reference 'RowReader'`.

- [ ] **Step 3: Write it**

```kotlin
package my.pinged.ledger.transfer

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException

/**
 * How a page of rows is fetched during an export: straight through, or past
 * damage.
 *
 * The seam is the *only* difference between the two. `ExportJson` keeps its
 * single transaction, its ordering and its streaming; salvage changes what
 * happens when a read throws, and nothing else.
 */
interface RowReader {
    /**
     * Up to [limit] rows after [after], or empty at the true end of the table.
     *
     * **Empty means finished.** `ExportJson.writePaged` stops on a short page,
     * so a reader returning four rows because six were unreadable would
     * silently truncate the export there -- which is why a salvaging reader
     * keeps going rather than returning what it has.
     */
    fun <T> page(
        after: Long,
        limit: Int,
        idOf: (T) -> Long,
        fetch: (Long, Int) -> List<T>,
    ): List<T>

    /** The healthy path. A corrupt read throws, and the export is abandoned. */
    object Strict : RowReader {
        override fun <T> page(
            after: Long,
            limit: Int,
            idOf: (T) -> Long,
            fetch: (Long, Int) -> List<T>,
        ): List<T> = fetch(after, limit)
    }

    /**
     * Narrows a failing page down to the rows that still read.
     *
     * **Halving, not row-by-row.** A badly damaged file would otherwise cost one
     * query per row for the whole table; halving costs `log2(limit)` extra
     * queries per damaged span and reaches single rows only where the damage
     * actually is. The design records that this choice had no measurement
     * behind it; if a real damaged file ever shows up, measure both.
     *
     * A span of exactly one row that still throws is the unreadable row itself:
     * it is counted in [skipped] and stepped over.
     */
    class Salvaging : RowReader {
        /** How many rows could not be read. Reported to the user, never hidden. */
        var skipped: Int = 0
            private set

        override fun <T> page(
            after: Long,
            limit: Int,
            idOf: (T) -> Long,
            fetch: (Long, Int) -> List<T>,
        ): List<T> {
            val kept = mutableListOf<T>()
            var cursor = after
            var span = limit
            while (kept.size < limit) {
                val rows = try {
                    fetch(cursor, span)
                } catch (thrown: SQLiteException) {
                    if (!corrupt(thrown)) throw thrown
                    if (span > 1) {
                        span = span / 2
                        continue
                    }
                    // One row, and it will not read. Step over it.
                    skipped++
                    cursor++
                    span = minOf(limit - kept.size, limit)
                    continue
                }
                if (rows.isEmpty()) break
                kept += rows
                cursor = idOf(rows.last())
                span = minOf(limit - kept.size, limit)
                if (span == 0) break
            }
            return kept
        }

        /**
         * A wrong key and a damaged page reach the same exception type, so only
         * the message separates a row to step over from a database that will
         * not open at all.
         */
        private fun corrupt(thrown: SQLiteException): Boolean =
            thrown is SQLiteDatabaseCorruptException ||
                thrown.message?.contains("malformed", ignoreCase = true) == true
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :feature:ledger:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.ledger.transfer.RowReaderTest`
Expected: PASS, both. If `salvagingSkipsTheUnreadableRowsAndKeepsWalking` loops, the stepping is not advancing `cursor` past an unreadable row — fix that rather than loosening the assertion.

- [ ] **Step 5: Falsify**

Make `Salvaging.page` delegate to `Strict.page`. Run. Expected: `salvagingSkipsTheUnreadableRowsAndKeepsWalking` FAILS by throwing. Restore.

- [ ] **Step 6: The gate**

Run: `./gradlew connectedDebugAndroidTest` then `./gradlew test lint :app:assembleDebug :app:processReleaseMainManifest`. Update CLAUDE.md's counts.

- [ ] **Step 7: Commit**

```bash
git add feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/RowReader.kt feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/RowReaderTest.kt
git commit -m "feat: walk a table past the rows that will not read"
```

---

### Task 17: Salvage, end to end

**This section was rewritten after Tasks 15-16 and the out-of-band fixes R38-R47.**
Its first draft predated `Transfers` (R41), the measured `RowReader` (R43-R46), the
poisoned-connection behaviour (R47), and the guards that came with each. That draft's
code was wrong on almost every interface, so this version states constraints, not
code. Recorded as R48.

**Files (expected, not prescriptive):**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/SalvageJson.kt`
- Modify:
  - `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/ExportJson.kt`, for the paging seam;
  - `feature/ledger/src/main/kotlin/my/pinged/ledger/settings/Transfers.kt`, `SettingsState.kt`, `SettingsViewModel.kt` and `SettingsScreen.kt`;
  - `app/src/main/kotlin/my/pinged/MainActivity.kt`, for the SAF launcher.
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/SalvageTest.kt` and a screen test.

**What exists to build on:**
- `RowReader.Salvaging(open, close, isCancelled, onStep)`. It is `Closeable` and opens a fresh connection after every corrupt read, because a damaged read poisons its connection: everything after it answers code 26.
- `Salvaging.unreadable: Map<String, Unreadable>` reports the loss per table:
  - `Counted(recovered, unread)`: "Recovered R of R+U; U could not be read" always adds up. It can be wrong only where the index and table disagree inside the damage.
  - `AtMost(recovered, ids)`: an **upper bound**, not a count. `recovered + ids` is not a total.
- `PagedTable.RAW_CAPTURE`, `.TXN` and `.MERCHANT_RULE`.
- `Databases.aside` / `DatabaseKey.existingRawKeyPassphrase` / `DatabaseFactory.buildAside`: an instance of its own, a key read that never mints, an open that never seeds, and a refusal while a gate is up.
- `Transfers`: the process-scoped owner that serialises export, check, restore and delete, keeps outcomes, and links each request to its requester's `Job`.
- `RawDatabaseAccessTest`: no new `runInTransaction` or `openHelper` site outside its allowlist.

**Constraints:**

1. **Salvage never reads through `Databases.shared`.** Its reads meet damage by design, and damage poisons whatever connection it lands on. Use `Salvaging` with an `open` that goes through the `aside` path (no key minted, no seed, refused under a gate) and a `close` that closes. Always `use` the reader, so a cancel or any non-damage failure cannot leave a connection open on the damaged file.

2. **Salvage is a fifth `Transfers` operation.** It is serialised with the other four, refused (not queued) while one runs, carries the requester link, and keeps its outcome until acknowledged. The rows are inert while it runs. No second scheduling mechanism.

3. **The file must restore. This is the point of the task.** `ImportJson.verifyReferences` refuses the whole document over any dangling reference. Enumerate every reference it enforces, and keep each one satisfiable when rows are dropped. Do not stop at transactions naming a lost capture. At least these:
   - a capture marked as a duplicate of a capture that was not recovered. `e04ab72` now files copies of an *undecidable* original as duplicate suspects naming that original, and the original is exactly the row that will not read;
   - the `Uncategorized` row;
   - anything a merchant rule or a transaction points at.

   Decide for each what happens to the referring row: dropped, or kept with the reference cleared. Say why in the KDoc, and count what was changed so the user is told.

4. **The report is true, and adds up on the screen.**
   - Render captures from their `Unreadable`. `Counted` becomes "Recovered R of R+U; U could not be read". `AtMost` becomes "Recovered R; up to N could not be read", never a total.
   - `Unreadable.recovered` for `txn` includes transactions the reference filter then leaves out. Do not call them rows in the file. Report the left-out count in its own sentence.
   - A table with no damage needs no line.
   - Progress is rows written plus `onStep`'s running total.

5. **Offer it from the damaged state, and withdraw Export there in the same commit (R35, R37).**
   - **Add** `RecoveryNotice`'s "Rescue what can still be read" action as a real tap target whose role reaches semantics, wired to a SAF `CreateDocument` launcher as export's is.
   - Remove `Export everything` from `DAMAGED`. Design section 6's table gives Damaged no Export. No commit on this branch may offer neither.
   - The DAMAGED copy says what is readable "can be written to a file and restored afterwards". That becomes true here; check it still is.

6. **Cancellation and refusal** behave as they do for export: an abandoned salvage deletes its document and keeps no outcome, and a never-started salvage releases its document. Reuse the machinery `e6e2b9d` built, not a copy of it.

7. **Measure, and put the numbers in the KDoc:**
   - the wall time of a salvage at 10,000 and 50,000 captures with `damageMidFile` damage;
   - whether a salvage running beside the listener stalls stage one's insert, given that each of salvage's opens may take a lock.

**Tests, each falsified by reverting the piece it guards:**
- A real damaged ledger gives back rows, and **the salvaged file restores** through `Restore.replaceEverything`. Falsify by removing the reference filter: the restore must then be refused.
- The same for a damaged ledger holding duplicate suspects that name an unreadable original.
- `Counted` adds up against the seeded total. `AtMost` is never rendered as a count.
- Salvage leaves `Databases.shared` unpoisoned: a capture right after a salvage is stored.
- Salvage is refused while another operation runs and under a gate. A salvage cancelled mid-walk deletes its document.
- A DAMAGED screen shows the rescue action and does not show `Export everything`; tapping the action launches the picker.
- The healthy-path export tests (`RoundTripTest`, `StreamingTest`, `ExportCursorTest`, `ExportIsolationTest`, `ExportCompletenessTest`) pass unmodified. That is the guard that the paging seam changed nothing.

**Gate:** `./gradlew connectedDebugAndroidTest` twice, then `./gradlew test lint :app:assembleDebug :app:processReleaseMainManifest`. Update CLAUDE.md's counts.

**Commit** as `feat: rescue what a damaged ledger can still read`. It is user-visible, so `feat`. Name explicit paths.

---

## Verification before the milestone is claimed done

- [ ] `./gradlew test lint :app:assembleDebug` — what CI runs.
- [ ] `./gradlew connectedDebugAndroidTest` — every instrumented test, on a device.
- [ ] `git status --porcelain core/data/schemas` is empty. Schema v1 never moved.
- [ ] Walk section 10 of the spec and confirm each listed state has been seen on a device, not only asserted: export idle/running/done/failed/failed-on-corruption, restore idle/confirm/running/done/failed, check never/running/clean/failed, delete typed-confirmation and the empty ledger afterwards, settings normal and storage-unavailable.
- [ ] Confirm on a device that the ledger's top-bar control reaches settings, the allow-list still works from inside it, and the capture-stopped banner's new button lands on the recovery notice.

## Open after the milestone

The final whole-branch review and its re-review found nothing blocking merge. These items remain, and
are recorded here because the execution ledger is deleted when the milestone closes.

**Before calling the milestone done**
- **The device walk this plan requires is not done.** That means seeing section 10's states on a
  device, the top-bar route, and the capture-stopped banner's button landing on the recovery notice.
  Compose tests assert most of it, but the three recovery screens have no artboard (design 13), and
  nobody has looked at them.

**At release**
- **Curate the CHANGELOG.** release-please would list 16 features and 23 fixes:
  - 20 of the fixes repair defects that only ever existed on this branch;
  - delete and restore each appear twice;
  - several `feat:` commits add code nothing called yet.

  Only `5118336` (the short or non-atomic key file), `1d7454c` (unguarded `ListenerStatus.report`)
  and probably `8c21110` touch behaviour that v0.3.0 users had. No change costs the user data or a
  permission, so the release needs no `!`.

**Follow-ups, not blocking**
- **A restore made before notification access is granted leaves restored `NEW` captures unparsed**
  until a grant and a foreground (`ListenerStatus.kt:164`). The outcome sheet does not say so.
- **The import requeues `MATCHED` captures that have no transaction** (`RawCaptureDao.requeueMatchedWithoutTxn`).
  Anything that comes to delete a transaction, such as the inbox Merge in spec 7.2, must move its
  capture off `MATCHED`, or every later restore brings the transaction back. Pin it in that milestone.
- **A process killed inside a restore's 0.5-2.4s wipe-to-import window leaves an empty ledger,**
  and the next launch says nothing about it, because `TransferState.lastWipe` lives only for the process.
- **The Card/Paper ground swap in contrast comments** (`Color.kt:41`, `SourcesScreen.kt:407-408`)
  predates this milestone.
- **Test runtime:** about 18s of the 208s is spent waiting out real backoff intervals that could be
  injected.

## Appendix: rulings made during execution

Code comments and commit messages cite these by number (for example "R41"). They were decided
while the plan was executed, recorded with their reasoning and the cost if wrong, and are kept
here so the citations resolve after the working ledger is gone. A few (R3, R8-R10, R17, R21) govern
how the work was carried out rather than the product, and are kept for the record. Line and commit
references are as of the ruling.

**R2.** Task 10's "Consumes: `SettingsViewModel.export` (Task 7)" is stale and is corrected to consume only `TransferJob`; `exportTo` is new in Task 10 — why: Task 7 was amended during plan self-review to stop defining an `export` that Task 10 immediately replaced — costs if wrong: an implementer hunts for a method that does not exist. Plan patched.

**R3.** where a task's Steps touch a file its Files block omits, the **Steps are authoritative** — why: every such case already appears in that task's own `git add` line, so the omission is in the summary, not the work — costs if wrong: a reviewer flags an out-of-scope file; the ledger answers it.

**R4.** Task 11's Files block gains `transfer/Restore.kt` — why: Task 11's whole purpose is reordering `replaceEverything` to validate before destroying, and omitting the file it fixes would let an implementer patch the symptom in the ViewModel instead — costs if wrong: none. Plan patched.

**R5.** `WipeCounts` is carried on `SettingsState` as `wipeCounts: WipeCounts?`, added in Task 12, not Task 7 — why: nothing before Task 12 needs it, and putting it in Task 7 would add a field with no reader for five tasks — costs if wrong: one extra field move. Plan patched.

**R6.** Task 7's holder keeps its direct `try/catch` around `Databases.shared` rather than `CaptureStorage.guarded`, and the Interfaces line is corrected — why: `guarded` collapses the whole `DatabaseUnavailableException` family into one `unavailable` branch, and this screen exists to tell `KEY_GONE` from `UNREADABLE`, which is exactly the distinction it erases — costs if wrong: the storage-unavailable DataStore flag is not refreshed from this screen; the banner still sets it from capture and `MainActivity.onResume`. Plan patched.

**R7.** the conclusion is contradicted by measurement and is rejected; the observation is real and the design is corrected. Design 2.3 records a run on the same fixture where `integrity_check` returned a problem list ("not ok") and a row walk recovered 2,200 of 5,000 captures, deterministically, twice on separate connections. So both behaviours exist and **which one occurs depends on which page the byte lands in** — not on the crypto config. What the implementer surfaced is a genuine flaw in my `Result` type, not in the milestone's premise: `Integrity.check` is only ever reached on a database that already opened, so classifying a pragma abort as `Unreadable` conflates "did not open" with "opened, and the check could not finish". Those are different states and only the first is hopeless. Decision: drop `Result.Unreadable`; a `SQLiteException` from the pragma is `Damaged`. Salvage is unaffected — it depends on rows reading up to the damaged page, which the same measurement confirms, not on the pragma completing. Costs if wrong: a database that cannot be read at all would be offered a salvage that recovers nothing and reports 0 rows — a wasted action, not a data loss. Plan patched: Task 1's Result type and KDoc, Task 13's `when`.

**R8.** commit `eaabdff` ("docs: a pragma that throws is damage...") also carries the implementer's `core/data/src/androidTest/.../Fixtures.kt` `damageMidFile` helper, because I used `git commit -a` while a subagent had work in the tree. History is not rewritten — why: the branch is unpushed so a rebase is cheap, but SHAs are already recorded in this ledger and quoted to a running subagent, and the defect is a test fixture sitting in a `docs:` commit, which changes no release behaviour (`docs:` and test-only code both bump nothing) — costs if wrong: one commit's message understates its contents; `git log --stat` shows the truth. Practice changed for the rest of the run: stage explicit paths, never `git commit -a`, while any subagent is live.

**R9.** task briefs are regenerated after every plan patch — why: the Task 1 implementer worked from a brief written before `eaabdff` and implemented the stale three-case `Result` before catching it, costing a rewrite — costs if wrong: none; regeneration is one command.

**R10.** the plan's commit steps are under-specified, not wrong, and are not patched in 15 places — instead every remaining implementer dispatch carries the requirement that a commit message is a subject line plus a short body giving the reason and what was measured, per CLAUDE.md — why: the defect is in how the plan abbreviates its final step, and fixing it in the dispatch template reaches every remaining task at once — costs if wrong: Tasks 1 and 2 keep bare-subject commits; both are small and self-evident from their diffs.

**R11.** `CaptureCaches.clear` keeps the whole-store wipe; its KDoc is corrected instead. The reviewer is right that the KDoc's stated reason is false — `SourceCounters`' per-package seen counts and the heartbeat describe the *device*, not the database, so "every key in it describes a database about to stop existing" is not true of them, and a comment asserting something false is the defect. The behaviour stands: both callers destroy the entire ledger first (restore is discard-then-import, behind a typed confirmation), §9.6's allow-list itself travels in the backup so nothing the user chose is lost, and the counts rebuild as notifications arrive. Splitting into two functions to spare a cosmetic discovery counter would reintroduce exactly the named-list-to-forget-to-update failure the whole-store wipe exists to avoid, where the symptom — a restored backlog never re-read — is silent. Costs if wrong: after a restore the Sources screen shows 0 seen for each package until new notifications arrive; no user choice and no captured data is affected.

**R12.** Task 6 implements the validate-before-destroy ordering directly, and Task 11 keeps only its falsifying test — why: as planned, Task 6 committed a `replaceEverything` that wipes before it reads, so a file that turns out not to be a backup destroys the ledger. That is the exact loss this milestone exists to prevent and it violates the spec's own invariant that nothing deletes without a separately confirmed act; shipping it knowingly, even for one task, is not defensible on a branch that may be interrupted. Falsification is preserved: Task 11 now moves `validate()` after the wipe, watches its own assertion fail, and restores. `DatabaseFactory.buildAt` moves to Task 6 with it. Costs if wrong: Task 6 is larger and its implementer may need to ask about factoring `build`. Plan patched (48c1724); briefs 6 and 11 regenerated.

**R13.** keep `Reparse` internal; move only `aRestoredBacklogIsReRead` to a new `RestoredBacklogTest` in `feature/capture/src/androidTest/`, beside `ReparseTest`. Why: this project has already decided this exact question — `feature/capture/build.gradle.kts` documents its androidTest→ledger dependency as chosen over "widening `CaptureIngest` from `internal` to public, which would put this module's private stage-one entry point into every other module's compile surface, permanently, to buy one test". `Reparse` is the same shape. The test's subject is capture's sweep, so that module is its right home rather than a workaround. Costs if wrong: one test class sits away from the code it restores through, and the ledger-side `RestoreTest` covers only the replace. Fixtures: `DatabaseFactory.build` already seeds categories, `ExportJson` is reachable via ledger's main, and the `RawCapture` literal is written inline — so no ledger androidTest fixture is needed.

**R14.** the probe gets its own **ephemeral random key** rather than the app's. Passing the probe's own name through to `rawKeyPassphrase` — the obvious fix, and what that parameter exists for — is WORSE than the bug: with the key file gone, the probe's path does not exist, so the guard falls through to `create(file)` and mints a new key at noBackupFilesDir/db.key, which is precisely what that guard's KDoc says "would make an intact database permanently unopenable and indistinguishable from corruption". A throwaway validator must never touch the app's key material. Costs if wrong: the probe validates under a different key than the real database; every other pragma and the schema stay identical, and the key is not what `ImportJson`'s guards read.

**R15.** cache invalidation moves to AFTER a successful import — why: it shrinks C2's destructive window to wipe+build+import, and "invalidate caches once the restore succeeded" is the honest ordering. If clearing then fails, the cost is a stale swept mark (the v0.2.1 bug, recoverable by a later clear) instead of a ledger lost outright. Costs if wrong: a crash between import and clear leaves a restored backlog unre-read until the next clear.

**R16.** `CaptureDays.forgetProcessMemo` loses its `@VisibleForTesting`, with a KDoc naming its production caller. Why: Task 4 made `CaptureHealth.forgetProcessMemo` production-reachable through `CaptureCaches.clear`, and it calls `CaptureDays.forgetProcessMemo()` — so `./gradlew lint` fails `VisibleForTests` at CaptureHealth.kt:94 and the branch does not pass what CI runs. The call is not tidiness: after a wipe the day memo is stale, and a `CaptureDays` that still believes it wrote today's row would skip writing it into the new empty table. Verified by reproducing the lint failure. Attributed to Task 4; fixed in Task 7's fix round because the branch must be green now. Costs if wrong: one more method loses a tests-only annotation it had earned.

**R17.** every remaining implementer dispatch requires `./gradlew test lint :app:assembleDebug` — what CI runs — before committing. Why: three tasks shipped before anything ran lint, and the failure above sat undetected across two reviews because I told reviewers not to run suites. Costs if wrong: a few minutes per task.

**R18.** every mutating operation on `SettingsViewModel` goes on one `enqueue()` chain, not just `load()`. Why: the race that changes what the user sees is between operations, not within one. A resume-fired `load()` can read `damaged == false` before `check()`'s `recordCheck` lands and publish after it — last write wins — so the screen says HEALTHY moments after a check found damage, reachable by an ordinary app-switch during the measured 121ms/10k-capture check. A stale refresh landing after `deleteEverything()` is worse: pre-wipe counts redisplayed on the screen whose job at that moment is telling the truth about what still exists. `SourcesViewModel` already funnels every mutating operation through one tail for this reason. Costs if wrong: operations serialise behind one another, which is what that holder already does and what its KDoc defends.

**R19.** `SettingsState.sourcesOn` becomes nullable and its read is guarded. Why: `refresh()` calls `enabledCount()` whenever storage is HEALTHY *or DAMAGED*, and DAMAGED comes from a persisted flag rather than a live check — so every resume of Settings after a recorded failure re-issues a count query against a database that can abort mid-scan. `refresh()` runs in `viewModelScope`'s bare `SupervisorJob` with no handler, which this codebase's own comments (SourcesViewModel.kt:196) say kills the process. The screen built to offer salvage would crash on arrival exactly when damage is real — the same shape as Task 6's C1. Catching to `0` was rejected: "0 ON" is a wrong count, and this milestone's invariant is that no count is presented as evidence of health (design 2.3, and the same reason `WipeCounts` is null on anything but healthy). Null means the row shows no value. Costs if wrong: the allow-list row is valueless on a damaged database until a check passes.

**R20.** `isCancelled` is wired to the coroutine's own liveness rather than to a new cancel button, and the "cancel" wording is corrected to say what is true. Why: the measured export is 334ms at 10,000 captures and 1,243ms at 50,000 — a user-facing cancel for a sub-second operation is ceremony, but a ViewModel cleared mid-export should stop writing and delete the partial document rather than finish a file nobody wants. Costs if wrong: a 50,000-capture export cannot be abandoned by hand; it can by leaving the screen.

**R21.** the two Minors are folded into this fix round rather than deferred. Why: M1 is a one-line duplicate string already visible on a device, and routing it to a separate session costs more than fixing it; M2 understates the one outcome this milestone exists to avoid understating. Costs if wrong: a slightly larger diff in a task whose review is otherwise clean.

**R22.** `WipeSheet` is content-only inside `ReceiptSheet`, not its own `Column(padding(20.dp))`. Why: the section was drafted before Task 11's fix round extracted `ReceiptSheet`; as written it both doubles the padding inside that wrapper and is the fourth hand-rolled chrome — the exact finding (I2) that fix round existed to close. Cost if wrong: none, this is the migration already mandated.

**R23.** the artboard's "Export it first" affordance is kept, wired to `SettingsScreen`'s existing `onExport`. Why: `design/Wipe.dc.html` draws it and the draft dropped it silently; the moment before an unbacked-up ledger is destroyed is the one moment the offer has value, and that is this milestone's subject. Cost if wrong: one callback, one row, and a sheet-to-sheet transition to get right.

**R24.** the typed field is a `BasicTextField` over a `Rule` underline, not `OutlinedTextField`. Why: nothing in `app` or `feature` draws a text input today, so this is the codebase's first and the artboard decides; Material3's box, floating label and focus tint are a second visual idiom on a letterpress surface — twice already a review finding on this branch. Consequence for the test: the label is a separate `Text`, so `onNodeWithText(label).performTextInput` would drive a node that takes no text; the suite finds the field with `hasSetTextAction()`.

**R25.** the `WHAT GOES` rows are the artboard's label/dotted-leader/mono-value shape, counts grouped as `%,d`. Why: the draft's `"$label  $count"` in one `Text` renders a row the artboard does not draw, and makes the label unfindable by exact text match — the positive case was untested and would have stayed so. Added `theCountsAreTheOnesGiven` and `theWordIsComparedExactly` to the suite.

**R26.** `AndroidGradlePluginVersion` added to the lint `disable` set in `build.gradle.kts` (3875d30). Why: AGP 9.4.1 shipped upstream and turned this branch AND `main` red against a pin neither touched. `build.gradle.kts` already disables `NewerVersionAvailable` and `GradleDependency` with exactly this reasoning written out — the AGP check is a third one that was simply missed, so this completes a decision the repo already made rather than taking a new one. Disabled rather than bumped: a bump re-reds on the next release. Verified in the right order — observed the two errors, made the change, observed `test lint :app:assembleDebug :app:processReleaseMainManifest` green.

**R27.** the sheet's `Transactions` count excludes `REJECTED`, to agree with `FEED_SQL`. Why: the two counts disagreed (months excluded them, transactions did not) and that was an oversight in my brief, not a decision; every number this app has shown the user excludes rejected rows, and the last screen before a destruction is the wrong place to introduce one they cannot reconcile. Cost if wrong: under-itemises physically destroyed rows, which the surrounding copy already covers.

**R28.** `dashedOutline` lifted into `theme/Perforation.kt` parameterised on colour ONLY, not on dash length. Why: `Perforation.kt`'s own invariant is that the dash pattern exists once, and a dash-length parameter reinstates the divergence one default away. Cost if wrong: a future artboard wanting a second dash length needs the parameter added back.

**R29.** the integrity verdict moves out of `:feature:ledger`'s `TransferStore` into a new `IntegrityStore` in `:core:data`; `:core:data` gains `androidx.datastore.preferences`. Why: the dependency runs `:feature:ledger` → `:feature:capture` → `:core:data`, so the worker cannot write `TransferStore`, and the drafted task logged the damaged verdict and dropped it. `Storage.DAMAGED` is read from that flag, so as drafted a weekly check that FOUND corruption changed nothing the user can see, leaving "tap Check now" and "an export happened to fail" as the only routes into the state — and Task 15's recovery screens nearly unreachable. A periodic check whose result reaches nobody is 121ms a week spent on a log line. `:core:data` owns the database the verdict is about, and both features already see it. Cost if wrong: a Room module now carries a preferences dependency, and the task grew a cross-module refactor of `TransferStore`, `SettingsViewModel` and `Wipe`.

**R30.** `Wipe.everything` propagates a forget failure on the success path and only swallows it best-effort when the destroy already threw. Why: the swallow was added because `Restore` called `Wipe.everything` outside its ledger-lost translation — but the same commit added that wrap, so the reason had already evaporated; with both callers handling a throw, an unfinished delete can be reported as one again. The `finally`'s real purpose (attempt the forget even when destroy throws) survives. Cost if wrong: a delete that cleared the ledger but not the verdict is now a visible failure rather than a silent one, which is the trade this milestone keeps choosing.

**R31.** `Wipe.everything` stops deciding. It still throws when `DatabaseKey.destroy` fails — fatal to both callers, and the property `ledgerLost` rests on — but reports a forget failure as a returned `Wipe.Leftover` so each caller applies its own policy: `deleteEverything` files it as the unfinished delete it is, `Restore` logs it and carries on with the import that can still succeed. Enforced with `@CheckResult` against this repo's `lint.abortOnError` rather than a naming convention — measured: ignoring the value fails the build. Cost if wrong: a stale DAMAGED banner over a freshly restored ledger until the next weekly check, documented at the branch that accepts it.

**R32.** the tests move from `app/src/androidTest/` to `app/src/test/`. Why: the drafted suite was plain JUnit with no `AndroidJUnit4` runner and no androidx.test import, filed under androidTest anyway; `app/src/test/` already exists (`BackStackRuleTest`), and a pure predicate tested there runs in CI's `test` task and needs no emulator. Cost if wrong: none.

**R33.** `GrantBanner`'s priority `when` becomes a pure `bannerFor(report, exportOverdue): BannerKind?`, with the composable reduced to supplying copy. Why: "after `current.looksDead`, so it can never appear while something is actually wrong" is this task's entire safety property, and inside a `private @Composable` reading a `StateFlow` nothing can reach it — reordered by accident, a user whose listener is dead reads "NO BACKUP" instead of "CAPTURE STOPPED". Every unfalsified claim on this branch has eventually become a review finding. Cost if wrong: a moderate refactor of a working composable, and the drawing still needs a device to test.

**R34.** the nudge uses `countAll()` — including REJECTED — where the delete sheet excludes them (R27). Why: the two ask different questions. The sheet itemises what the user would recognise losing; the nudge asks only whether an export file would carry anything at all, and it would. Recorded so a reviewer reads a decision rather than an inconsistency.

**R35.** `RecoveryNotice` draws WORDING only; the "Rescue what can still be read" action moves to

**R36.** the `STORAGE_UNAVAILABLE` banner becomes cause-neutral. Why: found while reading the code, not in the plan — `CaptureStorage.guarded` catches the SEALED `DatabaseUnavailableException` (key gone, unreadable, AND being deleted) and records one boolean, so the banner's "the key that unlocks it is gone from this phone" is shown on a full disk and while the user's own Delete everything runs. Design section 6: "its copy never says the data is gone in a state where it may not be." The new button takes the user to Settings, which DOES know which failure it is (`opens()` distinguishes by exception type). Cost if wrong: the banner says less; the precise sentence is one tap away. Alternative rejected: storing the exception kind in `capture_health` — a new persisted field for a distinction that Settings already computes live.

**R37.** Export is withdrawn from DAMAGED in Task 17, in the same commit that adds salvage, not here. Why: design section 6's table gives Damaged no Export, and `SettingsRows`' `readable = HEALTHY || DAMAGED` still offers it — but withdrawing it before salvage exists would leave a damaged ledger no way out. Swapping them in one commit means no commit on the branch ever has neither. Task 17's section carries this now.

**R38.** The stale ledger after a delete or restore (`LedgerViewModel` held a DAO bound to the instance `Databases.reset()` closed) is fixed NOW as its own commit, before Task 15's fix round, not deferred to the final review. Why: it is milestone-introduced — `reset()` became production API in this milestone — and it makes the central promise of Delete everything visibly false on the main screen. Every delete test passed because each checked the settings side and none looked at the ledger: a guard adjacent to its subject. Ordered first because Task 15's B2 (what the ledger says after a failed restore) depends on what the ledger shows after a reset. Cost if wrong: one task's worth of work inserted mid-milestone.

**R39.** hold a re-entrant gate over a restore's wipe AND import. Why: it closes the key race and the half-written-file opens in one mechanism, and converts today's silent drop of a notification in that window into a recorded one (the storage banner shows until the next guarded call). Same cost Delete everything already pays, for seconds instead of milliseconds. The reviewer showed the naive version fails: Wipe's own `whileDeleting` sets `deleting = false` in its `finally`, lifting an outer gate right after the wipe. Needs a depth count, or a wipe that does not gate when already gated.

**R40.** a confirmed restore checks cancellation BEFORE the wipe and runs wipe+import under `NonCancellable` once begun. Why: a cancel before anything is destroyed should cancel cleanly; a cancel halfway through is the worst outcome this milestone can produce, and a user backing out of a screen is not asking for their ledger to be deleted without a replacement. Cost if wrong: a restore the user genuinely wanted to abort runs to completion — which leaves them with the backup's data, recoverable, rather than with nothing.

**R41.** transfer operations (export, check, restore, delete) move to ONE process-scoped owner that serialises them process-wide and publishes their state; `SettingsViewModel` becomes a view over it. Why: R40 made a restore's lifetime the process, but its serialisation (`enqueue`) and its result (`_state`) stayed screen-scoped — (1) and (2) are the same mismatch. A process-level owner serialises destructive work across screens, keeps the outcome for whichever screen asks next, and lets a new screen show "finishing a restore" rather than an ordinary-looking screen that queues taps invisibly (the re-review's Minor). Cost if wrong: a moderate refactor of `SettingsViewModel`, mid- milestone, and one more process-lifetime singleton.

**R42.** nothing may reach the file through a `PingedDatabase` that `Databases` has replaced. `Integrity.check` (and `ExportJson`'s `openHelper` path) refuse an instance that is closed or no longer current, and `PeriodicIntegrity` validates its handle after its DataStore suspension. Why: a reopen through a stale helper bypasses the gate entirely, which makes every gate guarantee in R39 conditional on no one holding a handle. Cost if wrong: a check that would have run is skipped until the next due time — weekly, so cheap.

**R43.** salvage must terminate on real damage. The draft's fake returned an empty page past id 100 WITHOUT throwing; real damage on the table B-tree's rightmost path can make every keyset seek beyond the last good id throw, including the one that would answer "nothing left", and `Salvaging` then steps `cursor++` forever. Design 2.3's own walk stopped AT the exception — nobody ever tried past it. The walk needs an id upper bound from somewhere other than the damaged path; candidate: a secondary index (its own pages — why `COUNT(*)` said 5,000 on a file that read 2,200). Measure.

**R44.** the corruption classifier is measured, not assumed. Stepping over a transient disk I/O / full-disk error silently drops rows that would have read.

**R45.** `skipped` counts IDS stepped over — an upper bound on rows lost, because ids can have gaps. Task 17 must not present it as a count (its draft prints "2,800 could not be read"). If an intact index yields the real id set, the loss can be counted exactly.

**R46.** the algorithm is proven on real damage in Task 16, not deferred to Task 17 — its termination, classifier and bound all depend on how real damage behaves. And no gallop: jumping a damaged range can jump a readable island between two damaged pages, and losing readable rows is the one thing salvage exists to prevent.

**R47.** The poisoned shared connection (a damaged read leaves its SQLCipher connection answering code 26 to everything, and the weekly check ran on `Databases.shared`) is fixed out of band after Task 16's review and before Task 17, as R38 was. Why: it is milestone-introduced (Task 13), it silently drops the user's spending on exactly the ledger this milestone exists to protect, and Task 17's salvage is pointless if capture died the moment damage was found. Cost if wrong: another inserted task.

**R48.** Task 17's draft is replaced wholesale. Why: it predated `Transfers` (R41), the measured `RowReader` (R43–R46) and the poisoned-connection behaviour (R47); its code called `SettingsViewModel._state.value.copy`, `RowReader.Salvaging()` with no connection factory, read through `Databases.shared` (which salvage's own damaged reads would poison), and handled only one of `verifyReferences`' dangling references — while `e04ab72` now files copies of an undecidable original as duplicate suspects NAMING that unreadable original, a second dangling reference the draft would have shipped. Every code draft in this plan has been wrong on contact with the code, so the section now states the constraints and the tests. Cost if wrong: the implementer designs more itself — mitigated by the review loop.
