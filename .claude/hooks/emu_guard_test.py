#!/usr/bin/env python3
"""Tests for emu_guard.py. A guard that passes a device command lets one
session's install land in another's run, and one that refuses an ordinary
command blocks every session, so each direction has cases here."""
import json
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from emu_guard import refusal  # noqa: E402


class Refuses(unittest.TestCase):
    def test_device_commands(self):
        for command in [
            "./gradlew connectedDebugAndroidTest",
            "./gradlew :feature:capture:connectedDebugAndroidTest --continue",
            "./gradlew :smoke:connectedMinifiedAndroidTest",
            "./gradlew :app:installDebug",
            "gradle uninstallAll",
            "adb install app.apk",
            "adb shell wm size 320x640",
            "adb -s emulator-5554 shell am instrument -w x",
            "adb -e logcat -c",
            "/home/ray/Android/Sdk/platform-tools/adb shell pm clear x",
            "cd app && ./gradlew connectedDebugAndroidTest",
            "ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest",
            "env FOO=1 adb shell input tap 1 1",
            "timeout 60 adb shell dumpsys",
            "echo hi; adb reboot",
            "true || adb shell pm grant x y",
            "bash -c 'adb shell wm density 160'",
            "(adb shell settings put global x 0)",
            ".claude/bin/emu -- true && adb install x.apk",
            "cat <<'EOF' > x\nnot a command\nEOF\nadb install x.apk",
            "cd app\n./gradlew connectedDebugAndroidTest",
            "./gradlew \\\n  :app:connectedDebugAndroidTest",
        ]:
            with self.subTest(command=command):
                self.assertIsNotNone(refusal(command))


class Allows(unittest.TestCase):
    def test_ordinary_and_leased_commands(self):
        for command in [
            "./gradlew test lint :app:assembleDebug",
            "./gradlew :core:parse:test --tests '*Pack*'",
            ".claude/bin/emu -- ./gradlew connectedDebugAndroidTest",
            ".claude/bin/emu --ci-size -- ./gradlew :app:connectedDebugAndroidTest",
            "emu -- adb shell wm size",
            "adb devices -l",
            "adb -s emulator-5554 get-state",
            "adb version",
            "grep -rn 'adb shell' docs/",
            "git commit -m 'run adb install under the lease'",
            "echo connectedDebugAndroidTest",
            "cat <<'EOF' > x\nadb install y\nEOF",
            "python3 - <<'EOF'\ns = 'CI: `adb shell wm size 320x640 && adb shell wm density 160`'\nEOF",
            "cat <<-END\n\tx; adb install y\n\tEND",
        ]:
            with self.subTest(command=command):
                self.assertIsNone(refusal(command))


class Hook(unittest.TestCase):
    def run_hook(self, command, tool="Bash"):
        event = {"tool_name": tool, "tool_input": {"command": command}}
        done = subprocess.run(
            [sys.executable, str(HERE / "emu_guard.py")],
            input=json.dumps(event), capture_output=True, text=True,
        )
        return done.returncode, done.stderr

    def test_refusal_exits_2_and_names_the_lease(self):
        code, err = self.run_hook("./gradlew connectedDebugAndroidTest")
        self.assertEqual(code, 2)
        self.assertIn(".claude/bin/emu", err)

    def test_allowed_exits_0_silently(self):
        self.assertEqual(self.run_hook("./gradlew test"), (0, ""))

    def test_other_tools_pass(self):
        self.assertEqual(self.run_hook("adb install x", tool="Read"), (0, ""))

    def test_unparseable_is_let_through(self):
        self.assertEqual(self.run_hook("echo 'unbalanced; adb install x"), (0, ""))


if __name__ == "__main__":
    unittest.main()
