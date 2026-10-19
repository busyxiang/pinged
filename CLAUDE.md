# Working in this repository

Offline-only Android expense tracker. It builds a ledger by reading push
notifications from Malaysian banking apps and e-wallets. There is no network
permission and no server.

Design spec: `docs/superpowers/specs/2026-09-02-pinged-design.md`. Section
numbers in comments and commit messages refer to it. Modules: README's Layout.

## Commands

    ./gradlew test                        # JVM tests
    .claude/bin/emu -- ./gradlew connectedDebugAndroidTest   # needs a device
    .claude/bin/emu -- ./gradlew :smoke:connectedMinifiedAndroidTest   # the R8 build
    ./gradlew test verifyRoborazziDebug lint :app:assembleDebug   # CI's checks

`:feature:charts` compares Roborazzi screenshots against goldens committed in
`src/test/screenshots/`. Plain `test` only renders them; `verifyRoborazziDebug`
is what compares. Record new goldens with CI's `workflow_dispatch` and its
`record-screenshots` input, which renders on the same Robolectric image the
check uses, and commit the PNGs from its artifact.

`:feature:ledger`'s Compose tests that open no database and measure no
pixels, bounds or type run on the JVM under Robolectric (`src/test`, at CI's
320x640): a test there costs a few milliseconds and no lease. One that draws
and measures stays in `androidTest`, because Robolectric's drawing is not the
device's. Builders both sets need are in `src/sharedTest`.

**Every device command runs under `.claude/bin/emu`, with
`run_in_background`.** It leases the one emulator that every worktree's
session shares, and a lease can wait longer than the Bash timeout. Before
running or writing an instrumented test, read `docs/agents/device-tests.md`:
filters, the affected-suites run that precedes calling work done, loops, and
CI's shorter screen.

`./gradlew test` needs no worktrees under `.claude/worktrees/`, or
`RawDatabaseAccessTest` scans their copies and fails.

Release is minified. `:app`'s own instrumented suite runs against debug,
because it shares the app's process and R8 strips the classes it needs;
`:smoke` is the only test of what ships. It installs as Touch 'n Go's package,
so it will not install on a phone that has the real eWallet.

**The repository is public, and a fixture is a real notification.** Copy
one into `core/parse/src/test/resources/fixtures/` verbatim except for two
masks. A card's digits become `****`. Every transaction reference (`REF:`,
`QR…`, `FPX ID`, `Merchant Reference No.`) becomes zeros of the same shape.
The header says what was masked. No rule reads either value, and a test that
needs a sample sentence uses the masked one. The header also says `A real …`
or `Constructed, not observed`; `DictionaryPackTest` reads it. Device
exports stay outside the repository. Nothing else from them goes in a file or
on an issue beyond counts.

## Architecture

Two stages with a **durable table between them**, not a queue. The listener
extracts fields and does one insert into `raw_capture`; a WorkManager worker
reads that table, matches rules, and writes `txn`. A row still at
`parse_status = NEW` is unfinished work, so a process killed between the stages
loses nothing. Do not move parsing into the listener callback.

`:core:parse` has zero Android dependencies and a Gradle check enforces it.

## Constraints that have already cost money

**Never `\d`, `\w` or `\s` in a pattern.** `java.util.regex` reads them as
ASCII; ICU on Android reads them as Unicode classes. A JVM test cannot see the
difference, and a full-width amount then parses to a different number on the
phone than in the tests. `PackLoader` (in `Pack.kt`) refuses `\d` at load.

**Money is `Long` sen.** `BigDecimal` appears only in the string-to-sen step.

**A released schema is frozen.** Changing an entity moves Room's identity
hash, and CI regenerates the schema and fails on drift, so a change is a
version bump, a committed `core/data/schemas/…/N.json` and a migration
`MigrationTest` runs from every released version. Room's hash cannot see a
renamed enum constant, so the stored strings are frozen separately by
`EnumVocabularyTest`, nor a collation, which `SchemaConstraintTest` asserts off
`sqlite_master`. Room's auto-migration rebuilds a table for some changes:
v3 to v4 is hand-written because it would have rebuilt `category`.

**No whole-row upserts on `capture_source` or `capture_day`.** SQLite's REPLACE
deletes and re-inserts, so a partial write reverts `enabled` to false and
destroys allow-list consent unrecoverably. Every mutation is a targeted UPDATE
naming its own column.

**Never close an instance `Databases.shared` served while anything may use
it.** Room refuses a transaction's own `END` on a closed instance, and the
connection keeps its write lock for the life of the process. An instance leaves
service by `retire` (damage), and is closed when the last `Databases.leasing`
lease ends; or by `reset` (a delete or a restore), which waits up to 5s for
every lease and then closes before it returns. A lease still in a transaction
at 5s keeps its lock: harmless only because both production callers unlink the
file next, so the lock is on a dead inode. So no lease may outlast a gate --
stage two stops at one before its next capture; the longest lease a gate was
measured to leave up is 66ms. Every statement on an instance `shared` served
runs under a lease or inside `whileLive`, except two: the seed in
`DatabaseFactory.build`, inside `shared`'s monitor before the instance is
published, and Room's invalidation tracker, which takes `CloseBarrier` and
which `close` waits for.

**Do not rename or move `PingedNotificationListener`.** The notification-access
grant is keyed to its flattened `ComponentName`; renaming voids it silently.

**Room 2.8 passes a single connection to SQLCipher**, so a transaction
serialises every other caller for its duration whatever WAL says.

## Verification standard

A behaviour with no falsifying test has not landed. Before claiming a fix
works, revert it and confirm its own test fails **for its own reason** -- this
branch has found more than twenty guards that checked something adjacent to
their subject and passed either way.

Measure rather than assume, and put the number in the comment. Several
decisions here (raw key mode, keyset paging, `synchronous = FULL`) are
justified by measurements taken on an emulator and recorded next to the code.

## Comments

Write what stops the next reader breaking the code: invariants, spec
references, the measurement behind a constant, the reason for not doing the
obvious thing. Do not write the history of the file -- git holds that. A
comment that narrates what the code used to do and why that was wrong belongs
in the commit message.

One KDoc per declaration. Two `/** */` blocks in a row bind only the last, and
the first then documents nothing.

## Commit messages

**Conventional Commits.** `type: subject`, then a short paragraph: the reason,
what was measured, and anything left undone or still not understood. Under
about fifteen lines -- reasoning that needs more than that belongs in the spec
or the plan, and the message should point at it rather than restate it.

The type is read by `release-please`, so it decides the next version rather
than only labelling the change:

    fix:   a patch bump.  0.2.1 -> 0.2.2
    feat:  a minor bump.  0.2.1 -> 0.3.0
    feat!: breaking, and a minor bump too until 1.0.0
    chore, docs, test, refactor, ci, build, perf: no bump

A user-visible change must be `feat` or `fix`. Nothing else opens a release
PR, so a rule that reads a new bank wording committed as `chore` ships to
nobody. `!`, or a `BREAKING CHANGE:` footer, marks a release that costs the
user something: data they have to re-enter, a permission they have to grant
again.

The subject still names the change in words rather than describing the patch,
and the body is still where the value is. Conventional Commits governs the
first line only.

Do not claim a test count or a verification you did not run.

## Agent skills

### Issue tracker

GitHub Issues on busyxiang/pinged, via `gh`. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
