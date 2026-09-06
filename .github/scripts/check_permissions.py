#!/usr/bin/env python3
"""Pin the shipped manifest's permission and export surface.

The manifest this repository writes declares two permissions. The manifest
that ships declares more, because every library merges its own in, and a
dependency bump can add one without a single line changing here. This app's
central promise is about what it cannot do, so the set is asserted rather
than reviewed.

It is an allow-list on purpose. A deny-list would have to name the
permissions this app must never hold, and spec 11.4 keeps those strings out
of the tree precisely so that grepping for one stays a meaningful check.
An allow-list catches them anyway, by catching everything.

"Everything" is meant literally, so this sweeps every element that can carry
a permission rather than the two element types that happened to matter when
it was written: a <receiver> gated on DUMP and a <provider> gated on a
readPermission are both ways for a dependency to widen the app's surface.

It reads the *release* manifest, and it used to read the debug one. Those
are not the same document, which is not a theory: at the time this changed,
debug carried an exported `androidx.activity.ComponentActivity` that release
did not. A guard whose subject is "the shipped permission surface" and whose
input is a variant that never ships accepts whatever the release merge does
differently, in either direction.

`android:exported` is checked beside the permissions because it is the same
question asked the other way round. A permission says who may reach a
component; `exported` says whether anything outside the app may reach it at
all, and an exported component with no permission is reachable by every app
on the phone. A dependency adding one widens the surface exactly as a
`<uses-permission>` does, and nothing here would have said so.

Run it from the repository root after a build that produced both manifests:

    ./gradlew :app:assembleDebug :app:processReleaseMainManifest \
        && python3 .github/scripts/check_permissions.py
"""

import pathlib
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Elements that name a permission the app *holds*.
HOLDS = ("uses-permission", "uses-permission-sdk-23")
# Attributes by which an element *requires* a permission of its caller.
REQUIRES = ("permission", "readPermission", "writePermission")

# Every permission the shipped app is allowed to hold or require, keyed by
# where it appears. A bare name is one this app takes; anything of the form
# `tag[attribute]` is a grant the app demands of a caller, not a capability
# it holds.
EXPECTED = {
    # Held.
    ("uses-permission", "android.permission.ACCESS_NETWORK_STATE"),
    ("uses-permission", "android.permission.FOREGROUND_SERVICE"),
    ("uses-permission", "android.permission.POST_NOTIFICATIONS"),
    ("uses-permission", "android.permission.RECEIVE_BOOT_COMPLETED"),
    ("uses-permission", "android.permission.WAKE_LOCK"),
    # WorkManager's own, namespaced to the application id, declared and then
    # held by the same manifest.
    ("uses-permission", "my.pinged.tracker.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
    ("permission", "my.pinged.tracker.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
    # Required of callers. The system holds these; the app does not.
    ("service[permission]", "android.permission.BIND_JOB_SERVICE"),
    ("service[permission]", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
    # androidx work and profileinstaller diagnostics receivers, which only a
    # shell holding DUMP can trigger. Named in spec 11.4.
    ("receiver[permission]", "android.permission.DUMP"),
}

# Every component the shipped app opens to other apps, and why each is
# allowed to be open. Three of the four are protected by a permission in
# EXPECTED above; the launcher activity is the one that is genuinely open,
# because that is what a launcher activity is.
EXPECTED_EXPORTS = {
    # The launcher entry point. Opening the app is the whole of what an
    # outside caller can do with it: it takes no extras, and it reads nothing
    # from the intent that started it.
    ("activity", "my.pinged.MainActivity"),
    # Exported because the platform's JobScheduler binds it, and closed to
    # everything else by BIND_JOB_SERVICE, which only the system holds.
    ("service", "androidx.work.impl.background.systemjob.SystemJobService"),
    # Both reachable only by a shell holding DUMP -- see the permission
    # entries above, and spec 11.4.
    ("receiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver"),
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"),
}


def find_merged_manifest(root):
    """The release variant's *main* merged manifest, and only that one.

    The glob is anchored on `release` rather than left open: `debug` and
    `debugAndroidTest` also live under merged_manifest/, and a wider pattern
    picks between them by lexicographic accident -- which is how this came to
    be reading a variant that does not ship.
    """
    pattern = "app/build/intermediates/merged_manifest/release/*/AndroidManifest.xml"
    found = sorted(root.glob(pattern))
    if not found:
        sys.exit(
            f"No merged manifest at {pattern}. Build one first:\n"
            "    ./gradlew :app:processReleaseMainManifest"
        )
    if len(found) > 1:
        sys.exit(
            "Expected one merged manifest, found several. AGP's layout has "
            "changed and this check needs updating:\n  "
            + "\n  ".join(str(p) for p in found)
        )
    return found[0]


def surface(manifest):
    """Every (context, permission) pair the manifest mentions."""
    found = set()
    for element in ET.parse(manifest).iter():
        if element.tag in HOLDS or element.tag == "permission":
            found.add((element.tag, element.get(ANDROID + "name")))
        for attribute in REQUIRES:
            value = element.get(ANDROID + attribute)
            if value:
                found.add((f"{element.tag}[{attribute}]", value))
    return found


def exports(manifest):
    """Every (element, name) the manifest opens to other applications.

    `android:exported="true"` only. A component that omits the attribute is
    not exported on any SDK this app supports -- minSdk is 27 and the
    attribute is mandatory from 31 -- and lint already refuses the omission.
    """
    return {
        (element.tag, element.get(ANDROID + "name"))
        for element in ET.parse(manifest).iter()
        if element.get(ANDROID + "exported") == "true"
    }


def main():
    root = pathlib.Path(__file__).resolve().parents[2]
    manifest = find_merged_manifest(root)
    found = surface(manifest)

    exported = exports(manifest)

    print(f"Merged manifest: {manifest.relative_to(root)}")
    print(f"  permission references: {len(found)}")
    print(f"  exported components:   {len(exported)}")

    for context, name in sorted(found - EXPECTED):
        print(f"::error::Merged manifest gained a permission: {name} (as {context})")
    for context, name in sorted(EXPECTED - found):
        print(f"::error::Merged manifest lost an expected permission: {name} (as {context})")
    for tag, name in sorted(exported - EXPECTED_EXPORTS):
        print(f"::error::Merged manifest exports a new component: {name} (a <{tag}>)")
    for tag, name in sorted(EXPECTED_EXPORTS - exported):
        print(f"::error::Merged manifest no longer exports: {name} (a <{tag}>)")

    if found != EXPECTED or exported != EXPECTED_EXPORTS:
        print(
            "\nIf the change is intended, update EXPECTED or EXPECTED_EXPORTS in\n"
            "this file and say in the commit message which dependency added it\n"
            "and why the app may hold it, require it, or stand open to callers."
        )
        return 1
    print("Permission and export surface unchanged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
