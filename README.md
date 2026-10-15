# Latent

An Android app that brings photos in from a camera over USB, places them on the map with the
phone's location, and develops them: raw files included, without a cloud or an account.

*Latent* is named after the latent image: taken, but not yet developed.

## What it does

- **Imports from a camera over USB (MTP)**, only what is new, or only the days you pick.
- **Geotags** photos from the phone's own location record or an exported Google Timeline. JPEGs get
  GPS tags in their EXIF; raw files get an XMP sidecar.
- **Develops raw files** (Sony ARW) with the camera's lens corrections, and edits JPEGs the same way.
- **Edits** with exposure and tone curves, colour mixer and grading, texture, clarity, dehaze,
  sharpening and noise reduction, filters, cropping and perspective, masks (linear, radial, brush)
  and spot healing. Edits stay editable; a JPEG's original is kept and can be restored.
- **Keeps a library** by day, with ratings, albums, raw and JPEG shown as one, a trash, and sharing. It can list
  the phone's own photos too, from the folders you choose, without ever changing them.
- **Shows your photos on a map** (OpenStreetMap), once you turn online maps on.
- Fits phones, foldables, tablets and laptop windows, with keyboard and mouse: arrow keys to turn pages,
  a letter for each editing tool, `+`, `-` and `0` to zoom, `P`, `X`, `U` and `1`–`5` to cull, `J` for clipped tones, `\` to see the original,
  and a right click on a photo for a menu.

The app speaks English, Korean and Japanese, following the phone's language.

It has been used with a Sony ZV-E10 and Galaxy phones on Android 17. Other cameras that offer MTP may
work; raw development currently reads Sony ARW only.

## Privacy

Everything stays on the phone. The app has no account and no analytics, and it uses the internet only for
the optional map, which is off until you turn it on. Photos live as ordinary files in `DCIM/Latent`, where
gallery and backup apps can see them. See the [privacy policy](PRIVACY.md).

## Building

Requires Android 15 (API 35) or later on the device, and JDK 17 or later to build.

```bash
./gradlew assembleDebug
```

Unit and UI tests run on the JVM with Robolectric:

```bash
./gradlew testDebugUnitTest
```

Tests that time real photos on a device live in `app/src/androidTest`. Run them with
`adb shell am instrument`, passing sample files with `-e jpegSample <path> -e arwSample <path>`;
Gradle's `connectedAndroidTest` uninstalls the app afterwards, taking its database with it.

## Notes for contributors

- [`docs/ROADMAP.md`](docs/ROADMAP.md): what comes next, and why in that order.
- [`docs/DEVICE_CHECKS.md`](docs/DEVICE_CHECKS.md): what only a real phone can confirm.
- [`docs/PLAY_LISTING.md`](docs/PLAY_LISTING.md): the Play Store listing, permission declarations and data safety answers.

## License

Licensed under the [Apache License 2.0](LICENSE).

Sony and ZV-E10 are trademarks of Sony Group Corporation; Google Timeline is a feature of Google
LLC. This project is not affiliated with or endorsed by either.
