#!/usr/bin/env python3
"""Tests for test_failures.py.

The two XML shapes here are the ones each tool writes: Gradle's JVM tests a
`<testsuite>` root and a `message` attribute, AGP's connected tests a
`<testsuites>` root and the stack trace alone, as in run 40337515615's
`reports-instrumented-ledger-1`.
"""
import contextlib
import dataclasses
import io
import os
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_failures  # noqa: E402
from test_failures import annotation, read, summary  # noqa: E402

GRADLE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="my.pinged.parse.AmountTest" tests="3" skipped="1" failures="1" errors="0">
  <testcase name="passes" classname="my.pinged.parse.AmountTest" time="0.01"/>
  <testcase name="skips" classname="my.pinged.parse.AmountTest" time="0.0"><skipped/></testcase>
  <testcase name="readsSen" classname="my.pinged.parse.AmountTest" time="0.02">
    <failure message="expected:&lt;150&gt; but was:&lt;15000&gt;" type="java.lang.AssertionError">java.lang.AssertionError: expected:&lt;150&gt; but was:&lt;15000&gt;
	at org.junit.Assert.fail(Assert.java:89)
	at my.pinged.parse.AmountTest$readsSen$1.invoke(AmountTest.kt:42)
	at my.pinged.parse.AmountTest.readsSen(AmountTest.kt:40)
</failure>
  </testcase>
</testsuite>
"""

AGP = """<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="2" failures="1" errors="0" skipped="0">
  <testsuite name="my.pinged.ledger.ChooserArtboardTest" tests="2" failures="1">
    <testcase name="drawn" classname="my.pinged.ledger.ChooserArtboardTest" time="5.8" />
    <testcase name="anUnchosenTileIsOutlinedInBorder" classname="my.pinged.ledger.ChooserArtboardTest" time="5.6">
      <failure>java.lang.AssertionError: An unchosen tile's left edge is not Border
	at org.junit.Assert.assertTrue(Assert.java:42)
	at my.pinged.ledger.Pixels.edge(Pixels.kt:9)
</failure>
    </testcase>
  </testsuite>
</testsuites>
"""


class TestFailures(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text):
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_a_jvm_failure_is_named_with_its_message_and_line(self):
        self.write("core/parse/build/test-results/testDebugUnitTest/TEST-x.xml", GRADLE)
        self.write("core/parse/src/test/kotlin/my/pinged/parse/AmountTest.kt", "")
        failures, ran, _ = read(self.root)
        self.assertEqual({":core:parse": 3}, ran)
        [f] = failures
        self.assertEqual("AmountTest.readsSen", f.test)
        self.assertEqual(":core:parse", f.module)
        self.assertEqual("testDebugUnitTest", f.where)
        self.assertEqual("expected:<150> but was:<15000>", f.message)
        # The first frame in the class, a lambda's, not the method's own.
        self.assertEqual(("core/parse/src/test/kotlin/my/pinged/parse/AmountTest.kt", 42),
                         (f.source, f.line))

    def test_an_instrumented_failure_takes_its_message_from_the_trace(self):
        self.write("feature/ledger/build/outputs/androidTest-results/connected/debug/TEST-a.xml", AGP)
        [f] = read(self.root)[0]
        self.assertEqual("ChooserArtboardTest.anUnchosenTileIsOutlinedInBorder", f.test)
        self.assertEqual("connected/debug", f.where)
        self.assertEqual("java.lang.AssertionError: An unchosen tile's left edge is not Border", f.message)
        # No frame in the test's own class, so no place to annotate.
        self.assertEqual((None, None), (f.source, f.line))

    def test_an_error_is_a_failure(self):
        self.write("app/build/test-results/testDebugUnitTest/TEST-x.xml",
                   GRADLE.replace("<failure", "<error").replace("</failure>", "</error>"))
        self.assertEqual(["AmountTest.readsSen"], [f.test for f in read(self.root)[0]])

    def test_xml_outside_the_result_directories_is_not_read(self):
        self.write("app/build/reports/lint-results-debug.xml", GRADLE)
        self.write("app/build/test-results-old/TEST-x.xml", GRADLE)
        self.assertEqual(([], {}, []), read(self.root))

    def test_a_truncated_report_is_named_and_the_rest_still_read(self):
        self.write("core/data/build/outputs/androidTest-results/connected/debug/TEST-a.xml", AGP[:300])
        self.write("feature/ledger/build/outputs/androidTest-results/connected/debug/TEST-a.xml", AGP)
        failures, _, unreadable = read(self.root)
        self.assertEqual(1, len(failures))
        self.assertEqual(["core/data/build/outputs/androidTest-results/connected/debug/TEST-a.xml"],
                         [path for path, _ in unreadable])
        self.assertIn("Could not read `core/data/", summary(failures, {":x": 1}, unreadable))

    def test_the_summary_says_why_it_names_no_test(self):
        self.assertIn("No test reports were written", summary([], {}, []))
        self.assertIn("failed somewhere else", summary([], {":app": 4}, []))

    def test_the_summary_table_survives_a_pipe_in_the_message(self):
        self.write("core/parse/build/test-results/testDebugUnitTest/TEST-x.xml",
                   GRADLE.replace("expected:&lt;150&gt;", "a | b"))
        text = summary(*read(self.root))
        self.assertIn("### 1 failed test of 3 run", text)
        self.assertIn("| a \\| b but was:&lt;15000&gt; |", text)

    def test_runner_frames_collapse_and_the_cause_survives(self):
        trace = "\n".join([
            "java.lang.IllegalStateException: closed",
            "\tat my.pinged.data.Dao.read(Dao.kt:3)",
            "\tat my.pinged.data.DaoTest.reads(DaoTest.kt:7)",
            "\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(X.java:1)",
            "\tat org.junit.runners.ParentRunner.run(ParentRunner.java:413)",
            "\tat org.gradle.api.internal.tasks.testing.junit.JUnitTestExecutor.accept(J.java:84)",
            "Caused by: java.io.IOException: gone",
            "\tat my.pinged.data.Db.open(Db.kt:9)",
            "\tat androidx.test.internal.runner.TestExecutor.execute(T.java:1)",
        ])
        self.assertEqual("\n".join([
            "java.lang.IllegalStateException: closed",
            "\tat my.pinged.data.Dao.read(Dao.kt:3)",
            "\tat my.pinged.data.DaoTest.reads(DaoTest.kt:7)",
            "\t... 3 runner frames",
            "Caused by: java.io.IOException: gone",
            "\tat my.pinged.data.Db.open(Db.kt:9)",
            "\t... 1 runner frames",
        ]), test_failures.truncated(trace))

    def test_an_annotation_cannot_be_split_by_its_message(self):
        self.write("core/parse/build/test-results/testDebugUnitTest/TEST-x.xml", GRADLE)
        self.write("core/parse/src/test/kotlin/my/pinged/parse/AmountTest.kt", "")
        [f] = read(self.root)[0]
        f = dataclasses.replace(f, message="100% wrong\n::error::forged")
        line = annotation(f)
        self.assertEqual(1, len(line.splitlines()))
        self.assertTrue(line.startswith(
            "::error file=core/parse/src/test/kotlin/my/pinged/parse/AmountTest.kt,line=42,"
            "title=AmountTest.readsSen failed in %3Acore%3Aparse::"))
        self.assertTrue(line.endswith("::100%25 wrong%0A::error::forged"))

    def test_annotations_stop_at_githubs_limit_and_say_how_many_more(self):
        cases = "".join(
            f'<testcase name="t{i}" classname="C"><failure>boom {i}</failure></testcase>'
            for i in range(12))
        self.write("app/build/test-results/testDebugUnitTest/TEST-x.xml",
                   f"<testsuite>{cases}</testsuite>")
        out = io.StringIO()
        env = {k: v for k, v in os.environ.items() if k != "GITHUB_STEP_SUMMARY"}
        with contextlib.redirect_stdout(out), unittest.mock.patch.dict(os.environ, env, clear=True):
            self.assertEqual(0, test_failures.main(["x", str(self.root)]))
        errors = [line for line in out.getvalue().splitlines() if line.startswith("::error")]
        self.assertEqual(test_failures.MAX_ANNOTATIONS, len(errors))
        self.assertIn("3 more failed tests", errors[-1])
        # Every one is still in the log.
        self.assertEqual(12, out.getvalue().count("\nFAILED C.t"))


if __name__ == "__main__":
    unittest.main()
