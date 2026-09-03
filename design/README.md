# Design

Static mockups for Pinged, Receipt direction. Approved design:
[`../docs/superpowers/specs/2026-09-02-pinged-design.md`](../docs/superpowers/specs/2026-09-02-pinged-design.md)

## What is here

`*.dc.html` — one file per artboard, each a self-contained 390x844 mockup.
These are the source of truth; the pages below are generated from them.

| Group | Files |
|---|---|
| First run | `Onboarding` `Battery` `Sources` `Empty` |
| Every day | `Main` `Inbox` `Detail` `Cash` |
| Looking back | `Charts` `Search` |
| When it breaks | `Stopped` `Settings` |
| Sheets and lists | `Chooser` `Category` `Months` `Export` `Wipe` `Teach` `PackImport` `Rules` `Unread` |
| Rejected directions | `Quiet` `Warung` `Dark` |

`canvas.json` — artboard layout, two pages. Only consumed by the Claude
Design canvas editor, which needs Node or Bun to assemble and was not
available on the machine these were drawn on. Kept current so the canvas can
be seeded later without redrawing anything.

`screen-set.html`, `direction-study.html` — generated review pages,
published as Artifacts. Do not hand-edit; they are overwritten.

## Rebuilding the pages

```
python3 build-screen-set.py
python3 build-direction-study.py
```

Both read the `.dc.html` files in this directory, inline each artboard's
markup, and overwrite their output page. Captions, the design-system tables
and the launcher icon geometry all live in the build scripts.

## Previewing

```
python3 -m http.server 4321 --directory .
```

Then open `http://localhost:4321/screen-set.html`.

## Scenario

Every populated mockup is pinned to Wednesday 30 September 2026, month end:
RM2,847.30 across 112 captured transactions, RM100.00 of transfers excluded,
four items awaiting review. Category bars sum to the hero total. Keep new
mockups consistent with those figures.
