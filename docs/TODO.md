# To do

What is open right now, by who has to move. The longer list of features is in [`ROADMAP.md`](ROADMAP.md), what only a
phone can answer is in [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md), how builds go out is in [`RELEASES.md`](RELEASES.md).
Last updated 2026-10-20 (late night).

## Waiting for the owner

- [ ] **Signing secrets** in the repository (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
      `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, see RELEASES.md). Until they are there the nightly (it has run
      three nights, green) builds and publishes nothing. Then one manual *Run workflow* of **Nightly** with *force*
      to see the first pre-release, its checksum file and the clean-up of old ones.
- [ ] **`gh`**: installed; `gh auth login` is left. Then builds can be started and their logs read from here, and
      pull requests opened and read.
- [ ] **On the phones**: the checks in DEVICE_CHECKS, the risky ones first: the colour range and LUT shaders against
      the CPU result, subject and background masks (log tag `SubjectMask`: GPU or CPU, cold and warm time), the
      settings, search, compare, HEIF, presets, and the tab bar after its fix (no empty band above the buttons).
      Video: a portrait one, a 4K one, one with a rotation flag. Then a backup and its restore on the same phone and on
      the other one, before the first reinstall needs them (no uninstalling for the check).
- [ ] **Decide when publishing is settled**: the package name (RELEASES.md and the notes on Play), then the first real
      release key, which means every phone is reinstalled once (with a backup first: RELEASES.md, "Reinstalling a
      phone").
- [ ] **OpenCode pilot** (optional): install it, connect a provider, and have a cheap model read the Japanese and
      Korean strings for wording that is off. Source is public; no photos or places are ever sent.

## Ready to do (this side)

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

## On hold

- Sky mask, then tap to select (stages 2 and 3 of [`AI_MASKS.md`](AI_MASKS.md)); held back by the owner until the
  subject mask has been judged on a phone.
- NPU: measure the GPU and CPU time first; decide after.
- Point colour and calibration; the rest is in the colour range and the mixer.

## Later

- Video: LUTs and grading on Media3, 10-bit with them; HDR in the viewer; keeping the gain map (ROADMAP items 7, 10,
  11, 12).
- The stable workflow's first run, a baseline profile and the closed test that a personal Play account needs.
