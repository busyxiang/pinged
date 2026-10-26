#!/usr/bin/env python3
"""Name the tests that failed, where a CI run's reader looks first.

A failed instrumented suite logs only "There were failing tests. See the
report at: file:///...", and the report is inside the run's artifact, so
finding which test failed meant downloading and unpacking it. This reads the
JUnit XML both kinds of suite write and puts each failure in three places:

- the job summary, a table and each stack trace, on the run's own page;
- an `::error` annotation, which the run page and the PR's checks list, and
  which lands on the test's own line in the PR's diff when the stack names
  a frame in the test class;
- the step's log, for `gh run view --log-failed`.

Usage, from the repository root after a Gradle run, whether or not it passed:

    python3 .github/scripts/test_failures.py [ROOT]

It always exits 0. It reports a failure, it does not decide one: the Gradle
step has already failed the job, and a run that broke before any test ran
leaves no XML, which is reported as such rather than as a second error.
"""

import html
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

# Gradle's JVM tests, then AGP's connected tests.
RESULT_DIRS = ("build/test-results", "build/outputs/androidTest-results")

# GitHub shows ten error annotations per step and fifty per job, so more
# would be dropped; the summary keeps a bound of its own under its 1 MiB.
MAX_ANNOTATIONS = 10
MAX_DETAILED = 50
MAX_TRACE_LINES = 30

FRAME = re.compile(r"at ([A-Za-z0-9_.$]+)\.[^.(]+\(([^:()]+):([0-9]+)\)")

# The runner's own frames under the test method: reflection, JUnit's runner,
# Gradle's executor, AndroidX's. A JVM failure carried ~50 of them beneath
# the five that named the assertion and the test's line.
PLUMBING = re.compile(
    r"^\s*at ([A-Za-z0-9_.]+/)?(jdk\.internal\.reflect|java\.lang\.reflect|sun\.reflect"
    r"|org\.junit\.(runners?|internal)|org\.gradle|worker\.org\.gradle"
    r"|androidx\.test\.(internal|runner)|jdk\.proxy[0-9]*|com\.sun\.proxy)\."
)


@dataclass(frozen=True)
class Failure:
    module: str  # ":feature:ledger"
    where: str  # "testDebugUnitTest", "connected/debug"
    classname: str
    name: str
    message: str  # the first line
    trace: str
    source: str | None  # repo-relative path of the test's own file
    line: int | None

    @property
    def test(self):
        return f"{self.classname.rsplit('.', 1)[-1]}.{self.name}"


def result_files(root):
    for dirpath, dirnames, filenames in os.walk(root):
        # Never a build's own output, and a big tree to walk.
        dirnames[:] = [d for d in dirnames if d not in (".git", ".gradle", "node_modules")]
        rel = Path(dirpath).relative_to(root).as_posix()
        if any(f"/{d}/" in f"/{rel}/" for d in RESULT_DIRS):
            for f in sorted(filenames):
                if f.endswith(".xml"):
                    yield Path(dirpath) / f


def split_path(root, xml):
    """`feature/ledger/build/outputs/androidTest-results/connected/debug/x.xml`
    -> (":feature:ledger", "connected/debug")."""
    rel = xml.relative_to(root).as_posix()
    for d in RESULT_DIRS:
        head, sep, tail = rel.partition(f"{d}/")
        if sep:
            module = ":" + head.rstrip("/").replace("/", ":") if head else ":"
            return module, tail.rpartition("/")[0]
    raise ValueError(rel)


def locate(root, module, classname, trace):
    """The test's own file and line, from the first stack frame in its class.

    The file name comes from the frame rather than the class name, since a
    Kotlin file can hold a class of another name. The directory is the
    package's, under any of the module's source sets.
    """
    for m in FRAME.finditer(trace):
        cls, filename, line = m.group(1), m.group(2), int(m.group(3))
        if cls.split("$", 1)[0] != classname:
            continue
        package = classname.rpartition(".")[0].replace(".", "/")
        module_dir = root / module.strip(":").replace(":", "/")
        for candidate in sorted(module_dir.glob(f"src/*/*/{package}/{filename}")):
            return candidate.relative_to(root).as_posix(), line
        return None, None
    return None, None


def read(root):
    """Every failed test, the number of tests run per module, and the files
    that would not parse -- a runner killed mid-write leaves one."""
    failures, ran, unreadable, seen = [], {}, [], set()
    for xml in result_files(root):
        module, where = split_path(root, xml)
        try:
            tree = ET.parse(xml)
        except ET.ParseError as e:
            unreadable.append((xml.relative_to(root).as_posix(), str(e)))
            continue
        for case in tree.getroot().iter("testcase"):
            ran[module] = ran.get(module, 0) + 1
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                trace = (problem.text or "").strip()
                message = problem.get("message") or trace or problem.tag
                classname, name = case.get("classname", ""), case.get("name", "")
                key = (module, classname, name, trace)
                if key in seen:
                    continue
                seen.add(key)
                source, line = locate(root, module, classname, trace)
                failures.append(Failure(
                    module, where, classname, name,
                    message.strip().splitlines()[0] if message.strip() else problem.tag,
                    trace, source, line,
                ))
    return failures, ran, unreadable


def escape_data(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(s):
    return escape_data(s).replace(":", "%3A").replace(",", "%2C")


def annotation(f):
    props = [f"title={escape_property(f'{f.test} failed in {f.module}')}"]
    if f.source:
        props[:0] = [f"file={escape_property(f.source)}", f"line={f.line}"]
    return f"::error {','.join(props)}::{escape_data(f.message)}"


def truncated(trace):
    lines, plumbing = [], 0
    for line in trace.splitlines() + [""]:
        if PLUMBING.match(line):
            plumbing += 1
            continue
        if plumbing:
            lines.append(f"\t... {plumbing} runner frames")
            plumbing = 0
        lines.append(line)
    lines.pop()
    if len(lines) <= MAX_TRACE_LINES:
        return "\n".join(lines)
    return "\n".join(lines[:MAX_TRACE_LINES] + [f"... {len(lines) - MAX_TRACE_LINES} more lines"])


def cell(s):
    return html.escape(s, quote=False).replace("|", "\\|")


def summary(failures, ran, unreadable):
    out = []
    if not failures:
        out.append("### No test failed\n")
        out.append(
            "The reports name no failed test, so this job failed somewhere else: "
            "compilation, lint, or a step after the tests. Its log says where.\n"
            if ran else
            "No test reports were written, so the run stopped before any test did. "
            "The log of the step that failed says why.\n"
        )
    else:
        total = sum(ran.values())
        out.append(f"### {len(failures)} failed test{'s' * (len(failures) != 1)} of {total} run\n")
        out.append("| Module | Test | Failure |\n|---|---|---|")
        for f in failures:
            out.append(f"| `{f.module}` | `{cell(f.test)}` | {cell(f.message)} |")
        out.append("")
        for f in failures[:MAX_DETAILED]:
            place = f" at <code>{html.escape(f.source)}:{f.line}</code>" if f.source else ""
            out.append(f"<details><summary><code>{html.escape(f.classname)}.{html.escape(f.name)}</code>"
                       f" ({html.escape(f.where)}){place}</summary>\n")
            out.append(f"```\n{truncated(f.trace).replace('```', '` ` `')}\n```\n</details>\n")
        if len(failures) > MAX_DETAILED:
            out.append(f"{len(failures) - MAX_DETAILED} more stack traces are in this step's log.\n")
    for path, error in unreadable:
        out.append(f"Could not read `{path}`: {html.escape(error)}\n")
    return "\n".join(out) + "\n"


def log(failures, ran, unreadable):
    out = []
    out.append(f"{len(failures)} of {sum(ran.values())} tests failed")
    for module in sorted(ran):
        failed = sum(1 for f in failures if f.module == module)
        if failed:
            out.append(f"{module}: {failed} of {ran[module]}")
    for f in failures:
        out.append("")
        out.append(f"FAILED {f.classname}.{f.name} ({f.module}, {f.where})")
        out.extend("    " + line for line in truncated(f.trace or f.message).splitlines())
    for path, error in unreadable:
        out.append(f"Could not read {path}: {error}")
    if not ran:
        out.append("No test reports were written.")
    return "\n".join(out)


def main(argv):
    root = Path(argv[1] if len(argv) > 1 else ".").resolve()
    failures, ran, unreadable = read(root)
    print(log(failures, ran, unreadable))
    shown = failures if len(failures) <= MAX_ANNOTATIONS else failures[:MAX_ANNOTATIONS - 1]
    for f in shown:
        print(annotation(f))
    if len(shown) < len(failures):
        print(f"::error title=More failed tests::{len(failures) - len(shown)} more failed "
              "tests are listed in the job summary.")
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(summary(failures, ran, unreadable))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
