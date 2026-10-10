# What `capture_day` records, against a real export

Issue #63. The question: day by day, what does `capture_day` record on a real
phone, and where does it part from whether Pinged was actually watching?

The evidence is one device export, taken 2026-10-10 18:13 (+08:00): format 2,
schema v2, no restore, installed 2026-09-07. The export stays off the
repository. Only counts and dates from it appear here. Every number below was
computed from the file with a script, and none is estimated. Code references
are to `main` at 3d8a226.

## Answer

| Measure | Count |
|---|---|
| Dates in 2026-09-07..2026-10-10 with no `capture_day` row | **0** of 34 |
| Rows with `listener_bound = 1`, `saw_any_notification = 0` | **0** of 34 |
| `txn` dates with no row, or with a row saying unbound | **0** of 32 |
| Rows that show a part day (an unbind partway through) | **0**, and none can |

All 34 rows read `listener_bound = 1, saw_any_notification = 1`. On this
device the table never diverged from "Pinged was watching". It also could not
have said anything else. As the next section shows, the code can produce only
two row shapes, the export holds only one of them, and a row has no way to
represent losing the listener.

## When each column is written

**There are two production writers. Neither writes `false`.**

1. **The notification path.** `CaptureIngest.ingest` calls
   `CaptureDays.markNotificationSeen(LocalDates.of(now))`
   (`feature/capture/.../CaptureIngest.kt:97`). That reaches
   `CaptureDayDao.recordNotificationSeen` (`core/data/.../dao/CaptureDayDao.kt:66-77`):
   - It runs for any app's notification. Two kinds are skipped: Pinged's own
     package (`CaptureIngest.kt:70`) and group summaries (`:74`). The call runs
     before the allow-list gate, so a disabled package still marks the day.
   - It runs only if the database opens (`CaptureStorage.guarded`,
     `CaptureIngest.kt:87-99`). When the key is gone, no row is written.
   - It is called for `Arrival.POSTED` and for `Arrival.CATCHUP`. CATCHUP is the
     re-delivery of the whole shade in `onListenerConnected`
     (`PingedNotificationListener.kt:60`). A rebind with anything still in the
     shade therefore sets `saw_any_notification = 1` for the bind's date, even
     when nothing new was posted that day.
   - The date comes from `now` (the capture time) in `ZoneId.systemDefault()`
     (`core/data/.../LocalDate.kt:51`), not from the notification's `postTime`.
   - It sets `saw_any_notification = 1`. If the row does not exist yet, it
     creates it with `listener_bound = 1`.
2. **The bind path.** `onListenerConnected` calls
   `CaptureDays.markListenerBound` (`PingedNotificationListener.kt:41-43`).
   That reaches `recordListenerBound(date, true)` (`CaptureDayDao.kt:86-97`),
   which sets `listener_bound = 1`. If the row does not exist yet, it creates it
   with `saw_any_notification = 0`.

Each writer is memoised per process, keyed on day and database generation
(`core/data/.../CaptureDays.kt:75-80, 96-101`). After the first write of a day,
neither writer reaches the database again until the date changes or the
process dies.

`setListenerBound(..., false)` has no production caller. Its KDoc
(`CaptureDayDao.kt:48-52`) says "spec 10.2's throttled heartbeat writes what it
observed". No such heartbeat exists: `CaptureHealth.recordSeen`
(`feature/capture/.../CaptureHealth.kt:74-78`) writes only to DataStore. Only
an import (`Backup.kt:231-237`) can store `listener_bound = 0`.

The spec does not match the code either. Spec §4 says the row is "upserted at
most once per day from the same throttled path that writes the `DataStore`
heartbeat". In the code, the two writers above do it, and the heartbeat path
does not touch Room.

**The row shapes that production can produce:**

- `(1, 1)`: a notification arrived. A bind may also have happened.
- `(1, 0)`: the listener bound, and no notification arrived later that day
  in this process.

`(0, x)` is unreachable without an import. The export contains only `(1, 1)`.

## When a row is missing or wrong

- **Missing:** a calendar day on which no app posted anything that reached
  the listener and no bind happened. The cause can be a dead listener, a
  database that would not open, or a phone that was off.
- **Missing until later in the day:** if the listener stays bound across
  midnight, the new day's row is not written until the first notification of
  that day (`CaptureDayDao.kt:185-194`). An export shows only the final state,
  so this export cannot measure how long a row was missing on each day. All it
  can show is that every one of the 34 days eventually got its row.
- **Wrong (overclaims coverage):** a day that was bound for one minute and
  dead for the rest still reads `(1, 1)` or `(1, 0)`. Neither column records
  when the listener was bound or for how long.
- **Wrong (stale bind):** `saw_any_notification = 1` can come from a CATCHUP
  of notifications posted on earlier days.

**Who reads it.** The only production reader is `boundDayCount`
(`LedgerViewModel.kt:311`), which reads `listener_bound`. No production code
reads `saw_any_notification`.

## What a row can and cannot prove

**A row proves** that at some moment on that date (device zone), one of two
things happened while the database was openable:

- a notification from some app reached the listener, or
- the system bound the listener.

**A row cannot prove** any of the following:

- that the listener was bound for the whole day
- that it was bound when a given bank notification was posted
- that no unbind happened
- that the user had enabled any source

A missing row does not prove the listener was dead. A day with no
notification from any app and no bind produces the same missing row.

## Supporting measures from `raw_capture` and `txn`

These measures cover the two enabled sources only (210 rows), so they are much
sparser than the "any app" signal that writes `capture_day`.

- **Dates.** Captures fall on 33 distinct dates. The first is 2026-09-08. The
  one `capture_day` date with no capture is 2026-09-07, the install day.
- **Transactions.** `txn` covers 32 dates. For every txn,
  `local_date = date(occurred_at) = date(captured_at of its raw row)`.
- **Arrival.** All 210 rows are `POSTED`, and 0 are `CATCHUP`. Either no
  rebind found a bank notification still in the shade, or there were no
  rebinds after the install bind. The export cannot tell which. Either way, no
  rebind is visible in the data.
- **Delivery lag.** `captured_at - posted_at` is between 0.21 s and 0.29 s
  (median 0.225 s). 0 rows have a different local date for `captured_at` and
  `posted_at`, so no notification was delivered late across midnight.
- **Captures per date.** Minimum 1, median 6, maximum 17. Two dates have
  exactly one capture.
- **Hour of the day's first capture.** 22 of the 33 days fall at 11:00-12:59.
  Two fall in the 00:00 hour.
- **Longest silence inside a day,** measured between the first and last
  capture of that day:

  | Hours | Days |
  |---|---|
  | 0-4 | 10 |
  | 4-8 | 22 |
  | 12-16 | 1 (12.6 h) |

  The 12.6-hour silence runs from a capture just after midnight to the next
  one around midday. That matches the overnight quiet seen on every other day.
- **Gaps between consecutive captures across the whole history.** 29 are
  12 hours or longer, and 2 are 24 hours or longer (the longest is 24.6 h).
  Every one of these spans overnight.

None of these measures can separate an unbind from a quiet stretch with no
spending. Bank notifications follow what the user spends, and both enabled
sources were quiet overnight on every day.

## Conclusion

The table recorded "watching" on all 34 days, and nothing in the export
contradicts that. But the table cannot record the failure it was built to
show:

- `listener_bound` has no writer of `false`.
- Neither column records a part day.
- The column that separates "quiet" from "dead" (`saw_any_notification`) has
  no reader.

The midnight gap is real, but it is visible only during the day it occurs
(before that day's first notification). It leaves no trace in an export, and
it caused no missing row here.
