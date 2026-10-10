# Which screenshot-testing tool fits this toolchain

Research for #71 (part of #61, blocking #70). Gathered 2026-10-10 from release
notes, READMEs and issue trackers at the tags named below. Nothing was added to
the build. No tool was run against this project. Every "works with" below comes
from someone else's report, not from a run here.

## Recommendation

**Roborazzi** (1.76.0), in `:feature:ledger`'s JVM tests, with Robolectric
4.16.1 in `GraphicsMode.NATIVE` at `sdk=36`. Goldens committed as plain PNGs
under the module's source tree, recorded on CI's Linux runner, and checked by
the `checks` job rather than the emulator job.

Reasons, strongest first:

1. **Paparazzi has no stable release for AGP 9.** Its last stable, 1.3.5, is
   from 2024-11-07. AGP 9 support arrived in `2.0.0-alpha05.1` (2026-09-28),
   which is still an alpha.
2. **Paparazzi would render at API 37 with an API 36 renderer.** It takes the
   render SDK from `testOptions.targetSdk`, falling back to `compileSdk` (37
   here), but ships layoutlib 16.2.3 (Android 16). Compose then takes API-37
   code paths that layoutlib does not implement (cashapp/paparazzi#2491, open,
   reproduced on AGP 9.4.1). Lowering `testOptions.targetSdk` to 36 would also
   lower the target SDK of `:feature:ledger`'s instrumented test APK, which
   `libs.versions.toml` says now runs at 37 on purpose. The other way out is a
   per-rule `Paparazzi(environment = detectEnvironment().copy(compileSdkVersion
   = 36, …))` override, as a collaborator suggested for an older SDK gap in
   cashapp/paparazzi#1288. That is unsupported glue, and every test has to
   carry it.
3. **Roborazzi adds almost nothing new to download or cache.** Robolectric's
   native graphics runtime, `nativeruntime-dist-compat:1.0.18` (159 MB), is
   already a runtime dependency of `robolectric:4.16.1`. It is already in this
   machine's Gradle cache. CI already caches the Robolectric SDK image.
   Paparazzi would add about 176 MB of layoutlib.
4. **It runs in the 1m22s `checks` job, not the 11m46s emulator job** (#13).

The font question (§8) does not decide between the two. Both render bundled
`res/font` files with real Skia. But neither can show that IBM Plex Mono was
the face actually used, unless the test includes a control render. See
[Fonts](#fonts-the-numerals-in-ibm-plex-mono).

## Compatibility with `gradle/libs.versions.toml`

Project: AGP 9.4.0, Kotlin 2.4.10, Compose BOM 2026.08.00 (Compose 1.12.0),
Robolectric 4.16.1 (highest SDK 36), compileSdk/targetSdk 37, Gradle 9.7.1,
JDK 25 on CI (`.github/actions/setup-android/action.yml`).

| | Roborazzi 1.76.0 | Paparazzi 2.0.0-alpha05.1 |
|---|---|---|
| Release status | Stable, about every 1–2 weeks (1.62.0 → 1.76.0 between May and Sept 2026) | Alpha. Last stable is 1.3.5 (Nov 2024) |
| AGP 9 | Plugin supports it since 1.56.0 ([release][rz-1.56]). Built against AGP 8.13.2 ([catalog][rz-cat]) | Built against AGP 9.0.0 ([catalog][pz-cat]). #2491 shows it running on AGP 9.4.1 |
| Kotlin | POM pins `kotlin-stdlib` 2.0.21 as a *minimum*, so the consumer's 2.4.10 wins ([catalog][rz-cat]) | Built with Kotlin 2.3.0. #2491 used compose plugin 2.4.20 |
| Compose | No Compose version is forced. Uses the project's `ui-test-junit4` | Built against Compose 1.11.2. Project is on 1.12.0. Paparazzi has broken on a Compose/SDK gap before (#1866, cited in #2491) |
| Renderer / SDK | Robolectric, whose highest SDK is 36. Already pinned at `sdk=36` in `:feature:capture` | layoutlib 16.2.3 (API 36), but it renders at compileSdk 37 by default (#2491). layoutlib 17.0.3 exists on Google Maven, but Paparazzi has not adopted it |
| JDK | Robolectric 4.16 needs JDK 21 for SDK 36 ([4.16 notes][rl-4.16]). CI has 25 | Needs Java 21+ since alpha04 ([changelog][pz-cl]). CI has 25 |
| Module type | Any module running Robolectric tests | Library modules. `:feature:ledger` is `com.android.library` |

Known Roborazzi issues to expect:

- **Release unit tests are off by default under AGP 9.** So
  `recordRoborazziRelease` does not exist until
  `android.onlyEnableUnitTestForTheTestedBuildType=false` is set
  (takahirom/roborazzi#777). Debug, the variant this would use, is not affected.
- **Gradle 9 race (takahirom/roborazzi#830).** It reports `NoSuchFileException`
  on the screenshot input directory, seen in multi-module KMP builds
  (`testAndroidHostTest`). This project is not KMP, and only one module would
  capture.
- **Robolectric 4.15 changed screenshot sizing.** It was fixed in 1.46.1
  ([release][rz-1.46.1]). Earlier versions are not an option.

## Fonts: the numerals in IBM Plex Mono

**Both tools draw real glyphs.** In Robolectric's `NATIVE` graphics mode the
font stack is the real one. In `LEGACY` mode, "all the font and typeface logic
… is fake and stubbed out" (Robolectric maintainer, robolectric/robolectric#9732).
Paparazzi uses layoutlib, the renderer behind Android Studio's preview.
Bundled `R.font.*` resources need `unitTests.isIncludeAndroidResources = true`
on the Robolectric side. `:feature:capture` already sets it, and
`:feature:ledger`, where the fonts live, would need it as well.

**Both have a history of failing on custom fonts.**

- Robolectric: "Could not load font" with an explicit `Font(resId)` on SDK 35
  with 4.14 (robolectric/robolectric#9732, closed, and a maintainer could not
  reproduce it). There is also a Typeface init crash under NATIVE
  (robolectric/robolectric#9039, open, no reproducer).
- Paparazzi: the same "Could not load font" error, or blank snapshots, with a
  custom `FontFamily` after a BOM bump (cashapp/paparazzi#1288, open).

Both are reports, not reproductions on this toolchain.

**The finding specific to this project: a width test cannot catch a silent
fallback.** Robolectric's native runtime bundles Android's system fonts
(`nativeruntime-dist-compat-1.0.18.jar!/fonts/`, 237 entries). Its
`fonts.xml` maps `monospace` to `DroidSansMono.ttf`. Digit advances measured
with fontTools from the shipped files:

| Font | digit advance, per 1000 em |
|---|---|
| `ibm_plex_mono_regular.ttf` (`res/font`) | 600.0 for every digit |
| `DroidSansMono.ttf` (system `monospace`) | 600.1 for every digit |
| `CutiveMono.ttf` (system `serif-monospace`) | 605.5 for every digit |

At any realistic text size, 600.0 and 600.1 lay out identically. So if
`Mono`'s family failed to load and fell back to the system monospace face:

- columns would still align
- every bounds or width assertion would still pass
- the screenshot would show the wrong face

That is the failure §8 asks about. **The falsifying test is a control render.**
Render the same amount string once in `Mono` and once in
`FontFamily.Monospace`, then assert the two images differ. Also commit the
`Mono` render as the golden. The same check is needed under Paparazzi, since
layoutlib has its own fallback fonts. Per CLAUDE.md, the test also needs a
revert check: point `Mono` at `FontFamily.Monospace` and watch the test fail.

## CI cost

Neither tool touches the emulator job. The numbers that exist:

- **This project:** `checks` 1m22s, instrumented job 11m46s on a warm cache
  (#13). The Robolectric SDK image (about 190 MiB) is already cached
  explicitly in `ci.yml`. `nativeruntime-dist-compat` comes through Gradle
  and is in the cache setup-gradle saves.
- **Roborazzi's own sample, not this project:**
  - 37.6–74.7 ms per Compose capture ([1.76.0][rz-1.76])
  - 96 ms per generated preview test ([1.75.0][rz-1.75])
  - comparing an identical 1080×2340 image takes 0.37 ms ([1.73.0][rz-1.73])
  - Robolectric startup per test JVM comes on top of all of these
- **Paparazzi:** no timing figures in its notes. Extra downloads, by
  Content-Length on dl.google.com:
  - `layoutlib-16.2.3.jar` 60 MB
  - `layoutlib-runtime-16.2.3-linux.jar` 81 MB
  - `layoutlib-resources-16.2.3.jar` 35 MB

For about a dozen chart and row goldens, a few seconds added to `checks` is
the likely size. That is an estimate. Measure it on the PR that adds the tool.

## Golden images in a public repo

| | Roborazzi | Paparazzi |
|---|---|---|
| Record | `recordRoborazziDebug`, or `testDebugUnitTest -Proborazzi.test.record=true` | `recordPaparazziDebug` |
| Verify | `verifyRoborazziDebug`, or `-Proborazzi.test.verify=true` | `verifyPaparazziDebug` |
| Default golden location | `build/outputs/roborazzi`, not in source control. Move it with `roborazzi { outputDir = "src/test/screenshots" }` | `src/test/snapshots`, in source control |
| Upstream advice | README's CI example keeps goldens as an Actions artifact from `main`, downloaded on PRs (`retention-days: 30`) | Recommends Git LFS for snapshots |

Notes:

- **Neither belongs in the `test` that CI runs.** Plain `./gradlew test` does
  not compare unless the verify property or task is used, so `ci.yml` would
  need `verifyRoborazziDebug` or `-Proborazzi.test.verify=true` in `checks`.
  Otherwise a drifted chart passes.
- **Commit the PNGs, without LFS.** GitHub Free's LFS quota is 10 GiB storage
  and 10 GiB bandwidth. Actions checkouts *and fork pulls* count against the
  owner ([GitHub docs][gh-lfs]). In a public repo, strangers' forks spend that
  quota. A few dozen small PNGs do not need LFS.
- **Do not use artifact goldens either.** They expire. Under the README's
  artifact flow, a PR's run then has nothing to verify against, and the golden
  is not reviewable in the PR diff.
- **Record on CI's Linux runner.** Roborazzi's FAQ says rendering is not
  guaranteed identical across macOS, Ubuntu and Windows, and advises recording
  and verifying in the same environment. The dev machine is Linux too, but a
  different distro and CPU. Whether its renders match ubuntu-latest
  byte-for-byte is **unmeasured**.
- **Fixtures must stay safe to publish.** CLAUDE.md treats the repo as public.
  A golden is a picture of whatever data the test feeds, so screenshot inputs
  must be constructed amounts and merchant names, never anything from a device
  export.

## Alternative not compared in depth

Google's Compose Preview Screenshot Testing (`com.android.compose.screenshot`
0.0.1-alpha16) is still alpha. Its page says the standalone plugin is
deprecated as of AGP 9.5.0-alpha03, in favour of AGP test suites
([developer.android.com][g-shot]). It is not a fit for 9.4.0 today.

## Uncertainties

- **No tool was run here.** IBM Plex Mono rendering under Robolectric 4.16.1
  NATIVE at SDK 36, with Compose 1.12.0, is unverified. So is the absence of
  #9732 / #9039 on this stack. The first PR must show a golden with visibly
  Plex digits, plus the control test above failing when reverted.
- Roborazzi's CI is built against AGP 8.13.2 and Robolectric 4.14.1. AGP 9.4
  and Robolectric 4.16.1 are consumer combinations its own build does not
  exercise.
- The CI time added is an estimate from upstream sample figures.
- Robolectric 4.17 (beta) adds an SDK 37 image. Moving to it later would move
  every golden.
- Paparazzi may become viable once it adopts layoutlib 17 (the stated fix for
  #2491) and leaves alpha. Re-check then if Roborazzi causes trouble.

## Sources

- Roborazzi README at 1.76.0: https://github.com/takahirom/roborazzi/blob/1.76.0/README.md
- Roborazzi catalog at 1.76.0: [rz-cat]
- Roborazzi releases: [rz-1.56], [rz-1.46.1], [rz-1.73], [rz-1.75], [rz-1.76]
- Roborazzi issues: takahirom/roborazzi#777, #830, #687, #784
- Paparazzi README and catalog at 2.0.0-alpha05.1: https://github.com/cashapp/paparazzi/blob/2.0.0-alpha05.1/README.md, [pz-cat]
- Paparazzi changelog: [pz-cl]
- Paparazzi issues: cashapp/paparazzi#2491, #1288, #2409
- Robolectric 4.16 release notes: [rl-4.16]. Robolectric issues: robolectric/robolectric#9732, #9039
- layoutlib versions: https://dl.google.com/dl/android/maven2/com/android/tools/layoutlib/group-index.xml
- GitHub LFS billing: [gh-lfs]
- Compose Preview Screenshot Testing: [g-shot]
- Measured locally: digit advances via fontTools on `feature/ledger/src/main/res/font/ibm_plex_mono_regular.ttf` and on `fonts/*.ttf` / `fonts/fonts.xml` inside `nativeruntime-dist-compat-1.0.18.jar`

[rz-cat]: https://github.com/takahirom/roborazzi/blob/1.76.0/gradle/libs.versions.toml
[rz-1.56]: https://github.com/takahirom/roborazzi/releases/tag/1.56.0
[rz-1.46.1]: https://github.com/takahirom/roborazzi/releases/tag/1.46.1
[rz-1.73]: https://github.com/takahirom/roborazzi/releases/tag/1.73.0
[rz-1.75]: https://github.com/takahirom/roborazzi/releases/tag/1.75.0
[rz-1.76]: https://github.com/takahirom/roborazzi/releases/tag/1.76.0
[pz-cat]: https://github.com/cashapp/paparazzi/blob/2.0.0-alpha05.1/gradle/libs.versions.toml
[pz-cl]: https://github.com/cashapp/paparazzi/blob/master/CHANGELOG.md
[rl-4.16]: https://github.com/robolectric/robolectric/releases/tag/robolectric-4.16
[gh-lfs]: https://docs.github.com/en/billing/concepts/product-billing/git-lfs
[g-shot]: https://developer.android.com/studio/preview/compose-screenshot-testing
