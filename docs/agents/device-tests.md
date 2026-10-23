# Device tests

Instrumented tests need an emulator or phone attached. `:core:parse` is a plain
JVM module and has no instrumented source set, so anything device-specific is
tested from `:feature:capture`.

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

## Running the least

Every minute a session holds the emulator, the others wait, so run the least
that answers the question. While iterating, run the one class:
`-Pandroid.testInstrumentationRunnerArguments.class=<fully qualified name>`.
Gradle's configuration cache can reuse the previous filter, so pass
`--no-configuration-cache` whenever the filter changes. A comma list in
`class=` runs only its first class; for several, use
`-Pandroid.testInstrumentationRunnerArguments.tests_regex='.*(A|B|C).*'`.
A filter that ran too little looks like a pass: check the result XML's
`classname`s.

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
assert nothing; CI runs them, and `CI=true` before `./gradlew` runs them here.

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
it, with the figures that showed it. If an existing rule covers the cause,
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
it never touched, and only on the short screen.

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
Passing `Dispatchers.Unconfined` to a holder only runs its own steps inline;
it does not make the DataStore step synchronous. This applies to the JVM
tests under Robolectric as much as to the device ones.
