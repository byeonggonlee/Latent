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
- Where each one works: Claude Code runs on the owner's computer, with the Android SDK, committing as the owner,
  and in cloud sessions with **no Android SDK** (Google's Maven is blocked there), whose changes are proven only on
  GitHub Actions through a pull request. Both can run at once: on 2026-10-21 a cloud session and one on the
  computer built the same five items side by side, which a claim here would have saved. Codex: write down here what
  your setup can and cannot run.

## In progress

| Who | What | Branch or pull request | Files | Since |
|---|---|---|---|---|
| | Nothing claimed. | | | |

## For Codex

### 1. DNG: a colour matrix between the two calibration illuminants (feature, medium, needs the owner's files to judge)

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

### 2. DNG: lens distortion from OpcodeList3 WarpRectilinear (feature, medium, touches the seam)

- **Where**: `raw/Dng.kt` declares only `TAG_OPCODES_2 = 0xC741`; nothing reads `0xC742`. `Dng.kt` sets
  `lens = null`. `DEVICE_CHECKS.md` says the ultra wide's distortion is not corrected.
- **Do**: read opcode 1 (planes, then `kr0..kr3`, `kt0`, `kt1` per plane, then the centre `cx`, `cy`), apply it after
  demosaic where `RawRenderer.straighten` applies Sony's `LensProfile` today, with the centre and the tangential
  terms, and zoom so the corners land on real pixels as `LensCorrection` does. That changes the seam
  (`ArwImage.lens`, `LensCorrection`, `RawRenderer`): claim them under *In progress* first, and keep the ARW path's
  `LensCorrectionTest` and `RawRendererTest` as they are.
- **Coordinate**: since `33dd8af` the editor hides its lens correction chip when `ArwImage.lens` is null
  (`hasLensProfile` in `ui/editor/EditorViewModel.kt`). If this item gives DNGs a lens profile there, the chip
  comes back for them by itself; if it uses a field of its own, set `hasLensProfile` from it too.
- **Test first**: a synthetic warp on a grid (straight lines come out straight; the identity warp changes nothing).
  The match against the phone's own JPEG goes to `DEVICE_CHECKS.md`.

### 3. JPEG XL: check a tile's declared size before decoding it (hardening, small, optional)

- **Where**: `raw/JpegXl.kt` compares the picture's size with the tile only after `JXLDecoder.decode()`. A stream
  that declares a far larger picture than its tile allocates the whole frame first; callers catch the
  `OutOfMemoryError`, and a cut-short stream now fails fast (`cf6a263`), so this is only worth a small change.

### Waiting for a phone first (not to start yet)

- What is left of a `BaselineExposure` that the highlights stopped short, into the tone stage's exposure
  (`DEVICE_CHECKS.md`: only if a file comes out darker than the phone's JPEG).
- Gain maps before the `BaselineExposure` brightening (`Dng.kt`, `read`): it trades clipped corners for a darker
  frame, a choice to make on the owner's files.

## For Claude Code

Nothing handed over yet. Its own next items, so that Codex does not pick them too:

- Pair a Pixel's `.RAW-02.ORIGINAL.dng` with its `.RAW-01.COVER.jpg` (`ui/library/LibraryViewModel.kt`, `shotOf`, and
  `data/LibraryIndex.kt`, the same rule on adoption): the library's pairing rule, not the decoder.
- Test the next migration against `app/schemas/.../13.json` with Room's `MigrationTestHelper` (`room-testing`).
- The map: make the OpenStreetMap credit a link to its copyright page, and send the installed version in the
  `User-Agent` (`map/OnlineAccess.kt` still says `Latent/0.1`).
- `PRIVACY.md`: its date (October 15) is from before backups; say also that a restore copies the picked file into
  the app's cache while the summary is open.
- CI upkeep: the actions still on their Node 20 majors (`checkout`, `setup-java`, `setup-gradle`,
  `upload-artifact` `@v4`), the recursive `chown` of the SDK (about 70 s a run), lint findings printed into the log.

## Done

| Date | Who | What | Commit or pull request |
|---|---|---|---|
| 2026-10-21 | Claude Code (computer) | The 0.1.0 notes; Room schema exported and checked on CI; phone tests compiled on CI; test failures and `BackupSizeTest`'s heap growth in the log | `be92ec3`, `3765350`, `752fcc5`, `230ea56` |
| 2026-10-21 | Claude Code (computer) | A cut-short JPEG XL stream fails instead of hanging (was handed to Codex; done before the handover landed) | `cf6a263` |
| 2026-10-21 | Claude Code (computer) | Sync: a file of unknown size needs room for 4 GB; camera names that lead out of their folder and folders that list themselves are skipped; a restore checks again for a running sync | `11fff6e`, `8db87b9`, `b10040e` |
| 2026-10-21 | Claude Code (computer) | The lens correction chip hidden for raw files without a lens profile | `33dd8af` |
| 2026-10-21 | Claude Code (computer) | `release.yml` refuses empty secrets, the debug key and an undated section; CI checks the release build against `PRIVACY.md`, and the shaders with glslang; backup fixes; `SECURITY.md` | `0e962ad`, `2560dde`, `2557bc0`, `01e465a`, `769aa41` |
| 2026-10-21 | Claude Code (cloud) | This board, and the note on it in `AGENTS.md` and `CLAUDE.md` | pull request #2 |

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
