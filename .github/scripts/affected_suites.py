#!/usr/bin/env python3
"""The instrumented suites a set of changed files can affect.

Usage: affected_suites.py [--all] [--shard ID] < changed-paths
Prints the Gradle tasks to run, space-separated (empty: run none), and the
reason for each on stderr. Under GitHub Actions it also writes `tasks` and
`smoke` to $GITHUB_OUTPUT.

`--all` selects every suite without reading stdin: main's push. `--shard`
narrows the selection to one emulator job's share of it (SHARDS below), and
appends that shard's Gradle arguments when it runs anything.

The module graph is read from settings.gradle.kts and each module's
build.gradle.kts on every run, not written down here, so a new module or a
new `project(...)` edge is picked up without anyone remembering this file.

What a path does:
- `<module>/src/androidTest/**`: that module's own suite.
- `<module>/src/test/**`: nothing here. JVM tests run in the checks job.
- anything else under `<module>/` (src/main, its build file, R8 rules, the
  Room schema): a production change. It reaches the module's suite, every
  module that depends on it in production, transitively, and every module
  whose androidTest depends on any of those -- `:feature:capture`'s suite
  drives `:feature:ledger`'s screens. `:smoke` targets `:app`, so any
  production change anywhere reaches it, which is right: R8 reads it all.
- a path in INERT: nothing.
- no paths at all: every suite. That is a diff that failed, not a PR that
  changed nothing.
- anything else (root build files, gradle/, an unknown path): every suite.
  A rule this file does not know is a reason to run, not to skip.

Skipping is what a falsified rule here would cost, so main's push runs every
suite regardless (ci.yml) and catches what a PR skipped.
"""
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Read by nothing the emulator job builds or runs. The workflow files are here
# because the selection is the point of skipping a CI-only PR; main's run
# still exercises the emulator steps a CI change touches.
INERT = (
    re.compile(r"^docs/"),
    re.compile(r"^[^/]*\.md$"),
    re.compile(r"^version\.txt$"),
    re.compile(r"^\.release-please-manifest\.json$"),
    re.compile(r"^\.github/(workflows|actions|scripts|ISSUE_TEMPLATE)/"),
    re.compile(r"^\.claude/"),
    re.compile(r"^\.gitignore$"),
)

# One emulator job each. A single emulator runs the suites one after another:
# ~10 min of device time in #17's runs, :feature:ledger alone 357-381s of it,
# and :smoke's 6s test behind a ~3 min R8 build that took the runner's CPU
# from whichever suite was on the device meanwhile. Balanced on those sums:
# each half of :feature:ledger, :core:data + :app + :feature:capture
# (184-227s), and :smoke with its R8 build. :feature:charts joins the
# middle job: one test class, under a second on emulator-5554.
#
# The halves are AndroidJUnitRunner's numShards/shardIndex, which split by
# test, not by class, in one process per half.
LEDGER = ":feature:ledger:connectedDebugAndroidTest"
HALF = "-Pandroid.testInstrumentationRunnerArguments.numShards=2 -Pandroid.testInstrumentationRunnerArguments.shardIndex="
SHARDS = {
    "ledger-1": ([LEDGER], HALF + "0"),
    "ledger-2": ([LEDGER], HALF + "1"),
    "data-app-capture": ([
        ":core:data:connectedDebugAndroidTest",
        ":app:connectedDebugAndroidTest",
        ":feature:capture:connectedDebugAndroidTest",
        ":feature:charts:connectedDebugAndroidTest",
    ], ""),
    "smoke": ([":smoke:connectedMinifiedAndroidTest"], ""),
}

TEST_CONFIG = re.compile(r"^androidTest")
SKIPPED_CONFIG = re.compile(r"^(test|kapt|ksp|lint)")


def modules(root=ROOT):
    """Gradle path -> directory, from settings.gradle.kts."""
    settings = (root / "settings.gradle.kts").read_text()
    found = re.findall(r'^\s*include\("(:[^"]+)"\)', settings, re.M)
    return {m: m[1:].replace(":", "/") for m in found}


def edges(mods, root=ROOT):
    """(production, test): dependent -> modules it depends on."""
    prod = {m: set() for m in mods}
    test = {m: set() for m in mods}
    for m, d in mods.items():
        build = (root / d / "build.gradle.kts").read_text()
        for config, dep in re.findall(r'(\w+)\(project\("(:[^"]+)"\)\)', build):
            if TEST_CONFIG.match(config):
                test[m].add(dep)
            elif not SKIPPED_CONFIG.match(config):
                prod[m].add(dep)
        for target in re.findall(r'targetProjectPath\s*=\s*"(:[^"]+)"', build):
            test[m].add(target)
    return prod, test


def suite_task(m, mods, root=ROOT):
    """The module's instrumented task, or None if it has no suite."""
    d = root / mods[m]
    if (d / "src" / "androidTest").is_dir():
        return f"{m}:connectedDebugAndroidTest"
    if re.search(r"targetProjectPath", (d / "build.gradle.kts").read_text()):
        return f"{m}:connectedMinifiedAndroidTest"
    return None


def select(paths, root=ROOT):
    """(tasks in module order, {task: reason}) for the changed paths."""
    mods = modules(root)
    prod, test = edges(mods, root)
    suites = {m: suite_task(m, mods, root) for m in mods}
    everything = {t: "" for t in suites.values() if t}

    reasons = {}

    def add(m, why):
        t = suites.get(m)
        if t and t not in reasons:
            reasons[t] = why

    paths = [p.strip() for p in paths if p.strip()]
    if not paths:
        # A diff that could not be computed arrives as nothing. Skipping on
        # it would turn a broken input into a green run.
        return list(everything), {t: "no changed files were given" for t in everything}

    by_depth = sorted(mods.items(), key=lambda kv: -len(kv[1]))
    changed_prod = {}
    for path in paths:
        if any(p.search(path) for p in INERT):
            continue
        owner = next((m for m, d in by_depth if path.startswith(d + "/")), None)
        if owner is None:
            return list(everything), {t: f"{path} is outside every module" for t in everything}
        rest = path[len(mods[owner]) + 1:]
        if rest.startswith("src/androidTest/"):
            add(owner, f"its own test {path} changed")
        elif rest.startswith("src/test/"):
            continue
        else:
            changed_prod.setdefault(owner, path)

    reached = dict(changed_prod)
    frontier = list(changed_prod)
    while frontier:
        dep = frontier.pop()
        for m, deps in prod.items():
            if dep in deps and m not in reached:
                reached[m] = reached[dep]
                frontier.append(m)
    for m, path in reached.items():
        add(m, f"{path} changed{'' if m in changed_prod else ' in a module it depends on'}")
    for m, deps in test.items():
        hit = next((d for d in deps if d in reached), None)
        if hit:
            add(m, f"its tests drive {hit}, which {reached[hit]} changed")

    order = [t for t in everything if t in reasons]
    return order, reasons


def every_suite(root=ROOT):
    mods = modules(root)
    return [t for t in (suite_task(m, mods, root) for m in mods) if t]


def unsharded(root=ROOT):
    """Suites no shard runs. Each would pass every job by never starting."""
    covered = {t for tasks, _ in SHARDS.values() for t in tasks}
    return [t for t in every_suite(root) if t not in covered]


def main():
    args = sys.argv[1:]
    shard = args[args.index("--shard") + 1] if "--shard" in args else None
    if "--all" in args:
        tasks = every_suite()
        reasons = {t: "every suite was asked for" for t in tasks}
    else:
        tasks, reasons = select(sys.stdin.read().splitlines())

    extra = ""
    if shard is not None:
        missing = unsharded()
        if missing:
            sys.exit(f"No shard in affected_suites.py runs {' '.join(missing)}. Add it to SHARDS.")
        if shard not in SHARDS:
            sys.exit(f"Unknown shard {shard}. affected_suites.py defines {' '.join(SHARDS)}.")
        own, extra = SHARDS[shard]
        tasks = [t for t in tasks if t in own]

    for t in tasks:
        print(f"{t}: {reasons[t]}", file=sys.stderr)
    if not tasks:
        print("No instrumented suite this job runs can be affected by this change.", file=sys.stderr)
    line = " ".join(tasks)
    if tasks and extra:
        line += " " + extra
    print(line)
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            f.write(f"tasks={line}\n")
            f.write(f"smoke={'true' if any('Minified' in t for t in tasks) else 'false'}\n")


if __name__ == "__main__":
    main()
