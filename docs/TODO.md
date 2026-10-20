# To do

What is open right now, by who has to move. The longer list of features is in [`ROADMAP.md`](ROADMAP.md), what only a
phone can answer is in [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md), how builds go out is in [`RELEASES.md`](RELEASES.md).
Last updated 2026-10-20.

## Waiting for the owner

- [ ] **Pull request #1** (*Add a stable release workflow for v\* tags*, written in a cloud session): the `v*` release
      workflow, `CHANGELOG.md`, `SECURITY.md`, issue templates, and development of phones' DNG raw files
      (`raw/Dng.kt`, `raw/LosslessJpeg.kt`; the Sony ARW path only gains a `saturation` field with the same value).
      Read through here and run on its branch: 430 tests, lint and the androidTest compile pass, as in its CI. Not
      merged: it needs the owner's go (the DNG decoding has only been tried on synthetic files, see DEVICE_CHECKS).
- [ ] **Signing secrets** in the repository (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
      `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, see RELEASES.md). Until they are there the nightly (it has run
      three nights, green) builds and publishes nothing. Then one manual *Run workflow* of **Nightly** with *force*
      to see the first pre-release, its checksum file and the clean-up of old ones.
- [ ] **`gh`**: `brew install gh`, `gh auth login`. Then builds can be started and their logs read from here, and
      pull requests opened and read.
- [ ] **On the phones**: the checks in DEVICE_CHECKS, the risky ones first: the colour range and LUT shaders against
      the CPU result, subject and background masks (log tag `SubjectMask`: GPU or CPU, cold and warm time), the
      settings, search, compare, HEIF, presets, and the tab bar after its fix (no empty band above the buttons).
      Video: a portrait one, a 4K one, one with a rotation flag.
- [ ] **Decide when publishing is settled**: the package name (RELEASES.md and the notes on Play), then the first real
      release key, which means every phone is reinstalled once.
- [ ] **OpenCode pilot** (optional): install it, connect a provider, and have a cheap model read the Japanese and
      Korean strings for wording that is off. Source is public; no photos or places are ever sent.

## Ready to do (this side)

- [ ] **Backup and restore** of what lives only in the database (albums, picks and stars, edits and versions, own
      filters, LUT files, the timeline) into one file: the way across a reinstall, a new phone, a new package name
      and the first signed release. Medium. Not yet approved by the owner.
- [ ] **Smaller APK**: it is 38.7 MB with every processor kind in it; arm64 only would be about 14 MB less on a
      phone. Decide how (an ABI filter, or splits for Play) before the first release.
- [ ] A look at the other pop-ups that keep their state in the library's title bar (the stars filter menu, the
      album list, About, the confirmation to trash the rejected): none should close when the list empties or fills.
- [ ] A smoke test of ten minutes, written out from DEVICE_CHECKS in the order of risk, for after each nightly.

## On hold

- Sky mask, then tap to select (stages 2 and 3 of [`AI_MASKS.md`](AI_MASKS.md)); held back by the owner until the
  subject mask has been judged on a phone.
- NPU: measure the GPU and CPU time first; decide after.
- Point colour and calibration; the rest is in the colour range and the mixer.

## Later

- Video: LUTs and grading on Media3, 10-bit with them; HDR in the viewer; keeping the gain map (ROADMAP items 7, 10,
  11, 12).
- The stable workflow's first run, a baseline profile and the closed test that a personal Play account needs.
