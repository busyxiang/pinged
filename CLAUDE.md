# Working in this repository

Offline-only Android expense tracker. It builds a ledger by reading push
notifications from Malaysian banking apps and e-wallets. There is no network
permission and no server.

Design spec: `docs/superpowers/specs/2026-09-02-pinged-design.md`. Section
numbers in comments and commit messages refer to it.

## Commands

    ./gradlew test                        # 191 JVM tests
    ./gradlew connectedDebugAndroidTest   # 315, needs a device
    ./gradlew test lint :app:assembleDebug   # what CI runs

Instrumented tests need an emulator or phone attached. `:core:parse` is a plain
JVM module and has no instrumented source set, so anything device-specific is
tested from `:feature:capture`.

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
phone than in the tests. `PackLoader` refuses `\d` at load.

**Money is `Long` sen.** `BigDecimal` appears only in the string-to-sen step.

**Schema v1 is frozen.** Changing an entity moves Room's identity hash, and CI
regenerates the schema and fails on drift. Room's hash cannot see a renamed
enum constant, so the stored strings are frozen separately by
`EnumVocabularyTest`, nor a collation, which `SchemaConstraintTest` asserts off
`sqlite_master`.

**No whole-row upserts on `capture_source` or `capture_day`.** SQLite's REPLACE
deletes and re-inserts, so a partial write reverts `enabled` to false and
destroys allow-list consent unrecoverably. Every mutation is a targeted UPDATE
naming its own column.

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

Long, and about why rather than what. State what was measured, what was left
undone, and what is still not understood. Do not claim a test count or a
verification you did not run.
