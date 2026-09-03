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
| Network | No `INTERNET` permission | Strongest possible privacy claim; forces local design |

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

```
NotificationListenerService.onNotificationPosted(sbn)
  │
  ├─ package not in enabled allow-list → return immediately, nothing stored
  │
  ├─ extract extras: title, text, bigText, subText, postTime
  ├─ compute dedupe_hash = sha256(package | normalized_text | postTime/1000)
  ├─ INSERT raw_capture                      ← always, even if unparseable
  │
  ├─ hash seen within 60s → mark DUPLICATE_OF, stop
  │
  ├─ RuleMatcher(package)
  │     1. reject patterns  (promo / OTP-TAC / failed / reversal-notice)
  │     2. transaction templates, highest priority first
  │     no match → parse_status = UNMATCHED, stop
  │
  ├─ Normalizer      amount → sen, occurred_at, merchant cleanup
  ├─ Categorizer     learned rule → bundled dictionary → Uncategorized
  ├─ DuplicateDetector  cross-package same-amount window check
  ├─ ConfidenceGate  → COMMITTED or PENDING
  └─ INSERT transaction; UI updates via Room Flow
```

**The callback does three things only:** extract extras, check the allow-list,
and enqueue. Everything downstream — the insert, matching, categorization,
duplicate detection — runs on a single-threaded dispatcher with a bounded
queue.

An earlier draft of this design had parsing run inline in the callback on
the grounds that the regex is sub-millisecond. The regex is; the work around
it is not. A SQLCipher insert costs 5-20ms, index maintenance grows with the
table, and duplicate detection is a query. Doing that synchronously on the
listener's main thread is an ANR risk that gets worse as data accumulates,
which is exactly the failure mode this app cannot afford, because a killed
listener is a silent gap in the ledger.

Ordering within the queue is preserved so that duplicate detection sees
captures in arrival order. If the queue saturates, captures are still
written to `raw_capture` and marked for the re-parse job rather than
dropped.

WorkManager is used for the daily health check, bulk re-parse after a pack
upgrade, and the pack-import dry run.

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
| posted_at | Long | notification `when`, epoch millis |
| captured_at | Long | when this app saw it |
| title | Text? | |
| text | Text? | |
| big_text | Text? | |
| sub_text | Text? | |
| extras_json | Text? | whitelisted extras only, see section 15.6 |
| dedupe_hash | Text, indexed | |
| parse_status | Enum | `MATCHED`, `UNMATCHED`, `REJECTED`, `DUPLICATE_OF` |
| rejected_by_rule_id | Text? | which reject pattern fired |
| matched_rule_id | Text? | |
| pack_version | Int | pack that produced this outcome |
| duplicate_of_id | Long? | |
| user_reject_rule_id | Long? | hidden from the unread list, section 5.7 |

Raw captures are never deleted by the app. They are the audit trail, they
let a rule change be validated against real history, and they make any field
we failed to extract recoverable later by re-parsing. A user-initiated
"delete all data" wipes them along with everything else.

### `transaction`

| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| raw_capture_id | Long? | null for manual entries |
| amount_sen | Long | always positive |
| currency | Text | `MYR` default |
| direction | Enum | `EXPENSE`, `REFUND` |
| occurred_at | Long | |
| merchant_raw | Text? | exactly as parsed |
| merchant_display | Text? | after cleanup, user-editable |
| category_id | Long | FK, defaults to Uncategorized |
| source_package | Text? | null for manual |
| source_label | Text? | e.g. `Touch 'n Go eWallet` |
| confidence | Enum | `HIGH`, `REVIEW` |
| state | Enum | `COMMITTED`, `PENDING`, `REJECTED` |
| is_excluded | Bool | transfers/reloads: kept, not counted |
| exclusion_reason | Enum? | `TRANSFER`, `CARD_PAYMENT`, `ATM_WITHDRAWAL`, `USER` |
| note | Text? | |
| user_edited | Bool | blocks re-parse overwrite |
| created_at, updated_at | Long | |

`REFUND` rows subtract from totals. `is_excluded` rows appear in the list
greyed out and are absent from every total and chart.

### `merchant_rule`

| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| match_type | Enum | `EXACT` on save, `CONTAINS` only after section 6.2 |
| pattern | Text | matched against `merchant_raw`, case-insensitive |
| merchant_display | Text | |
| category_id | Long | |
| origin | Enum | `BUNDLED`, `LEARNED` |
| priority | Int | `LEARNED` always outranks `BUNDLED` |
| hit_count | Int | |

### `category`

Flat, no hierarchy. Seeded: Makan, Groceries, Transport, Petrol & tolls,
Bills & utilities, Telco & internet, Shopping, Health, Education, Family,
Religious & zakat, Government & fees, Entertainment, Uncategorized.

Columns: `id`, `name`, `icon_key`, `sort_order`, `is_protected`.

**Icons.** `icon_key` holds a Lucide icon name. The icons are imported as
vector drawables rather than through a library dependency: single-colour
stroke paths on a 24px grid, tinted at runtime from the `ink` and `muted`
tokens. The seeded mapping is fixed:

| Category | `icon_key` | Category | `icon_key` |
|---|---|---|---|
| Makan | `utensils` | Family | `users` |
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
and delete only when unused. Uncategorized has `is_protected = true` and can
be neither renamed, re-iconed, nor deleted, because the confidence gate and
the categorizer both resolve to it by name-independent id.

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

**Rename is global and retroactive, by design.** `transaction.category_id`
is a foreign key; no transaction stores a category name. So renaming a
category relabels every transaction that references it, in every past month,
in the charts, and in future exports. That is correct for fixing a label
("Makan" to "Food") and wrong for repurposing one ("Shopping" to "Baby
things"), which would silently rewrite history. The rename dialog therefore
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
| package | Text PK | |
| label | Text | display name |
| enabled | Bool | allow-list gate |
| is_authoritative | Bool | see section 7.2 |
| first_seen_at | Long | |

Rows are created by the discovery screen (section 9.5), not hardcoded.

### `capture_health`

Single-row table: `last_listener_connected_at`, `last_any_notification_at`,
`last_matched_notification_at`, `consecutive_silent_days`.

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

Reject patterns are evaluated before any transaction template, always.

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

If a real Malaysian format cannot be expressed within this vocabulary, that
is the signal to reconsider a per-package Kotlin handler — not to grow the
rule language.

### 5.3 The traps the reject patterns exist for

These are the real reason default-deny is non-negotiable:

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
  terminal codes matching `-[A-Z0-9]{2,4}$`), collapse whitespace, title-case
  for display. `merchant_raw` is preserved untouched.
- **Date.** The notification `when` timestamp is authoritative unless a rule
  extracts `occurred_at` (relevant for card postings that backdate).
- **Direction.** Comes from the matched rule only, never inferred from text.

The prefix and suffix lists live in the pack, not in code, so they are
editable without a release.

### 5.5 Versioning and re-parse

Each transaction records the `pack_version` and `matched_rule_id` that
produced it. On pack upgrade, a WorkManager job re-parses raw captures whose
`parse_status = UNMATCHED`.

Re-parse never modifies a transaction where `user_edited = true` or
`state != PENDING`. Newly matched captures produce new transactions subject
to the normal confidence gate.

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
predicates in section 5.2; and every package with rules also declares its
reject patterns first.

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

**Layer 1 — same notification re-posted.** Android fires
`onNotificationPosted` on updates as well as new posts. `dedupe_hash`
(package + normalized text + second-bucketed `postTime`) repeating within
60 seconds is stored as `DUPLICATE_OF` and produces no transaction.

**Layer 2 — same purchase seen twice.** One card swipe can fire both the
banking app and a separate card-alert notification. Flag
`DUPLICATE_SUSPECT` when: identical `amount_sen`, within 10 minutes,
different `source_package`, and merchant tokens overlap or one merchant is
absent. The newer transaction goes to the review inbox offering
**Merge** or **Keep both**.

Duplicates are never dropped automatically. Two genuine RM5.00 parking
payments in one afternoon are entirely normal, and silently deleting one
would be a wrong total the user cannot detect.

The coarse control that removes most of this class: marking one
`capture_source` as `is_authoritative` and disabling the others.

### 7.3 Transfers, reloads, card payments

Rules tag wallet reloads, credit-card bill payments, DuitNow transfers to
self, and ATM withdrawals as `kind: TRANSFER_SUSPECT` with an
`exclusion_reason`. These land in the review inbox with a targeted prompt —
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

v1 ships two, both without a charting dependency.

**Month at a glance.** A hero total for the selected month, followed by
horizontal bars per category ordered by magnitude, each showing category
name, proportional bar, and ringgit amount. Horizontal bars rather than a
pie: Malaysian category names are long, and the top three categories will
have similar magnitudes, which pies compare badly. Implementation is a
column of `Row`s containing `Box(Modifier.fillMaxWidth(fraction))`.

**Top merchants.** A ranked list of merchants for the period with total
amount and transaction count. Not a chart, and the highest
insight-per-pixel screen in the app: the characteristic Malaysian spending
leak is a small amount repeated many times, which a category total hides
completely. "Transport RM388" is inert; "Grab, 18 times, RM312" is
actionable.

### Honesty rules, enforced in the presentation layer

- **No unfair period comparison.** A month-to-date total is never compared
  against a completed month. Either compare same-day-of-month to
  same-day-of-month, or suppress the comparison until the month closes.
- **No charts on thin data.** Below 14 days of capture history, show
  "collecting — N days of data" in place of the chart rather than a
  partial-month shape that reads as a trend.
- **Excluded and pending rows never enter a total.**

Category bars use a single-hue ramp, darkest for the largest category.
Categorical colour is not used anywhere in the app: category identity is
icon plus name (section 4), which keeps filtering from repainting the chart
and keeps the ramp meaning magnitude and nothing else.

### Explicitly rejected

Pie and donut charts, dual-axis charts, animated counters, categorical
colour palettes.

### Charting dependency policy

No chart library while the visualizations are rows, squares and lists.
Adopt Vico (Compose-native and maintained; MPAndroidChart is View-based and
requires interop) at the point of building an axis-bearing time-series
chart or interactive tooltips. Never grow a homemade charting framework in
between.

To keep that switch cheap, chart composables accept plain data classes
(`List<CategoryTotal>`, `List<MerchantTotal>`) and know nothing of Room or
repositories.

## 9. Screens

### 9.1 Transaction list (home)

Chronological, grouped by day, with day subtotals. Compact month summary
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

The two v1 visualizations, with a month selector.

### 9.4 Manual entry

For cash. Amount keypad open and focused on launch, category as a chip row
of most-used-first, merchant optional, note optional. Target three taps to
save. Reachable from a FAB on the list and from a home-screen quick
settings tile.

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

*Stage 1, metadata only.* For a package with `enabled = false`, the listener
writes only `package`, `last_seen_at` and a seen count to `capture_source`.
No title, no text, no extras. The discard happens in the first statements of
`onNotificationPosted`, before any content-bearing write. The app can
therefore display "some messaging app, 340 notifications seen" while holding
none of their content.

*Stage 2, content capture.* Only once the user enables a package does that
package's notification content get stored in `raw_capture`.

**Screen structure.**

- **Suggested** — known Malaysian bank and wallet packages that are actually
  installed on this device, resolved through `PackageManager`, so the list
  is useful on first run rather than empty. The suggested identifier list
  ships in the parser pack and is refined as identifiers are verified on
  real devices.
- **Recently seen** — packages observed posting notifications, with counts,
  so a source the suggested list missed is discoverable.
- **All apps** — searchable fallback.

Each row shows app icon, label, seen count, an enable toggle, and the
`is_authoritative` flag described in section 7.2.

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

Three defences:

1. **Heartbeat.** The listener updates `last_any_notification_at` on every
   notification from any app, and `last_listener_connected_at` on every
   `onListenerConnected`. Liveness is therefore observable independently of
   whether the user spent money. Heartbeat updates for non-allow-listed
   packages write only a timestamp — no notification content is stored.
2. **Daily WorkManager check.** If `isNotificationListenerAccessGranted` is
   false, or no notification of any kind has been seen in 24 hours, raise a
   persistent in-app banner: "Capture stopped N days ago — tap to fix",
   linking to a remediation screen. The app never fails silently.
3. **OEM onboarding.** At first run, detect manufacturer and deep-link the
   relevant autostart or background-power screen (MIUI autostart, Oppo and
   Realme startup manager, Vivo background power management), with
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` as the generic fallback. Deep
   links are attempted defensively: an unresolvable intent falls back to
   app settings plus written instructions.

Additionally: `onListenerDisconnected` is handled by requesting rebind, and
the notification-access grant state is re-checked on every app foreground.

## 11. Privacy and security

- **No `INTERNET` permission in the manifest.** The app cannot exfiltrate
  data, by construction.
- **Allow-list before storage.** Notifications from packages not enabled in
  `capture_source` are discarded at the top of `onNotificationPosted`,
  before any write. The app never holds unrelated notification content,
  even transiently.
- **Encryption at rest.** Room over SQLCipher, passphrase generated on
  first run and stored in the Android Keystore.
- **No analytics, no crash reporting, no ads, no third-party SDKs.**
- **Export is user-initiated only**, through the Storage Access Framework,
  so the app never holds broad storage permission.
- **Delete all data** wipes transactions, raw captures, learned rules and
  health state in one action, with confirmation.

Play Store readiness note: public release will require a privacy policy and
a notification-access justification, and would add `INTERNET` only if a
remote parser-pack channel is introduced. Nothing in this design blocks
either.

## 12. Export

- **CSV** for spreadsheets: date, amount, currency, direction, merchant
  display, merchant raw, category, source app, excluded flag, note.
- **JSON** for full fidelity: transactions plus raw captures plus learned
  rules plus categories, sufficient to rebuild the database.

Both are generated on demand and written through the Storage Access
Framework.

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

Instrumented — two tests guarding wiring that unit tests cannot reach:
a synthetic notification from an enabled package posted through a real
`NotificationListenerService` produces a committed transaction; and a
notification from a disabled package produces no `raw_capture` row and no
stored content, enforcing the section 9.6 invariant.

## 14. Build order

1. `:core:data` schema, plus `:core:parse` with the rule engine and a
   fixture corpus for a single wallet.
2. `:feature:capture` listener, allow-list, discovery screen, raw capture
   persistence.
3. Confidence gate, review inbox, transaction list.
4. Categorization with bundled dictionary and learned rules.
5. Dedup layers and transfer handling.
6. The two visualizations.
7. Manual entry, export, capture health, OEM onboarding.

Rule coverage for additional banks and wallets is continuous from step 1
onward, driven by the authoring loop in section 5.6.

## 15. Performance at rest

Volume is not the risk. Using the figures in the design mockups — around
112 transactions and roughly 850 captured notifications a month — five years
of use is about 6,700 transactions, 50,000 raw captures and 40MB on disk.
SQLite on a phone is untroubled by that. The risks are all in access
patterns and thread placement, so they are specified rather than left to
discovery.

### 15.1 Indexes

Every query that runs per-capture or per-frame has an index behind it.

| Query | Index |
|---|---|
| Duplicate layer 1, by hash | `raw_capture(dedupe_hash)` |
| Duplicate layer 2, same amount in a window | `transaction(amount_sen, occurred_at)` |
| Month list, charts, month picker | `transaction(occurred_at)` |
| Review inbox badge and list | `transaction(state)` where state is `PENDING` |
| Excluded rows filtered from totals | covered by the `occurred_at` index plus a `state`/`is_excluded` predicate |
| Unread captures list | `raw_capture(parse_status, posted_at)` |
| Learned rule lookup | `merchant_rule(pattern)` |
| Reject rule lookup | `user_reject_rule(source_package, skeleton)` |

Duplicate layer 2 deserves the note: it runs on every single capture, and
without `(amount_sen, occurred_at)` it is a full scan of the transaction
table each time. It is the query most likely to be missed and the one that
degrades most predictably.

### 15.2 Search

`LIKE '%grab%'` cannot use a B-tree index, so a naive search scans every row
on every keystroke. Search is backed by a Room `@Fts4` table mirroring
`merchant_display`, `merchant_raw` and `note`, kept in step with the
`transaction` table by trigger or by the same DAO write. Filters (category,
source, date range, amount range) are ordinary indexed predicates applied
alongside the FTS match.

### 15.3 Aggregates in SQL, never in Kotlin

Category totals, merchant rankings, month totals and day subtotals are all
`SUM` and `GROUP BY` queries. Rows are never loaded to be summed in
application code. The chart composables take
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

### 15.7 Tests

Performance claims are cheap to assert and expensive to discover late:

- A seeded database of 50,000 raw captures and 6,700 transactions is a test
  fixture, not a thought experiment. Duplicate detection, the month
  aggregate and a search query each run against it with an upper bound on
  query time.
- A Room query-plan test asserts that duplicate layer 2 and the month
  aggregate use their indexes rather than scanning, so an index dropped in a
  later migration fails the build instead of the app.
- The re-parse job is tested for resumption after cancellation mid-run.

## 16. Deferred

**v1.1** — daily-rhythm calendar heatmap; budgets per category; recurring
and subscription detection.

**v1.2+** — six-month stacked trend (adopting Vico); accounts and observed
balances with gap detection; remote parser-pack updates, and sharing derived
templates between users, both of which introduce `INTERNET` and need a trust
model; per-package Kotlin handlers if the declarative vocabulary proves
insufficient.
