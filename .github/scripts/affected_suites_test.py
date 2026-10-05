#!/usr/bin/env python3
"""Tests for affected_suites.py, against this repository's own module graph.

A suite selected wrongly is skipped silently: the run is green and shorter,
which is what success also looks like. So each rule has a case here, and the
cases name suites, not counts.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from affected_suites import select  # noqa: E402

APP = ":app:connectedDebugAndroidTest"
DATA = ":core:data:connectedDebugAndroidTest"
CAPTURE = ":feature:capture:connectedDebugAndroidTest"
LEDGER = ":feature:ledger:connectedDebugAndroidTest"
SMOKE = ":smoke:connectedMinifiedAndroidTest"
ALL = {APP, DATA, CAPTURE, LEDGER, SMOKE}


def suites(*paths):
    return set(select(list(paths))[0])


class AffectedSuites(unittest.TestCase):
    def test_no_paths_runs_everything(self):
        # What a diff that failed to compute looks like. #10's was too large
        # for `gh pr diff` and came back empty.
        self.assertEqual(ALL, suites())

    def test_docs_and_ci_run_nothing(self):
        self.assertEqual(set(), suites("docs/notes.md", "CLAUDE.md", ".github/workflows/ci.yml"))

    def test_a_root_build_file_runs_everything(self):
        self.assertEqual(ALL, suites("build.gradle.kts"))
        self.assertEqual(ALL, suites("gradle/libs.versions.toml"))

    def test_an_unknown_path_runs_everything(self):
        self.assertEqual(ALL, suites("tools/new-thing.sh"))

    def test_parse_reaches_every_suite(self):
        self.assertEqual(ALL, suites("core/parse/src/main/kotlin/my/pinged/parse/Merchant.kt"))

    def test_ledger_reaches_capture_through_its_tests_but_not_data(self):
        # :feature:capture's androidTest depends on :feature:ledger; nothing
        # in production does.
        self.assertEqual(
            {LEDGER, CAPTURE, APP, SMOKE},
            suites("feature/ledger/src/main/kotlin/my/pinged/ledger/home/LedgerViewModel.kt"),
        )

    def test_a_module_build_file_is_a_production_change(self):
        self.assertEqual({LEDGER, CAPTURE, APP, SMOKE}, suites("feature/ledger/build.gradle.kts"))

    def test_an_instrumented_test_runs_only_its_own_suite(self):
        self.assertEqual({DATA}, suites("core/data/src/androidTest/kotlin/my/pinged/data/LedgerFeedTest.kt"))

    def test_a_jvm_test_runs_no_suite(self):
        self.assertEqual(set(), suites("app/src/test/kotlin/my/pinged/SomeTest.kt"))

    def test_app_reaches_smoke(self):
        self.assertEqual({APP, SMOKE}, suites("app/src/main/kotlin/my/pinged/MainActivity.kt"))
        self.assertEqual({APP, SMOKE}, suites("app/build.gradle.kts"))

    def test_smoke_runs_alone(self):
        self.assertEqual({SMOKE}, suites("smoke/src/main/kotlin/my/pinged/smoke/FirstCaptureTest.kt"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
