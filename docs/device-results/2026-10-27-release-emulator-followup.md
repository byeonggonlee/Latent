# 0.1.0 release: Codex emulator follow-up, 2026-10-27

Agent: Codex (GPT-6-based agent; exact model variant and reasoning effort unverified).

The owner authorized the emulator while the S24 Ultra was unavailable. This is partial release evidence,
not a completion of the S24 checklist or an authorization to tag the release. No product code changed.

## Build and environment

- Tested application source: `d77a7f4` on `origin/main`, including merged PRs #48 and #49, excluding PR #50.
  Work branch: `codex/release-010-emulator`; the claim commit `23e9613` changes only the handoff board.
- Debug package `com.latentimage.latent.debug`, version 0.1.0 (code 1); app and instrumentation APKs built and
  installed with `adb install -r -t`. Existing app data was preserved. No uninstall, data clear or
  `connectedAndroidTest` was used.
- Existing `latent36` AVD, `emulator-5554`, Android 16/API 36, arm64, Pixel 8 profile, 1080×2400 at 420 dpi;
  started with `-gpu host -no-snapshot-save -no-audio`, without a data wipe.
- SurfaceFlinger: `Google (Apple), Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.0
  (4.1 Metal - 91.7)`. App heap limit 576 MB. This does not provide the ES 3.1 compute path required by the GPU tests.
- The S24 Ultra was not connected to ADB. The connected older Pixel XL was not used.

## Samples and procedure

Public samples already downloaded from rawsamples.ch were copied, unchanged, into the debug app's cache:
`cache/release-010/landscape.jpg` is `RAW_ONEPLUS_ONE-A0001.JPG` (2,878,794 bytes), and
`cache/release-010/sony-7m2.arw` is `RAW_SONY_ILCE-7M2.ARW` (25,001,984 bytes). The JPEG is a portrait photo
stored sideways (EXIF orientation 6); its temporary filename does not describe its orientation.
These files and screenshots containing them are not committed.

Tests used `adb -s emulator-5554 shell am instrument -w -r`, with runner
`com.latentimage.latent.debug.test/androidx.test.runner.AndroidJUnitRunner` and arguments:

- `jpegSample`: `/data/user/0/com.latentimage.latent.debug/cache/release-010/landscape.jpg`
- `arwSample`: `/data/user/0/com.latentimage.latent.debug/cache/release-010/sony-7m2.arw`
- `videoSample`: `/data/user/0/com.latentimage.latent.debug/cache/release-010/synthetic.mp4`

An initial attempt to copy from shared storage with `run-as` was denied by Android. The resulting missing-JPEG
failures were setup failures, superseded by transferring the host files through ADB stdin into `run-as` and
verifying their sizes before the runs below. The video was an emulator screen recording, not camera footage.

## Instrumentation results

The valid core batch ran 9 tests: **6 passed, 2 assumption skips, 1 failure** (29.771 s).

- `GpuToneOnDeviceTest`: both tests skipped because `Graphics.available` was false, including picker parity
  after an earlier mask. No GPU/CPU agreement was established.
- `GpuDetailOnDeviceTest`: failed its usable-graphics assertion in this ES 3.0 environment. This is an unmet
  environment prerequisite, not evidence of correct shaders and not a confirmed product regression.
- `SubjectMaskOnDeviceTest`: both tests passed on CPU; GPU delegate setup failed. CPU setup 34 ms, cold 3,081 ms,
  warm runs [2,447, 2,521, 2,534, 2,504, 2,265] ms (median 2,504 ms). Real-photo outline: 4,863 ms,
  input 780×1052, output 759×1024, coverage 24.0%. No accelerator parity was tested.
- `SkyMaskOnDeviceTest`: both tests passed on CPU; GPU delegate setup failed. CPU setup 61 ms, cold 596 ms,
  warm runs [531, 652, 697, 626, 553] ms (median 626 ms). Real-photo sky: 1,336 ms,
  input 780×1052, output 759×1024, coverage 29.2%. No accelerator parity was tested.
- `ThumbnailOnDeviceTest`: both tests passed, including PR #46's generated JPEG without an EXIF thumbnail.
  JPEG Coil at 420 px: median 128 ms; stored thumbnail creation 169 ms, reuse 0 ms, Coil from cache 13 ms.
  ARW Coil: 62 ms; stored thumbnail creation 112 ms, reuse 0 ms, Coil from cache 15 ms.
  Cache copies were not in MediaStore, so these results do not cover that route.

The edit/video batch ran 3 tests: **2 passed, 1 failure** (112.1 s).

- `VideoPlaybackOnDeviceTest`: passed, ready in 732 ms, no reported failure. The tiny synthetic recording
  reported a duration of 0 s. This exercises preparation only; it is not camera-video playback, sustained
  decoding, audio, seeking or rotation validation.
- `EditOnDeviceTest#editsARawFileWithinTheAppsMemory`: passed with public ILCE-7M2. Proxy 508 ms;
  full-size region 467 ms; reframed export with every tool in that test 37,608 ms. Preview slider median:
  167 ms for a few sliders, 2,046 ms for every tool (worst 2,150 ms). No out-of-memory failure occurred.
  This method has no ZV-E10 model assertion; it does not establish the owner's ZV-E10 regression result.
- `EditOnDeviceTest#editsWithTheRestOfTheToolsWithinTheAppsMemory`: JPEG save completed in 26,347 ms,
  then failed at line 189: `expected:<(4208, 3120)> but was:<(3120, 4208)>`.
  Source inspection explains the difference: `sizeOf` reads stored pixel dimensions, while
  `PhotoEnhancer.bakesOrientation` deliberately makes this EXIF-rotated photo upright when applying healing
  and local edits. The assertion does not account for this supported orientation normalization.
  This is a test expectation gap for the substitute fixture, not established image corruption. Queue a
  display-dimensions/EXIF-aware assertion for Claude Code; do not weaken the test just to get a green run.
  The failure stops the method before its later RAW glow/brush/healing export, which remains unchecked.
  JPEG preview median with those tools was 1,242 ms (worst 2,379 ms).

`RawOnDeviceTest` and `editsAJpegWithinTheAppsMemory` were not run with the substitute samples: their camera
assertions require the owner's ZV-E10 files, which were unavailable. Emulator timings are not phone targets.

## UI checks on the installed main build

At default font scale, light app theme, verified through screenshots and UIAutomator nodes:

- Library uses dark status/navigation icons; the viewer and editor use light icons over their dark workspace.
  Icons return to dark in the library.
- Public ILCE-7M2 opens in the viewer and finishes development in the editor, with enabled controls and a visible
  photo rather than an error. The developed image and camera preview show no obvious gross colour failure on
  this emulator capture; calibrated neutral-grey judgement on the S24 screen remains pending.
- A long press selects a photo; the bar exposes Share, Delete, Compare and More. More contains Rating, and its
  star/clear-rating menu opens. No rating was chosen. Editing was closed without saving; no photo was deleted.
- This was not a large-text or landscape UI pass on main. PR #50 had separate layout probes described below.

## PR #50 review

Inspected `28493073ee02ece042861376934a00def13bef1e` (all seven changed files), published a COMMENT review:
[LGTM after one P2 fix](https://github.com/byeonggonlee/Latent/pull/50#pullrequestreview-5664082226).
At 320×640 dp and 200% font, English, choosing a valid blue and then an achromatic sample makes the expanded
outside-scroll status crowd Cancel out of view. Keep actions reserved and make status fit/scroll without
truncating the invalid reason or retained hue. Default-font portrait and large-font landscape probes retained
stable picture/action positions. The disposable review fixture ran 23 tests: 22 passed; only the newly added
large-font portrait Cancel visibility probe failed. No review probe was committed or used to change production.

## Host checks and remaining release gates

`assembleDebug assembleDebugAndroidTest` passed. The required host command
`testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest` passed: JVM XML reports 817 tests,
0 failures, 0 errors, 4 skipped. Lint reported 0 errors and 14 warnings. `git diff --exit-code -- app/schemas`
passed. Host used the available JDK 25; CI uses JDK 21.

Still needed on S24: shader/CPU parity including the picker, detail GPU, subject/sky GPU cold/warm timing,
owner ZV-E10 decoding/editing, screen colour judgement, thumbnails and real camera video, and the phone UI pass.
The PR #50 P2 also awaits its author's fix and re-review. No release date, tag, merge or distribution decision
was made by this validation.
