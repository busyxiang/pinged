# Pinged — transfer milestone design

2026-09-18. Follows the ledger milestone, released as `v0.3.0` and running on
hardware. Section numbers prefixed `§` refer to `2026-09-02-pinged-design.md`.

## 1. What this milestone is for

The ledger milestone's own closing section nominated this one:

> **The export risk is deferred, not resolved.** Real financial history is
> accumulating on a phone, `ExportJson` is implemented and tested, and nothing
> in the app can reach it. A reinstall, a factory reset or a lost phone loses
> everything, unrecoverably (§11.1). This milestone does not fix that, and
> §11.2 argued import should have been first for exactly this reason. It should
> be the next milestone unless something displaces it.

Nothing displaced it. The candidate was §9.2's review inbox, and the phone
answered: no capture on it is `PENDING`, so no money is sitting outside the
totals and the inbox is a badge with nothing behind it. It stays deferred.

Meanwhile `v0.3.0`'s re-parse recovered months of captures that had been inert,
so there is materially more on that phone to lose than when the sentence above
was written. `ExportJson`, `ImportJson` and `Backup` are 1,350 lines of
finished, round-trip-tested code whose only callers are their own tests.

This milestone gives them a user, and builds the recovery paths of §11.1 that
they exist to serve.

**It is the recovery milestone, not a backup button.** That distinction was
taken deliberately, because the states that destroy data are exactly the ones
where the user is not in the app choosing to press Export: an OTA invalidating
a Keystore key, a device-to-device transfer, an OEM process-killer landing
mid-write.

## 2. What was measured

Every number here is from a Pixel_10a emulator, API 37, against a real
SQLCipher-encrypted database. The spikes that produced them were deleted; this
section is their output, and the numbers are repeated in comments at the code
they justify.

### 2.1 An integrity check cannot run at open

Each pragma ran on its own fresh open with the order alternated across three
repeats. Run back to back on one connection the second reads what the first has
already decrypted — the first attempt measured `integrity_check` at 1 ms
against `quick_check`'s 6 ms on the same file, which is an artifact and not a
result.

| Rows | On disk | `quick_check` | `integrity_check` |
|---:|---:|---:|---:|
| 1,000 | 0.9 MB | 6 ms | 7 ms |
| 10,000 (≈ 1 year at §15's 850/month) | 8.6 MB | 101 ms | 121 ms |
| 50,000 (≈ 5 years) | 42.5 MB | 496 ms | 644 ms |

Variance across repeats was ±2 ms. Cost is linear in file size: ~12 ms/MB and
~15 ms/MB respectively.

**Opening the database costs 4 ms** (`OpenTest`, pre-existing). So a check at
open makes the open 26× slower at one year of capture and 125× slower at five,
paid on every cold start of a process that is spawned per notification burst.

**§11.1 says corruption is "detected at open". This design does not do that**,
and the divergence is deliberate rather than an oversight.

The check therefore runs **on demand** from the transfer screen and
**periodically** from the `ParseWorker` that already runs. Once it is rare,
the 20 ms saving that would have favoured `quick_check` buys nothing, and
`quick_check` is the weaker instrument — it does not verify index entries
against table rows. **Use `integrity_check`.**

These are warm-cache numbers. The emulator is a production build, so
`/proc/sys/vm/drop_caches` is unavailable and a cold check could not be
measured. They are a lower bound; the decision does not turn on the margin.

### 2.2 An export holds the database, briefly

`ExportJson` writes the whole document inside one transaction so that the file
cannot contain a transaction referencing a capture the file omits, and Room 2.8
hands SQLCipher a single connection, so that transaction serialises capture for
its duration.

| Rows | Output | Database held |
|---:|---:|---:|
| 1,000 | 0.6 MB | 67 ms |
| 10,000 | 5.9 MB | 334 ms |
| 50,000 | 29.5 MB | 1,243 ms |

`ExportJson`'s KDoc cites 6001 ms, which is a writer blocked against a
deliberately held 6000 ms transaction — it measures the blocking, not the
duration.

This wrote to a counting stream in memory, so it **excludes SAF
document-provider I/O and is a lower bound.** The design's use of it is
qualitative: at realistic sizes the stall is a fraction of a second, so the
export screen does not warn that capture is paused. Progress and cancellation
are still wired, because `ExportJson` already takes both lambdas and the
five-year case is over a second.

### 2.3 Corruption is partial, and a row count lies about it

One byte was flipped deep inside a 1.3 MB encrypted database holding 5,000
captures, then each operation was run on its own fresh connection:

| Operation | Result |
|---|---|
| Open | succeeded |
| Walk rows | **2,200 of 5,000**, then `SQLiteDatabaseCorruptException` |
| `countAll` | **5,000** |
| `integrity_check` | not ok |
| Walk rows again, new connection | 2,200 — identical |

Flipping a byte in page 1 instead threw `DatabaseUnreadableException` at open,
which is what `DatabaseKey`'s own KDoc predicts: a damaged header and a wrong
key are the same failure.

Three findings, each load-bearing below:

1. **SQLCipher's per-page HMAC does not make corruption all-or-nothing.**
   Damage is contained to the pages it touches, so §11.1's "opens, but fails
   its integrity check" state is real and 44% of that ledger was still
   readable. This is what section 5 exists for.
2. **The partial read is deterministic**, not luck — the same count on a
   second connection.
3. **`COUNT(*)` is answered from an index without touching the damaged data
   pages, so it reported 5,000 rows on a database that could produce 2,200.**
   Nothing in the recovery path may present a count as evidence of health.

One flipped byte is a synthetic injury. Real corruption — a torn write, a
truncated file, a bad block — may distribute differently. The spike proves
partial readability is possible, not that 44% is typical.

### 2.4 The format question, answered on the read side

CSV was proposed as an alternative because it is smaller. It is, and it does
not matter. Same database, same rows, written and read three ways:

| | Write, 10,000 rows | Read, 10,000 rows |
|---|---|---|
| JSON backup, all six tables | 11.6 MB, 526 ms | **785 ms, decode + every insert** |
| CSV, transactions only | 1.36 MB, 100 ms | 33 ms decode |
| JSON, the same fields as the CSV | 2.57 MB, 149 ms | 35 ms decode |

**Decoding is 4% of a restore.** The other 96% is SQLite writing rows. A format
that decoded instantly would save 4% of a sub-second operation.

For the same fields JSON costs 1.89x the bytes — structural, since it repeats
every field name per row — and the two decode at the same speed, 35 ms against
33 ms, because `JsonReader` streams. The write side favours CSV by about 1.5x.

None of it bears on the backup, which is not a format choice: CSV cannot carry
six tables with referential integrity, and `ImportJson` reads JSON. It does
confirm §12's reasoning that CSV is the right shape for the spreadsheet export,
which is a different feature that happens also to write a file.

Two caveats. Both decode figures are medians after a warm-up pass; the first
attempt had CSV decoding 1,000 rows slower than 10,000, which was JIT. And the
CSV parser read the whole document into a string where `JsonReader` streams, so
a streaming CSV reader would likely beat 33 ms — saving 2% of a restore.

If the backup ever feels heavy the lever is raw-capture notification text, not
syntax: 11.6 MB of which the transactions are 2.57 MB.

## 3. Decisions taken

| Decision | Not chosen, and why |
|---|---|
| The recovery milestone, not an export button | A screen with Export / Import / Delete is a few days' wiring and protects only the user who remembered to press it before the phone died. The §11.1 states arrive unannounced. |
| `integrity_check`, on demand and periodically | At open, per §11.1 as written. Measured at 26× the cost of the open itself after a year of capture (2.1). `quick_check` was preferred while the at-open case was live and lost its only advantage with it. |
| Salvage export, tolerant of unreadable rows | Offering only "start fresh" in the damaged state. That discards the 2,200 readable captures of 2.3 because they share a file with 2,800 broken ones, on data Android will not replay. It is the largest piece of new logic here and the only part that recovers rather than protects. |
| Delete-all deletes the file | `clearAllTables()` plus `VACUUM`, which is what §11.3 describes. Deleting the file makes the `VACUUM` moot and rotates the key in the same act. |
| §9.5's settings screen, with only its YOUR DATA section live | A single `Data` screen. `design/Settings.dc.html` draws settings as the home for this, and the ledger milestone rejected it "only to keep this milestone one screen wide" — a reason that expires here, since this is the milestone that gives `ExportJson` a home. |
| No bottom navigation yet | The artboards all draw SPENDING / CHARTS / SETTINGS. `CHARTS` has no screen behind it, and a visible dead tab is worse than no tab. The ledger's top-bar control re-points from the allow-list to settings, and the allow-list moves inside it as drawn. |
| JSON only; CSV deferred with §12 | Building `Export.dc.html` as drawn. Measured (2.4): CSV is smaller and no faster where it counts, so nothing is bought by pulling §12's RFC 4180 quoting, UTF-8 BOM and formula neutralisation into a recovery milestone. |
| The nudge is the lowest branch of the existing banner | A new surface, a notification, or a dialog. `MainActivity.GrantBanner` is already a severity-ordered `when`; appending a branch makes it structurally impossible to nag about backups while capture is dead. |
| Cache-clearing is a direct call into `:feature:capture` | An injected lambda from `:app`, to avoid a module edge that **already exists**: `feature/ledger/build.gradle.kts` declares `implementation(project(":feature:capture"))`, and `SourcesViewModel` already calls `CaptureStorage`, `Graph` and `SourceCounters` across it. `Databases`' KDoc records the *database handle* moving to `:core:data`, not the edge being removed. So `:feature:capture` exposes a public clear beside `SourceCounters`, and the ledger calls it. |

## 4. Scope

**In.** §9.5's settings screen carrying only its `CAPTURE` and `YOUR DATA`
sections, with the allow-list moved inside it and the ledger's top-bar control
re-pointed at it; the export sheet, the replace-from-a-backup flow, the check,
and `Wipe.dc.html`'s delete confirmation; SAF plumbing for both directions with
progress and cancellation; §11.3's delete;
`integrity_check` on demand and periodically; the salvage export of section 5;
the four states of section 6 with only their true actions offered; the staleness
nudge of section 8; and the import path clearing the re-parse mark so a restored
backlog is re-read.

**Sequenced so it can be cut.** Salvage (section 5) is the largest piece of new
logic here and the only one whose path may never execute. The plan puts it last,
so that if it overruns, the milestone still ships with every other recovery path
working and salvage lands next — rather than the screen and the delete arriving
late because the rescue took longer than expected.

**Out, and named so the plan cannot quietly grow.** CSV export (§12) — it is for
spreadsheets, not recovery, and shares nothing with this but the SAF call. The
other nine items of §9.5. The review inbox (§9.2). Scheduled or automatic export.
Search (§15.2). Merge-on-import in any form.

## 5. Salvage

**The healthy-path export is the wrong instrument for a damaged database.**
`ExportJson` streams inside one transaction, so the first unreadable page throws
and leaves a truncated file its own KDoc says the caller must delete: one bad row
and the user gets nothing, out of a ledger that was 44% readable.

Salvage is a second read path with two differences and no others.

**It narrows rather than gives up.** Pages of 500 as now; a page that throws is
re-read in smaller spans to find which rows are unreadable, those are skipped,
and the walk continues from beyond them. It counts what it could not read.

**It keeps the file importable.** `ImportJson.verifyReferences` refuses the
*entire* document if any transaction names a capture the document does not
contain — which is precisely what dropping unreadable captures creates. Salvage
therefore records the capture ids that survived and drops transactions orphaned
by the loss. This is possible because `Backup.ALL_SECTIONS` writes
`raw_captures` before `txns`, so the surviving set is known by the time the
transactions are written.

**It reports the loss.** "Recovered 2,200 of 5,000 captures; 2,800 could not be
read." A salvaged file that silently omits rows is worse than no file, because
the user restores it and believes it.

**It runs outside a transaction.** The single-transaction guarantee exists to
make the file internally consistent; here the reference fix-up provides that
directly, and holding a transaction across a walk that is expected to throw
serialises capture for no gain.

## 6. The states, and what each may offer

| State | Detected by | Export | Salvage | Replace from a file | Start fresh | Wait |
|---|---|---|---|---|---|---|
| Healthy | opens, no failed check | ✓ | — | ✓ | ✓ | — |
| Damaged | a check failed, or an export hit corruption | — | ✓ **urged, first** | ✓ | ✓ | — |
| Key gone | `DatabaseKeyUnavailableException` | — | — | ✓ | ✓ | — |
| Unreadable | `DatabaseUnreadableException` | — | — | ✓ | ✓ | ✓ **default** |

**Healthy is the absence of bad news, not a clean bill of health.** The check
runs periodically and on demand (2.1), never at open, so at any moment the app
may simply not have looked recently. The screen therefore states when the last
check ran and offers to run one, rather than implying the database was verified
just now.

**A failed export is the likeliest way damage is discovered.** Because checks
are periodic, the common path is a user in the Healthy state pressing Export and
`ExportJson` throwing `SQLiteDatabaseCorruptException` partway. That is not an
error message and a shrug: the truncated document is deleted as always, the
screen moves to Damaged, and it offers salvage — which is the action that was
wanted. An export that dies on corruption and leaves the user where they
started would waste the one moment they were already trying to rescue their
data.

**The last row is why nothing deletes on its own.**
`DatabaseUnreadableException`'s KDoc lists what lands there: "a corrupt file, a
full disk or a storage error". A full disk is transient. A screen that offers
"start fresh" as the obvious response to that state will one day delete a
healthy database because storage was briefly full. Its default action is to do
nothing yet, and its copy never says the data is gone in a state where it may
not be.

**"Replace from a file" is one action, not two.** `ImportJson.requireEmptyLedger`
refuses a device holding any capture, transaction or learned rule, and in the
key-gone state there is no database to write into at all. So the sequence is
always discard-then-import, it is presented as a single confirmed act, and its
confirmation says in words what it destroys.

The `storageUnavailable` branch of `MainActivity.GrantBanner` gains a button
that navigates here. Its KDoc's reasoning is preserved, not overturned: the
banner still does not act, because "neither belongs behind a single tap on a
banner", and the destructive choices stay behind a screen that can explain them.

## 7. Delete all data

Two of §11.3's pieces already exist. `DatabaseKey.destroy` deletes the wrapped
key file and the Keystore entry. `Databases.reset` closes the pool before
dropping the handle, which its KDoc explains is load-bearing — otherwise "the
next statement writes to a deleted inode and the failure surfaces in some other
test class entirely". `reset` becomes production API here.

Two are dead and should be recorded as such rather than left for a later reader
to hunt: there are **no FTS shadow tables**, because §15.2's search is not built;
and the `VACUUM` was specified because `clearAllTables()` does not shrink the
file, which deleting the file makes moot.

**The hazard §11.3 does not mention is that capture races the delete.** The
listener lives in this process and calls `Databases.shared()` on the next
notification, rebuilding the database halfway through its removal. A
process-wide gate makes `shared()` throw `DatabaseUnavailableException` for the
duration, which reuses the path `CaptureStorage.guarded` already owns and tests.
Notifications arriving during a delete are dropped, which is correct: the user
asked for everything to go.

**Clearing the DataStore closes a known gap.** `Reparse`'s KDoc records that its
swept-pack mark does not survive a device transfer, and that "the fix belongs in
the import path". Delete-all clears `capture_health`, so a backup restored
afterwards is re-read rather than sitting inert — the `v0.3.0` bug arriving by a
second route. The import path clears the mark explicitly as well, because that
is where the invariant belongs and it must not depend on delete-all having run.

`captureStore` is `internal`, so the clearing function lives in
`:feature:capture` and is public beside `SourceCounters`. Nothing needs
inverting to reach it: `:feature:ledger` already depends on that module.

## 8. The staleness nudge

`MainActivity.GrantBanner` is a severity-ordered `when`. The nudge is its last
branch, so it can only appear when the grant is present, capture is alive and
nothing else is wrong.

It fires when there is something to lose and no recent copy of it: transactions
exist, and either no export has happened or the last was more than 30 days ago.
An empty ledger says nothing. **The 30 days is a guess and is recorded as one.**

`last_export_at` lives in a new `transfer` DataStore in `:feature:ledger` — a
separate file because `captureStore` is `internal` to `:feature:capture` and
`CaptureHealth`'s KDoc warns that two delegates over one file name throw.

`Backup`'s KDoc is explicit that DataStore does not travel in the export, so
after a restore the last export reads as never and the nudge fires. That is
wrong in the safe direction.

## 9. Presentation

The screens are drawn. `design/README.md` says the artboards "are the source of
truth", and `SourcesScreen`'s KDoc says "the artboard is the specification of
the visual result". Three of them are this milestone's: `Settings.dc.html`,
`Export.dc.html` and `Wipe.dc.html`. Where this design departs from one, it says
so and why.

No new module. `feature/ledger` already hosts `sources/` and `transfer/`; the
screens join them under `feature/ledger/settings/`. `Settings` is a third
`@Serializable data object` in `Destinations.kt`, which that file's KDoc says is
all a new destination needs.

### 9.1 The row grammar, taken from the artboard

Every settings row in `Settings.dc.html` is the same shape, and the new rows use
it rather than inventing one: a **label**, an optional **caption** beneath it in
small caps, an optional **value** on the right, and a chevron when the row leads
somewhere. "Capture health / LIVE, 2 MIN AGO" is the pattern a freshness value
follows.

### 9.2 Settings, and what is deliberately not on it

Entry is the ledger's existing top-bar control, re-pointed: it opened the
allow-list, and now opens settings, with the allow-list a row inside as the
artboard draws it. One control moves; no new navigation.

**Only the rows with a screen behind them are drawn.** Six of the artboard's
rows — capture health, unread notifications, pack import, taught rules,
categories, the review threshold — belong to milestones that do not exist.
Shipping them as dead rows is worse than shipping four that work, so the
`HOW THINGS GET SORTED` section is absent entirely and `CAPTURE` holds one row.

| Section | Row | Value on the right |
|---|---|---|
| CAPTURE | Capture sources | `4 ON`, as drawn |
| YOUR DATA | Export everything | **the last export**: `3 DAYS AGO`, or `NEVER` |
| | Replace everything from a backup | — |
| | Check my data | `CHECKED 2 DAYS AGO`, or `NEVER CHECKED` |
| | Delete everything | — |

**The last export is a value on its own row**, not a separate panel. That is
what the row grammar is for, it is where a user looks to find out, and it is the
same fact the banner nudge (section 8) reads — one source, two places, which is
the arrangement §4.6 of the ledger design already argues for.

The artboard's footer stays, because both halves are true and cheap: `NO
INTERNET PERMISSION · NOTHING LEAVES THIS PHONE`, and the build and pack
version beneath it.

The two new rows are not in the artboard because it was drawn before §11.2 made
restore a v1 feature. `Replace everything from a backup` carries the caption
`DISCARDS WHAT IS HERE FIRST`, because that is what it does (section 6).

### 9.3 Export, minus the half that is CSV

`Export.dc.html` draws two format cards, a range picker (`September` /
`Everything so far`) and exclusion toggles (`Transfers and reloads`, `The four
still awaiting review`). With CSV out, **all of it goes**, and for a reason
stronger than scope.

**Those options belong to the CSV card and are wrong on the JSON one.** The
artboard's own description of the JSON export is "enough to rebuild Pinged from
scratch" — a document filtered to September, or with pending transactions
withheld, rebuilds nothing. `ImportJson.verifyReferences` would refuse it
outright, because the transactions it kept reference captures the filter
removed. A filtered backup is not a backup, and offering the filter beside it
invites the user to make one.

What remains is the JSON card's text, a count of what is about to be written,
the action, and the artboard's closing line — `YOU CHOOSE WHERE IT GOES ·
PINGED CANNOT UPLOAD IT ANYWHERE` — which is a privacy claim this app can make
and should.

When §12's CSV lands, the cards and the options return with it, on its side.

### 9.4 Delete, taken from the artboard whole

`Wipe.dc.html` is adopted as drawn: `THIS CANNOT BE UNDONE`, the question, a
`WHAT GOES` itemisation, the paragraph explaining that Android will not hand
back notifications from the past, `Export it first`, and a **typed `DELETE`
confirmation** before the destructive action is live.

This replaces the vaguer "separately confirmed act" of section 12. Typing the
word is the right weight for the one action in this app that cannot be undone,
and `Export it first` is exactly where an escape hatch belongs.

**One correction the artboard could not know.** Its `WHAT GOES` counts come from
`COUNT(*)`, which 2.3 measured reporting 5,000 rows on a database that could
produce 2,200. On a damaged database the itemisation overstates what is being
lost. So on any state other than healthy the counts are omitted rather than
guessed, and the screen says it cannot count what it cannot read.

### 9.5 The recovery states have no artboard

Damaged, key-gone and unreadable were never drawn. Section 6 is their only
specification, and they are built from the same row grammar and the banner
copy that already exists in `theme/Strings.kt`.

### 9.6 Where the work runs

`viewModelScope`, not WorkManager. Both entry points already take `onProgress`
and `isCancelled`. WorkManager would buy survival across process death, which is
worth nothing here: a process death mid-export leaves a truncated file that must
be discarded regardless. **The ViewModel owns that deletion**, and it is the
first thing tested.

## 10. States the screens must render

Enumerated because a state with no drawing is a state that ships as a blank
region or a spinner that never ends.

**Export.** Idle, with the count of what will be written. Running, with progress
and a cancel — no "capture is paused" warning, per 2.2. Done, naming where the
file went. Failed, having deleted the partial document, saying why. **Failed on
corruption**, which moves the screen to Damaged and offers salvage (section 6).

**Replace from a backup.** Idle. Confirming, stating in words what is destroyed.
Running. Done, reporting `ImportReport`'s counts. Failed, and explicitly
**nothing was imported** — the read runs in one transaction, so a failure rolls
back rather than leaving half a ledger.

**Check.** Never checked. Running. Clean, with the time it ran. Failed, which is
the Damaged state.

**Delete.** The artboard's sheet; the confirmation disabled until `DELETE` is
typed; running; and the empty ledger afterwards, which is `Empty.dc.html` and
already built.

**Settings itself.** Normal. And storage-unavailable, where export and check are
not offered because they cannot run, and the rows say why rather than failing
when pressed.

## 11. Testing

- A failed or cancelled export deletes its SAF document. Falsified by removing
  the deletion and asserting the document survives.
- Delete-all leaves no database, `-wal`, `-shm`, key file, Keystore alias or
  DataStore key — asserted against the filesystem and the Keystore, never by
  calling the delete path a second time.
- A notification arriving mid-delete does not resurrect the database.
- **A restored backlog is re-read.** Falsified by reverting the import path's
  clearing of the swept mark: the restored captures must stay at their terminal
  `parse_status`. This is the `v0.3.0` failure, reproduced as a test.
- Salvage over a deliberately damaged file recovers the readable rows, drops the
  orphaned transactions, and the file it produces **imports**. The last clause is
  the one that matters; a salvaged file `verifyReferences` rejects is not a
  rescue.
- Salvage reports the count it could not read, and that count is not taken from
  `COUNT(*)` (2.3).
- Each state offers only its true actions, asserted against the state holder
  rather than the UI.
- The nudge stays silent on an empty ledger and on a recent export.
- Every state of section 10 renders, asserted against the state holder. The one
  that would otherwise ship unbuilt is export-failed-on-corruption, because it
  needs a damaged database to reach and is the state a user meets at the worst
  moment.
- The settings screen offers no row it cannot run: in the storage-unavailable
  state, export and check are absent rather than present and failing.
- The delete sheet's action stays disabled until `DELETE` is typed, and its
  `WHAT GOES` counts are omitted rather than guessed on any state but healthy
  (2.3).

Damaging a database for a test is the same byte-flip the spike used, kept as a
fixture helper rather than re-derived per test.

## 12. Invariants that must not break

- **Nothing deletes without a separately confirmed act.** Not on a transient
  failure, not as a side effect of import, not from a banner.
- **No count is presented as evidence of health** (2.3).
- **A produced file either imports or is deleted.** There is no third outcome,
  for the healthy export or the salvage.
- **The tag-only boundary on the signing keystore is untouched**; this milestone
  changes no workflow.
- **Schema v1 stays frozen.** Nothing here adds an entity or a column.

## 13. Where this design is thin

**The corruption evidence is one flipped byte.** Real damage may not distribute
like it. Salvage is built for a shape of failure observed once, synthetically.

**Cold-check cost is unmeasured.** The emulator is a production build and
refuses `drop_caches`, so 2.1 is a warm lower bound. The at-open decision has a
26× margin and does not turn on it; a future decision might.

**The 30-day nudge threshold is arbitrary** and has no evidence behind it.

**Salvage's narrowing strategy is unspecified here** — halving a failed page
versus falling straight to single rows is an implementation choice with a real
cost difference on a badly damaged file, and there is no measurement to decide
it. It belongs in the plan, with a number.

**Three of this milestone's screens were drawn and three were not.** Settings,
export and delete implement approved artboards; damaged, key-gone and unreadable
are designed here in prose and have never been seen. They are also the screens a
user meets while frightened, which is the worst combination of untested design
and high stakes in the app.

**Scheduled export is still not built**, so the nudge is the whole defence
against a user who does not act on it. Whether that is enough is the thing the
next months answer, and §16 already holds the larger version.
