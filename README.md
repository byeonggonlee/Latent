# Latent

An Android app that brings photos in from a camera over USB, places them on the map with the
phone's location, and develops them: raw files included, without a cloud or an account.

*Latent* is named after the latent image: taken, but not yet developed.

## What it does

- **Imports from a camera over USB (MTP)**, or from its card in a card reader or the camera connected as a drive:
  only what is new, whichever way it came before, or only the days you pick. A file that only looks like one already
  in the library is copied over and compared before it is left out.
- **Geotags** photos from the phone's own location record or an exported Google Timeline. JPEGs get
  GPS tags in their EXIF; raw files get an XMP sidecar.
- **Develops raw files**: Sony ARW with the camera's lens corrections, and the DNGs of phones such as a Galaxy's Pro
  mode with their shading corrections, and their lens's distortion where the file describes it. JPEGs are edited the
  same way.
- **Edits** with exposure and tone curves, colour mixer and grading, texture, clarity, dehaze,
  sharpening and noise reduction, filters (Lightroom presets and `.cube` LUTs can be imported), cropping
  and perspective, masks (linear, radial, brush, colour range, tone range) and spot healing. Edits stay editable; a JPEG's original
  is kept and can be restored.
- **Finds the subject** of a photo, its background or its sky, for a mask in one tap: worked out on the phone by
  models inside the app (U²-Netp for the subject, EfficientViT-Seg for the sky, on LiteRT), with nothing sent
  anywhere.
- **Shows HDR photos as HDR** in the viewer (an Ultra HDR JPEG with its gain map, a 10-bit HEIF in HLG or PQ), on
  a phone with an HDR screen. Editing stays SDR, and the editor says so for an Ultra HDR photo.
- **Keeps a library** by day, with ratings, albums, raw and JPEG shown as one, a trash, and sharing (with the place
  left out, if you choose). It can list
  the phone's own photos too, from the folders you choose, without ever changing them.
- **Shows your photos on a map** (OpenStreetMap), once you turn online maps on.
- **Backs up** albums, picks and stars, edits and versions, own filters, LUTs and the location history into one file
  that you keep, and restores it after a reinstall or on another phone ([format](docs/BACKUP_FORMAT.md)).
- Fits phones, foldables, tablets and laptop windows, with keyboard and mouse: arrow keys to turn pages,
  a letter for each editing tool, `+`, `-` and `0` to zoom, `P`, `X`, `U` and `1`–`5` to cull, `J` for clipped tones, `\` to see the original,
  and a right click on a photo for a menu.

The app speaks English, Korean and Japanese, following the phone's language.

It has been used with a Sony ZV-E10 and Galaxy phones on Android 17. Other cameras that offer MTP may
work. Raw development reads Sony ARW, DNG mosaics and linear DNGs, uncompressed, in lossless JPEG or in JPEG XL:
a Galaxy's Pro mode and Expert RAW files among them.

## Privacy

Everything stays on the phone. The app has no account and no analytics, and it uses the internet only for
the optional map, which is off until you turn it on. Photos live as ordinary files in `DCIM/Latent`, where
gallery and backup apps can see them. See the [privacy policy](PRIVACY.md).

## Building

Requires Android 15 (API 35) or later on the device, on 64-bit ARM, and JDK 17 or later to build (CI uses 21).

```bash
./gradlew assembleDebug
```

Every build carries the native code of 64-bit ARM only (`arm64-v8a`); an emulator of another kind takes
`-PlatentAbis=x86_64`.

Unit and UI tests run on the JVM with Robolectric:

```bash
./gradlew testDebugUnitTest
```

### On GitHub

[![Build](https://github.com/byeonggonlee/Latent/actions/workflows/build.yml/badge.svg)](https://github.com/byeonggonlee/Latent/actions/workflows/build.yml)

The workflow in `.github/workflows/build.yml` runs on every push to `main`, on every pull request, and on request
("Run workflow" on the Actions tab, also from the GitHub app). It runs the tests and lint, compiles the tests that run
on a phone, checks the GPU shaders with glslang, the committed Room schema and the release build against
[`PRIVACY.md`](PRIVACY.md), and builds the release APK. It takes 3 to 8 minutes. The APK is kept for 14 days as the
`latent-release-apk` artifact (downloading needs a GitHub login), the test and lint reports for 7 days.

That APK is signed with the runner's own debug key, which no phone's install has: it installs on a phone without
the app, but not over a nightly or a build made on another computer (uninstall first, which takes the app's database
along; back it up first under Settings, Backup and restore, and restore it afterwards).

The nightly (`nightly.yml`) and stable (`release.yml`) builds are signed with the release key, kept in repository
secrets, and published as releases for the phones. Each builds and signs in a job that may only read the repository,
and publishes in a job of its own, which may write to it but never sees the signing secrets. They, the version
numbers and signing are described in [`docs/RELEASES.md`](docs/RELEASES.md).

Tests that time real photos on a device live in `app/src/androidTest`. Install the debug build and its tests
(`./gradlew installDebug installDebugAndroidTest`), then run one with `adb shell am instrument`, passing sample files
with `-e jpegSample <path> -e arwSample <path>` (or `-e videoSample <path>`):

```bash
adb shell am instrument -w -e class com.byeonggon.latent.enhance.EditOnDeviceTest -e jpegSample <path> \
  com.latentimage.latent.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The debug build is another app, "Latent Debug" (`com.latentimage.latent.debug`), with its own database and its own
library folder, `DCIM/Latent Debug`: it installs beside a nightly and leaves the nightly's photos and data alone.
Gradle's `connectedAndroidTest` uninstalls it afterwards, taking its database with it. See
[`docs/DEVICE_CHECKS.md`](docs/DEVICE_CHECKS.md), section 9.

## Notes for contributors

- [`CHANGELOG.md`](CHANGELOG.md): what each release changed.
- [`docs/RELEASES.md`](docs/RELEASES.md): the builds, the version numbers, signing, and how a release is made.
- [`docs/ROADMAP.md`](docs/ROADMAP.md): what comes next, and why in that order.
- [`docs/DEVICE_CHECKS.md`](docs/DEVICE_CHECKS.md): what only a real phone can confirm, and
  [`docs/SMOKE_TEST.md`](docs/SMOKE_TEST.md), the short pass after each nightly.
- [`docs/BACKUP_FORMAT.md`](docs/BACKUP_FORMAT.md) and [`docs/AI_MASKS.md`](docs/AI_MASKS.md): the backup file, and
  the mask models and where they come from.
- [`AGENTS.md`](AGENTS.md) and [`docs/HANDOFF.md`](docs/HANDOFF.md): how the agents that work on Latent share it.
- [`docs/PLAY_LISTING.md`](docs/PLAY_LISTING.md): the Play Store listing, permission declarations and data safety answers.

## License

Licensed under the [Apache License 2.0](LICENSE).

Sony and ZV-E10 are trademarks of Sony Group Corporation; Google Timeline is a feature of Google
LLC. This project is not affiliated with or endorsed by either.
