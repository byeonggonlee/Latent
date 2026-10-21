# To do

What is open right now, by who has to move. The longer list of features is in [`ROADMAP.md`](ROADMAP.md), what only a
phone can answer is in [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md), how builds go out is in [`RELEASES.md`](RELEASES.md), which
agent (Claude Code, Codex or Antigravity) is on what in [`HANDOFF.md`](HANDOFF.md), and the owner's decisions on how
the app is built in [`architecture/decisions.md`](architecture/decisions.md).
Last updated 2026-10-22.

## Waiting for the owner

- [x] **Signing secrets** (2026-10-22): the release key below is in the four secrets (`SIGNING_KEYSTORE_BASE64`,
      `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, see RELEASES.md), so the nightly now
      signs what it builds and publishes it.
- [ ] **The first signed nightly on the phones**, once the new app reads the originals the old one kept (Codex's P2
      on [PR #11](https://github.com/byeonggonlee/Latent/pull/11): `DCIM/Latent/.originals/` stays the old package's,
      and the new app reads it by path): one manual *Run workflow* of **Nightly** with *force* (its checksum file and
      the clean-up of old ones), then each phone moves to it once (RELEASES.md, "Reinstalling a phone"). Until then no
      nightly is for the phones, though they are signed and published.
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
- [x] **Smaller APK**: the CI and nightly builds carry arm64 only, 20 MB instead of 39 MB (`-PlatentAbis`).

- [ ] **Sky mask** (stage 2 of [`AI_MASKS.md`](AI_MASKS.md)), then tap to select (stage 3): no longer held back, the
      owner found the subject mask fine on the phone (2026-10-21). Queued for Claude Code in `HANDOFF.md`.
- [ ] **Import from a card reader** or a camera in Mass Storage mode, as a sync over MTP does (2026-10-21). Queued
      for Claude Code in `HANDOFF.md`.

## On hold

- NPU: measure the GPU and CPU time first; decide after.
- Point colour and calibration; the rest is in the colour range and the mixer.

## Later

- Video: LUTs and grading on Media3, 10-bit with them; HDR in the viewer; keeping the gain map (ROADMAP items 7, 10,
  11, 12).
- The stable workflow's first run, a baseline profile and the closed test that a personal Play account needs.
