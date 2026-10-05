# Pinged — design

Date: 2026-09-02
Status: approved design, ready for implementation planning

**Name.** Pinged. Play Store listing title: "Pinged — Auto Expense Tracker",
so the short name carries word of mouth while the descriptor carries search.
The name points at the mechanism, and its past tense states the product's
premise: the transaction is already recorded and you did nothing.

**Application ID.** `my.pinged.tracker`. This must be settled before the
first install on any device — changing an `applicationId` later is a
different app to Android, forcing a reinstall and discarding the database.
Since raw captures cannot be backfilled from before install (section 10),
that loss is permanent.

**API levels.** `minSdk 27`, `targetSdk` current-1 as Play requires.
Twenty-seven is not a preference, it is forced: named regex group access
(`Matcher.group(String)`, which the whole parser pack format in section 5
depends on) arrives at **API 26**, and
`NotificationManager.isNotificationListenerAccessGranted()` — the most
safety-critical check in the app — arrives at **API 27**. Below 27 that
check means hand-parsing `Settings.Secure.enabled_notification_listeners`.
Adaptive icons are also API 26; the `<monochrome>` layer needs
`compileSdk 33+` and is ignored below API 33.

If minSdk ever has to drop below 26, the pack compiler must resolve group
names to indices at load time and the runtime must use `group(int)`. That
changes rule compilation, so it is a decision to take now rather than
discover.

**Service class name.** The notification-access grant is stored by the
system against the flattened `ComponentName` of the listener service, in
`Settings.Secure.enabled_notification_listeners`. Renaming or moving that
class silently voids the grant with no user-visible event and no callback.
The fully-qualified name of the listener service is therefore as frozen as
`applicationId`.

**Launcher icon.** Adaptive icon, ink ground with a cream receipt stub and
one stamp-red line. Artwork stays inside the central 72dp of the 108dp
canvas, with the stub inside 66dp so no launcher mask clips it. A monochrome
layer ships for themed icons.

**Outstanding check.** Play Store name collision and a MyIPO trademark
search in class 9 (software) and class 36 (financial services). If Pinged is
taken by a finance app, the fallback is Notiflow, which changes nothing in
this design.

## 1. Purpose

Pinged is an Android expense tracker for Malaysian users that builds its
ledger from push notifications emitted by local banking apps and e-wallets,
with no manual data entry required for digital spending.

The product thesis: in Malaysia a normal person spends across two banks and
three wallets (Maybank/MAE, CIMB, Touch 'n Go eWallet, GrabPay, ShopeePay,
Setel), and every one of those apps already pushes a notification on every
transaction. That stream is a complete expense feed nobody is reading.

### Goals

- Capture digital spending automatically, accurately, and silently.
- Never invent a transaction that did not happen.
- Answer one question well: what did I spend this month, and on what.
- Work entirely offline, on device, with no account and no server.

### Non-goals for v1

- Budgets, savings goals, financial advice.
- Recurring/subscription detection.
- Account balances, net worth, reconciliation against real bank balances.
- Multi-user, sync, cloud backup.
- SMS ingestion.
- Multi-currency conversion (foreign-currency transactions are stored with
  the currency reported and excluded from MYR totals).

## 2. Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| Distribution | Personal build first, Play-ready architecture | No compliance overhead now, no dead ends later |
| Ingestion | `NotificationListenerService` only, no SMS | Google Play's SMS policy does not permit expense tracking as a use case |
| Data model | Flat expense stream, no accounts, no balances | Fastest path to a useful app; accounts not architecturally blocked |
| Categorization | Bundled merchant dictionary + learned rules | Fully offline, deterministic, user-fixable |
| Confirmation | Confidence-based hybrid | Only uncertain captures cost the user attention |
| Parse engine | Declarative parser pack (rules as data) | Format fixes need no app release |
| Charting | No chart library in v1 | v1 charts are rows, squares and lists |
| Network | No `INTERNET` permission | No network access at all; forces local design. Not the same as "cannot exfiltrate" — see 11.1 |

### Accepted risk

The flat model cannot detect that capture silently under-reported. This was
chosen with the trade-off understood. It is mitigated, not solved, by
retaining raw captures (section 5.5) and by capture health monitoring
(section 10). If under-counting later proves painful, the migration path is
to add an `account` table and attribute existing rows by `source_package` —
which is why `source_package` is stored on every transaction.

## 3. Architecture

### Modules

```
:core:parse       pure Kotlin, zero Android deps. Rule matching, normalization.
:core:categorize  pure Kotlin. Dictionary lookup, learned rules.
:core:data        Room entities, DAOs, repositories, SQLCipher setup.
:feature:capture  NotificationListenerService, capture health, OEM onboarding.
:feature:ledger   Compose UI: list, detail, review inbox, charts, manual entry.
:app              DI wiring, navigation, app-level settings.
```

`:core:parse` and `:core:categorize` have no Android dependencies. The whole
parse and categorize engine therefore runs under plain JUnit on the JVM
against a fixture corpus — no emulator, milliseconds per run. Since parser
quality is the product, this is the most important structural decision in
the design.

### Capture flow

Capture is two stages with a durable boundary between them. Stage one only
writes; stage two reads the table, never a queue.

```
STAGE 1 — onNotificationPosted(sbn), listener main thread
  │
  ├─ sbn.packageName is our own package        → return
  ├─ sbn.notification.flags & FLAG_GROUP_SUMMARY → return
  ├─ package not in enabled allow-list         → heartbeat only, return
  │
  ├─ extract via getCharSequence(...)?.toString():
  │     EXTRA_TITLE, EXTRA_TEXT, EXTRA_BIG_TEXT, EXTRA_SUB_TEXT
  │     plus sbn.key, sbn.id, sbn.tag, sbn.user, channelId, flags
  │     posted_at = sbn.postTime          (never notification.when)
  │
  └─ hand off to the write dispatcher (single thread)
        └─ INSERT raw_capture             ← the durable boundary
              content_hash = sha256(package | user | normalized_text)
              no timestamp in the hash

STAGE 2 — parse worker, reads raw_capture where parse_status = NEW
  │
  │  duplicate layer 1, and the id comparison is not optional: stage one
  │  inserts before stage two claims, so a query without it matches the
  │  row being processed and NOTHING is ever a transaction
  │
  ├─ earlier row, same sbn.key + content_hash, arrival = CATCHUP
  │                                                       → UPDATE_OF, stop
  ├─ earlier row, same sbn.key + content_hash, within 10 min, POSTED
  │                                                       → UPDATE_OF, stop
  ├─ same, BEYOND 10 min                       → carry on; becomes a txn
  │                                              flagged DUPLICATE_SUSPECT
  ├─ same content_hash within ±60s, different sbn.key   → DUPLICATE_OF, stop
  ├─ title, text and bigText all null                    → NO_EXTRAS, stop
  │
  ├─ RuleMatcher(package)
  │     1. transaction templates, highest priority first
  │     2. no template matched → consult reject patterns
  │            matched a reject → REJECTED
  │            matched nothing  → UNMATCHED
  │     3. a pattern ran out of time → GAVE_UP, and re-parse revisits it;
  │        never folded into UNMATCHED, because a timeout is not evidence
  │        that no rule matches
  │     (a capture matching BOTH a template and a reject is MATCHED,
  │      and the collision is logged for rule review — see 5.3)
  │
  ├─ Normalizer      amount → sen, occurred_at, local_date, merchant cleanup
  ├─ Categorizer     learned rule → bundled dictionary → Uncategorized
  ├─ DuplicateDetector  layer 2: same amount, different package, ±10 min
  ├─ ConfidenceGate  → COMMITTED, or PENDING with the pending_reason that
  │                    fired; a PENDING row with no reason is refused
  └─ commitCapture: INSERT txn AND mark the capture, in ONE transaction.
     Two statements would leave a kill between them with a transaction
     whose capture is still NEW, which then sorts first forever.
```

**Why the boundary is a table and not a queue.** An in-memory queue between
the callback and the insert loses captures on process death, which is the
failure this whole design is organised against, and the first notification
after a cold start is exactly when the process is least stable. So stage one
does the smallest possible durable write and nothing else; stage two is
free to be slow because its input is already safe. There is no
queue-saturation case to handle, because there is no queue holding
unwritten data.

**Stage one must stay small.** Extract, filter, hand off. Everything else —
matching, categorization, duplicate detection, the confidence gate — is
stage two's work on a background dispatcher.

An earlier draft of this design had parsing run inline in the callback on
the grounds that the regex is sub-millisecond. The regex is; the work around
it is not. A SQLCipher insert costs 5-20ms, index maintenance grows with the
table, and duplicate detection is a query. Doing that synchronously on the
listener's main thread is an ANR risk that gets worse as data accumulates,
which is exactly the failure mode this app cannot afford, because a killed
listener is a silent gap in the ledger.

Stage two processes rows in `posted_at` order so duplicate detection sees
captures as they arrived. Because its input is a table, an interrupted run
simply resumes: any row still at `parse_status = NEW` is unfinished work.

WorkManager is used for the daily health check, bulk re-parse after a pack
upgrade, and the pack-import dry run.

### Reading a notification is not straightforward

Four facts about the payload that the parse design depends on:

**Extras must be read as `CharSequence`.** `Bundle.getString(EXTRA_TEXT)`
returns **null** when the value is a `SpannableString`, because it casts
internally and swallows the failure. Banks and wallets very commonly bold
the amount, which makes the value a `Spannable`. Every field is read with
`extras.getCharSequence(key)?.toString()`. This single line decides whether
Maybank is captured at all.

**Some notifications carry no text.** An app that draws its notification
entirely with custom `RemoteViews` has null title, text and bigText, and the
content lives only inside the views — unreachable since hidden-API
restrictions landed in API 28. Such captures are classified `NO_EXTRAS`
rather than `UNMATCHED`, so the unread list (section 9.6) does not invite the
user to teach a rule for a message the app cannot see.

**Group summaries are skipped.** A notification with `FLAG_GROUP_SUMMARY`
carries aggregated or system-generated text, which produces either unread
noise or a duplicate of its own child.

**Work profiles post under the same package name.** A listener in the
personal profile receives managed-profile notifications, and the same bank
app in both profiles shares a package identifier — so `sbn.user` is part of
`content_hash` and is stored on every capture, or cross-profile posts
collapse into each other. `PackageManager` in the personal profile also
cannot resolve a work-profile-only package's label or icon, which the
capture-source screen handles the same way it handles any unresolvable
package (section 9.6). Capture is per user handle; the ledger is not split.

**Reconnecting recovers what is still on screen.** On
`onListenerConnected`, `getActiveNotifications()` returns notifications
still in the shade, and bank notifications often sit there for hours. Those
are ingested through the same stage-one path and deduplicated by
`content_hash` like anything else. This narrows but does not close the gap
after a kill: the honest claim is that history cannot be recovered beyond
what is still in the notification shade.

Android 15 may also redact notifications its classifier considers sensitive
from listeners without the privileged `RECEIVE_SENSITIVE_NOTIFICATIONS`
permission. The classifier targets one-time codes, which section 5.3 rejects
anyway, but a success message carrying a reference number could plausibly be
caught. Verify against a real corpus on an API 35 device before assuming
section 5.3's traps are the only obstacle between the app and a bank's text.

### Design stance: default-deny

A transaction is created only when notification text matches a known
transaction template for that specific package. The engine never creates a
transaction because it found a currency amount in some text. This single
stance is what prevents the dominant failure mode described in section 5.2.

## 4. Data model

Amounts are stored as `Long` sen. No floating point anywhere in the money
path, including intermediate parsing (use `BigDecimal` for the string → sen
conversion, then convert to `Long`).

### `raw_capture`

| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| source_package | Text | |
| posted_at | Long | `sbn.postTime`, system-assigned, always valid |
| when_millis | Long? | `notification.when`, kept always, trusted only when non-zero, not later than `posted_at`, and within 24h before it — see below for why the window is asymmetric |
| captured_at | Long | when this app saw it |
| sbn_key | Text, indexed | `sbn.key`, identifies the notification slot |
| notif_id | Int | `sbn.id` |
| notif_tag | Text? | `sbn.tag` |
| user_handle | Int | `sbn.user`, distinguishes work profile from personal |
| channel_id | Text? | so a muted channel can be diagnosed |
| flags | Int | `notification.flags` |
| arrival | Text | `POSTED` or `CATCHUP`. Which path delivered this capture: `onNotificationPosted`, or section 10.1's `getActiveNotifications()` rebind catch-up. Section 7.2's duplicate layer 1 rule 1 needs it — a catch-up post is by definition still live, which is what "the app refreshed its notification" actually means, so it is exempt from rule 1's ten-minute window. Recorded at capture time because nothing can reconstruct it afterwards |
| title | Text? | all four read via `getCharSequence(...)` |
| text | Text? | |
| big_text | Text? | |
| sub_text | Text? | |
| extras_json | Text? | whitelisted extras only, see section 15.6 |
| content_hash | Text, indexed | sha256(package, user, normalized text). No timestamp |
| parse_status | Enum | `NEW`, `MATCHED`, `UNMATCHED`, `REJECTED`, `DUPLICATE_OF`, `UPDATE_OF`, `NO_EXTRAS`, `GAVE_UP` |
| rejected_by_rule_id | Text? | which reject pattern fired |
| matched_rule_id | Text? | |
| pack_version | Int | pack that produced this outcome |
| duplicate_of_id | Long? | |
| user_reject_rule_id | Long? | hidden from the unread list, section 5.7 |

`posted_at` is `sbn.postTime`, not `notification.when`. `when` is an
app-controlled field that is regularly left at or set to zero; trusting it
dates a transaction to January 1970, where it vanishes from every month
view and every chart while still counting as captured. `when` is kept
separately, and "a sane window" is now specified, because a symmetric
tolerance is wrong in a way that only shows up against a real
`Notification`.

**The window is asymmetric: non-zero, not in the future relative to
`postTime`, and no more than 24 hours before it.** A symmetric
`abs(when - postTime) < N` mis-dates genuine notifications, and this was
caught by a real end-to-end test rather than by reading. `Notification.when`
defaults to the moment `build()` is called, which is *after* a `postTime`
that the system assigned earlier or that a test backdated — so a symmetric
rule accepted a `when` five seconds in the future and dated the transaction
by it, and would accept one a week ahead. There is also no legitimate reason
for `when` to be later than the post: the event cannot happen after the
notification announcing it. Earlier is legitimate, because a bank may
announce a transaction some time after it cleared, which is the case the
field exists for.

A rule-extracted `occurred_at` still outranks both.

`content_hash` deliberately excludes time. An earlier draft hashed
`postTime/1000` alongside the text and then looked for repeats "within 60
seconds" — which can never fire, because two posts a second apart hash
differently. Identity comes from content; recency is a separate predicate.
`sbn_key` is stored because Android already tells us whether a post is a new
notification or an update to an existing slot, and inferring that from text
is strictly worse.

Raw captures are never deleted by the app. They are the audit trail, they
let a rule change be validated against real history, and they make any field
we failed to extract recoverable later by re-parsing. A user-initiated
"delete all data" wipes them along with everything else.

### `txn`

| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| raw_capture_id | Long? | null for manual entries |
| amount_sen | Long | always positive |
| currency | Text | `MYR` default |
| direction | Enum | `EXPENSE`, `REFUND` |
| occurred_at | Long | epoch millis |
| local_date | Int, indexed | `yyyymmdd` in the fixed zone, see section 15.7 |
| merchant_raw | Text? | exactly as parsed |
| merchant_display | Text? | after cleanup, user-editable |
| category_id | Long, indexed | FK to `category.id`, `ON DELETE RESTRICT`. Falls back to Uncategorized, but that is **applied by the writer**, not a column default: SQLite `DEFAULT` takes a constant and the Uncategorized id is a seeded row, i.e. data. There is no DDL default and there cannot be one. With the FK live, an insert that forgets to resolve it fails with SQLite error 787 instead of landing somewhere plausible |
| source_package | Text? | null for manual |
| source_label | Text? | e.g. `Touch 'n Go eWallet` |
| confidence | Enum | `HIGH`, `REVIEW` |
| state | Enum | `COMMITTED`, `PENDING`, `REJECTED` |
| pending_reason | Enum? | `RULE_REVIEW`, `TRANSFER_SUSPECT`, `MERCHANT_MISSING`, `DUPLICATE_SUSPECT`, `OVER_THRESHOLD` — which of section 7.1's gates fired. Non-null exactly when `state` is `PENDING`, and the review inbox (section 9.2) cannot choose its per-card prompt without it |
| is_excluded | Bool | transfers/reloads: kept, not counted |
| exclusion_reason | Enum? | `TRANSFER`, `CARD_PAYMENT`, `ATM_WITHDRAWAL`, `USER` |
| note | Text? | |
| user_edited | Bool | blocks re-parse overwrite |
| created_at, updated_at | Long | |

The table is named `txn`, not `transaction`, because `transaction` is a
SQLite reserved keyword. Room quotes its generated DDL so it would work, but
every hand-written query, trigger and FTS `content=` reference would need
escaping forever. Renaming costs nothing now.

`local_date` is a precomputed `yyyymmdd` integer, not a derived value.
Grouping by day through `strftime(..., 'localtime')` depends on the process
timezone, cannot use an index, and silently reshuffles history when the user
travels. Section 15.7 fixes the zone.

`REFUND` rows subtract from totals, which means a category total can be
zero or negative. Section 15.3 specifies how that is clamped before it
reaches a bar width. `is_excluded` rows appear in the list greyed out and
are absent from every total and chart.

### `merchant_rule`

| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| match_type | Enum | `EXACT` on save, `CONTAINS` only after section 6.2 |
| pattern | Text, `COLLATE NOCASE` | matched against `merchant_raw`, case-insensitive. The collation is on the **column**, not only in the matching code: under SQLite's default `BINARY`, the unique index below treats `MCD KLCC` and `mcd klcc` as different rules while a case-insensitive lookup matches both, so one merchant resolves to two categories depending on which row the plan reaches first. Section 6.1's writer uppercases, so the exposure is a bundled or imported pack (section 5.9) — and normalization being the only thing holding it is the same enforced-only-incidentally shape this design keeps having to remove |
| merchant_display | Text | |
| category_id | Long, indexed | FK to `category.id`, `ON DELETE RESTRICT` |
| origin | Enum | `BUNDLED`, `LEARNED` |
| priority | Int | `LEARNED` always outranks `BUNDLED` |
| hit_count | Int | |
| scoped_package | Text NOT NULL, default `''` | empty string means "not scoped to a package". **Not nullable, deliberately:** SQLite treats NULLs in a unique index as distinct, so a nullable column lets the unique index below accept two *unscoped* rules for one pattern — which is the shape section 6.1's learned-rule writer produces, i.e. the only shape that mattered. Room's `@Index` cannot express a `COALESCE` expression index, so the sentinel lives on the column |

Unique on (`match_type`, `pattern`, `scoped_package`), so a second learned
rule for one merchant is refused rather than leaving the resolved category a
function of the query plan. Section 6.1's "tap once more to correct it"
therefore updates the existing row rather than writing a rival to it.

### `merchant_alias` and `merchant_name`

Schema v2. Both are written only by section 6.4's merchant sheet, and both
are keyed by `txn.merchant_key` values rather than by a foreign key: a key is
a string several rows share, not a row of any table.

`merchant_alias`:

| Column | Type | Notes |
|---|---|---|
| merchant_key | Text PK | a `txn.merchant_key` the user has declared to be another merchant |
| canonical_key | Text NOT NULL | the `merchant_key` it groups under |

**One level, never a chain.** No `canonical_key` appears as a
`merchant_key`, and no row's `merchant_key` equals its own `canonical_key`.
Section 6.4's merge maintains this, so resolution is one `LEFT JOIN` and
never a recursive query. The column is not called `key`, which is an SQLite
keyword that Room's query parser refuses as a result-column alias.

`merchant_name`:

| Column | Type | Notes |
|---|---|---|
| merchant_key | Text PK | a `merchant_key`; read only while it is canonical, i.e. not a `merchant_alias.merchant_key` |
| display | Text NOT NULL | the merchant's name, never blank |

A name whose key is later merged into another stays, unread, so that
separating the merge gives the merchant its name back.

### `category`

Flat, no hierarchy. Seeded: Food & Drinks, Groceries, Transport, Petrol & tolls,
Bills & utilities, Telco & internet, Shopping, Health, Education, Family,
Religious & zakat, Government & fees, Entertainment, Uncategorized.

Columns: `id`, `name`, `icon_key`, `sort_order`, `is_protected`.

**Icons.** `icon_key` holds a Lucide icon name. The icons are imported as
vector drawables rather than through a library dependency: single-colour
stroke paths on a 24px grid, tinted at runtime from the `ink` and `muted`
tokens. The seeded mapping is fixed:

| Category | `icon_key` | Category | `icon_key` |
|---|---|---|---|
| Food & Drinks | `utensils` | Family | `users` |
| Groceries | `shopping-basket` | Religious & zakat | `hand-heart` |
| Transport | `car` | Government & fees | `landmark` |
| Petrol & tolls | `fuel` | Entertainment | `ticket` |
| Bills & utilities | `zap` | Uncategorized | `circle-dashed` |
| Telco & internet | `wifi` | Shopping | `shopping-bag` |
| Health | `heart-pulse` | Education | `graduation-cap` |

Religious & zakat deliberately uses a giving gesture rather than a place of
worship. The category covers zakat, church tithes, temple donations and
festival giving; a building would exclude most of its users.

**There is no category colour, and this is load-bearing.** Charts encode
magnitude on a single-hue ramp (section 8), so category identity is carried
by icon and name alone. A colour column would be dead weight and would
invite categorical colour back in, breaking the chart rule.

**Editing.** Categories are user-editable: rename, change icon, add, reorder,
merge, and delete only when unused. Uncategorized has `is_protected = true`
and can be neither renamed, re-iconed, nor deleted, because the confidence
gate and the categorizer both resolve to it by name-independent id.

**Uncategorized is refused as a merge source and allowed as a merge
target**, and the asymmetry is deliberate. Merging it away deletes it, which
is fatal for the reason above. Merging *into* it renames nothing, re-icons
nothing and deletes nothing — the row is untouched — and §7.1 already
designates it as where "we don't know what this is" lives, which is exactly
what a transaction becomes when the category it was filed under goes away.
Forbidding it as a target would leave a user wanting to remove a
little-used category with only bad options: nominate an unrelated category
and deliberately mis-file their own history, or keep a category they do not
want. Protection that forces mis-filing is not protection.

**"Only when unused" is enforced by the database, not by the dialog.**
`txn.category_id` and `merchant_rule.category_id` are real foreign keys to
`category.id` with `ON DELETE RESTRICT`, and both columns are indexed. An
earlier draft called `category_id` a foreign key while the schema declared
none, which is worth recording because of how it would have failed: nothing
stops the delete, the rows orphan, and the damage appears later and
elsewhere. An inner join drops the orphans out of every total, so money
disappears with no error anywhere; a left join feeds nulls into non-null
fields and crashes on a screen far from the delete that caused it. A
constraint the schema does not hold is not a rule, it is a comment.

**Which means a blocked delete needs a way out: merge.** A category in use
offers "move its transactions to another category, then delete this one"
rather than a greyed-out button. The sheet states the blast radius first —
"412 transactions and 3 learned rules will move to Groceries" — and states
plainly that it cannot be undone, because unlike a rename a merge destroys
information: afterwards nothing records which transactions came from which
category, so it cannot be split back.

Merge is one transaction that reassigns `txn`, reassigns `merchant_rule`,
and only then deletes the source row. That order is deliberate. `RESTRICT`
means a merge that forgets one of the two tables fails at the delete instead
of silently orphaning the rows it missed, so the constraint converts a
future editing mistake into a loud failure at the point of the mistake. Both
tables matter: learned rules also carry `category_id`, and an orphaned rule
is a merchant the user taught that quietly stops categorizing.

Deleting a category is therefore never a way to lose transactions. The
money is never in the category row.

Changing an icon behaves exactly like renaming: the icon is resolved from the
category row at render time, so it changes everywhere at once, including in
past months. A renamed category with a stale icon is worse than no icon at
all, which is why the two are edited from the same sheet.

**The picker set.** Because icons ship as bundled vector drawables rather
than a library, only bundled icons can be offered. Seeding just the
fourteen defaults would make "add a category" impossible to complete, so the
app bundles a picker set of roughly forty-eight icons, grouped so the sheet
is scannable:

| Group | Icons |
|---|---|
| Food and drink | `utensils` `coffee` `pizza` `soup` `ice-cream-cone` `beer` |
| Shops | `shopping-basket` `shopping-bag` `shopping-cart` `store` `gift` `shirt` |
| Getting around | `car` `bus` `train-front` `plane` `bike` `fuel` `ship` |
| Home and bills | `zap` `wifi` `droplet` `flame` `house` `wrench` `trash-2` |
| Money and admin | `banknote` `wallet` `credit-card` `piggy-bank` `receipt` `landmark` `hand-coins` |
| People and health | `users` `baby` `heart-pulse` `stethoscope` `pill` `dog` |
| Learning and work | `graduation-cap` `briefcase` `book-open` `smartphone` |
| Enjoying yourself | `ticket` `gamepad-2` `music` `film` `dumbbell` `palette` |
| Anything else | `hand-heart` `scissors` `tag` `circle-dashed` |

At roughly 1 KB per drawable this costs about 50 KB, so bundling the set
rather than the minimum is not a size decision worth agonising over.

Icon names must be checked against the pinned Lucide release when they are
imported. The grouping above is the design decision; an individual name that
has been renamed upstream is a cheap correction, not a redesign.

**No places of worship in the picker, deliberately.** Bundling a mosque
without a church and a temple would ship a default about who this app is
for. `hand-heart` and `landmark` cover the need. If the set is ever extended
here it has to be all of them or none.

**Rename is global and retroactive, by design.** `txn.category_id`
is a foreign key; no transaction stores a category name. So renaming a
category relabels every transaction that references it, in every past month,
in the charts, and in future exports. That is correct for fixing a label
("Food & Drinks" to "Makan", if a user prefers it) and wrong for
repurposing one ("Shopping" to "Baby things"), which would silently rewrite
history. The rename dialog therefore
states the blast radius before committing: "412 transactions will show the
new name", with a suggestion to create a new category instead when the
intent is repurposing.

Two consequences worth stating so they are not treated as bugs:
`merchant_rule` rows also reference `category_id`, so learned rules follow a
rename automatically; and a CSV export written before a rename keeps the old
name, because export writes the name resolved at the moment of export rather
than a live reference.

### `user_reject_rule`

| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| source_package | Text | scoped to one app |
| skeleton | Text, indexed | digit-stripped signature, see section 5.7 |
| sample_text | Text | the notification that created it, for review |
| created_at | Long | |
| hit_count | Int | |

Rules the user creates by tapping "never a transaction" on an unread
capture. They do not affect the ledger; see section 5.7.

### `user_template_rule`

| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| source_package | Text | always scoped to one app |
| pattern | Text | derived, see section 5.8 |
| direction | Enum | `EXPENSE` or `REFUND`, chosen by the user |
| has_merchant_group | Bool | false yields missing-merchant review |
| match_count | Int | first three matches are forced to `REVIEW` |
| sample_text | Text | the capture it was derived from |
| created_at | Long | |

Templates the user derived by tapping an amount and a merchant. Consulted
only after every pack template for the package has failed.

### `capture_source`

| Column | Type | Notes |
|---|---|---|
| pkg | Text PK | the package identifier; named `pkg` and not `package` because the latter is a Kotlin hard keyword, so the property could never carry the spec's name and a column that differs from its property is a trap in raw SQL |
| label | Text | display name |
| enabled | Bool | allow-list gate |
| is_authoritative | Bool | wins duplicate pairs, see section 7.2 |
| first_seen_at | Long | |
| last_notification_at | Long? | per-source liveness, see section 10 |
| expected_monthly_count | Int? | rolling average, for the same check |

Rows are created by the discovery screen (section 9.5), not hardcoded.

### `capture_day`

| Column | Type | Notes |
|---|---|---|
| local_date | Int PK | `yyyymmdd` in the fixed zone, same encoding as `txn` |
| listener_bound | Bool | the grant was present and something was bound |
| saw_any_notification | Bool | at least one notification from any app arrived |

One row per day, upserted at most once per day from the same throttled path
that writes the `DataStore` heartbeat, gated on the local date having
changed since the last upsert. It exists so the daily rhythm grid
(section 8) can tell "you spent nothing" apart from "Pinged was not
watching" — a distinction no aggregate over `txn` can recover, because both
cases are an absence of rows.

At roughly 365 rows a year this is the smallest table in the database, and
one write a day does not carry the invalidation cost that keeps the
per-notification heartbeat out of Room.

### `capture_health` — removed, and why it is worth saying so

An earlier draft specified a single-row Room table holding
`last_listener_connected_at`, `last_any_notification_at`,
`last_matched_notification_at` and `consecutive_silent_days`. It
contradicted section 10.2, which was written later and reasoned the
question through properly: `last_any_notification_at` changes on every
notification on the device, 100-300 times a day, and Room's invalidation
tracker is table-granular, so that column in Room re-emits every `Flow`
observing it on every notification. Section 10.2 wins. There is no
`capture_health` table.

Liveness is therefore answered in two places, by design:

- **Now** — `DataStore`, throttled to one write per five minutes
  (section 10.2). Drives the capture-stopped banner.
- **Historically, per day** — `capture_day` above. Drives the hatched cells
  in the daily rhythm grid and the "N days not captured" labels on the
  month picker.

## 5. Parser pack

### 5.1 Format

```json
{
  "pack_version": 7,
  "packages": [{
    "package": "<verified on device>",
    "label": "Touch 'n Go eWallet",
    "reject": [
      { "id": "promo",  "any_of": ["cashback","voucher","diskaun","% off","jom "] },
      { "id": "otp",    "any_of": ["OTP","TAC","do not share","jangan kongsi"] },
      { "id": "failed", "any_of": ["unsuccessful","gagal","failed","declined"] }
    ],
    "rules": [
      { "id": "tng-payment-v1", "priority": 100,
        "direction": "EXPENSE", "confidence": "HIGH",
        "requires": { "text_contains_all": ["Payment of","successful"] },
        "pattern": "Payment of RM(?<amount>[\\d,]+\\.\\d{2}) to (?<merchant>.+?) successful" },

      { "id": "tng-reload-v1", "priority": 90,
        "direction": "EXPENSE", "confidence": "REVIEW", "kind": "TRANSFER_SUSPECT",
        "exclusion_reason": "TRANSFER",
        "requires": { "text_contains_all": ["Reload"] },
        "pattern": "Reload of RM(?<amount>[\\d,]+\\.\\d{2})" }
    ]
  }]
}
```

The `package` value above is a deliberate placeholder. Package identifiers
are observed on device through the capture-source screen (section 9.6)
rather than guessed, because a wrong identifier fails silently and would be
indistinguishable from a bank that simply never notifies.

**Transaction templates are evaluated first; reject patterns are consulted
only when no template matched.** An earlier draft had rejects run first,
always. That is unsafe, and section 5.3 explains why.

Named groups: `amount` is required; `merchant`, `occurred_at`, `balance`,
`account_tail`, `ref` are optional. In v1 only `amount`, `merchant` and
`occurred_at` are consumed; others are captured into the raw record for
future use.

### 5.2 Condition vocabulary

Deliberately tiny. Four predicates plus a field selector, no boolean
nesting, no arithmetic:

- `text_contains_all: [String]`
- `text_contains_any: [String]`
- `text_contains_none: [String]`
- `title_contains_any: [String]`
- `field: "title" | "text" | "bigText" | "concat"` (default `concat`,
  meaning `title + " " + (bigText ?: text)`)

All string comparison is case-insensitive and whitespace-normalized.

**Regex flags are fixed for every `pattern`,** because leaving them implicit
makes each rule's behaviour a guess: `CASE_INSENSITIVE | UNICODE_CASE |
DOTALL`, and never `MULTILINE`. `DOTALL` matters — `bigText` contains
newlines, and without it `(?<merchant>.+?)` silently fails to match across
one. The `pattern` runs against the same field the conditions select.

**Whitespace and digits are normalized for Unicode before matching.** Java's
`\s` does not match U+00A0 or U+202F, and bank notifications carry
non-breaking spaces around amounts routinely; `\d` matches ASCII only unless
`UNICODE_CHARACTER_CLASS` is set. Normalization replaces `\p{Zs}` with a
plain space, strips zero-width and bidi marks, and only then matches. Exact
skeleton equality in sections 5.7 and 5.8 depends on this, and would
otherwise fail invisibly on precisely the messages it exists to handle.

**The amount fragment is a shared primitive, not retyped per rule.** The
pack declares it once, tolerant of a space after `RM`, of `MYR`, of thousands
separators and of a missing decimal part, and rules reference it. Every rule
author reinventing `RM(?<amount>[\d,]+\.\d{2})` guarantees that half of
them reject "RM 50" and "MYR50.00".

If a real Malaysian format cannot be expressed within this vocabulary, that
is the signal to reconsider a per-package Kotlin handler — not to grow the
rule language.

### 5.3 The traps the reject patterns exist for

These are the real reason default-deny is non-negotiable.

**But rejects run after templates, not before.** Malaysian wallets append
reward copy to genuine success messages: *"Payment of RM52.30 to 99
Speedmart successful. You earned RM1.05 cashback."* That is a real expense
whose text contains `cashback`. Under reject-first ordering it is discarded
before any template runs, producing exactly the silent under-report that
section 2 admits the flat model cannot detect. A matched transaction
template is strictly stronger evidence than a keyword, so it wins.

A capture that matches both a template and a reject pattern is recorded as
`MATCHED` and the collision is logged against both rule ids, so it surfaces
in rule review rather than being silently resolved either way. Reject
patterns are also the reason the word list must be treated with suspicion:
`"jom "` is an ordinary Malay word that appears in campaign and merchant
names, and any keyword short enough to be convenient is long enough to be
wrong.

The traps themselves:

1. **Promotional pushes.** Grab, Shopee, foodpanda and every wallet push
   marketing copy containing ringgit amounts ("Get RM10 off your next
   order"). Amount-sniffing would fabricate expenses daily. This is the
   single largest false-positive source.
2. **TAC/OTP messages.** "TAC 123456 for RM250.00 transfer. Do not share."
3. **Failed transactions.** "unsuccessful", "gagal", "declined" must produce
   nothing.
4. **Refunds and reversals.** "refund", "bayaran balik", "reversal" are
   `REFUND` direction, not expenses.
5. **Bilingual variants.** Rules must cover both English and Malay phrasing
   for the same event ("Anda telah membuat pembayaran" alongside "You have
   made a payment").

### 5.4 Normalization

- **Amount.** Strip `RM`/`MYR` and spaces, remove thousands separators,
  parse with `BigDecimal`, convert to `Long` sen. Reject if more than two
  decimal places, if zero, or if above a sanity ceiling of RM1,000,000.
- **Merchant.** Strip known acquirer prefixes (`TNG*`, `GRAB*`, `PYMT-`,
  `DUITNOWQR-`, `FPX-`, `IBG-`, `MBB-`), strip trailing corporate and
  location noise (`SDN BHD`, `SDN. BHD.`, `S/B`, trailing ` MY`, trailing
  terminal codes matching `-[A-Z0-9]{2,4}$`), collapse whitespace.
  `merchant_raw` is preserved untouched. Title-casing is applied **only when
  the raw string is entirely uppercase**, with an exception list in the pack:
  applied unconditionally it produces "Tng 99speedmart", "Kk Super Mart" and
  "Mcdonald's", which is worse than leaving the acquirer's shouting alone.
- **Date.** `sbn.postTime` is authoritative unless a rule extracts
  `occurred_at` (relevant for card postings that backdate).
  `notification.when` is never authoritative; see section 4.
- **Local date.** `local_date` is computed at parse time from `occurred_at`
  in the fixed zone (section 15.7) and stored, never derived at query time.
- **Direction.** Comes from the matched rule only, never inferred from text.

The prefix and suffix lists live in the pack, not in code, so they are
editable without a release.

### 5.5 Versioning and re-parse

Each transaction records the `pack_version` and `matched_rule_id` that
produced it. On pack upgrade, a WorkManager job re-parses raw captures whose
`parse_status` is `UNMATCHED` **or** `REJECTED`.

Including `REJECTED` is not optional. Fixing an over-broad reject pattern is
one of the most likely reasons to ship a pack at all, and a design that
never revisits rejected captures cannot recover the transactions that
pattern ate.

Re-parse in this mode never modifies an existing transaction: it only
creates new ones, subject to the normal confidence gate.

**A third mode handles captures that were parsed wrongly.** A rule that
captured the wrong group and recorded RM1,234.00 as RM1.23 across three
hundred committed transactions is not repairable by the two modes above, and
"rules as data, format fixes need no release" is hollow if only the absence
of a match can be fixed. So a pack upgrade also re-runs `MATCHED` captures
and compares the result against the stored transaction. Differences are not
applied silently — they are presented as a reviewable list with old and new
values, and the user accepts or declines. Transactions with
`user_edited = true` are excluded from the comparison entirely, because a
human decision outranks any rule.

Section 5.9's dry run already computes this count; it is the same
calculation, and treating a non-zero result as a reviewable diff rather than
as a validation failure is what makes packs able to fix real damage.

**A re-parse that changes a row's `merchant_key` carries the user's merchant
decisions with it.** Section 6.4's aliases and names are keyed by
`merchant_key`, so a pack whose normalization moves a key from `K` to `K'`
would otherwise drop the moved rows out of a merge the user made, and out
from under the name they gave it. Accepting such a diff writes `K'` into
`merchant_alias` beside `K`, pointing at the same canonical key, and moves a
`merchant_name` keyed by `K` to `K'` when `K` was canonical and no row keeps
it. Rows the user never merged or renamed need nothing.

### 5.6 Rule authoring loop

Coverage grows through a fixed loop, supported by in-app tooling:

1. An in-app **Unmatched captures** screen lists raw notification text for
   captures with `parse_status = UNMATCHED`, newest first, with a
   "copy as fixture" action.
2. The copied text becomes a fixture file in `:core:parse` test resources,
   paired with its expected outcome.
3. Rules are written until the fixture passes.
4. The full corpus runs on every change, so a new rule cannot regress an
   existing bank.

Fixtures are redacted of account numbers before being committed.

### 5.7 Notifications that are shaped like money but never are

Some packages emit notifications that look transactional and are not: a
fixed-deposit maturity notice, a statement-ready alert, a balance summary, a
"you spent RM23.50 this week" digest. They carry an amount, they come from a
bank, and no transaction template will ever match them. Left alone they
accumulate in the unread list (section 9.6) and bury the notifications that
genuinely need a new rule.

So the unread list offers a third outcome beside "copy as test case":
**never a transaction**, which writes a `user_reject_rule`.

**These rules cannot suppress a transaction.** Ordering is deliberate: pack
reject patterns, then transaction templates, and only if nothing matched are
user reject rules consulted. A user rule therefore decides *whether an
unmatched capture is shown in the unread list*, never whether a transaction
is created. A fumbled tap can clutter or de-clutter that list and can do
nothing else. The raw capture is still stored, as always, so the decision is
fully reversible.

**Matching is by digit-stripped skeleton.** The full text is useless as a
pattern because the amount changes every time. On creating the rule, the
capture text is normalized: lowercased, whitespace collapsed, and every run
of digits with its embedded separators replaced by a single placeholder.

```
"Your FD of RM5,000.00 has matured. View details in MAE."
  -> "your fd of rm# has matured. view details in mae."
```

A later capture is hidden only when its skeleton is equal to a stored one
for the same package. Equality rather than substring matching is chosen on
purpose: it makes over-rejection almost impossible, at the cost of needing
one rule per distinct message shape, which is the right trade for something
the user cannot see working.

**Review.** User reject rules are listed in settings beside learned
merchants, each showing its `sample_text` and hit count, and each deletable.
Deleting one returns its captures to the unread list.

### 5.8 Teaching a rule by example

Exposing a regex field to the user is not an option. A malformed pattern does
not fail loudly: it fabricates transactions, or silently stops matching a
bank that worked yesterday. That is precisely what default-deny exists to
prevent.

But the unread-captures screen already shows the exact text, and the user
knows what it means. So the app asks for the two facts it cannot infer
rather than for a pattern: **tap the amount, then tap the merchant**.

Everything the user did not tap becomes a literal, escaped. Every remaining
run of digits becomes `\d+`, which is the same skeleton normalization used
for reject rules in section 5.7 — one mechanism serving two purposes.

```
raw       "Transaksi berjaya. RM88.00 telah ditolak dari akaun anda 1234."
tapped     amount = 88.00, no merchant present in this message

derived    Transaksi berjaya\. RM(?<amount>[\d,]+\.\d{2}) telah ditolak
           dari akaun anda \d+\.
```

Where no merchant is present the derived rule captures amount only, and its
transactions land in the review inbox under the existing missing-merchant
reason (section 7.1) rather than needing a new state.

**This is safe for the same reason exact merchant matching is safe.** The
pattern is the whole message shape with only digits generalized, so it
cannot over-match. It is narrow by default and self-healing: a message shape
the rule does not cover simply stays unread, visible, and teachable again.

**Three guards.**

- A user-derived template yields `confidence: REVIEW` for its first three
  matches, so the amounts are eyeballed before it commits silently.
- Derived templates are stored in a `user_template_rule` table, listed in
  settings beside learned merchants and reject rules, and deletable. Deleting
  one leaves transactions it already produced untouched.
- Pack rules always win. A user template is consulted only after every pack
  template for that package has failed, so a user cannot shadow a rule that
  ships correct.

**Why this matters beyond convenience.** Without it, every Malaysian bank
that changes a message format needs a release from the developer. With it,
coverage grows wherever the app is installed. Sharing derived templates
between users is deliberately out of scope: it needs `INTERNET`, and a
stranger's rule that misreads amounts is a bad failure with no local
evidence. That is a v2 conversation with a trust model attached.

### 5.9 Importing a parser pack

A pack arrives as a single JSON file through the Storage Access Framework.
Import is validated and previewed, never applied on trust.

**Validation, all of which must pass before the preview is offered:** the
file parses; `pack_version` is an integer greater than the installed one;
every rule declares an `id` unique within its package; every `pattern`
compiles and declares an `amount` group; every condition uses only the five
predicates listed in section 5.2; and every package with rules also declares its
reject patterns first.

**Regex safety, which compiling does not give you.** A pattern that compiles
can still backtrack catastrophically, and a pack arrives as a file. So
validation also runs each pattern against a pathological input, and both the
dry run and the re-parse job match against a `CharSequence` wrapper that
throws once a per-match wall-clock budget expires, with a hard cap on input
length. Without this, one nested quantifier in an imported pack hangs a
worker over 50,000 captures with no way out.

**Dry run against stored history.** Because raw captures are never deleted
(section 4), an incoming pack can be tested against the user's own
notifications before it is accepted. The preview reports what would change:

```
Pack 8 adds rules for Bank Islam, Boost and BigPay.

Against your stored notifications it would newly match 31 captures
worth RM740.20, and change nothing already recorded.
```

The preview is computed by running the candidate pack over the most recent
5,000 `raw_capture` rows, on a background worker, with progress shown. It
never writes. The cap is stated in the UI copy — "tried against your last
5,000 notifications" — rather than sampling silently, because a preview the
user cannot size is a preview they cannot trust. Section 16 explains the
bound. Three figures are reported: captures newly
matched, their total, and the count of existing transactions the pack would
have parsed differently — which must be zero for a well-formed pack, since
re-parse only touches `UNMATCHED` captures, and is surfaced loudly if it is
not.

Accepting the import stores the pack, bumps the installed version, and
enqueues the section 5.5 re-parse job. The previous pack is retained so an
import can be rolled back, which matters because a pack is the one artifact
that can change how every future notification is read.

## 6. Categorization

Resolution order, first hit wins:

1. `merchant_rule` with `origin = LEARNED` (user taught it)
2. `merchant_rule` with `origin = BUNDLED` (shipped dictionary)
3. `Uncategorized`

The bundled dictionary seeds recognisable Malaysian merchants across
groceries and convenience (99 Speedmart, Lotus's, AEON, Mydin, Giant,
Village Grocer, KK Super Mart, 7-Eleven), petrol (Shell, Petronas, Petron,
Caltex, BHPetrol, Setel), transport (Grab, Touch 'n Go tolls, MyRapid,
Rapid KL), food delivery (foodpanda, GrabFood, ShopeeFood), telco and
utilities (Maxis, Celcom, Digi, U Mobile, Unifi, Time, Astro, TNB, Air
Selangor, Syabas, IWK), retail (Shopee, Lazada, Watsons, Guardian, Uniqlo,
Mr DIY), and government and statutory (LHDN, KWSP/EPF, PERKESO/SOCSO, JPJ,
MyEG, PTPTN, zakat bodies).

### 6.1 Learned rules match the exact string

When the user categorizes an uncategorized transaction, the app writes a
`LEARNED` rule with `match_type = EXACT` over the normalized form of
`merchant_raw`: uppercased, every non-alphanumeric character replaced by a
space, runs of whitespace collapsed. Nothing is stripped and nothing is
inferred.

```
"Restoran Yuen Kee Home Town Cafe"  ->  EXACT "RESTORAN YUEN KEE HOME TOWN CAFE"
"TNG*99SPEEDMART"                   ->  EXACT "TNG 99SPEEDMART"
```

**Why exact rather than a guessed substring.** The two failure modes are not
symmetric. An exact rule that is too narrow shows the merchant as
Uncategorized again; the user taps once more, a second rule is written, and
the system has healed itself in full view. A substring rule that is too
broad silently miscategorizes merchants the user never taught it about, and
if applied retroactively it rewrites confirmed history. Spending complexity
to save a tap, and buying the dangerous failure to do it, is the wrong
trade.

**Why exact is also sufficient in practice.** Merchant strings that vary
between payments belong to chains, ride-hailing and delivery — acquirer
strings with terminal codes and trip suffixes. Those merchants are in the
bundled dictionary (section 6) and never reach the learning path at all.
Learned rules exist for the long tail: a mamak, a kopitiam, a small shop
paid by DuitNow QR. There the merchant name comes from the merchant's own
registered record rather than an acquirer, so it arrives identically each
time. Exact matching is strongest exactly where learning is needed.

**The accepted cost.** A merchant whose string does vary accumulates one
rule per variant. This is noisy rather than wrong: the rules are listed in
settings with hit counts, so a dead variant is visible and deletable, and
section 6.2 collapses the common case automatically.

### 6.2 Broadening, only on evidence

A `CONTAINS` rule is never written from a single observation. It is offered
when a second observation proves what the stable part of the name is.

When an uncategorized capture arrives whose normalized string has a longest
common substring with an existing `LEARNED` pattern that is at least eight
characters long and at least half the length of the shorter of the two
strings, the app proposes replacing both exact rules with one `CONTAINS`
rule on that substring, trimmed outward to whole-token boundaries.

```
existing  "RESTORAN YUEN KEE HOME TOWN CAFE"
new       "YUEN KEE HOME TOWN"
proposal  CONTAINS "YUEN KEE HOME TOWN"
```

The pattern comes from two real observations, which is better evidence than
any vocabulary list could be. The eight-character and one-half thresholds
are judgement, not measurement, and are the first numbers to revisit once
there is a real corpus.

**Two guards.** A proposed substring whose every token appears in the
generic list below is refused, so two unrelated `SDN BHD` merchants cannot
broaden into each other. And the proposal is always shown in full before
saving — "match anything containing YUEN KEE HOME TOWN" — so a poor
suggestion is declined in the moment rather than discovered months later.

**Generic list, used only to refuse an all-generic substring.** It is no
longer load-bearing, so it does not need to be exhaustive: `SDN` `BHD`
`BERHAD` `ENTERPRISE` `TRADING` `HOLDINGS` `GROUP` `RESOURCES` `SERVICES`
`SOLUTIONS` `MARKETING` `RESTORAN` `RESTAURANT` `KEDAI` `WARUNG` `GERAI`
`CAFE` `KOPITIAM` `MAKAN` `MART` `MINIMART` `SUPERMARKET` `PASARAYA`
`STORE` `SHOP` `OUTLET` `FARMASI` `KLINIK` `DUITNOW` `QR` `FPX` `IBG`
`PYMT` `POS`

It ships in the parser pack rather than in code.

### 6.3 Retroactive application

Either kind of rule can be applied to existing transactions, and the chooser
sheet counts the matches before offering it.

If those matches span more than one category that is not Uncategorized, the
rule is too broad for retroactive use: the app names the conflict and
applies the rule going forward only. Silently recategorizing history the
user has already confirmed is the one thing this feature must never do.

Learned rules are reviewable and deletable in settings. Deleting a rule
leaves already-categorized transactions untouched.

### 6.4 One shop, two keys: the user says so

`merchant_key` is derived from what the bank sent, and what the bank sends
depends on the rail that moved the money rather than on the shop. One
restaurant, measured on a real device:

| Rail | Sent | `merchant_key` |
|---|---|---|
| MAE Scan & Pay | `YUENKEEHOMETOWNCAFE` | `YUENKEEHOMETOWNCAFE` |
| TNG DuitNow | `Restoran Yuen Kee Home Town Cafe` | `RESTORAN YUEN KEE HOME TOWN CAFE` |

Section 8's "Top merchants" groups by `merchant_key`, so this shop would be
listed twice with its spending split between the two rows. **This is not
rare.** A device export at pack 11 (76 transactions) had 4 of its 7 MAE Scan
& Pay merchants sent with the spaces stripped out, which was 8 of 15 Scan &
Pay payments. MAE card spend (11 merchants) and every TnG wording (25) had
none. It depends on the merchant, not on the rail: `MR KOPI` keeps its space
on Scan & Pay.

**No normalization recovers it.** Removing every space still leaves
`RESTORAN` in front. Treating one key as a suffix of the other is a guess
that merges two different shops, and section 6.2's longest-common-substring
test finds only `CAFE`-sized overlaps, because the spaces it would line up
on are gone. And no casing rule can fix the name either: section 5.4's
title-caser has no word boundaries left, so it produces
`Yuenkeehometowncafe`. The only party who knows these are the same shop is
the user.

**So the user says so, from the merchant sheet.** A long press on a ledger
row whose `merchant_key` is not null opens it. A tap is not used, so that it
cannot be confused with the uncategorized chip that already sits inside the
row. The sheet offers two things.

- **Rename.** A text field, filled with the name the row shows now. Saving it
  writes `merchant_name` for the row's canonical key (section 4). It renames
  every past and future transaction of that merchant, exactly as a category
  rename does (section 4, `category`), and the sheet shows how far it reaches
  first: "23 transactions will show the new name". Saving a blank name, or
  the name the pack would derive anyway, deletes the row instead, so that no
  stored value merely restates the default. A merged merchant is the
  exception and keeps that name stored: unnamed, each row falls back to its
  own capture's name, and the two halves would read apart again.
- **Same shop as…** A searchable list of the other merchants, each with its
  transaction count. Choosing one merges this merchant into it.

**Merging is reversible, which a category merge is not.** A category merge
rewrites `txn.category_id` and destroys the record of where each row came
from. A merchant merge rewrites no `txn` row. Every row keeps the key its
own capture produced, and the merge is only a `merchant_alias` row, so
"Separate from Yuen Kee" in the same sheet deletes that row and the split
comes back exactly as it was. Rewriting `merchant_key` in place was
rejected for this reason. It would also have put a fourth writer on a
column that spec 5.5's re-parse owns, and every new capture under the old
key would have split again until a lookup was added to stage two as well.

A merge of source `S` into target `T` is one transaction:

1. Resolve both to canonical keys: `s = alias(S) ?: S`, `t = alias(T) ?: T`.
   If `s = t`, stop.
2. `UPDATE merchant_alias SET canonical_key = t WHERE canonical_key = s`, so
   whatever was already merged into `s` follows it. This is the step that
   keeps aliases one level deep.
3. `INSERT INTO merchant_alias (merchant_key, canonical_key) VALUES (s, t)`.
   A plain insert: `s` is canonical, so it is not already a `merchant_key`,
   and a conflict here means step 1 or 2 is wrong. It should fail loudly.
4. When `t` has no name yet, write the name the sheet showed for it. The
   merged merchant takes the target's name. `s`'s own name, if it had one,
   is left in place: nothing reads it while `s` is merged, and separating
   gives it back. Without that write, a merged row would go on
   showing its own capture's name, `Yuenkeehometowncafe` under `Restoran
   Yuen Kee Home Town Cafe`, which is half of what the merge was for. The
   sheet says this before the tap, and the user can rename it afterwards.

Each step is a targeted statement naming its own columns, never a
whole-row upsert, for the reason `capture_source` gives. Writing
`merchant_name` is SQLite's `INSERT … ON CONFLICT(merchant_key) DO UPDATE`,
which updates the row in place. `REPLACE` would delete it and insert it
again.

**Resolution is at read time, in SQL.** The identity of a transaction is
`COALESCE(merchant_alias.canonical_key, txn.merchant_key)`, through
`LEFT JOIN merchant_alias ON merchant_alias.merchant_key = txn.merchant_key`. The
name it shows is `COALESCE(merchant_name.display, txn.merchant_display,
txn.merchant_raw)`, joined on that identity. Every place that shows a
merchant name reads it through that one expression: the ledger row, the
review inbox, section 8's ranking, section 15.2's search, and section 12's
CSV. Section 15.3 still holds, because both joins are on primary keys and
the grouping stays in SQL. They do cost the `txn(merchant_key)` index its
ordered walk of a `GROUP BY`. A period's rows are found through
`local_date` and grouped in a temporary B-tree, which `QueryPlanTest` pins
alongside the two primary-key lookups. Measured on emulator-5554 with 5,000
transactions over 200 merchants, 50 of them merged: the feed's page 4,000
rows deep went from 0.29 ms to 0.90 ms, because Room's `OFFSET` evaluates
the joins for every row it skips, and the ranking over a year took 3.2 ms.
The 15.8 fixture is still to be measured before the ranking's screen ships.

`merchant_display` therefore stays what the pack derived from the capture.
The user's edit of a merchant's name is this sheet, at the merchant level,
and it does not set `user_edited`. That flag still means that section 5.5
must not touch the row.

**Suggestions, never merges.** The "Same shop as…" list puts first any
merchant whose key, with every non-alphanumeric removed, contains this one's
key or is contained by it, provided the shorter key is at least eight
characters. That puts `RESTORAN YUEN KEE HOME TOWN CAFE` at the top of
`YUENKEEHOMETOWNCAFE`'s list. The test only orders the list, and it never
merges anything, so its threshold, which reuses section 6.2's eight and is
judgement, can be wrong without anything being written. It lives in
`:core:parse` as a pure function and is tested on the JVM. It is written with
explicit character classes, for the reason section 5.4 gives about `\w`.

**Categories are untouched.** A merge changes how merchants group, not
which category any row is filed under. A learned rule (section 6.1) still
matches its own exact string, so the two halves of a merged merchant can
carry two categories. The ranking shows the merchant once either way.
Applying one half's rule to the other is section 6.3's question and needs
its conflict check, so a merge does not do it implicitly.

**Schema v2 and backup format 2.** The two tables are new entities, so this
is the schema's first migration: two `CREATE TABLE`s and nothing changed in
v1's tables, tested from v1 under the SQLCipher factory as section 13
requires. The JSON backup gains `merchant_aliases` and `merchant_names`
sections and moves to format 2. A format-1 file still imports, and restores
with both tables empty. An older build refuses a format-2 file by number,
which is the behaviour `Backup.FORMAT_VERSION` already specifies. `Wipe`,
salvage and the export completeness check cover both tables like the other
six.

**Tests that must fail without the change:**

- `MigrationTest`: a v1 file with transactions migrates to v2 with every row
  intact and both tables empty, and the result matches `2.json`.
- Merging A into B and then B into C leaves both A and B pointing at C, and
  no row has a `canonical_key` that is also a `key`.
- The Yuen Kee pair ranks as one merchant with the summed amount and count
  of 2 after the merge, and as two again after "Separate".
- A capture parsed *after* a rename shows the new name with no further write.
  This proves resolution happens at read time and that stage two needs no
  lookup.
- A backup round trip carries both tables. A format-1 file imports. A
  format-3 file is refused.
- The suggestion ordering puts `RESTORAN YUEN KEE HOME TOWN CAFE` first for
  `YUENKEEHOMETOWNCAFE`, and does not suggest a 7-character key at all.

**Not drawn.** The merchant sheet has no `design/*.dc.html`. It was built
from the existing sheets' parts (`WipeSheet`'s underlined field and
bordered buttons, `CategoryPicker`'s list rows), and a drawing should
replace that when one exists.

## 7. Confidence, duplicates, transfers

### 7.1 Two queues, deliberately separate

| Queue | Question | Blocking |
|---|---|---|
| Review inbox | Is this a real expense, is the amount right | Yes — `PENDING`, not counted |
| Needs category | What kind of spending is this | No — already counted |

Malaysian merchants are a long tail: DuitNow QR to a mamak, a pasar malam
stall, an unfamiliar `SDN BHD`. Routing unknown merchants to the review
inbox would produce a wall of items in week one and the app would be
abandoned. So unknown merchants commit immediately as `Uncategorized` and
surface as a non-blocking chip on the transaction list; one tap categorizes
and writes a learned rule.

A transaction is set `PENDING` if any of the following hold:

- matched rule declares `confidence: REVIEW`
- matched rule declares `kind: TRANSFER_SUSPECT`
- `amount` was extracted but `merchant` is missing or empty
- `DuplicateDetector` flagged it
- `amount_sen` exceeds a user-configurable threshold, default RM500

Otherwise it is `COMMITTED`. The gate is a deterministic rule list, not a
numeric score, so the app can always state exactly why an item needs review.

### 7.2 Duplicate detection, two layers

**Layer 1 — the same notification, again.** Android fires
`onNotificationPosted` for updates as well as new posts, and it tells you
which is which: `sbn.key` identifies the notification slot. Two rules,
in order:

- Same `sbn_key` **and** same `content_hash` as an earlier row, **within 10
  minutes** → `UPDATE_OF`, no transaction. An app that refreshes its own
  notification has not spent money twice.
- Same `content_hash` from the same package and user within 60 seconds, with
  a different `sbn_key` → `DUPLICATE_OF`.
- Same `sbn_key` and same `content_hash` **beyond** 10 minutes → a
  transaction **is** created, flagged `DUPLICATE_SUSPECT` and therefore
  `PENDING` (section 7.1), landing in the review inbox with Merge / Keep
  both. This extends layer 2 to the same-package case, which its own
  conditions exclude.

**Rule 1 used to have no window, and that was a wrong-money bug.**
`content_hash` deliberately contains no timestamp (section 4), and
normalization preserves digits, so the hash is a function of amount plus
merchant plus wording — which means **two genuinely separate identical
payments have the same identity by construction.** With no window, a wallet
that posts through one reused notification slot would produce this: "Payment
of RM10.00 to Touch 'n Go Reload successful" on Monday, byte-identical text
on Wednesday, same slot, so the same `sbn_key` and the same `content_hash`.
Rule 1 fires, no transaction is created, and RM10 is missing from the ledger
with no error, no review item, and no way for the user to notice except by
reconciling against a bank statement — the exact task this app exists to
remove. It also contradicted this section's own closing rule that duplicates
are never dropped automatically. The reasoning that produced a time-free
hash was sound; "identity comes from content" is simply only safe when
content is unique per event, and for recurring identical spend it is not.

**Why the window is short, and why the boundary is safe in one direction
only.** Inside the window a match is dropped silently, so a false positive
there loses money undetectably. Outside it, the same pair becomes a review
item, so a false negative costs the user one tap. The asymmetry means the
window should be as short as tolerable rather than as long as plausible. Ten
minutes reuses layer 2's number so the design carries two windows rather
than three, and two byte-identical payments through one slot inside ten
minutes is rare enough to accept.

**The rebind catch-up path is exempt, because there the liveness signal is
free.** Section 10.1's catch-up calls `getActiveNotifications()` on
`onListenerConnected`, which by definition returns only posts that are
**still live** — and "the notification is still posted" is what rule 1
actually means by a refresh, far more precisely than elapsed time does. So a
capture arriving through the catch-up path applies rule 1 with no window,
while a capture arriving through `onNotificationPosted` applies the ten
minutes. Without that exemption every rebind after an app update or a reboot
would re-deliver each live banking notification as a review item, and
rebinding is already the thing section 10.1 says this app dies of first.

`raw_capture` records which path a capture arrived by, so this is decidable
at parse time rather than guessed.

`content_hash` contains no timestamp (section 4). An earlier draft hashed
the second-bucketed `postTime` into it and then looked for repeats within
sixty seconds, which is self-cancelling: two posts a second apart hash
differently, so the window could only ever fire inside a single second,
where it was redundant. Every real notification update would have produced a
second transaction.

**Layer 2 — same purchase seen twice.** One card swipe can fire both the
banking app and a separate card-alert notification. Flag
`DUPLICATE_SUSPECT` when: identical `amount_sen`, within 10 minutes,
different `source_package`, and merchant tokens overlap or one merchant is
absent. The newer transaction goes to the review inbox offering
**Merge** or **Keep both**.

Duplicates are never dropped automatically. Two genuine RM5.00 parking
payments in one afternoon are entirely normal, and silently deleting one
would be a wrong total the user cannot detect.

`is_authoritative` **orders the pair; it does not resolve it.** An earlier
draft said a pair spanning an authoritative and a non-authoritative source
"resolves toward the authoritative source automatically and does not reach
the review inbox". That contradicted the paragraph immediately above it, and
it never said what became of the losing side — which is the whole question.
Committing both double-counts the money; discarding one drops money
automatically, which is exactly what this section forbids. There is no third
option that skips the inbox.

So every `DUPLICATE_SUSPECT` pair reaches the review inbox. What
`is_authoritative` does is decide **which row is presented as the keeper**
and which as the candidate to merge away, so the common case is one tap on a
pre-selected correct answer rather than a decision from scratch. That is a
real benefit and it costs no silent deletion.

Note also that nothing in the capture milestone writes this column — there
is no caller for it yet — so the ordering is specified and inert until the
capture-source screen offers the toggle.

### 7.3 Transfers, reloads, card payments

Rules tag wallet reloads, credit-card bill payments, DuitNow transfers to
self, and ATM withdrawals as `kind: TRANSFER_SUSPECT` with an
`exclusion_reason`.

**Nothing is auto-excluded at parse time, and the wording elsewhere in this
section should not be read as saying otherwise.** A tagged capture is
written with `is_excluded = false` and `exclusion_reason = null`, and lands
`PENDING` — so it is not in any total, but it has not been silently removed
from one either. The distinction matters: auto-exclusion would mean money
left the totals without the user being asked, which is the same class of
silent wrongness as dropping a duplicate. The pack's `exclusion_reason` is a
**suggestion**, used to pre-select the inbox's answer, and it needs no
storage at parse time because it is re-derivable from `matched_rule_id`.
`is_excluded` and `exclusion_reason` are written only when the user answers,
or when a persistent rule they created earlier answers for them.

These land in the review inbox with a targeted prompt —
"Reload Touch 'n Go RM100 — exclude from spending?" — offering **Exclude**,
**Keep as expense**, or **Always exclude Touch 'n Go reloads**, the last of
which writes a persistent rule.

Excluded transactions are retained with `is_excluded = true`. They render
greyed in the list and are absent from all totals and charts. They are not
deleted: seeing that money moved is useful even when it is not spending.

Cash withdrawn at an ATM is excluded as a transfer. Spending that cash is
covered by manual entry (section 9.4); v1 does not attempt to model a cash
wallet.

## 8. Visualization

v1 ships three, none of which needs a charting dependency.

**Month at a glance.** A hero total for the selected month, followed by
horizontal bars per category ordered by magnitude, each showing category
name, proportional bar, and ringgit amount. Horizontal bars rather than a
pie: Malaysian category names are long, and the top three categories will
have similar magnitudes, which pies compare badly. Implementation is a
column of `Row`s containing `Box(Modifier.fillMaxWidth(fraction))`.

**Daily rhythm.** A calendar grid for the selected month, one cell per
day, Monday-first, on the same single-hue ramp as the category bars. It
answers a question neither of the other two can: *when* the money goes.
Malaysian spending is rhythmic — payday, weekend makan, the monthly grocery
run, the bills cluster — and a month total flattens all of it. Thirty
squares are also the cheapest visualization in the app: a `Row` of `Box`es
per week, reading the `local_date` aggregate that section 9.1 already
computes for the day subtotals. No new query.

**A cell has three states, and conflating any two of them is a lie.**

| State | Rendering | Means |
|---|---|---|
| Spent | filled, ramp step by magnitude | there were transactions, and this is their total |
| Nothing spent | outlined, unfilled | Pinged was watching and saw no spending |
| Not captured | hatched | Pinged was **not** watching, so nothing is known |

The third state is the one that makes this visualization honest, and the
reason a naive heatmap must not ship. An empty cell for a day the listener
was unbound tells the user they spent nothing on a day they may well have
spent hundreds. That is the same wrong-money failure the parser's
default-deny posture exists to prevent, arriving through the presentation
layer instead.

**Which means the heatmap needs evidence of liveness per day, and the
heartbeat in section 10.2 cannot supply it.** That heartbeat is a single
`DataStore` timestamp, deliberately: it is written on every notification
from any app and must not touch Room. One timestamp answers "is capture
alive now", which is all the capture-stopped banner needs, and it cannot
answer "was capture alive on 14 August". So the heatmap adds `capture_day`
(section 4) — one row per local date, upserted at most once a day from the
same throttled path that writes the heartbeat, gated on the date having
changed. One Room write per day does not have the invalidation problem that
put the heartbeat in `DataStore`.

Days before the install date are outside the grid's range entirely, drawn
blank rather than hatched, with the install date named in the footer — the
`Months` screen already establishes that vocabulary. Days after today in an
open month are likewise blank, never "nothing spent".

**Ramp buckets are quartiles of the displayed month's non-zero days, and
the legend prints the ringgit range they span.** A ramp keyed to the
month's own distribution keeps a quiet month legible instead of uniformly
pale, but it makes two months' colours incomparable, so the absolute
anchors are always on screen. Never let the colour be the only reading.

Zero-spend days are outlined rather than given a fifth ramp step: zero is
categorically different from "the least you spent", and the ramp means
magnitude and nothing else (see below).

**Top merchants.** A ranked list of merchants for the period with total
amount and transaction count. Not a chart, and the highest
insight-per-pixel screen in the app: the characteristic Malaysian spending
leak is a small amount repeated many times, which a category total hides
completely. "Transport RM388" is inert; "Grab, 18 times, RM312" is
actionable.

It ranks by section 6.4's resolved identity, not by raw `merchant_key`, and
does not ship before section 6.4 does. Without the merge, a shop paid
through two rails splits its total between two rows, and the export that
measured this found it in 4 of 7 Scan & Pay merchants.

### Honesty rules, enforced in the presentation layer

- **No unfair period comparison.** A month-to-date total is never compared
  against a completed month. Either compare same-day-of-month to
  same-day-of-month, or suppress the comparison until the month closes.
- **No charts on thin data.** Below 14 days of capture history, show
  "collecting — N days of data" in place of the chart rather than a
  partial-month shape that reads as a trend.
- **Excluded and pending rows never enter a total.** A day whose only
  transactions are excluded therefore reads as "nothing spent", which is
  correct for the total and misleading for the day. Tapping the cell opens
  that day filtered, excluded rows visible and struck through, so the money
  that moved is never hidden — only kept out of the arithmetic.
- **A gap in capture is never drawn as a zero.** See the three cell states
  above. This applies to every visualization: the month total goes grey and
  is labelled do-not-trust when the month contains uncaptured days
  (section 9, `Stopped`).

Category bars use a single-hue ramp, darkest for the largest category.
Categorical colour is not used anywhere in the app: category identity is
icon plus name (section 4), which keeps filtering from repainting the chart
and keeps the ramp meaning magnitude and nothing else.

### Typefaces, and how they ship

The Receipt design uses three families: **Instrument Serif** for hero
numerals and sheet titles, **Karla** for body text, and **IBM Plex Mono**
for the small-caps labels, amounts and every machine-voiced line. 
**Only the mono carries tabular numerals, and the division of duties follows
from that.** An earlier version of this paragraph claimed the serif's
numerals were tabular too. Measured from the shipped file, they are not:
Instrument Serif Regular's digit advances run 249–460 per 1000 em — a "1" is
249 and a "0" is 460, 1.85× apart — and the font contains no `tnum` feature
at all, so no `fontFeatureSettings` can conjure one. IBM Plex Mono's digits
are all 600, monospaced by construction.

So **every column of amounts is IBM Plex Mono**, which is where alignment is
load-bearing and where a proportional fallback would break it in exactly the
place accuracy is being claimed. **Instrument Serif is for single display
numbers** — the hero total, a sheet's headline figure — where proportional
digits are not a defect but the reason the face reads like money set in a
book rather than in a spreadsheet.

The consequence to design around: a serif hero number changes width as its
digits change, so nothing may be positioned relative to its right edge, and
two serif figures must never be stacked and expected to align. If a column
ever appears to want the serif, that is the moment to re-measure the file
rather than assume.

**They are bundled as resources, not fetched.** All three are SIL Open Font
License, so bundling is permitted, and the OFL text ships with the app
(a licences entry in settings). Only the weights actually used are
included — Instrument Serif Regular, Karla 400/500/700, IBM Plex Mono
400/500 — which is a few hundred KB against an APK already over ten MB.

The alternative, `androidx.compose.ui.text.googlefonts`, is rejected on two
grounds. It resolves through the Play Services font provider, so a device
without Play, or a user who sideloaded from GitHub onto a de-Googled ROM,
silently gets the fallback font — and this app's whole distribution story
is sideloading. Second, "the amounts are aligned" is a property that must
not depend on a network-adjacent component resolving at runtime.

A missing glyph must never fall back silently in the money path: the mono
family is declared with an explicit fallback chain, and the numerals are
verified in the screenshot tests rather than assumed.

### Explicitly rejected

Pie and donut charts, dual-axis charts, animated counters, categorical
colour palettes.

### Charting dependency policy

No chart library while the visualizations are rows, squares, a grid of squares, and lists.
Adopt Vico (Compose-native and maintained; MPAndroidChart is View-based and
requires interop) at the point of building an axis-bearing time-series
chart or interactive tooltips. Never grow a homemade charting framework in
between.

To keep that switch cheap, chart composables accept plain data classes
(`List<CategoryTotal>`, `List<MerchantTotal>`, `List<DayTotal>`) and know nothing of Room or
repositories.

## 9. Screens

### 9.1 Transaction list (home)

Chronological, grouped by day, with day subtotals. Three queries, not one:
a `PagingSource` for the rows, an aggregate keyed by `local_date` for the
day subtotals, and a separate month aggregate for the pinned summary. Day
subtotals cannot be summed inside a paged list, because a page boundary can
fall mid-day; Paging's separator support inserts the day header, and the
subtotal is joined in from the aggregate. Compact month summary
pinned at top (total + top three categories). Each row shows merchant
display name, category icon, source app, amount. Uncategorized rows carry a
tappable chip. Excluded rows are greyed with an exclusion badge. Search and
filter by category, source, date range, amount range.

### 9.2 Review inbox

Badge-counted entry point. One card per `PENDING` transaction showing:

- the **raw notification text verbatim**, always
- the app's interpretation (amount, merchant, category, source)
- the specific reason it needs review
- two or three large actions appropriate to the reason

Swipe to accept; "accept all" for batch clearing. Showing the raw text is a
hard requirement — the user must always be able to see exactly what the app
saw.

### 9.3 Charts

The three v1 visualizations, with a month selector: the daily rhythm grid
first, because it is the month's shape and the smallest of the three, then
category bars, then the merchant ranking.

### 9.4 Manual entry

For cash. Amount keypad open and focused on launch, category as a chip row
of most-used-first, merchant optional, note optional. Target three taps to
save. Reachable from the cash button on the list, and from a launcher shortcut
(`ShortcutManager`, a long-press on the app icon). Not a Quick Settings
tile: those are `TileService`, they live in the notification shade rather
than the home screen, the user has to add them by hand, and on API 34
launching an activity from one needs the `PendingIntent` overload of
`startActivityAndCollapse`. A shortcut is discoverable and free.

### 9.5 Settings

- Capture sources (section 9.6)
- Unmatched captures (rule authoring aid, section 5.6)
- Learned merchant rules: review, edit, delete
- User reject rules: review with sample text, delete (section 5.7)
- Rules you taught: review with sample text and match count, delete (5.8)
- Import a parser pack, with validation and dry run (section 5.9)
- Categories: add, rename, delete-if-unused
- Review threshold amount
- Export
- Capture health status
- Delete all data

### 9.6 Capture source allow-list

Android provides no per-app filter for `NotificationListenerService`: the
service receives every notification from every installed app, and filtering
is entirely this app's responsibility. The allow-list is therefore both a
user-facing screen and the mechanism the privacy claim rests on.

**Two stages.**

*Stage 1, metadata only.* For a package with `enabled = false`, the app
records only the package identifier and a seen count. No title, no text, no
extras. The discard happens in the first statements of
`onNotificationPosted`, before any content-bearing write.

Two honest corrections to how this used to be described. First, the app
cannot avoid *receiving* content: the system hands the process a full
`StatusBarNotification`, extras and all, before any of this code runs. What
is promised is that nothing unrelated is *stored*, which is a real and
testable promise, but a narrower one. Second, these counters live in
`DataStore`, not in `capture_source` — see section 10 for why a per-
notification Room write is unaffordable.

Third, and this is a decision rather than a correction: a durable per-package
seen count is itself a record of which apps the user has and how often each
one speaks, which for some apps is more sensitive per byte than the ledger.
So only a count is kept, never a **last**-seen timestamp.

**The 30-day drop is measured from first seen, and the distinction is the
whole point.** An earlier draft said "only a count is kept, never a
timestamp" and, in the same breath, that unenabled packages are "dropped
after 30 days" — thirty days since *what* being unanswerable, which made
the cleanup unimplementable as written. The resolution is that
`capture_source.first_seen_at` already exists and is not the thing the
privacy rule forbids: one datum recorded when a package is first discovered
says the app is installed, which the picker shows anyway. A **last**-seen
timestamp, updated on every notification, is a different object entirely —
it is a record of when the user talks to whom, from which sleep, work
patterns and absences read off directly. That is what must never exist, and
a count cannot reconstruct it.

So the rule is: a package that has never been enabled, and that the user has
never touched in the picker, is dropped once `first_seen_at` is more than 30
days old. Two consequences, neither a bug: a rediscovered package returns
with a fresh `first_seen_at` and a count of 1, losing its old count; and an
app that posts rarely can cycle in and out of the list, which is the
discovery list working rather than failing.

*Stage 2, content capture.* Only once the user enables a package does that
package's notification content get stored in `raw_capture`.

**Screen structure.**

- **Suggested** — known Malaysian bank and wallet packages that are actually
  installed on this device.
- **Recently seen** — packages observed posting notifications, with counts,
  so a source the suggested list missed is discoverable.

Each row shows the app label and icon where they can be resolved, a seen
count, an enable toggle, and the `is_authoritative` flag from section 7.2.

**Package visibility is the constraint here, and it is a hard one.** From
API 30, `PackageManager` results are filtered: an app sees only packages it
declares an interest in. Resolving a label, an icon or "is it installed"
for an arbitrary package needs either `QUERY_ALL_PACKAGES` or a static
`<queries>` list in the manifest. `QUERY_ALL_PACKAGES` is a Play-restricted
permission granted for device search, antivirus, file managers and similar;
an expense tracker will not qualify, and an earlier draft of this spec was
wrong to claim nothing here blocked a public release.

So: the known bank and wallet identifiers are declared in `<queries>` at
build time, and "All apps" is dropped as a browsing surface. The consequence
has to be stated because it undercuts a claim made elsewhere — the
*suggested list* now needs an app release to grow, even though *parse rules*
still do not. Packages outside the manifest list are shown by identifier
alone when they post something, which is enough to enable them but not
pretty.

Whether receiving a notification from a package grants visibility to that
package must be verified on a real API 30+ device before onboarding copy is
written. This design assumes it does not.

**Just-in-time prompt.** When an installed package from the suggested list
posts a notification while still disabled, the app raises a single one-time
prompt offering to enable capture for it. This keys on the package
identifier alone and never on notification content.

**Invariant.** The discard-before-store path is the whole basis of the
privacy posture and is covered by an explicit instrumented test asserting
that a notification from a disabled package produces no `raw_capture` row
and no stored content of any kind.

## 10. Reliability

The failure mode that kills this category of app is silent capture death.
Xiaomi, Oppo, Vivo and Realme are dominant in Malaysia and aggressively kill
notification listeners. A stopped listener that says nothing leaves the user
trusting a fabricated total for weeks.

### 10.1 Rebinding, which is where this app dies first

**Replacing the APK unbinds the listener, and on some platforms nothing
rebinds it.** This is long-standing behaviour and the most common cause of
"it worked, then it stopped" in this whole product category.

**Measured, and the premise is narrower than it was written.** On an AOSP
emulator at API 37 the system rebinds the listener *by itself*. A/B with the
receiver removed from the manifest entirely, and the process genuinely
replaced — pid and binder proxy both changed — the listener came back with
none of this app's code running. With the receiver present, logcat shows the
system binding **143ms before** the receiver runs, and `requestRebind` being
answered `is already bound`.

So on current AOSP the receiver is redundant. It stays, for two reasons that
are not the original one: `minSdk` is 27 and this is not measured across
that range, and the OEM ROMs the rest of this section exists for are exactly
the platforms that diverge from AOSP here. What changes is the framing — the
receiver is insurance against the platforms section 10 was written for, not
a fix for something every install breaks.

Do not delete it on the strength of one emulator, and do not describe it to
a user as the thing keeping capture alive.

Three things address it, and all three are required:

- A manifest receiver for `ACTION_MY_PACKAGE_REPLACED` and
  `BOOT_COMPLETED` (with `RECEIVE_BOOT_COMPLETED`) calling
  `NotificationListenerService.requestRebind(ComponentName)`, API 24.
- `requestRebind` on every app foreground while the grant is present.
- `onListenerDisconnected` requesting rebind, which covers only the case
  where the process survives.

The fallback when `requestRebind` does not take is toggling the service
component with `setComponentEnabledSetting`, which forces the system to
re-evaluate the binding.

**The grant and the binding are independent.**
`isNotificationListenerAccessGranted` can return true while nothing is
bound — that is precisely the OEM-kill signature, and naming it matters
because the two are easy to conflate. The check is still worth making,
because it is the only thing that detects the voided-`ComponentName` case
described in the front matter.

### 10.2 Detecting a dead listener, honestly

**Heartbeat.** A timestamp is updated on every notification from any app,
and on every `onListenerConnected`, so liveness is observable regardless of
whether the user spent money. These writes go to `DataStore`, throttled to
at most one write every five minutes, and never to Room: a phone posts
100-300 notifications a day, Room's invalidation tracker is table-granular,
and a heartbeat row in the database would re-emit every `Flow` observing
that table on every notification on the device.

**Foreground check is the primary detector.** On every app foreground: is
the grant present, is anything bound, and when was the last notification of
any kind seen? A stale answer raises the capture-stopped banner (section 9,
`Stopped`).

**A daily WorkManager check is best-effort, not a guarantee, and the spec
previously overclaimed here.** The worker runs in the same process the OEM
killed; on MIUI, ColorOS and FuntouchOS with autostart denied, it does not
run after the app is killed or swiped away. On AOSP, an app in the
`RESTRICTED` standby bucket gets roughly one window a day and periodic work
has no timing guarantee under Doze. So the worker is a bonus that sometimes
catches the problem earlier, and the claim made to the user is corrected to
match: Pinged notices when you open it, and tells you then.

**Reaching a user who is not opening the app needs a notification**, which
needs `POST_NOTIFICATIONS` (API 33+ runtime permission) and a channel. This
is declared and requested — it was missing from an earlier draft, along with
the same requirement for the section 9.6 just-in-time prompt. The app also
skips its own package at the top of `onNotificationPosted`, since its own
notifications reach its own listener.

**The always-visible signal costs nothing and is the most honest one:** the
home screen carries "last captured N minutes ago". No worker, no permission,
no promise that can turn out to be false.

### 10.3 Per-source liveness, which catches the likelier failure

A global "no notifications in 24 hours" check catches a dead listener. It
does not catch the more common and equally destructive case: the listener is
alive, other apps are notifying, and one bank's transaction channel has been
muted — by the user, by an app update, or by the OEM's notification manager.
The ledger silently loses one source while capture health reports green.

So `capture_source` carries `last_notification_at` and a rolling
`expected_monthly_count`, and the check is per source: "no Touch 'n Go
notifications in 14 days, and there were 40 last month". For a model with no
balances to reconcile against, this is the closest thing to a gap detector
the design has, and the data is already being written.

### 10.4 OEM onboarding

At first run, detect manufacturer and deep-link the relevant autostart or
background-power screen (MIUI autostart, Oppo and Realme startup manager,
Vivo background power management), with
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` as the generic fallback.

Two corrections to how defensively this has to be written. Those OEM
activities are frequently `exported="false"`, which means `resolveActivity`
returns non-null and `startActivity` then throws `SecurityException` — not
`ActivityNotFoundException`. Every deep link is wrapped against `Throwable`,
and the intent table is pack data that is expected to rot, not code. And
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` does very little on those ROMs,
whose process killers are independent of AOSP Doze; it is a fallback for
stock Android, not for the devices this section exists for.

Prefer `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (API 30) for the
grant itself, which opens this app's own toggle rather than a list, falling
back to `ACTION_NOTIFICATION_LISTENER_SETTINGS`.

### 10.5 Two states with no automatic recovery

**Force stop.** After Settings, Force stop, the app is in the stopped state:
the listener stays unbound and manifest broadcasts, including
`BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, are not delivered until the user
launches the app. Nothing recovers on its own. The remediation screen says
so.

**Before first unlock.** Listeners are not bound before the first unlock
after a reboot, and neither the Keystore key nor credential-encrypted
storage is available. Notifications posted in that window are lost. Small,
and a stated gap rather than a mystery.

**Restricted settings, API 33+.** For an app installed from outside an app
store — which is exactly the personal build — notification access sits
behind restricted settings: the toggle is greyed with "For your security,
this setting is currently unavailable", and the user must first use App
info, then the overflow menu, then Allow restricted settings. Android 15
generalises this as Enhanced Confirmation Mode. Onboarding must walk the
user through it or the app cannot be granted access at all. Whether an
`adb install` is exempt differs from a file-manager install and must be
tested on API 33 and API 35 before the onboarding copy is written.

## 11. Privacy and security

- **No `INTERNET` permission in the manifest.** The app itself has no
  network access. This is not the same as "cannot exfiltrate by
  construction", which an earlier draft claimed — see the backup section
  below, which is the hole in that claim.
- **Allow-list before storage.** Notifications from packages not enabled are
  discarded at the top of `onNotificationPosted`, before any write. The
  precise promise is that nothing unrelated is *stored*: the system hands
  the process the full notification before any of this code runs, so
  receiving it is not optional and it is dishonest to imply otherwise.
- **Encryption at rest.** Room over SQLCipher, key generated on first run
  and wrapped by the Android Keystore.
- **No analytics, no crash reporting, no ads, no third-party SDKs.**
- **Export is user-initiated only**, through the Storage Access Framework,
  so the app never holds broad storage permission.

### 11.1 Backup, and the ways the user loses everything

Four facts that compound, and the last draft of this spec addressed none of
them.

**`android:allowBackup` defaults to true.** Left alone, Android Auto Backup
uploads the app's files — the SQLCipher database among them — to the user's
Google Drive, through a system process that does not care that this app has
no `INTERNET` permission. That is both a privacy hole and useless, because:

**Keystore keys are not backed up and cannot be.** They are non-exportable,
app-scoped, and destroyed by uninstall and factory reset. They do not travel
with Google Backup or with device-to-device transfer. So a restored database
arrives without the key that opens it.

**`allowBackup="false"` alone is insufficient on API 31+.** Cloud backup and
device-to-device transfer are configured separately in
`android:dataExtractionRules`, under `<cloud-backup>` and
`<device-transfer>`. A new phone set up by D2D transfer would otherwise
receive the database with no key and SQLCipher would fail to open it, most
likely as a crash loop on first launch.

So: `allowBackup="false"`, the database and key excluded under **both**
`dataExtractionRules` sections, and the wrapped key held in
`getNoBackupFilesDir()`.

**Corruption has to have a path too.** An OEM process-killer landing
mid-write, or power loss, can leave a database that fails to open or fails
`PRAGMA integrity_check`. Because raw captures are irreplaceable, this is
detected at open and handled explicitly: tell the user, offer to export
whatever still reads, offer to start fresh. Silence or a crash loop here
destroys the one thing the app cannot rebuild.

**The database-without-key state must be handled, not crashed.** It arises
from D2D transfer, from a Keystore key invalidated by an OTA
(`KeyPermanentlyInvalidatedException` happens on real OEM devices), and from
partial restores. On detecting it the app says plainly what happened and
offers to start fresh or restore from an export. It never crash-loops.

### 11.2 Restore is a v1 feature, not a v1.1 feature

Section 12 claims the JSON export is "sufficient to rebuild the database".
Nothing in the spec read it back, which made that claim untrue, and left the
user with no recovery path at all from any of the states above.

JSON import is therefore built in **build step 1**, not step 7. The
development argument is as strong as the user-facing one: without it, every
debug reinstall destroys real capture history that provably cannot be
recovered, because Android will not replay the past.

### 11.3 Delete all data

Deleting everything means the database file plus its `-wal` and `-shm`
companions, the FTS shadow tables, the `DataStore` counters, and the
Keystore alias — deleted and regenerated. Then `VACUUM`, because
`clearAllTables()` does not shrink the file and section 15.6 promises the
user an honest storage figure.

### 11.4 Play Store readiness

Public release needs a privacy policy, a notification-access justification,
and a Data Safety declaration — which, with no network access and no
analytics, is genuinely "no data collected or shared".

One thing in this design **does** constrain a public release, and section
9.6 explains it: broad package visibility needs `QUERY_ALL_PACKAGES`, which
an expense tracker will not be granted, so the suggested-source list is
declared in `<queries>` at build time. An earlier draft's claim that nothing
here blocked a public release was wrong.

**The shipped permission set is six, not two, and the difference is
WorkManager's.** The app declares `RECEIVE_BOOT_COMPLETED` and
`POST_NOTIFICATIONS`. `androidx.work`'s own manifest merges in `WAKE_LOCK`,
`ACCESS_NETWORK_STATE` and `FOREGROUND_SERVICE`, and `androidx.core` adds a
signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. An earlier draft
of this spec and a comment in the manifest both claimed the set was "exactly
these two", which stopped being true the moment section 3 chose WorkManager
for stage two.

None of the three is a hole in the privacy claim, and the reason is worth
being precise about rather than reassuring: `ACCESS_NETWORK_STATE` reads
connectivity state and **cannot open a socket** — sending anything still
needs `INTERNET`, which is absent, so the Data Safety declaration is
unaffected. `WAKE_LOCK` keeps the CPU awake for a worker run.
`FOREGROUND_SERVICE` is declared by the library and unused by this app,
since nothing calls `setForeground`.

They are not removed with `tools:node="remove"`, deliberately: WorkManager
takes `WAKE_LOCK` on every run and reads `ACCESS_NETWORK_STATE` from its
constraint trackers at startup, so removing either fails at runtime *inside
the library*, where nothing in this app would notice. `FOREGROUND_SERVICE`
is the one plausibly removable and is left in place until a Play submission
makes it worth the risk — Play policy scrutinises a declared
`FOREGROUND_SERVICE` without a service type, so **revisit this before any
Play upload**, not before sideloading.

WorkManager also merges two exported components: its `SystemJobService`
(guarded by `BIND_JOB_SERVICE`) and `DiagnosticsReceiver` (guarded by
`DUMP`), plus `ProfileInstallReceiver` from `profileinstaller` (also `DUMP`).
All three are reachable only by the system or by shell, so none widens the
app's attack surface to other apps.

## 12. Export

- **CSV** for spreadsheets: date, amount, currency, direction, merchant
  display, merchant raw, category, source app, excluded flag, note.
- **JSON** for full fidelity: transactions plus raw captures plus learned
  rules plus categories plus section 6.4's merchant aliases and names, sufficient to rebuild the database — and read back
  by the importer in section 11.2, which is what makes that claim true.

Both are written through the Storage Access Framework.

**Both must stream.** At 50,000 raw captures with full text the JSON export
is tens of megabytes; it is written with `JsonWriter` straight to the SAF
`OutputStream`, off the main thread, with progress and cancellation. Nothing
is assembled in memory first.

**CSV has three requirements that are easy to miss.** RFC 4180 quoting,
because merchant names and notes contain commas, quotes and newlines. A
UTF-8 BOM, because Excel is an intended destination and mangles UTF-8
without one. And neutralisation of leading `=`, `+`, `-` and `@` in any
field, because a merchant string arrives from outside the app and a
spreadsheet will treat it as a formula.

**Numbers and dates are formatted with `Locale.ROOT`.** `String.format`
under a comma-decimal locale writes `12,34` into a CSV field and corrupts
the file.

On import, `ACTION_OPEN_DOCUMENT` MIME filtering on `application/json` is
unreliable across document providers, so the picker accepts `*/*` and the
content is validated after reading.

## 13. Testing

`:core:parse` — the priority. A golden fixture corpus of real notification
strings, each paired with an expected outcome. Critically, the corpus
includes **negative fixtures**: promotional pushes, TAC messages, failed
transactions, and reversal notices, each asserted to produce no transaction.
Every newly supported bank contributes fixtures before rules are written.

`:core:parse` also covers: amount normalization edge cases (thousands
separators, `MYR` prefix, three decimals rejected, zero rejected, sanity
ceiling), merchant cleanup, rule priority ordering, and reject-before-match
ordering.

**Two constraints in this design pass their tests while doing nothing, and
both have to be tested for enforcement rather than declaration.** They are
the same trap wearing different clothes, and both were caught only because
a test was written to fail:

- **Encryption.** Every test of opening, migrating and querying the database
  passes identically against a plaintext file. So one test reads the first
  sixteen bytes off disk and asserts they are not `SQLite format 3\u0000`,
  and another asserts the platform's keyless SQLite cannot open the file at
  all. Removing `.openHelperFactory` must fail them both.
- **Foreign keys.** Room's generated `onOpen` does issue
  `PRAGMA foreign_keys = ON`, so the constraint looks handled. But that
  pragma is **per-connection**, and SQLCipher — unlike the framework helper
  — runs a connection pool behind one `SQLiteDatabase`. Room's `execSQL` is
  a write, so it lands on the pool's primary connection and nowhere else.
  Deletes and inserts are also writes, which is why constraint tests passed
  anyway: enforcement by accident of statement routing. Reading the pragma
  back off the readable connection returned 0. The fix is
  `SupportSQLiteDatabase.setForeignKeyConstraintsEnabled(true)` from a
  `RoomDatabase.Callback`, which reconfigures the whole pool rather than one
  connection. So the test suite reads `PRAGMA foreign_keys` back from every
  reachable connection, and separately proves a constraint violation throws.

The general rule both cases teach: **a guard that cannot be observed failing
has not been tested.** Assert the guard's effect, then break the guard on
purpose and watch the assertion go red.

`:core:categorize` — learned rules outrank bundled, unknown falls through to
Uncategorized, retroactive application of a new learned rule.

Learned rules — an exact rule stores the normalized string and a
byte-identical later capture hits it; a capture differing by one token does
not hit, and produces a second rule rather than a silent miss; longest
common substring broadening over the worked pair yields `YUEN KEE HOME
TOWN`, is trimmed to whole tokens, and is refused when every token in the
substring is generic; and the conflict case, where a rule matching two
different non-Uncategorized categories is applied going forward but refused
retroactive application.

Derived templates — literal spans are escaped so a message containing regex
metacharacters cannot break the pattern; untapped digit runs generalize to
`\d+` while the tapped amount keeps its capture group; a derived template is
forced to `REVIEW` for exactly its first three matches; and the ordering
case, that a pack template matching the same text wins over a user template.

Pack import — every validation rule rejects its own malformed case; the dry
run reports counts without writing; and a pack that would reparse an already
matched capture differently is reported rather than applied quietly.

User reject rules — skeleton normalization over amounts, thousands
separators and mixed-language text; a stored skeleton hides a later matching
capture from the unread list; and the load-bearing negative case, that a
user reject rule whose skeleton also matches a real transaction template
does **not** suppress the transaction.

Dedup — identical notification repeats collapse; cross-package same-amount
pairs flag; two genuine same-amount purchases outside the window do **not**
merge; same amount within the window from the same package collapses.

Confidence gate — table-driven across every `PENDING` trigger, plus the
negative case that a clean known-merchant capture commits.

`:core:data` — Room migration tests. The schema will move; migrations are
tested from every released version.

**Extraction, which the JVM corpus does not cover.** The corpus tests
`:core:parse` from strings onward; nothing tested `Notification` to strings,
which is where the bugs are. A Robolectric or instrumented suite builds real
`Notification.Builder` objects in each style and asserts the extracted field
set: `SpannableString` values (which `getString` returns null for),
`bigText` versus `text` selection, `MessagingStyle`'s `EXTRA_MESSAGES`,
`InboxStyle`'s `EXTRA_TEXT_LINES`, group summaries, and a fully custom
`RemoteViews` notification, which must classify as `NO_EXTRAS`.

**Room and SQLCipher seams, settled in week one rather than at step 6.**
`MigrationTestHelper` opens a plain unencrypted database unless it is given
the SQLCipher `SupportSQLiteOpenHelper.Factory`, so without that the
migration tests exercise a database the app does not ship. `exportSchema`
is on and the schema JSON is committed from v1, because "migrations are
tested from every released version" is impossible retroactively without it.
And whether FTS4 with the `unicode61` tokenizer is available in the pinned
SQLCipher build is verified before search is designed around it.

**The seeded-scale fixture** from section 15.8 is a real database of 50,000
raw captures and 6,700 transactions, used for the timing assertions there.

Instrumented — two tests guarding wiring that unit tests cannot reach:
a synthetic notification from an enabled package posted through a real
`NotificationListenerService` produces a committed transaction; and a
notification from a disabled package produces no `raw_capture` row and no
stored content, enforcing the section 9.6 invariant.

Granting notification access in an instrumented test is done with
`UiAutomation.executeShellCommand("cmd notification allow_listener <flattened
component>")`, then waiting for `onListenerConnected`; API 33+ also needs a
`POST_NOTIFICATIONS` grant. Feasible, but not free, and the plan should not
read as though it were.

**What no emulator can test.** OEM process-killing cannot be reproduced in
CI. The mitigation is a manual device matrix — one Xiaomi, one Oppo or
Realme, one Vivo, one Samsung, one Pixel — and a documented multi-day soak
with a known notification cadence, checked against expected capture counts.
Stated here so that it gets scheduled rather than assumed.

## 14. Build order

1. `:core:data` schema, `:core:parse` with the rule engine and a fixture
   corpus for a single wallet, **and JSON export plus import**. Import is
   first, not last: without it every debug reinstall destroys capture
   history that cannot be recovered (section 11.2).
2. `:feature:capture` listener, allow-list, discovery screen, raw capture
   persistence.
3. Confidence gate, review inbox, transaction list.
4. Categorization with bundled dictionary and learned rules.
5. Dedup layers and transfer handling.
6. The two visualizations.
7. Manual entry, capture health, OEM onboarding, and the rebinding
   receivers from section 10.1 — which in practice want doing on day one,
   because without them capture dies on every build installed.

Rule coverage for additional banks and wallets is continuous from step 1
onward, driven by the authoring loop in section 5.6.

## 15. Performance at rest

Volume is not the risk, and the numbers below are for *captured* rows only.
The listener sees far more than it captures: a normal phone posts 100-300
notifications a day. Those touch nothing in the database — heartbeat and
per-package counters live in `DataStore` and are throttled (sections 9.6 and
10.2), because Room's invalidation tracker is table-granular and a
per-notification row write would re-emit every `Flow` observing that table
on every notification on the device.

Using the figures in the design mockups — around
112 transactions and roughly 850 captured notifications a month — five years
of use is about 6,700 transactions, 50,000 raw captures and 40MB on disk.
SQLite on a phone is untroubled by that. The risks are all in access
patterns and thread placement, so they are specified rather than left to
discovery.

### 15.1 Indexes

Every query that runs per-capture or per-frame has an index behind it.

| Query | Index |
|---|---|
| Duplicate layer 1, by content and slot | `raw_capture(content_hash)`, `raw_capture(sbn_key)` |
| Duplicate layer 2, same amount in a window | `txn(amount_sen, occurred_at)` |
| Month list, charts, month picker, daily rhythm grid | `txn(local_date)` |
| Which days capture was alive | `capture_day` PK is `local_date` |
| Review inbox badge and list | `txn(state, occurred_at)` |
| Excluded rows filtered from totals | covered by the `local_date` index plus a `state`/`is_excluded` predicate |
| Unread captures list | `raw_capture(parse_status, posted_at)` |
| Stage two work queue | `raw_capture(parse_status, posted_at)`, same index |
| Learned rule lookup | `merchant_rule(pattern)` |
| "used by N transactions", and the category foreign keys | `txn(category_id)`, `merchant_rule(category_id)` |
| Reject rule lookup | `user_reject_rule(source_package, skeleton)` |

Duplicate layer 2 deserves the note: it runs on every single capture, and
without `(amount_sen, occurred_at)` it is a full scan of the `txn` table
each time. It is the query most likely to be missed and the one that
degrades most predictably.

**None of these are partial indexes.** Room's `@Index` supports `value`,
`name`, `unique` and `orders` — there is no `where` clause. An earlier draft
specified a partial index on `PENDING` rows; creating that in raw migration
SQL would put the database permanently out of step with Room's expected
schema and fail validation. A plain composite on `(state, occurred_at)`
serves both the badge count and the ordered inbox, and is expressible.

### 15.2 Search

`LIKE '%grab%'` cannot use a B-tree index. At 6,700 transactions a scan of
two text columns is a few milliseconds, so the honest reason to move off it
is ranking and multi-token queries rather than raw speed — and the first fix
is a **debounce on the query**, which no earlier draft specified and which
matters more than the index.

Search is then backed by a Room `@Fts4` table with
`contentEntity = Txn::class`, mirroring `merchant_display`, `merchant_raw`
and `note`. External-content FTS means **Room generates the sync triggers**;
hand-maintaining them through DAO writes is a bug farm. The FTS entity's
`rowid` maps to the content entity's `INTEGER PRIMARY KEY`, and FTS tables
can carry neither indices nor foreign keys.

**Adopting FTS changes the feature, not only its performance, and that is a
product decision.** FTS4 matches token prefixes: `grab*` finds "Grab", but
nothing finds "Grab" from the query `rab`. Infix matching needs trigram
tokenization, which is FTS5-only and not available here. Prefix search is
accepted as the behaviour; the search field's placeholder says "starts
with".

The tokenizer is `unicode61`, not the default `simple`, which splits on
ASCII non-alphanumerics only and handles "Touch 'n Go" and non-ASCII
merchant names badly. Its availability in the pinned SQLCipher build is
verified in week one (section 13).

**SQLCipher's key derivation is the one performance decision that dwarfs the
rest.** SQLCipher 4 defaults to 256,000 PBKDF2-HMAC-SHA512 iterations, paid
on *every* database open — including every cold listener process start, on
the critical path of capturing a notification. Because the passphrase is a
random Keystore-wrapped value rather than a human password, key derivation
buys nothing: raw key mode
(`PRAGMA key = "x'<64 hex key><32 hex salt>'"`) skips it entirely.

Filters (category, source, date range, amount range) are ordinary indexed
predicates applied alongside the FTS match.

### 15.3 Aggregates in SQL, never in Kotlin

Category totals, merchant rankings, month totals and day subtotals are all
`SUM` and `GROUP BY` queries, keyed on `local_date`. Rows are never loaded
to be summed in application code.

Refunds subtract, so the aggregate is
`SUM(CASE WHEN direction = 'REFUND' THEN -amount_sen ELSE amount_sen END)`
— which can return zero or a negative for a category, or for a whole month.
`Box(Modifier.fillMaxWidth(fraction))` requires a fraction in `(0f, 1f]`,
and a month total of zero makes the denominator zero. So the mapping layer
clamps: a net-negative category renders at zero width with its true
signed amount shown as text, and a zero or negative month total suppresses
the bars entirely rather than dividing by it. Both cases are in the fixture
set, because both arrive on a real refund. The chart composables take
`List<CategoryTotal>`/`List<MerchantTotal>` — already the shape section 8
requires for a possible later chart library — so the aggregate is computed
once by SQLite and the UI holds only the result.

### 15.4 The transaction list is paged

Home observes a Room `PagingSource`, not `Flow<List<Transaction>>`. The
latter re-emits every row on every insert, which at a few thousand rows is
visible jank on the screen the user looks at most. The pinned month summary
is a separate aggregate query, so a new capture updates the total without
re-reading the list.

### 15.5 Batch jobs are chunked, cancellable and bounded

Two jobs read the whole capture history and both are bounded:

**Re-parse after a pack upgrade** (section 5.5) walks `UNMATCHED` captures
with a keyset cursor in chunks, commits per chunk, and records its position
so an interrupted run resumes rather than restarting. It never holds the
result set in memory.

**Pack import dry run** (section 5.9) is capped at the most recent 5,000
captures. At 50,000 captures and 46 rules an uncapped preview is over two
million regex evaluations, and it grows for as long as the app is installed.
The cap is stated in the UI rather than applied silently.

### 15.6 What grows, and the one thing that is trimmed

Raw captures are never deleted; that decision earns its keep three times
over (re-parse, reject-rule sample text, and the import dry run). But
`extras_json` is the fat column and exists only for extraction nobody has
specified yet. So only a whitelist of keys is stored — the ones the parse
pipeline reads — and the rest of the bundle is discarded at capture time.
Title, text and bigText are kept in full, forever, which is what every
stated benefit actually depends on.

Settings shows storage used, so growth is observable rather than mysterious.
That figure will read higher than the 40MB estimate above, which counts row
data only: the FTS index typically costs 30-50% of the text it indexes, the
ordinary indexes cost more, SQLCipher pads pages, and the write-ahead log
adds its own. Worth saying so here, so it is not filed as a bug later.

### 15.7 Time zone, currency and locale

Three formatting decisions that silently corrupt data if left implicit.

**One fixed zone, stored as a column.** `occurred_at` is epoch millis, but
every grouping the app does is by local day or month. The zone is the
device's current zone, resolved once at parse time into the `local_date`
column (`yyyymmdd`). Grouping through `strftime(..., 'localtime')` instead
would depend on the process time zone, could not use an index, and would
silently reshuffle history when the user travels — a month total that
changes because someone flew to Bangkok is a bug nobody would diagnose.

**Currency is formatted explicitly, never by default locale.**
`NumberFormat.getCurrencyInstance()` on a phone set to en-US renders MYR as
"MYR 12.34" or worse. An explicit MYR formatter produces "RM12.34"
everywhere.

**`Locale.ROOT` for every machine-facing operation** — amount parsing, CSV
writing, and every `uppercase()`/`lowercase()` in normalization, skeleton
matching and token comparison. `String.format("%.2f")` under a
comma-decimal locale writes `12,34`, which corrupts both the CSV export and
any amount parsed back from it. The Turkish dotless-i is the classic reason
`uppercase()` without a locale is a latent bug in exactly this kind of
string matching.

### 15.8 Tests

Performance claims are cheap to assert and expensive to discover late:

- A seeded database of 50,000 raw captures and 6,700 transactions is a test
  fixture, not a thought experiment. Duplicate detection, the month
  aggregate and a search query each run against it with an upper bound on
  query time.
- A query-plan check asserts that duplicate layer 2 and the month aggregate
  do not scan, so an index dropped in a later migration fails the build
  instead of the app. It is a substring assertion — plan output is not
  stable across SQLite versions, SQLCipher bundles its own build distinct
  from the platform's, and results depend on whether `ANALYZE` has run — so
  it asserts `SEARCH` rather than `SCAN TABLE` on named queries only, and
  is expected to need maintenance. The timing test above is the one that
  carries real weight.
- The re-parse job is tested for resumption after cancellation mid-run.

## 16. Deferred

**v1.1** — budgets per category; recurring
and subscription detection.

**v1.2+** — six-month stacked trend (adopting Vico); accounts and observed
balances with gap detection; remote parser-pack updates, and sharing derived
templates between users, both of which introduce `INTERNET` and need a trust
model; per-package Kotlin handlers if the declarative vocabulary proves
insufficient.
