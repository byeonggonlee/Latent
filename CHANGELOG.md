# Changelog

What changed in each stable release. The newest is first; a nightly's notes are the commits since the one before
it (see [`docs/RELEASES.md`](docs/RELEASES.md)). The release workflow publishes the section of the tagged version
as the release notes, so a section is written before the tag is.

## [0.1.0] - unreleased

The first release. Latent is an Android app that brings photos in from a camera over USB, places them on the map
with the phone's location, and develops them, without a cloud or an account.

### What it does

- **Imports from a camera over USB (MTP)**: only what is new, or only the days you pick. Raw and JPEG of a shot
  show as one.
- **Geotags** from the phone's own location record or an exported Google Timeline: GPS tags in a JPEG's EXIF, an
  XMP sidecar for a raw file.
- **Develops raw files**: Sony ARW with the camera's lens corrections, and the DNGs of phones (such as a Galaxy's
  Pro mode) with their shading corrections. JPEGs are edited the same way; a JPEG's original is kept and can be
  restored.
- **Edits**: exposure and tone curves, colour mixer and grading, texture, clarity, dehaze, sharpening and noise
  reduction, filters, cropping and perspective with auto level, spot healing, and masks (linear, radial, brush,
  colour range, tone range, and subject and background found on the phone). Edits stay editable and can be kept
  as named versions.
- **Looks**: 16 built-in filters, your own, Lightroom presets (`.xmp`) and 3D `.cube` LUTs imported (LUTs on
  stills).
- **Keeps a library** by day, with ratings, pick and reject flags, albums, search by Exif, date and place, a
  compare view, a trash, sharing at a size and JPEG quality, and the phone's own photos listed from the folders
  you choose, without ever changing them.
- **Shows photos on a map** (OpenStreetMap), once online maps are turned on.
- Fits phones, foldables, tablets and laptop windows, with keyboard and mouse. English, Korean and Japanese.

### Good to know

- Android 15 (API 35) or later.
- Tried with a Sony ZV-E10 and Galaxy phones on Android 17. Other cameras that offer MTP may work. Raw
  development reads Sony ARW and DNG mosaics, uncompressed or in lossless JPEG. Linear DNGs (a Galaxy's Expert
  RAW) and JPEG XL compressed DNGs cannot be developed yet. Phone DNGs are developed
  without correcting the lens's distortion.
- Everything stays on the phone: no account, no analytics, and the internet only for the optional map, which
  is off until you turn it on. See [`PRIVACY.md`](PRIVACY.md).
- Export is JPEG only, on an 8-bit pipeline and an SDR screen. There is no sky mask yet.
- Subject and background masks, LUTs, colour range masks and the other newest tools have been tested on the JVM
  and are still being judged on phones; see [`docs/DEVICE_CHECKS.md`](docs/DEVICE_CHECKS.md).
- No backup and restore yet: uninstalling the app takes its database (edits, albums, ratings) along. Photos are
  ordinary files in `DCIM/Latent` and stay.
- The APK is signed with the project's own key and is installed from the release page, not from a store. A phone
  updates only to builds signed with the same key.
