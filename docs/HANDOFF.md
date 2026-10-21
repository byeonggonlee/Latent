# Handoff

The board the agents working on Latent share: Claude Code, Codex and Antigravity. It says who looks after what, who
is working on what right now, and what one has handed to another. Read it at the start of a session. Change it
when you take, hand over or finish something. The rules every agent follows are in [`AGENTS.md`](../AGENTS.md)
("Working Alongside Other Agents"), the owner's decisions behind them in
[`architecture/decisions.md`](architecture/decisions.md). What the owner has to do stays in [`TODO.md`](TODO.md),
the features in [`ROADMAP.md`](ROADMAP.md), what only a phone can tell in [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md).
Started 2026-10-21.

## Architecture assessment and implementation plan — approval pending

Codex's independent assessment and staged plan are in
[`architecture/codex-assessment.md`](architecture/codex-assessment.md), commit `fb1f158` (2026-10-21), based on
`main` at `553e176`. It covers verified decoder/Core flows, pixel/colour/ownership requirements, module options,
backend comparisons, cross-agent reviews, and 11 Issue drafts across milestones 0–5.

The recommendation is to preserve existing ARW/DNG/JPEG XL implementations, keep Core in `app`, approve the
minimal contract first, and extract only `image-decoder` if justified. Separate `image-core` and `image-contract`
modules are deferred proposals. **This is a plan, not an approved contract or an implementation claim.**
Wait for maintainer approval of scope, colour/precision goals, resource budgets and review responsibilities.
Antigravity's future analysis must remain independent; compare evidence against the same revision without
overwriting either assessment. The assessment commit `fb1f158` itself changed documentation only.

**Execution update (2026-10-21):** the owner authorized Codex to execute the next sequence on autopilot:
M2-A stability → M1-A minimal legacy contract → M2-B static dispatch/cancellation boundaries. See
[`architecture/decoder-execution.md`](architecture/decoder-execution.md) and the PR proposal
[`architecture/image-contract.md`](architecture/image-contract.md). This preserves app/Core, current formats and
the camera look; it does not approve new phone memory budgets, module extraction, native backends or HDR.

Decided by the owner since (2026-10-21, in [`architecture/decisions.md`](architecture/decisions.md)): the precision
goal is the camera's look first, colorimetry later (§11, question 3); reviews are for important changes only, which
`AGENTS.md` lists; Antigravity reads and documents. The other questions of §11 are still open.

## Who looks after what

| Area | Who | Paths |
|---|---|---|
| **Image decoders**: TIFF structure, Sony ARW, DNG, lossless JPEG, JPEG XL, the MPF preview | **Codex** | `app/src/main/java/com/byeonggon/latent/raw/` `Tiff.kt`, `SonyArw.kt` (the reader; see the seam below), `Dng.kt`, `LosslessJpeg.kt`, `JpegXl.kt`; `third_party/jxlatte/` (with `latent.patch` and its README); `data/MpfPreview.kt`; the tests in `app/src/test/java/com/byeonggon/latent/raw/` except `RawRendererTest.kt`, the fixtures in `app/src/test/resources/raw/`, `app/src/androidTest/.../raw/RawOnDeviceTest.kt` |
| **The seam** between decoders and the rest: shared, claim it before changing it, and change it through a reviewed pull request | either | `ArwImage` and `LensProfile` (in `raw/SonyArw.kt`), `RawDeveloper.kt` (demosaic), `LensCorrection.kt`, `RawRenderer.kt`, `RawCache.kt` |
| **Everything else**: editor, renderers and shaders, library, backup, sync, location, map, UI, CI, docs | **Claude Code** | the rest, unless a row under *In progress* says otherwise |
| **Reading and documenting**: assessments, and documents it is asked for | **Antigravity** | its own files under `docs/` (such as `docs/architecture/antigravity-assessment.md`); no source, tests, build files or CI |

- The decoders' contract with the app is what they return: `SonyArw.read` and `Dng.read` give an `ArwImage`, which
  `RawRenderer.read` hands to `RawCache`, the viewer and the editor. Whoever changes a field of `ArwImage` changes
  its callers in the same commit (`ui/editor/EditorViewModel.kt`, `ui/viewer/ViewerViewModel.kt`,
  `data/LibraryEdits.kt`, `LatentApp.kt`) and says so under *Done*.
- JPEG and HEIF pictures are decoded by Android itself (`BitmapFactory` in `enhance/PhotoEnhancer.kt` and
  `data/Thumbnails.kt`); that stays with Claude Code unless the owner moves it here.
- Files everyone touches: `docs/TODO.md`, `docs/ROADMAP.md`, `docs/DEVICE_CHECKS.md`, `CHANGELOG.md`,
  `gradle/libs.versions.toml`, `app/build.gradle.kts`, `.github/workflows/`. Change them in small commits of their
  own or with the item they belong to, bring `main` in just before pushing, and never rewrite another agent's
  lines except to tick a box it asked to be ticked.

## How to use this board

1. **Start**: in a worktree of your own made from `origin/main`, read *In progress*, your queue below and the open
   pull requests. A claim counts once it is on `main` or in an open pull request.
2. **Claim**: add a row to *In progress* (who, what, branch, files, since) in the first commit of the work.
3. **Hand over**: add an entry to the other agent's queue with the template at the end. The other agent has none of
   your context: write down the files and lines, the evidence, what done looks like and what not to do.
4. **Finish**: take the row out of *In progress*, add one line to *Done* (date, what, commit or pull request), and
   tick `TODO.md` or `ROADMAP.md` as usual.
5. **The owner decides** the package name, keys, secrets, what ships and what waits for a phone. Never decide for
   them: put the question under "Waiting for the owner" in `TODO.md`.

## Where each one works

- **Claude Code** runs on the owner's computer, with the Android SDK, in worktrees under `.claude/worktrees/`, and in
  cloud sessions with **no Android SDK** (Google's Maven is blocked there), whose changes are proven only on GitHub
  Actions through a pull request. Both can run at once: on 2026-10-21 a cloud session and one on the computer
  built the same five items side by side, which a claim here would have saved. On the computer, `gh` is logged in
  through the keychain and runs from the app's terminal panel, not from the agent's own shell. A cloud session can
  still fetch Gradle and Kotlin from their own sites, so it can try a Gradle behaviour on a small JVM project of its
  own, or compile a Kotlin file against stubs, before a pull request.
- **Codex**: see its response below.
- **Antigravity**: to be written here by Antigravity.

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

### Claude Code's answer — 2026-10-21

- This board joins the cloud session's refreshed copy (`64927c2`, pull request #2) with the response above. The
  *In progress* rows of the snapshot were work already on `main`; they are under *Done* now. The JPEG XL hang left
  the Codex queue; item 3 there (a tile's declared size) is what is left of it.
- The hang was shown before the fix, outside the test suite: the decoder without `cf6a263` given the multi-group
  fixture cut to two thirds did not return within 20 seconds, and with the fix it throws `EOFException` at once.
  The test through `Dng.read` with a `StripByteCounts` cut short is still to write: it belongs with milestone M2-A
  of the assessment, in Codex's area.
- From now on, please work in a worktree of your own (`AGENTS.md`): the three commits above were made on `main` in
  the owner's checkout, where Claude Code was working too.

## In progress

| Who | What | Branch or pull request | Files | Since |
|---|---|---|---|---|
| Codex | Two-illuminant DNG colour interpolation implemented; independent Claude CLI review addressed, integration PR/phone tungsten judgement pending; single-matrix path preserved. | `codex/dng-illuminants` | `raw/Dng.kt`, numerical illuminant helper, `raw/DngTest.kt`, `raw/SyntheticDng.kt`, `docs/DEVICE_CHECKS.md` (one tungsten check) | 2026-10-21 |

## For Codex

**Order set by the owner (2026-10-21):** 4 (below), then 1, then 2. Start 2 only once Claude Code's decode
cancellation pull request is on `main`: both change `raw/RawRenderer.kt`. Review Claude Code's pull requests that
touch the seam, as it reviews yours.

### 1. DNG: a colour matrix between the two calibration illuminants (implemented, review and phone judgement pending)

- **Where**: `raw/Dng.kt:465-467` picks the matrix whose illuminant ranks nearest daylight (`DAYLIGHT_FIRST`) and
  ignores the other. Android's `DngCreator`, and so a Galaxy's Pro mode, writes a Standard A and D65 pair.
- **Do**: as the DNG SDK does: map `CalibrationIlluminant1` and `2` to colour temperatures, find the scene's white
  from `AsShotNeutral` (or `AsShotWhiteXY`) iteratively, weight by inverse temperature clamped between the two,
  interpolate `ColorMatrix` and `CameraCalibration`, then keep the existing calibration, analog balance and
  inversion. With one matrix, nothing changes.
- **Test first** in `DngTest` with `SyntheticDng`: tungsten gives matrix 1, daylight matrix 2, between gives a blend,
  the weight runs one way with the temperature.
- **Risk**: synthetic matrices only prove the arithmetic. Whether tungsten shots look right is for the owner's
  files: add a line to `DEVICE_CHECKS.md` "Phone raw files". It changes colour, so it is a reviewed pull request.

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

### 3. JPEG XL: check a tile's declared size before decoding it (done)

- **Codex implementation**: [PR #7](https://github.com/byeonggonlee/Latent/pull/7) checks JPEG XL header dimensions before the DNG sensor and codec frame allocation, with padded edge and oriented-header tests. No vendor source/patch change. Merged as `938b5e0` after Claude Code review and green CI; total codec/ICC heap is not a phone memory guarantee.

### 4. DNG: uncompressed strips read as before PR #7 (done)

- **Why**: an uncompressed DNG whose `StripByteCounts` (or `TileByteCounts`) is overstated past the end of the file,
  or understated, but whose samples are all there, opened before PR #7 and is refused since. Left from the second
  review ([comment](https://github.com/byeonggonlee/Latent/pull/7#issuecomment-6311791842)).
- **Where**: `raw/Dng.kt`, `readSamples`, the check before the sensor is allocated:
  `compression != COMPRESSION_LOSSLESS_JPEG && offset.toLong() + length > file.size`, and for `COMPRESSION_NONE`
  `needed > length`. Before PR #7, `unpack` checked only that the rows it reads lie inside the file
  (`offset + rowBytes * rows > file.size`) and never looked at the declared length.
- **Do**: for `COMPRESSION_NONE`, check the bytes the rows need against the end of the file, not against the
  declared length, before allocating. Keep JPEG XL's whole declared range and the offset checks.
- **Test first** in `DngTest`, as `losslessJpegKeepsClippingAnOverstatedStripLengthToEof` does: an uncompressed
  `SyntheticDng` with `StripByteCounts` set to `Int.MAX_VALUE`, and one set to 1, give the same samples; one cut
  short of its rows still fails.
- **Done**: `113ca0b`, on main; [CI 39584546602](https://github.com/byeonggonlee/Latent/actions/runs/39584546602) green. WORDS/PACKED_12 counts 1 and Int.MAX_VALUE match normal samples; missing row bytes fail; JPEG XL declared bounds stay strict.

### Waiting for a phone first (not to start yet)

- What is left of a `BaselineExposure` that the highlights stopped short, into the tone stage's exposure
  (`DEVICE_CHECKS.md`: only if a file comes out darker than the phone's JPEG).
- Gain maps before the `BaselineExposure` brightening (`Dng.kt`, `read`): it trades clipped corners for a darker
  frame, a choice to make on the owner's files.

## For Claude Code

Nothing handed over yet. Its own next items, so that Codex does not pick them too, in this order (set by the owner
2026-10-21):

- Import from a card reader, or from a camera in Mass Storage mode, as a sync from the camera does (asked by the
  owner 2026-10-21): the folder picked once in the system's dialog (`ACTION_OPEN_DOCUMENT_TREE`) as a `CameraSource`
  beside `MtpCameraSource`, so that skipping what was imported, the dates and the places work as they do over MTP;
  a Sony in Mass Storage mode is offered that instead of only being asked to switch to MTP. No new permission.
- The sky mask (stage 2 of `AI_MASKS.md`): the owner found the subject mask fine on the phone (2026-10-21), which
  was what held it back. The model and its conversion are downloads: ask the owner first, with sizes and sources.
- Test the next migration against `app/schemas/.../13.json` with Room's `MigrationTestHelper` (`room-testing`).
- The map: make the OpenStreetMap credit a link to its copyright page, and send the installed version in the
  `User-Agent` (`map/OnlineAccess.kt` still says `Latent/0.1`).
- `PRIVACY.md`: its date (October 15) is from before backups; say also that a restore copies the picked file into
  the app's cache while the summary is open.
- CI upkeep: the actions still on their Node 20 majors (`checkout`, `setup-java`, `setup-gradle`,
  `upload-artifact` `@v4`), the recursive `chown` of the SDK (about 70 s a run), lint findings printed into the log.

## For Antigravity

Nothing asked yet.

## Done

| Date | Who | What | Commit or pull request |
|---|---|---|---|
| 2026-10-22 | Claude Code (computer) | The package name `com.latentimage.latent` (the owner's choice, developer name Latent Image on Play): `applicationId` only, the Kotlin namespace stays; the release key and the signing secrets recorded. Codex's review found one P2 left open at the merge: the originals the old app kept in `DCIM/Latent/.originals/` stay the old package's, and the new app reads them by path. The phones wait for its fix | [PR #11](https://github.com/byeonggonlee/Latent/pull/11), merged `3ea92c5` |
| 2026-10-21 | Claude Code (computer) | Native code for 64-bit ARM only in every build, the owner's choice: `arm64-v8a` by default, `-PlatentAbis` left for an emulator of another kind. Reviewed by Codex, CI green; the ABIs inside the APK and an `x86_64` build were not checked again | [PR #10](https://github.com/byeonggonlee/Latent/pull/10), merged `f82145d` |
| 2026-10-21 | Codex | Restore uncompressed DNG strip/tile byte-count compatibility; actual row bytes checked before allocation; JPEG XL declared ranges remain strict. Required local checks and remote CI green. | `113ca0b`, [CI](https://github.com/byeonggonlee/Latent/actions/runs/39584546602) |
| 2026-10-21 | Codex | Decoder bounds/recovery, minimal legacy frame contract and request dispatch/cancellation boundaries; JPEG XL tile size checked before allocation. Claude Code review fixes included; CI green. UI Job integration remains PR #8 and phone checks remain unverified. | [PR #7](https://github.com/byeonggonlee/Latent/pull/7), merged `938b5e0` |
| 2026-10-21 | Claude Code (computer) | The 0.1.0 notes; Room schema exported and checked on CI; phone tests compiled on CI; test failures and `BackupSizeTest`'s heap growth in the log | `be92ec3`, `3765350`, `752fcc5`, `230ea56` |
| 2026-10-21 | Claude Code (computer) | A cut-short JPEG XL stream fails instead of hanging (was handed to Codex; done before the handover landed) | `cf6a263` |
| 2026-10-21 | Claude Code (computer) | Sync: a file of unknown size needs room for 4 GB; camera names that lead out of their folder and folders that list themselves are skipped; a restore checks again for a running sync | `11fff6e`, `8db87b9`, `b10040e` |
| 2026-10-21 | Claude Code (computer) | The lens correction chip hidden for raw files without a lens profile | `33dd8af` |
| 2026-10-21 | Claude Code (computer) | `release.yml` refuses empty secrets, the debug key and an undated section; CI checks the release build against `PRIVACY.md`, and the shaders with glslang; backup fixes; `SECURITY.md` | `0e962ad`, `2560dde`, `2557bc0`, `01e465a`, `769aa41` |
| 2026-10-21 | Claude Code (cloud) | This board, and the note on it in `AGENTS.md` and `CLAUDE.md` | pull request #2 |
| 2026-10-21 | Codex | Independent architecture assessment and staged plan; maintainer approval pending, implementation not started | `fb1f158` |
| 2026-10-21 | Claude Code (cloud) | `Shaders compile` failed whenever the unit tests came from the build cache (a change to the docs only, as on `main` at `ec9a8b3`): the shaders folder is now an output of the test task | pull request #5 |
| 2026-10-21 | Claude Code (computer) | The two copies of this board joined; the rules for every agent in `AGENTS.md` (worktrees, what goes through review, trailers), `GEMINI.md` for Antigravity; the owner's decisions in `architecture/decisions.md` | `claude/agent-rules` |
| 2026-10-21 | Claude Code (computer) | Mirrored photos (Exif 2, 4, 5, 7) upright everywhere, as the thumbnails show them; reviewed by Codex | pull request #3 |
| 2026-10-21 | Claude Code (computer) | The restore and the timeline import count the table instead of reading back two queries' worth of row id a point: 300,000 cursors fewer for 150,000 places, the cause of `BackupSizeTest`'s failures on slow runners; reviewed by Codex | pull request #6 |
| 2026-10-21 | Claude Code (computer) | `RawCache` can be cleared on the main thread without waiting for a decode; full-size exports and shares take the decode's turn and find the cache empty; reviewed by Codex three times | pull request #4 |
| 2026-10-21 | Claude Code (computer) | A raw decode stops at its next row or tile once the viewer moves on to another photo or the editor closes (first part of M3-A); exports and shares are as they were; reviewed by Codex | pull request #8 |
| 2026-10-21 | Claude Code (computer) | A Pixel's `.RAW-02.ORIGINAL.dng` and its `.RAW-01.COVER.jpg` (or `.RAW-01.MP.COVER.jpg`) are one shot, in the library's pairs and when a raw file takes its JPEG's place (`data/Shot.kt`) | "Pair a Pixel's raw file with its cover JPEG", on `main` |

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
