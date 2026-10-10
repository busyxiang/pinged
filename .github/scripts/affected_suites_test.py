#!/usr/bin/env python3
"""Tests for affected_suites.py, against this repository's own module graph.

A suite selected wrongly is skipped silently: the run is green and shorter,
which is what success also looks like. So each rule has a case here, and the
cases name suites, not counts.
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from affected_suites import SHARDS, select, unsharded  # noqa: E402

APP = ":app:connectedDebugAndroidTest"
DATA = ":core:data:connectedDebugAndroidTest"
CAPTURE = ":feature:capture:connectedDebugAndroidTest"
LEDGER = ":feature:ledger:connectedDebugAndroidTest"
CHARTS = ":feature:charts:connectedDebugAndroidTest"
SMOKE = ":smoke:connectedMinifiedAndroidTest"
ALL = {APP, DATA, CAPTURE, LEDGER, CHARTS, SMOKE}


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

    def test_the_theme_reaches_every_screen_but_not_data(self):
        # :core:ui has no suite of its own; its fonts and palette are drawn
        # by :feature:ledger's, :feature:charts' and :app's, and R8 bundles
        # them for :smoke.
        self.assertEqual(
            {LEDGER, CHARTS, CAPTURE, APP, SMOKE},
            suites("core/ui/src/main/kotlin/my/pinged/ui/theme/Color.kt"),
        )

    def test_charts_reaches_app_but_not_the_ledger(self):
        # :feature:charts never depends on :feature:ledger (#81), and nothing
        # but :app depends on it.
        self.assertEqual(
            {CHARTS, APP, SMOKE},
            suites("feature/charts/src/main/kotlin/my/pinged/charts/ChartsScreen.kt"),
        )

    def test_capture_reaches_charts(self):
        # Charts reads through `CaptureStorage.guarded`.
        self.assertIn(CHARTS, suites("feature/capture/src/main/kotlin/my/pinged/capture/CaptureStorage.kt"))

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


class Shards(unittest.TestCase):
    def test_every_suite_has_a_shard(self):
        self.assertEqual([], unsharded())

    def test_no_suite_runs_whole_in_two_shards(self):
        # Two halves of one suite are fine; the same suite unsplit in two jobs
        # is the device time this split exists to save.
        whole = [t for tasks, extra in SHARDS.values() if not extra for t in tasks]
        self.assertEqual(len(whole), len(set(whole)))

    def test_the_workflow_runs_every_shard(self):
        ci = (Path(__file__).resolve().parents[1] / "workflows" / "ci.yml").read_text()
        listed = re.search(r"^\s*shard: \[([^\]]*)\]", ci, re.M)
        self.assertIsNotNone(listed, "ci.yml has no `shard: [...]` matrix")
        self.assertEqual(set(SHARDS), {s.strip() for s in listed.group(1).split(",")})


if __name__ == "__main__":
    unittest.main(verbosity=2)
