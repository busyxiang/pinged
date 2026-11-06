# Device tests

Instrumented tests need an emulator or phone attached. `:core:parse` is a plain
JVM module and has no instrumented source set, so anything device-specific is
tested from `:feature:capture`.

## Before writing a test

Each line is a shape of test code that has flaked here, and passed on a dev
emulator before it failed on CI. Its section has the cause, the figures and
the way to make it fail on purpose.

- A tap or read straight after `MainActivity` launches, with no
  `compose.awaitTheTabBar()` first. [The app before its first
  sample](#the-app-before-its-first-sample).
- A tap with no `performScrollTo()` first, or `performScrollTo()` on a lazy
  row the list has not composed. [CI's screen](#cis-screen).
- A step after typing into a field, on a screen that measures its layout
  once. [The keyboard](#the-keyboard).
- An assert straight after `waitForIdle()` on a result that DataStore,
  `Dispatchers.IO` or `runBlocking` produces, on the device or under
  Robolectric, including one a tab switch or a resume starts. [Work the compose clock does not
  drive](#work-the-compose-clock-does-not-drive).
- A write straight after building a holder that reads in its `init`.
  [A holder that reads on arrival](#a-holder-that-reads-on-arrival).
- `System.nanoTime()` around a step, from one run or at the longest of
  several, held to a bound. [A duration on a busy
  host](#a-duration-on-a-busy-host).
- `withTimeoutOrNull(1_500)` around an answer that a wrong outcome would
  never give. [A duration on a busy host](#a-duration-on-a-busy-host).
- A `while (now < deadline) { ...; Thread.sleep(100) }` loop of your own
  around a condition that reads Espresso or compose. [A wait of your
  own](#a-wait-of-your-own).
- One `uiAutomation.takeScreenshot()` straight after `waitForIdle()`,
  read for a sheet's pixels. [A screenshot of a
  sheet](#a-screenshot-of-a-sheet).
- A seeded `RawCapture` at `UNMATCHED` with no `packVersion`, read back
  from the unread list. [A capture stage two may
  requeue](#a-capture-stage-two-may-requeue).
- A tap on a word near a link in a text, asserting it opens nothing.
  [A link's touch area](#a-links-touch-area).

A rule added for a new flake gets a line here too, written as the code a
reader would see in their own test, not as the cause.

## The lease

Sessions in other worktrees share the one emulator and install the same
applicationId, so an unleased run lands in the middle of theirs.
`.claude/bin/emu` holds a lock for the command, boots the emulator if it is
down, and stops one it booted after 15 idle minutes (it holds 5G). A
PreToolUse hook refuses `adb` and connected, install or uninstall tasks
outside it. A lease waits up to 30 minutes for the one before it, longer than
the Bash timeout, so run every `emu` command with `run_in_background`;
`emu --status` names the holder.

A lease that holds the emulator for 20 minutes is stopped, its command's
whole process group with it, and `emu` exits 124; `--max-hold SECONDS` is for
the rare run that needs longer. At the start and end of every lease `emu`
kills shell-user processes left orphaned on the device.

A lease's command can be stopped part-way and run again from the start: the
user can put a waiter first, and the holder then makes way and reruns after
it, as the same process. So a lease's command must be safe to run twice; one
that is not leaves its own state behind it, not only the device's.

Leases queue first come, first served, within a priority. Pass `--priority
low` for a run nobody is waiting on: a repro or flake loop, a falsification
script, anything you would otherwise leave running while you work on
something else. Every ordinary lease then goes ahead of it. `--priority high`
is the user's to give, not a session's to take; the user can also raise or
lower a waiting lease from the emu-queue pane.

## Running the least

Every minute a session holds the emulator, the others wait, so run the least
that answers the question. While iterating, run the one class:
`-Pandroid.testInstrumentationRunnerArguments.class=<fully qualified name>`.
Gradle's configuration cache can reuse the previous filter, so pass
`--no-configuration-cache` whenever the filter changes. A comma list in
`class=` runs only its first class; for several, use
`-Pandroid.testInstrumentationRunnerArguments.tests_regex='.*(A|B|C).*'`.
A filter that ran too little looks like a pass: check the result XML's
`classname`s. Alternate whole names, `.*(FooReadsTest|FooViewModelTest).*`:
`.*Foo(Reads|ViewModel)Test.*` selected no test at all, and the build passed
in 4s with an empty result XML. Why is not understood. `class=` with one
fully qualified name has done the same, with `--no-configuration-cache`
passed, for a class added to the APK in that build
(`ReviewFocusScrollTest`); `tests_regex=.*ReviewFocusScrollTest.*` then ran
it.

Before calling work done, run the suites the change can reach, which is what
CI's emulator job runs on a pull request into `main` that is not a draft, at
CI's 320x640 as that job does:

    .claude/bin/emu --ci-size -- ./gradlew $({ git diff --name-only $(git merge-base main HEAD); git ls-files --others --exclude-standard; } | python3 .github/scripts/affected_suites.py)

The diff includes uncommitted and untracked files: a branch with no commits
yet has an empty committed diff, and an empty diff selects every suite.
An empty selection is the opposite: no suite can be reached, and the bare
`./gradlew` only prints help.
The whole `connectedDebugAndroidTest` is for when the selection rules
themselves are in doubt; main's push runs every suite regardless.
A local run skips `:core:data`'s `@Measurement` tests, which log a figure and
assert nothing, and so does a PR's CI run; main's push runs them, and
`PINGED_MEASURE=true` before `./gradlew` runs them here.

## Loops

A loop that repeats a test, to reproduce a flake or count its rate, takes one
lease per iteration: the others queue behind a lease for as long as it lasts.
Lease each run and let the loop wait between them:

    for i in $(seq 10); do .claude/bin/emu -- ./gradlew <task> <filter> || fails=$((fails + 1)); done

An `emu` inside a lease's command waits for that same lease and never gets
it, so the loop goes outside `emu`, never inside it.

A loop backgrounded through `adb shell` keeps `adb shell` from returning,
redirected or not, and its lease hangs until `--max-hold` stops it. Run the
loop on the host.

To find a flake's cause, log the figures it depends on and compare a passing
run with a failing one. Put the test and the log read in one script, so one
lease runs `adb logcat -c`, then the test, then `adb logcat -d -s <TAG>`. Then
call the script from the loop: `emu -- script.sh runN`. Stop the loop once you
have one run of each, to free the emulator. To falsify a flake's fix, loop the
reverted code until the first failure. Then check that the failure in the
result XML is at the test's own assertion.

A session that fixes a flaky test also updates this file, in the same branch.
Add the cause as a rule that stops the next test or screen from repeating
it, with the figures that showed it, and a line under [Before writing a
test](#before-writing-a-test). If an existing rule covers the cause,
explain why the rule was missed. A cause that stays only in the commit is
found again by the next flake.

## CI's screen

CI's emulator screen is shorter than a dev one. A tap in an instrumented test
uses `performScrollTo()` first, or a row pushed off the screen is missed on
CI only. `performScrollTo()` cannot reach a row of a lazy list that was never
composed: scroll the list itself first, with `performScrollToKey` or
`performScrollToNode`. Every run that decides done is at CI's size, with
`emu --ci-size`: it sets 320x640 at density 160 and resets both when the
command ends, fails or is killed. That includes the suites a change reaches
and not only the tests it adds: a change to what a screen draws breaks tests
it never touched, and only on the short screen. #366's one sentence under
ON YOUR PHONE pushed Sources' lower list out of composition at that size, and
`SourcesAllowListTest`, which waited for a row there without scrolling, failed
every run until it scrolled the list with `performScrollToNode`.

## The keyboard

A test that types into a field does not wait for the keyboard. The keyboard
rises over several frames, and the window shrinks at each one. In
`MerchantSheet`'s list at CI's size, the heights went 435, 373, 281, then
229px. A test's next step can land at any of those heights. So code that
measures the layout once, for example in a `LaunchedEffect` keyed on the typed
text, sees whatever height that frame has. `MerchantSheet`'s
scroll-to-search ran at 373px, before the list could scroll, and moved 0px.
After that, nothing ran again. The test failed 4 runs of 17. `waitForIdle()`
did not hide the race.

Code that scrolls or places something from a measured layout has to react to
the layout changing. Collect a `snapshotFlow` of the viewport size and act on
each value, as `MerchantSheet` now does. Acting once on the first value races
the keyboard. A lazy item can also be missing from `visibleItemsInfo` for a
frame while the list shrinks. So scroll by the item's index, or wait for the
next layout, rather than give up. The fix goes in the screen, not the test.
A user's keyboard rises the same way.

## Work the compose clock does not drive

`waitForIdle()` waits for compose, not for a thread compose does not drive.
A read that suspends on DataStore (`captureStore`), a real `Dispatchers.IO`
or a `runBlocking` finishes on its own clock, and `waitForIdle()` can return
first. `RewriteRereadTest` counted 2 reads where CI had seen 1: the first
read still held `ScreenRead`'s turn, so the second had not begun. It passed
on a dev machine 5 runs of 5. Holding the DataStore step 300ms made it fail
every time, which is the way to find this before CI does.

A test that follows such a read polls for the thing it asserts on, with a
deadline (a published answer, a count of runner calls), and then asserts.
The rule is easy to miss when the test never asks for the read. A tab
switch or a lifecycle move starts it. `ReviewWaysInTest` waited for Charts'
read and then asserted the Spending tab's count straight after
`waitForIdle()`. The count is a separate read, and until it lands the tab
draws a dash whose description the test's matcher counts. `MonthPickerTest`
moved the activity to `RESUMED`, called `waitForIdle()`, and asserted this
month's total was absent. That passes before the resume's re-read lands,
whether the read is right or wrong. A negative assert after an unwaited read
cannot fail. Wait for the read itself: the summary's
`ScreenRead.beforeEachOperation` to count that one has started, then
`rereading` false. With the resume made to re-read this month and the read
held 500ms, the old test passed and the new one failed at its assertion.
With the tab's count held 3s, the old `ReviewWaysInTest` failed with the
dash counted as 1, and the new one passed.

Passing `Dispatchers.Unconfined` to a holder only runs its own steps inline;
it does not make the DataStore step synchronous. This applies to the JVM
tests under Robolectric as much as to the device ones.

## The app before its first sample

A test that launches `MainActivity` calls `compose.awaitTheTabBar()` before
its first tap or read of the app. A class whose rule launches it does this in
`@Before`. Without notification access, which is how CI's emulator starts,
`AppStatus` does not yet know whether first run shows onboarding. Until its
first sample has run the probe, the backup count and `onboards`, the window
is bare Paper. That sample is work the compose clock does not drive (above).
The rule there was missed because the read is the app's own, started by
`onResume`, and no line of the test asks for it.
`AlertSettingsChannelTest` tapped SETTINGS 0.49s into its run on CI and found
no node. On the shared emulator, without the grant, the bar was drawn 230 to
266ms after launch, and it was already there after `waitForIdle()` in 8
launches of 8. So a dev run passes, and only CI's slower emulator loses the
race.

To catch a test that skips the wait, pass
`-Pandroid.testInstrumentationRunnerArguments.holdSampleMs=1500`. It holds
every sample, so the race fails every time instead of only on CI.
`BackupReminderTest` grants notification access and leaves it on, so in a
whole run every class after it starts granted, with the bar drawn from the
first frame. In one lease's script, under `--priority low`, revoke the grant
and then run the classes in question:
`adb shell cmd notification disallow_listener
my.pinged.tracker/my.pinged.capture.PingedNotificationListener`.
Held 1.5s without the grant, 22 tests in 11 classes failed at a tap or read
of the bar straight after launch. The other 5 tests in those classes waited
for something else first. With the wait, all 27 passed.
`TabBarWaitTest` holds the sample 3s, and takes the grant away for its run.
Under the hold, `BackupReminderTest` also fails 3 or 4 of its 4 tests. The
reminder is never composed, and why is not yet understood, so a whole run
with the hold is not clean.

## A holder that reads on arrival

A holder built with a read in its `init` (`ManualEntries` ranks the chips
there) has a read in flight that the test did not ask for.
It reads before the test's next write and publishes from its own thread, so
it can land after the read the test then waits on, and put the old answer
back. `CategoryRankingTest.aManualEntryCountsLikeACapture` failed on CI with
"expected Family but was Food & Drinks" for this reason. It passed on every
earlier run, because the window is a few microseconds wide.

A test that builds such a holder waits for the arrival read's answer
(`categories.first { it.isNotEmpty() }` under `withTimeout`) before it writes.
To find the window instead of waiting for CI to hit it, make the arrival read
publish late: spin it until the later read has published, then publish. With
the later read held 300ms, the old test failed every time at the CI assertion;
with the wait it passed under the same hold. The chips are a `ScreenRead`
now, whose turn lands reads in the order they were asked, so that holder
can no longer publish a stale read over a newer one (#376); a holder that
reads outside a `ScreenRead` still can.

## A duration on a busy host

A test that times a step on the device and holds it to a bound measures wall
time, and wall time includes every moment the thread was runnable but not
running. CI's emulator shares a 4-vCPU runner with Gradle, so a descheduling
lands on whichever step happens to be running. `ReadHoldTest` failed on main
twice this way: one page's transaction at 97ms in one run and 140ms in
another, against the 66ms bound, while the other nine pages took 5 to 34ms.

Starving the host reproduced it. Pin qemu to one host CPU and run two busy
loops on that CPU, inside the lease's script, and unpin it on exit:

    taskset -acp 0 $(pgrep -f qemu-system-x86_64-headless)
    taskset -c 0 sh -c 'while :; do :; done' &   # twice
    # on exit: kill the loops, then taskset -acp 0-11 <the same pid>

Busy loops inside the guest do not do the same. They reproduced timing races
in app code, but not CI's render timeouts.

Logging each transaction's wall time beside `Debug.threadCpuTimeNanos()` and
`Debug.getRuntimeStat("art.gc.gc-count")` showed what the outlier was. One page
took 90ms of wall time on 19ms of CPU, with no GC in it. Unstarved, wall time
and CPU time were equal to the millisecond. So it was not GC, and it was not
the transaction doing more work. Thread CPU time is no fix either: on the
starved guest, the cold first read's pages took 74ms of it, against 17ms
unstarved.

A step's work is the same each time it runs on the same data, and a
descheduling is not. So a test times the step several times on the same data
and holds the bound against each step's shortest run. It also checks that
every run took the same steps, or the shortest pairs different work.
`ReadHoldTest` reads 3 times. Starved, the old single-read test failed 3 runs
of 3 and the new one passed 5 of 5. With the pages put back into one
transaction, a real hold, the new one failed at 211ms and 212ms unstarved, and
at 794ms starved. This cannot see a hold that comes only on some runs, such as
contention with another caller. A test that needs to see that has to make the
contention happen on every run.

A cancel's latency is timed the same way. `RowReaderDamageTest` and
`SalvageTransfersTest` cancel the same walk of the same damaged file 3
times and hold the bound to the quickest stop. `SharedInstanceUnderUseTest`
reseeds its backlog each round, since a run consumes it. `OpenTest` opens
the same file 3 times. None of these five was seen failing: starved as
above, their single samples peaked at a 20.7ms cancel against 50ms, a 26ms
open against 150ms and a 24ms delete wait against 500ms, in 3 runs of each.
They took the shape `ReadHoldTest` failed on CI with, so they were changed
before CI found them. Several runs held at their longest are no better
than one: `RetroactiveFixTimingTest` holds the shortest of its 5 saves,
since one descheduled save would fail a bound on the longest.

A budget for an answer that a wrong outcome never gives is not a timed
step. In `TransfersTest`, a request queued behind a held restore would
not answer until the restore is released. So the budget it is given to be
refused only has to outlast a descheduling, and fit inside the hold. Size
it to the hold, not to how fast the right outcome usually comes.

## A capture stage two may requeue

A capture seeded at `UNMATCHED` keeps `RawCapture.packVersion`'s default of
0. `Reparse.sweep` moves every such capture back to `NEW` on any stage-two
run that finds the sweep for the loaded pack unrecorded, which a fresh
install of the test APK is, and the drain then parses it again. Until it
does, it is off the unread list; a wording the pack reads or rejects never
comes back. A Teach save, a foreground with the grant held, or a
notification starts that run, and WorkManager can run it after the test that
asked for it has ended, in the next test's freshly seeded ledger.
`UnreadFromSourcesLineTest.settingsOpensCouldNotReadUnfiltered` failed once
in its first two runs, straight after the class's Teach test, with its MAE
fixed-deposit notice missing from the list. That run was not logged, so this
path is the explanation that fits, not one observed. Seed the capture with
`packVersion = Graph.parsePack().packVersion`, as stage two would have
marked it, and with a wording no pack rule reads or rejects.

## A link's touch area

Compose widens a pointer target smaller than 48dp to 48dp when a tap hits
nothing else, and a `LinkAnnotation` run is such a target: at the sources
line's 16dp lines, a link reaches 16dp above and below itself. So a tap on
a word in the line under a link opens that link, whatever the text around
it does. `SourcesLineTapTest`'s first version tapped `CANNOT` one line under
`NO APP IS READ YET` at CI's 320dp width, and it opened Capture sources with
the whole-text click removed as with it, so it could not fail. It now draws
the line 120dp wide and asserts the word it taps is two lines below every
link before it taps.

## A screenshot of a sheet

A sheet is a dialog, a window of its own, and `waitForIdle()` returns once
compose is done, not once that window's frame is on the display.
`uiAutomation.takeScreenshot()` reads the display. So a test that takes one
capture can read the screen before the sheet arrives: bare Paper, with no
scrim and no sheet. `SheetChromeTest.theChoosersTopEdgeIsDashedInInk` failed
1 to 2 full `:feature:ledger` runs in 3 on 2026-11-06, while several
sessions shared the host, and passed alone. Three more full runs on a
quieter host all passed.

Starving the host ([A duration on a busy host](#a-duration-on-a-busy-host))
reproduced it in the class alone: "top row, 52px down, is 0% Ink and 100%
Paper", the screenshot bare Paper, though `dumpsys window` straight after
listed the dialog's window on screen. One capture failed 5 class runs of 6,
at the chooser's and the receipt sheet's edges, and
`SharedControlsTest.theChoosersDisabledSaveIsAnOutline` failed 4 of 4 with
Save's bounds 100% Paper. Capturing in a loop after `waitForIdle()` showed
the dialog arriving 22 to 77ms later. No failing frame showed a window an
earlier test had left.

A test that reads a sheet off the screen uses
`ArtboardProbe.settledScreen()`, which captures until two captures 100ms
apart agree. The 100ms has to be longer than that lag, or two bare screens
agree. Starved, all 14 `SheetChromeTest` tests passed 4 runs of 4 with it.
Captures for 1s after each of those 48 settles changed once, by a 13x12px
status-bar icon. `captureToImage` is no fix: it cannot find a sheet's dialog,
and reads the activity's window instead.

## A wait of your own

`boundedWaitUntil` is the one wait for a condition that reads Espresso or
compose. Its KDoc says why. A condition that looks at semantics or a view
first waits for idle, and a loop that checks its deadline only between
calls cannot stop a call that never returns. `BoundedWaitSourceTest`
refuses a bare `waitUntil`. It cannot see a loop written by hand, and
`DeleteReachesTheLedgerTest` had one around an Espresso `check` and
`waitForIdle()`, waiting for the keyboard. A loop over a database row, a
flag or a DataStore value, from a test with no screen, cannot hang this
way and is fine.
