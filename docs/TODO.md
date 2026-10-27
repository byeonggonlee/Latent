# To do

What is open right now, by who has to move. The longer list of features is in [`ROADMAP.md`](ROADMAP.md), what only a
phone can answer is in [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md), how builds go out is in [`RELEASES.md`](RELEASES.md), which
agent (Claude Code, Codex or Antigravity) is on what in [`HANDOFF.md`](HANDOFF.md), and the owner's decisions on how
the app is built in [`architecture/decisions.md`](architecture/decisions.md).
Last updated 2026-10-23.

## Waiting for the owner

- [ ] **Tag `v0.1.0`** once Codex's S24 release checks are in (`HANDOFF.md`, *For Codex* §0) and nothing in them
      blocks: Claude Code then writes the date into `CHANGELOG.md` and tags, as `RELEASES.md` says.

- [x] **Sony SR2 white balance cross-family review** (2026-10-27, [PR #49](https://github.com/byeonggonlee/Latent/pull/49)):
      Claude Opus 5.5 (via Claude Code) reviewed `ca35692`, LGTM; the public 7M2 ARW develops on the emulator with
      neutral colour. Merged `7d58294` on the owner's instruction. Phone colour judgement and the ZV-E10 regression
      check remain in DEVICE_CHECKS.md.

- [x] **Photo colour picker cross-family review** (2026-10-27, [PR #47](https://github.com/byeonggonlee/Latent/pull/47)):
      Claude Opus 5.5 (via Claude Code) reviewed the full diff at `ac8a415`, LGTM; CI green. Merged `142f7f7` with
      the owner's authorization. S24/GPU/TalkBack checks remain in DEVICE_CHECKS.md.
- [ ] **Photo colour picker UX follow-ups** (PR #47 review, non-blocking P3): clarify the retained previous hue when
      a new sample is invalid (current behavior follows the approved design); show hardware-key guidance only where
      useful; reserve the hue line to prevent the image moving after the first sample; preserve mask-panel scroll
      on returning from picking. These are follow-up suggestions, not conditions on the merged PR.

- [x] **Verified reinstallation imports, transfer cost** (legacy audit LA-01, HANDOFF; decided 2026-10-22): a camera
      file that only looks like one in the library by name and size is compared whole before it is left out, and the
      person is asked first before the one-time transfer that comparing many of them takes. A file not compared, the
      person having declined or not yet answered, is never recorded as imported. Claude Code implements it.
- [x] **Autopilot of 2026-10-22** (decided that day): Claude Code works through the legacy audit's findings, the HEIF
      share, roadmap items 10 and 11 and the sky mask, one reviewed pull request each, and merges what Codex approves
      without asking again, for this run only. The owner asks Codex for a thorough review of the roadmap items. The
      sky mask's downloads are approved: EfficientViT-Seg B1 (ADE20K) weights and the tools to convert them, outside
      the repository. The Timeline's limit on a stay is left to Claude Code (a week).
- [x] **Release/nightly token scope priority** (legacy audit LA-09, HANDOFF; decided 2026-10-22): before the
      `v0.1.0` tag. The nightly and stable workflows build in a read-only job and publish in a job of its own
      (RELEASES.md, Signing); a reviewed pull request, the nightly after it shows the split works.

- [x] **Signing secrets** (2026-10-22): the release key below is in the four secrets (`SIGNING_KEYSTORE_BASE64`,
      `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, see RELEASES.md), so the nightly now
      signs what it builds and publishes it.
- [ ] **The first signed nightly on the phones**: the first one with the new app taking over the old one's
      originals ([PR #12](https://github.com/byeonggonlee/Latent/pull/12), on `main` since 2026-10-22) is the next
      scheduled one, or one manual *Run workflow* of **Nightly** with *force*. Each phone then moves to it once
      (RELEASES.md, "Reinstalling a phone"), with the check in DEVICE_CHECKS, "Backup and restore", and
      `SMOKE_TEST.md`. `nightly-20261022-213`, the first signed one, is from before that fix: not for the phones.
      The signing secrets work: it was signed with the release key and published.
- [ ] **On the phones**: the checks in DEVICE_CHECKS, the risky ones first: the colour range and LUT shaders against
      the CPU result, subject and background masks (log tag `SubjectMask`: GPU or CPU, cold and warm time), the
      settings, search, compare, HEIF, presets, and the tab bar after its fix (no empty band above the buttons).
      Video: a portrait one, a 4K one, one with a rotation flag. Then a backup and its restore on the same phone and on
      the other one, before the first reinstall needs them (no uninstalling for the check).
- [x] **The release key** (2026-10-22): alias `latent`, `CN=Latent Image, C=KR`, RSA 4096, made on the owner's
      computer and kept outside the repository (RELEASES.md, "Signing"). The package name was settled the same day:
      `com.latentimage.latent`, developer name Latent Image on Play.
- [ ] **The rest of Codex's architecture questions** (`architecture/codex-assessment.md` §11; question 3, the
      precision goal, is decided in `architecture/decisions.md`): the first scope of the decoder work, whether
      standalone JPEG XL, JPEG or HEIF come under the decoders, the phones and the memory and time budgets, the
      rights to sample photos and who checks on a phone, and who approves a change of the contract.
- [ ] **OpenCode pilot** (optional): install it, connect a provider, and have a cheap model read the Japanese and
      Korean strings for wording that is off. Source is public; no photos or places are ever sent.

## Ready to do (this side)

- [x] **Repeated import from the same folder** (owner report, 2026-10-23): summary item 5 of the SC-57G run on nightly
      347 failed, then turned out to be a mismatch of versions between what was tried and the nightly: on the right
      build the import works. Not reproduced, no defect found, nothing fixed. See
      [the device report](device-results/2026-10-23-sc-57g.md).
- [ ] **Remaining SC-57G summary checks**: item 6 (disconnecting the camera and running out of space during a sync, then
      carrying on) and item 27 (restoring a backup on another phone, which the owner judges rare and can wait). Items
      24 (LUTs and Lightroom presets), 26 (backup and same-phone restore) and 28 (cancelling a restore) work, with
      the other 27 and item 5: 31 of 33. Detailed GPU/CPU numbers, timing and memory, 16 KB pages and the debug
      instrumentation checks remain separate (DEVICE_CHECKS, section 9). See
      [the report](device-results/2026-10-23-sc-57g.md).

- [x] **`BackupSizeTest` on CI** (2026-10-21): its next failure was read. The heap's growth by class was about 1.2 MB
      of 10.9 MB; the rest was some 300,000 closed cursors waiting for their finalizers, which the restore made by
      asking Room for every point's row id. Fixed in the restore and the timeline import (pull request #6); the test's
      limits and measurement are as they were.
- [x] **`gh`** installed and logged in (2026-10-20); its token is in the keychain, so it runs from the Terminal
      panel, not from the agent's own shell.
- [x] **Pull request #1** merged 2026-10-20: the `v*` release workflow, `CHANGELOG.md`, `SECURITY.md`, issue
      templates, and phones' DNGs (mosaics and linear, uncompressed, lossless JPEG or JPEG XL through
      `third_party/jxlatte`, with a positive `BaselineExposure`). To try on a phone: DEVICE_CHECKS, "Phone raw files".
- [x] **Backup and restore** of what lives only in the database (albums, picks and stars, edits and versions, own
      filters, LUT files, detected masks, the timeline, camera imports) into one zip that the person keeps: the way
      across a reinstall, a new phone, a new package name and the first signed release (RELEASES.md, "Reinstalling a
      phone"). Settings → Backup and restore; format, matching rule and merge rules in
      [`BACKUP_FORMAT.md`](BACKUP_FORMAT.md). Built 2026-10-20, not yet tried on a phone (DEVICE_CHECKS, "Backup and
      restore").
- [x] The other dialogs of the library's title bar (About, albums, the confirmation to trash the rejected) now live
      above it, like the search sheet; a test shows About staying up while the library fills under it.
- [x] A ten-minute check for the phone after each nightly: [`SMOKE_TEST.md`](SMOKE_TEST.md).
- [x] **Smaller APK**: every build carries arm64 only, 20 MB instead of 39 MB (`-PlatentAbis` for an emulator).

- [x] **Sky mask** (stage 2 of [`AI_MASKS.md`](AI_MASKS.md), 2026-10-22, [PR #29](https://github.com/byeonggonlee/Latent/pull/29)):
      "+ Sky" in the editor; the phone checks are in DEVICE_CHECKS, "Sky masks". Tap to select (stage 3) is next.
- [x] **Import from a card reader** or a camera in Mass Storage mode, as a sync over MTP does (2026-10-22,
      [PR #13](https://github.com/byeonggonlee/Latent/pull/13)): the Camera tab's "Card or drive". The phone checks are in
      DEVICE_CHECKS, "From the card".

## On hold

- NPU: measure the GPU and CPU time first; decide after.
- Point colour and calibration; the rest is in the colour range and the mixer.

## Later

- Video: LUTs and grading on Media3, 10-bit with them; carrying an Ultra HDR photo's gain map through an edit
  (ROADMAP items 7, 11, 12). HDR in the viewer, and telling that an edit drops the gain map, are done (2026-10-22,
  pull requests #27 and #28).
- The stable workflow's first run, a baseline profile and the closed test that a personal Play account needs.
- Optional: rename the source package `com.byeonggon.latent` (the `namespace`; about 300 files) to match the
  application id `com.latentimage.latent`. Nobody sees it and nothing needs it before Play, so it waits. If it is done,
  do it in one rename-only pull request while no other is open (no change of content; the CI checks, the schema diff
  and a search for the old name come out clean). It could be a trial of Gemini 3.8 Flash as an author, which needs the
  owner's one-off exception to the rule that Antigravity writes no code, recorded in `architecture/decisions.md`, and
  a review by another model.
