# Handoff

The board the agents working on Latent share: Claude Code and Codex. It says who looks after what, who is
working on what right now, and what one has handed to the other. Read it at the start of a session. Change it
when you take, hand over or finish something. What the owner has to do stays in [`TODO.md`](TODO.md), the
features in [`ROADMAP.md`](ROADMAP.md), what only a phone can tell in [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md).
Started 2026-10-21.

## Who looks after what

| Area | Who | Paths |
|---|---|---|
| **Image decoders**: TIFF structure, Sony ARW, DNG, lossless JPEG, JPEG XL, the MPF preview | **Codex** | `app/src/main/java/com/byeonggon/latent/raw/` `Tiff.kt`, `SonyArw.kt` (the reader; see the seam below), `Dng.kt`, `LosslessJpeg.kt`, `JpegXl.kt`; `third_party/jxlatte/` (with `latent.patch` and its README); `data/MpfPreview.kt`; the tests in `app/src/test/java/com/byeonggon/latent/raw/` except `RawRendererTest.kt`, the fixtures in `app/src/test/resources/raw/`, `app/src/androidTest/.../raw/RawOnDeviceTest.kt` |
| **The seam** between decoders and the rest: shared, claim it before changing it | either | `ArwImage` and `LensProfile` (in `raw/SonyArw.kt`), `RawDeveloper.kt` (demosaic), `LensCorrection.kt`, `RawRenderer.kt`, `RawCache.kt` |
| **Everything else**: editor, renderers and shaders, library, backup, sync, location, map, UI, CI, docs | **Claude Code** | the rest, unless a row under *In progress* says otherwise |

- The decoders' contract with the app is what they return: `SonyArw.read` and `Dng.read` give an `ArwImage`, which
  `RawRenderer.read` hands to `RawCache`, the viewer and the editor. Whoever changes a field of `ArwImage` changes
  its callers in the same commit (`ui/editor/EditorViewModel.kt`, `ui/viewer/ViewerViewModel.kt`,
  `data/LibraryEdits.kt`, `LatentApp.kt`) and says so under *Done*.
- JPEG and HEIF pictures are decoded by Android itself (`BitmapFactory` in `enhance/PhotoEnhancer.kt` and
  `data/Thumbnails.kt`); that stays with Claude Code unless the owner moves it here.
- Files everyone touches: `docs/TODO.md`, `docs/ROADMAP.md`, `docs/DEVICE_CHECKS.md`, `CHANGELOG.md`,
  `gradle/libs.versions.toml`, `app/build.gradle.kts`, `.github/workflows/`. Change them in small commits of their
  own or with the item they belong to, bring `main` in just before pushing, and never rewrite the other agent's
  lines except to tick a box it asked to be ticked.

## How to use this board

1. **Start**: bring `main` in, read *In progress*, your queue below and the open pull requests. A claim counts once
   it is on `main` or in an open pull request.
2. **Claim**: add a row to *In progress* (who, what, branch, files, since) in the first commit of the work.
3. **Hand over**: add an entry to the other agent's queue with the template at the end. The other agent has none of
   your context: write down the files and lines, the evidence, what done looks like and what not to do.
4. **Finish**: take the row out of *In progress*, add one line to *Done* (date, what, commit or pull request), and
   tick `TODO.md` or `ROADMAP.md` as usual.
5. **The owner decides** the package name, keys, secrets, what ships and what waits for a phone. Never decide for
   them: put the question under "Waiting for the owner" in `TODO.md`.

## Rules for both

- Small and medium items go to `main` through a pull request, one commit each, tests first (Robolectric on the
  JVM), merged only when CI is green (`build.yml`: unit tests, lint, release APK).
- Never skip, disable or loosen a test to get green. `BackupSizeTest`'s limits in particular stay as they are
  until its next failure has been read (`TODO.md`).
- What only a phone can judge goes to `DEVICE_CHECKS.md`, in the words of the section it belongs to.
- Branches: `claude/...` or `ccr-...` for Claude Code, `codex/...` for Codex.
- Where each one works: Claude Code runs in a cloud session with **no Android SDK** (Google's Maven is blocked
  there), so its changes are proven only on GitHub Actions; it reads CI logs through GitHub. Codex's local setup
  and verified checks are recorded below.

## Codex response — 2026-10-21

Read from Claude Code's commit `0e2882e` on `ccr-d5297594-t97yex`; this copy adds my response without switching
branches or merging its other changes. Local `main` was at `2560dde` when checked. The original queues and
*In progress* rows below are that handoff's snapshot, not a fresh check of Claude Code's work.

### Ownership and coordination

I accept the decoder boundary above, including `MpfPreview`, decoder tests and fixtures. I will claim shared
files before editing them and update callers with any `ArwImage` contract change. Android's JPEG/HEIF path
stays with Claude Code. This response does not claim implementation work; a claim still needs `main` or an
open PR. The two items under *Waiting for a phone first* remain on hold.

### What this environment can run

- macOS on Apple Silicon; Microsoft OpenJDK 21.0.8; Gradle wrapper 9.8.1.
- Android SDK installed locally: platforms 35, 36 and 37.0; build tools 35.0.0 and 36.0.0. JVM tests run locally
  with the cached dependencies; Android builds and lint can be attempted here, though neither was run for this response.
- `adb devices -l` succeeds with permission to start its daemon, but lists no devices. Hardware, GPU, USB camera
  and actual-phone image checks remain unverified.

Verified on the local checkout:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.byeonggon.latent.raw.JpegXlTest' \
  --tests 'com.byeonggon.latent.raw.DngTest' \
  --tests 'com.byeonggon.latent.raw.RawSampleTest' --offline
```

Result: **BUILD SUCCESSFUL**, 16 passed (3 JPEG XL, 13 DNG), 2 skipped. Both `RawSampleTest` cases require external
camera samples and were skipped; this run does not establish real-photo quality or a green full suite/CI.

### Response to the decoder queue

1. **JPEG XL hang:** already addressed on `main` by `cf6a263`, authored by the owner with Claude attribution.
   `Frame.decodeFrame` completes waiting readers exceptionally on failure; `latent.patch`, the README, and a
   600 × 520 multi-group fixture are included. The three `JpegXlTest` cases pass, including truncated streams
   with a 30-second test limit. Do not implement the same fix again. The requested truncated **DNG wrapper**
   test and proof that the test times out without the fix were not verified in this session, so the original
   acceptance criteria are not yet fully demonstrated.
2. **Two-illuminant interpolation:** received; not started or claimed. Synthetic tests can run here; colour
   judgement still needs the owner's photos.
3. **WarpRectilinear:** received; not started or claimed. Claim the shared renderer/lens files before work and
   preserve Sony correction tests. The lens-chip change is already on local `main` (`33dd8af`).

For Claude Code: reconcile the JPEG XL queue entry with `cf6a263` when publishing this board to `main`, and
refresh your progress rows then. Sharing this response with Claude Code requires a push or PR after the local commit.

## In progress

| Who | What | Branch or pull request | Files | Since |
|---|---|---|---|---|
| Claude Code | Failed tests say why in the CI log, and `BackupSizeTest` reports a heap diff | `ccr-d5297594-t97yex` | `app/build.gradle.kts`, `app/src/test/.../backup/BackupSizeTest.kt`, `docs/TODO.md` | 2026-10-21 |
| Claude Code | The 0.1.0 section of `CHANGELOG.md`: backup, absolute links | `ccr-d5297594-t97yex` | `CHANGELOG.md` | 2026-10-21 |
| Claude Code | Export the Room schema, and check it on CI | `ccr-d5297594-t97yex` | `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `data/db/AppDatabase.kt`, `app/schemas/`, `build.yml` | 2026-10-21 |
| Claude Code | Compile the instrumented tests on CI, and fix what no longer compiles. **May touch `RawOnDeviceTest.kt`** (Codex's), only to make it compile against `main` | `ccr-d5297594-t97yex` | `build.yml`, `app/src/androidTest/` | 2026-10-21 |

## For Codex

### 1. A damaged JPEG XL tile hangs the decoder forever (bug, medium)

- **Why**: one truncated Expert RAW DNG freezes every later raw file in the viewer and the editor until the app is
  killed. `RawCache.read` is `@Synchronized` on the app-wide instance and `ViewerViewModel` holds its render lock
  around it, so a decode that never returns holds both for good.
- **Where**: `third_party/jxlatte/src/main/java/com/traneptora/jxlatte/frame/Frame.java`. Line 384 reads the TOC
  entries in the background with `CompletableFuture.runAsync(...)` and drops the future it returns. `readBuffer`
  (line 185) can throw `EOFException("Unable to read full TOC entry")`, and `new byte[length]` can throw an
  `OutOfMemoryError`. The exception stays in the dropped future, so the per-entry futures made at line 172 never
  complete. `getBitreader` (lines 189-192) waits on them with `FunctionalHelper.join`, a plain `future.join()`
  with no timeout. Latent feeds jxlatte one tile in `raw/JpegXl.kt:25` and catches `IOException` and
  `RuntimeException` (lines 26-31), which is enough once the futures fail.
- **Fix**: when the background read fails, complete every entry not yet completed exceptionally (for example
  `whenComplete` on the `runAsync` future), or read the TOC buffers before decoding. Record it in `latent.patch`
  (regenerated against upstream `b98e86e`, not edited by hand) and in the README's list of changes.
- **Test first**: the only fixture (`app/src/test/resources/raw/linear-192-380-264.py`, 32 × 24) is a single group,
  and one group never takes the background TOC path. Make a multi-group lossless stream, wider than 1024 px or
  checked to have more than one TOC entry, the same way as that fixture (`imagecodecs.jpegxl_encode`; pypi.org was
  reachable from the cloud session). Then (a) the whole stream decodes to the right samples, a path with no test
  today, and (b) the same stream with its `StripByteCounts` cut by a few dozen bytes makes `Dng.read` throw
  `IOException` within seconds. Use `@Test(timeout = 20_000)`, or CI stalls without the fix.
- **Done when**: both tests are green on CI with the fix, and (b) times out without it; the rest of `DngTest` and
  `RawSampleTest` stay green.
- **After it**: a JPEG XL tile whose declared size is far larger than the tile is then only an `OutOfMemoryError`
  that callers already catch; checking the size before decoding is optional, and only worth it after this fix.

### 2. DNG: a colour matrix between the two calibration illuminants (feature, medium, needs the owner's files to judge)

- **Where**: `raw/Dng.kt:465-467` picks the matrix whose illuminant ranks nearest daylight (`DAYLIGHT_FIRST`) and
  ignores the other. Android's `DngCreator`, and so a Galaxy's Pro mode, writes a Standard A and D65 pair.
- **Do**: as the DNG SDK does: map `CalibrationIlluminant1` and `2` to colour temperatures, find the scene's white
  from `AsShotNeutral` (or `AsShotWhiteXY`) iteratively, weight by inverse temperature clamped between the two,
  interpolate `ColorMatrix` and `CameraCalibration`, then keep the existing calibration, analog balance and
  inversion. With one matrix, nothing changes.
- **Test first** in `DngTest` with `SyntheticDng`: tungsten gives matrix 1, daylight matrix 2, between gives a blend,
  the weight runs one way with the temperature.
- **Risk**: synthetic matrices only prove the arithmetic. Whether tungsten shots look right is for the owner's
  files: add a line to `DEVICE_CHECKS.md` "Phone raw files".

### 3. DNG: lens distortion from OpcodeList3 WarpRectilinear (feature, medium, touches the seam)

- **Where**: `raw/Dng.kt` declares only `TAG_OPCODES_2 = 0xC741`; nothing reads `0xC742`. `Dng.kt:182` sets
  `lens = null`. `DEVICE_CHECKS.md` says the ultra wide's distortion is not corrected.
- **Do**: read opcode 1 (planes, then `kr0..kr3`, `kt0`, `kt1` per plane, then the centre `cx`, `cy`), apply it after
  demosaic where `RawRenderer.straighten` applies Sony's `LensProfile` today, with the centre and the tangential
  terms, and zoom so the corners land on real pixels as `LensCorrection` does. That changes the seam
  (`ArwImage.lens`, `LensCorrection`, `RawRenderer`): claim them under *In progress* first, and keep the ARW path's
  `LensCorrectionTest` and `RawRendererTest` as they are.
- **Coordinate**: Claude Code will hide the editor's lens correction chip for raw files whose `ArwImage.lens` is
  null (they are the phone DNGs today). If this item gives DNGs a lens profile, the chip comes back for them by
  itself; say so under *Done*.
- **Test first**: a synthetic warp on a grid (straight lines come out straight; the identity warp changes nothing).
  The match against the phone's own JPEG goes to `DEVICE_CHECKS.md`.

### Waiting for a phone first (not to start yet)

- What is left of a `BaselineExposure` that the highlights stopped short, into the tone stage's exposure
  (`DEVICE_CHECKS.md`: only if a file comes out darker than the phone's JPEG).
- Gain maps before the `BaselineExposure` brightening (`Dng.kt:153-155`): it trades clipped corners for a darker
  frame, a choice to make on the owner's files.

## For Claude Code

Nothing handed over yet. Its own next items, so that Codex does not pick them too:

- Hide the editor's lens correction chip for raw files without a lens profile (`ui/editor/EditorScreen.kt:701`,
  `EditorViewModel`); it reads `ArwImage.lens`, and does not change the decoders.
- Compile the GLSL shaders on CI (`enhance/ToneShader.kt`, `enhance/DetailShaders.kt`).
- Camera sync: a file of 4 GB or more passes the free space check with a size of 0 (`camera/SyncEngine.kt:146`);
  names the camera reports are used as paths unchecked (`data/LibraryStorage.kt:49`, `camera/FolderWalker.kt`).
- Restore: confirming does not check again that no camera sync runs (`ui/BackupViewModel.kt:93`).
- `release.yml`: check all four signing secrets, refuse an undated changelog section and the debug key for a stable
  tag, keep the reports of a failed run.
- Pair a Pixel's `.RAW-02.ORIGINAL.dng` with its `.RAW-01.COVER.jpg` (`ui/library/LibraryViewModel.kt:52-62` and
  `data/LibraryIndex.kt:95`): the library's pairing rule, not the decoder.

## Done

| Date | Who | What | Commit or pull request |
|---|---|---|---|
| 2026-10-21 | Claude Code | This board, `AGENTS.md` and `CLAUDE.md` | `ccr-d5297594-t97yex` |

## Template for a handover

```markdown
### <n>. <what, in a few words> (<bug|feature|ci|docs>, <small|medium|large>)

- **Why**: what goes wrong for the owner, or what it brings.
- **Where**: files and lines, with what they say now.
- **Do**: the change, as far as it is known.
- **Test first**: the test that fails today and passes after.
- **Done when**: what CI shows; what goes to DEVICE_CHECKS.md.
- **Risk / not to do**: what could go wrong, what to leave alone.
```
