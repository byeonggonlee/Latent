# To do

What is open right now, by who has to move. The longer list of features is in [`ROADMAP.md`](ROADMAP.md), what only a
phone can answer is in [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md), how builds go out is in [`RELEASES.md`](RELEASES.md).
Last updated 2026-10-20 (late night).

## Waiting for the owner

- [ ] **Pull request #1** (*Add a stable release workflow for v\* tags*, written in a cloud session): the `v*` release
      workflow, `CHANGELOG.md`, `SECURITY.md`, issue templates, and development of phones' DNG raw files. Since
      added here: linear DNGs (three samples a pixel) as both S26 modes write them, Pro mode in lossless JPEG and
      Expert RAW in JPEG XL (jxlatte, MIT, kept in `third_party/jxlatte` and patched to decode a 4000x3000 picture in
      384 MB of heap instead of more than 1.2 GB), and a positive `BaselineExposure` (Expert RAW's is +3). Both real
      S26 files develop on a computer, upright and as bright as the phone's JPEG. `main` is merged into it: 468
      tests and lint pass. Not merged: it needs the owner's go.
- [ ] **Signing secrets** in the repository (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
      `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, see RELEASES.md). Until they are there the nightly (it has run
      three nights, green) builds and publishes nothing. Then one manual *Run workflow* of **Nightly** with *force*
      to see the first pre-release, its checksum file and the clean-up of old ones.
- [ ] **`gh`**: `brew install gh`, `gh auth login`. Then builds can be started and their logs read from here, and
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
