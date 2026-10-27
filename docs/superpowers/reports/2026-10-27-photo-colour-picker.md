# Photo colour picker verification — 2026-10-27

Agent: Codex. Base: `14d3890`. Branch: `codex/colour-picker-design`. [PR #47](https://github.com/byeonggonlee/Latent/pull/47).

The owner approved the design and implementation. This branch implements photo hue picking and optional fixed
swatches; it does not change the saved edit format, Room schema or backup format.

## Local evidence

- `ANDROID_HOME=/Users/byeonggon/Library/Android/sdk ./gradlew testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest`: successful. JVM results: 804 tests, zero failures/errors, four skipped; Lint: zero errors (14 existing warnings in each variant).
- `git diff --exit-code -- app/schemas` and `git diff --check`: clean.
- New coverage: six sampler/prefix-renderer tests, thirteen picker flow tests, three geometry tests and three panel tests.
  Existing editor, rendering, settings and backup tests also pass.
- The flow tests exercise real Room/application/editor state, touch-down and drag, keyboard and accessibility
  actions, one-step Undo, no-op Apply, Cancel/Back, achromatic rejection, stale close/reopen work, EXIF rotation,
  whole-photo framing, configuration recreation and device-local preference preservation.
- A new instrumentation case compares CPU/GPU output after applying a picked hue following an earlier local mask.
  It compiles; it has **not run on a device**.

## Synthetic UI captures

Robolectric Compose captures using generated blue/gray patches, not photographs or device screenshots. Visually
checked crosshair/magnifier placement and readable, reachable action labels. These do not prove phone ergonomics.

![Portrait picker](../../screenshots/colour-picker-portrait.png)

![Landscape picker at 200% text](../../screenshots/colour-picker-landscape-200.png)

## Limits and merge gate

The Galaxy S24 Ultra was absent from `adb devices -l`. No device was installed, cleared or uninstalled for this
feature. Actual GPU parity, TalkBack, finger occlusion, responsiveness, one-hand use, lens correction and real-photo
judgement remain on [DEVICE_CHECKS.md](../../DEVICE_CHECKS.md).

Preparation IOException/OOM handling was inspected, not fault-injected; stale publication is tested at the real
close/reopen boundary. These limits must not be described as a successful on-device check.

## Fresh review and fixes

A fresh read-only reviewer was explicitly dispatched as `gpt-6-astra` (harness selection); it reviewed the full
`14d3890..e026628` diff, surrounding code and both screenshots. The report self-identified as Codex/GPT-6 with
exact variant/effort unverified. It found two Important accessibility issues, no Critical or Minor issues:

- Canvas arrows trapped D-pad focus. Enter/centre now transfers focus to Apply (or Cancel without a valid sample),
  on key-up so it cannot accidentally apply; arrows on buttons navigate normally. A D-pad-only regression failed
  before the fix and passes after it, including Apply, Undo and no-candidate Cancel.
- The swatch menu lacked checked semantics. Its single menu action now exposes checkbox role and toggleable state;
  both state assertions failed before the fix and pass afterward.

The added keyboard instruction also exposed the candidate value falling below the visible scroll area at 200%
landscape. Its visibility regression failed before moving the value outside the scrolling hint and passes after.
Final full-suite verification includes these fixes; no second reviewer pass is claimed.

The reviewer set aside unavailable device checks and forced failure injection. Both remain explicit limits above,
with the risk that hardware-specific alignment/GPU/accessibility or rare recovery faults can remain undetected.
No Minor findings were deferred.

A fresh whole-branch Codex review is supplemental. A qualified cross-family reviewer must cover the full colour,
state/cancellation, renderer and UI diff before merge, per AGENTS.md. No merge is authorized by this report.
