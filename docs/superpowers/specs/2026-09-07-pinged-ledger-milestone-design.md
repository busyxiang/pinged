# Pinged — ledger milestone design

2026-09-07. Follows the capture milestone, released as `v0.1.1` and running on
hardware. Section numbers prefixed `§` refer to
`2026-09-02-pinged-design.md`.

## 1. What this milestone is for

The capture milestone ends with an app that records transactions and cannot
show you one. `v0.1.1` on a phone reaches the capture-source allow-list and
stops there, because that is the only screen. Every `txn` row written since
notification access was granted exists in an encrypted database with no reader.

This milestone builds §9.1, the transaction list, and makes it home.

It has a second job that is not in §9.1 and is worth stating plainly, because it
changes what "done" means. **Nothing in the shipped app can currently tell a
captured notification that parsed from one that did not.** Stage two logs
`Stage two: N processed, M failed`, where "processed" means only that the row
left `NEW`; a row filed `UNMATCHED` because no rule recognised a bank's wording
logs identically to one that produced a transaction. The release build is not
debuggable, so `run-as` cannot read the database, and `ExportJson` works but has
no UI. So the transaction list is also the first surface on which Task 14 of the
capture plan — does a real Malaysian bank notification become a row worth
looking at — can be answered at all.

## 2. Decisions taken

Each of these closes off an alternative, and the alternative is recorded so a
later reader can see it was considered rather than missed.

| Decision | Not chosen, and why |
|---|---|
| Transaction list first, before the review inbox and before settings | Settings is smaller and would retire a live risk: real financial history is accumulating on a phone with no way to get it off, and §11.1 says that history is unrecoverable. It lost because the list is what the app is for and what makes capture observable. **The export risk does not go away by being deprioritised** — see section 9 below. |
| Core feed only: rows, day subtotals, pinned month summary | §9.1 also specifies search and filter. Search needs an FTS table, which is a new entity, which moves Room's identity hash — schema v2, a migration, and the frozen-schema guards updated. Filters are `WHERE` clauses and cost no migration, but roughly double the screen's surface for a ledger that is currently empty. |
| Manual categorization only | §9.1's chip writes a learned rule (§6.1). Deferred: the tap-to-assign half makes the month summary's top-three honest for one `UPDATE`, while §6.2's broadening-on-evidence and §6.3's retroactive application are a subsystem. |
| Navigation 3, pinned `1.1.7` | Navigation 2 (`navigation-compose`) works, and Google has not deprecated it. Nav3 was verified stable rather than assumed — see section 5.1. Hand-rolled navigation was rejected for a reason specific to this app: a `MutableStateFlow<Screen>` does not survive process death, and this process is created and destroyed constantly by design. |
| Paging 3 | §15.4 already decided this and gave the reason: `Flow<List<Txn>>` re-emits every row on every insert. A plain `Flow` is cheaper today and honest YAGNI at zero rows, but retrofitting paging is a screen rewrite, and Room's `PagingSource` invalidation is what makes a new capture appear live. Hand-rolled keyset paging gets neither invalidation nor separators and was the worst of the three. |
| `androidx.lifecycle.ViewModel` | `SourcesViewModel` is named ViewModel and deliberately is not one; its KDoc says the artifact would be "a dependency bought for one class". That reasoning has expired rather than been wrong: it was true at one screen and is not at two, with four more specified. `Pager`'s flow needs `cachedIn(scope)` on a scope that outlives configuration change, which is the problem `ViewModel` exists for. |
| Ledger home, top-bar icon to the allow-list | Bottom navigation would give a screen touched roughly once the same prominence as the one looked at daily. A settings screen holding the allow-list would pull §9.5 into scope — attractive, because it also gives `ExportJson` a home, and rejected only to keep this milestone one screen wide. |

## 3. Scope

**In.** The transaction list as home: paged chronological rows grouped by day
with day subtotals, a pinned month summary with a total and top three
categories, manual category assignment, the capture-source allow-list reachable
as a second destination, and the capture-stopped banner relocated so it is
visible on both.

**Out, and named so the plan cannot quietly grow.** Search (§15.2), filters,
charts (§8, §9.3), the review inbox (§9.2), learned merchant rules (§6.1),
broadening (§6.2), retroactive application (§6.3), settings (§9.5), the export
and import UI, manual entry (§9.4), transfer and reload confirmation (§7.3),
CSV export (§12), delete-all-data (§11.3).

**In after all, and it is part of §9.1 rather than of another section, so it
has to be said here:** the per-row **category icon**. It was declared out
twice — first because the app shipped none of the Lucide glyphs, then because
the row needed a layout decision — and both reasons have since been answered:
the fourteen `icon_key` icons spec §4 names ship as bundled vector drawables,
and the row holds a fixed-width leading slot so the rows with nothing to draw
keep the merchant names aligned. An **excluded** row is given a mark of its
own rather than its category's, because §7.3 takes it out of every total and
the category it carries therefore describes nothing; the artboard draws it the
same way. The category also stays spelled out in the line beneath the
merchant, so the glyph is a second encoding rather than the only one.

That it was reasoned in `LedgerScreen`'s own documentation during
implementation and never declared here is how a specified-and-not-built item
stays invisible to a scope check — which is why it is written down here now
that it is built.

## 4. The data layer

**No schema change. Schema v1 stays frozen**, and this was checked rather than
hoped for: every index these queries need is already on `txn`, which is a sign
§15.1 was written with this screen in mind.

    Index("occurred_at")                      the feed's ORDER BY
    Index(["local_date", "occurred_at"])      day subtotals
    Index(["state", "occurred_at"])           what counts, versus what shows
    Index("category_id")                      the month summary's top three

`txn` already carries `local_date` as a stored column, which is the key §9.1
asks day subtotals to be grouped on, so nothing has to derive a local date at
query time.

Four reads. `TxnDao.pageFrom` is **not** one of them and must not be reused: its
KDoc says it is the export cursor, keyset on `id` ascending, which is the wrong
order for a feed.

### 4.1 The feed

    SELECT * FROM txn
    WHERE state != 'REJECTED'
    ORDER BY local_date DESC, occurred_at DESC, id DESC

Returned as a Room `PagingSource<Int, Txn>`.

**`local_date` leads, which this section originally did not say and had to be
corrected to.** `local_date` is not a function of `occurred_at` — it is the day
the money moved in the zone it moved in (§15.7) — so ordering on `occurred_at`
alone makes the sequence of days down the list non-monotone: X, Y, X. Section
5.4 has the headings break on `local_date`, so that draws the same date twice,
and each heading looks that date up in the aggregate and prints the whole day's
subtotal. Monday's total, twice, with nothing on screen adding to either. The
cost is nil: `(local_date, occurred_at)` supplies the whole `ORDER BY` as a
reverse index scan with no temp b-tree, measured at 0.443ms against 0.446ms for
a page 4000 rows deep.

The `id` tiebreaker is not decoration. Two captures inside the same second — a
bank posting a payment and its own confirmation — are otherwise ordered
arbitrarily, and an arbitrary order under paging is an unstable one: the same
row can appear on two pages or on none.

`PENDING` and excluded rows **are in the feed**. They are not in any total. See
section 6 below.

### 4.2 Day subtotals

Grouped by `local_date`, over `state = 'COMMITTED' AND is_excluded = 0`, signed
by `direction` so a `REFUND` reduces its day rather than adding to it
(`Direction` is `EXPENSE, REFUND`).

Separate from the feed, because §9.1 is right that a page boundary can fall
mid-day. A subtotal summed from loaded rows would be short by whatever sits on
the next page, and it would be short *silently* — the number looks like a
number.

### 4.3 Month summary

The same predicate. One row for the total, plus `GROUP BY category_id` limited
to three.

### 4.4 Month trust

From `capture_day`, which holds `listener_bound` and `saw_any_notification` per
day. §8 requires that "a gap in capture is never drawn as a zero", so the month
total greys out and is labelled do-not-trust when the month contains days
capture was not alive for.

This query is the one a reader is most likely to think optional. It is not.
Without it the summary confidently understates a month the app spent dead,
which is the most damaging thing this screen could do: the number is wrong, it
looks right, and the user has no way to suspect it.

### 4.5 Currency

`txn` has a `currency` column and the pack produces only MYR today, so a single
`SUM` would be correct now and wrong later. Totals `GROUP BY currency` and
render one line per group. A second currency then appears as a second line
rather than as ringgit added to something else — a failure that would otherwise
be invisible.

### 4.6 One predicate, three places, held together by tests

`state = 'COMMITTED' AND is_excluded = 0` is §8's "excluded and pending rows
never enter a total", and 4.2 and 4.3 both need it — three queries in all, since
4.3 is a total and a `GROUP BY category_id`. If it drifts between them, the day
subtotals and the month total disagree and the screen contradicts itself in a
way that reads as an arithmetic bug in the app rather than as a predicate typo.

**This section originally said the predicate is defined once, and the
implementation deliberately does not.** It is written out in all three
`@Query` strings. A `const val` would reach Room's processor — Kotlin folds a
compile-time constant before the processor sees the concatenation — so the
choice is about reading, not capability: three short literals diff more
clearly than one assembled at a distance, and a `@DatabaseView` is ruled out
outright because it would move the schema's identity hash on a frozen schema.
What actually stops the drift is behavioural: `LedgerAggregateTest` covers
each half of the predicate against each of the three queries. `TxnDao.dayTotals`
carries the argument and names the cases.

## 5. Presentation

### 5.1 Dependencies

Navigation 3 was verified against Google's Maven rather than recalled:

- `1.0.0` stable (Nov 2025) through **`1.1.7`** (26 Aug 2026), seven patch
  releases past `1.1.0`. `1.2.0-beta01` is in flight; pin the stable `1.1.7`.
- `navigation3-runtime` depends on `androidx.lifecycle:lifecycle-runtime`
  and **not** on `lifecycle-viewmodel`, so it does not force the artifact this
  project declined; the ViewModel decision in section 2 is taken on its own merits.
- It depends directly on `savedstate` and `savedstate-compose`. The back stack
  is saved state, which is the whole reason it beats a hand-rolled switch here.
- `navigation3-ui` floors at Compose `1.10.0`. The Compose BOM in use,
  `2026.08.00`, pins `1.12.0`, so this project is ahead and nothing is
  upgraded or downgraded transitively.
- Nav3 keys are `@Serializable`. This costs nothing new: the
  `kotlin.plugin.serialization` plugin is already in the root build as
  `apply false` and `:core:parse` already applies it for `Pack.kt`.

Added: `navigation3-runtime`, `navigation3-ui`, `lifecycle-viewmodel-compose`,
`room-paging`, `paging-runtime`, `paging-compose`, and Nav3's per-destination
ViewModel scoping artifact.

**Two things deliberately not asserted here**, because they were not verified
and this project's dependency table exists to record exactly this kind of
check: Nav3's `minCompileSdk` from its AAR metadata — not exposed as fetchable
text, negligible risk for an August 2026 release against `compileSdk 37`, and
it fails loudly at build time the way `sqlcipher-android` 4.18.0 did — and the
exact coordinate and version of the ViewModel scoping artifact, which tracks
the lifecycle line rather than navigation3's. Both are a minute's work at
implementation time and neither should be written into the plan from memory.

### 5.2 Module placement

The ledger screen goes in `:feature:ledger`, beside `sources/`. The `NavDisplay`
and the back stack stay in `:app`, which CLAUDE.md describes as wiring and the
single Activity. The Nav3 keys are `@Serializable` objects in `:app`, so the
serialization plugin is applied there.

### 5.3 State holders

`LedgerViewModel` owns the paging flow (`cachedIn(viewModelScope)`), the
day-subtotal map, the month summary and the trust flag.

`SourcesViewModel` becomes a real `ViewModel`. Its `enqueue` chain documents
that `tail` needs no lock because it is written and read only from the main
thread; `viewModelScope` is main-dispatched, so that invariant survives the
conversion — but it is an invariant, so the test asserts it rather than the
comment claiming it.

### 5.4 Day headers

Paging's `insertSeparators` inserts a header carrying **only the date**. The
composable looks that date's subtotal up in the aggregate's
`Map<LocalDate, Long>`.

Not because it is simpler. The feed and the aggregate are separate flows that
emit independently, so a header rendering a number from an unloaded or stale
aggregate is a wrong number on screen. Absent means no number yet. It never
means zero.

### 5.5 The banner

The capture-stopped banner moves above the `NavDisplay` rather than into the
ledger. Capture being dead matters on whichever screen the user is looking at,
and one holder computing it once is one place to get it wrong instead of two.

## 6. States the ledger must render

Five, and none may be mistaken for another.

| State | Renders |
|---|---|
| Storage unavailable (§11.1) | Nothing, plus the banner. The policy `SourcesScreen` already uses: an empty ledger asserts "you spent nothing" when the truth is "this cannot be read". |
| Not yet read | Not empty. `loaded = false` until the first query returns. |
| Genuinely empty | "Nothing captured yet", with a route to capture sources — and never "nothing spent". |
| Month untrustworthy | Total greyed and labelled do-not-trust (§8, and section 4.4 below). |
| Has pending or excluded rows | Both shown, both marked, neither counted. |

The last row carries more weight in this milestone than it will later. **There
is no review inbox yet**, so a `PENDING` transaction has nowhere to be resolved.
If the ledger does not show it, it is money that exists in the database and
nowhere on screen. Visible, marked and uncounted is the only honest option until
§9.2 exists.

## 7. Testing

The semantics are SQL, so the tests that matter are instrumented in
`:core:data` against the real encrypted database. The cases are the adversarial
ones:

- a day whose only transactions are excluded: subtotal absent, rows present
- a `REFUND` reduces its day
- a `PENDING` row is in the feed and in neither total
- a page boundary falling mid-day: the header still shows the whole day
- two currencies produce two lines and never one sum
- inserting a `txn` invalidates the `PagingSource`
- a month containing an uncaptured day is reported untrustworthy

`:app`'s `LaunchTest` changes subject, because home is the ledger now, and gains
the storage-unavailable path.

Per CLAUDE.md each of these is falsified rather than trusted green. The
page-boundary test needs saying twice: with a small fixture the loaded rows
happen to contain the whole day, so a version that reads the subtotal from
loaded rows passes anyway. That test needs a fixture where the day demonstrably
straddles a page, and reverting the aggregate must make it fail.

## 8. Invariants that must not break

**The `onResume` ordering.** `MainActivity` currently runs
`sources.refresh().join()` and *then* reads health, because `refresh()` is what
opens the database and therefore what sets or clears the storage flag
`ListenerStatus.report` reads. With the ledger as home the allow-list may never
be composed, so the thing that opens the database becomes the ledger's first
read. The ordering has to be re-established against the new trigger. The
existing comment records that both directions of this bug were seen on device.

**Schema v1.** Nothing here changes an entity. If something appears to need to,
that is a signal the scope has grown into search or filters.

**`:core:parse` stays Android-free.** Nothing in this milestone belongs there.

## 9. Where this design is thin

**The pending ratio is still unknown.** The capture plan asked for it and it
cannot be answered yet: the ledger is empty. If a large fraction of real
captures arrive `PENDING`, the review inbox is the app's main surface rather
than a badge, and this screen's layout is wrong in a way no fixture will show.
The first weeks of real capture decide it.

**Every test runs on fixtures.** There are no real transactions. What fixtures
cannot answer is whether a Malaysian bank's notification parses into a row worth
looking at — Task 14's outstanding question, which this milestone gives somewhere
to appear but does not settle.

**The export risk is deferred, not resolved.** Real financial history is
accumulating on a phone, `ExportJson` is implemented and tested, and nothing in
the app can reach it. A reinstall, a factory reset or a lost phone loses
everything, unrecoverably (§11.1). This milestone does not fix that, and §11.2
argued import should have been first for exactly this reason. It should be the
next milestone unless something displaces it.

**Nav3 is new here.** The API is stable but young, the minor line is still
moving, and there is far less accumulated lore than for Navigation 2 when
something behaves oddly. This is a cost paid on purpose, not an oversight.
