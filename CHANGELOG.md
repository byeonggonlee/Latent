# Changelog

What changed in each stable release. The newest is first; a nightly's notes are the commits since the one before
it (see [`docs/RELEASES.md`](docs/RELEASES.md)). The release workflow publishes the section of the tagged version
as the release notes, so a section is written before the tag is.

## [0.2.0] - unreleased

Smaller improvements after the first release: editing, consistency of the interface, and small additions.

### Added

- **Canon CR2 imports** from cameras and cards, with embedded previews where available. Raw development is
  experimental and limited to the EOS 7D's full-resolution 14-bit RAW files, which open about as bright as the
  camera's own JPEGs. Other Canon bodies and reduced-size
  sRAW/mRAW files can be imported but cannot yet be developed. CR3 is not supported.
- **Find by dates:** besides the last 7 or 30 days and a year, the library can be searched for the days picked on a
  calendar, from one day to any range.

### Changed

- **Editor sliders** read their state at a glance: a changed one has its name in full contrast with a dot after
  it, the thumb is white with a dark edge, and the track is filled from where the setting starts, so *Feather*,
  a LUT's or a filter's amount, the brush and spot sizes and a colour's spread fill from the left rather than from
  their default. Temperature and tint start from **As Shot**, named at their centre, on tracks that run from blue to
  amber and from green to magenta.
- The note that LUTs made for Log video expect Log footage is shown under a chosen Log LUT only, not under the
  looks for everyone who has not imported a LUT.
- Settings with a few exclusive values (tab position, theme, grid size, how long the trash keeps photos, JPEG
  quality) are segmented buttons, and the library's kind filters look like the app's other filter chips instead of
  turning black when chosen.
- **One icon family:** close, more, back, delete, play, the map pin, the arrows and zoom in, and undo, redo, auto
  and the library tab, were filled Material symbols; they are drawn as outlines like the app's other icons. A
  reject button shows the same crossed circle as a rejected photo's badge.
- **Empty screens** show an icon, a sentence and one way on: a library narrowed to nothing offers *Show everything*,
  which takes off the kind, flag, stars and album at once. The map's card says that online maps are off. Secondary
  buttons are tonal rather than outlined, beside one filled button for the main action.
- **Edits keep their look:** an edit saved from now on records which version of the app's rendering it was begun
  with, so a later change to how photos are developed leaves it as it was. Edits saved with 0.1.0 have no such
  record and take 0.2.0's rendering.

### Fixed

- DNG lens correction now reads the standard OpcodeList3 tag. Previously the reader and its synthetic fixtures
  used the same wrong tag, so real files carrying supported distortion metadata did not offer lens correction.

## [0.1.0] - 2026-10-27

The first release. Latent is an Android app that brings photos in from a camera over USB, places them on the map
with the phone's location, and develops them, without a cloud or an account.

### What it does

- **Imports from a camera over USB (MTP)**, or from its card in a card reader or the camera connected as a drive
  (Import tab, "Card or drive"): only what is new, whichever way it came before, or only the days you pick. Raw and
  JPEG of a shot show as one. A camera file that only looks like a photo already in the library (the same name and
  size, after a reinstall or with photos copied from another phone) is copied over and compared before it is left
  out, so a different photo is never skipped; with more than 20 such files or 2 GB of them, the app asks first
  ("Copy over and compare").
- **Geotags** from the phone's own location record or an exported Google Timeline: GPS tags in a JPEG's EXIF, an
  XMP sidecar for a raw file. A Timeline stay longer than a week, or one that ends before it begins, is left out
  and counted in the import's message.
- **Develops raw files**: Sony ARW with the camera's lens corrections (also from older bodies, such as an ILCE-7M2,
  that keep their white balance only in Sony's encrypted private data), and the DNGs of phones (such as a Galaxy's
  Pro mode) with their shading corrections, and with colours matched to the light of the shot between the two a
  phone calibrates for (tungsten and daylight, say). A DNG that describes its lens's distortion (DNG's
  WarpRectilinear) gets the lens correction switch a Sony file has. JPEGs are edited the same way; a JPEG's original
  is kept and can be restored.
- **Edits**: exposure and tone curves, colour mixer and grading, texture, clarity, dehaze, sharpening and noise
  reduction, filters, cropping and perspective with auto level, spot healing, and masks (linear, radial, brush,
  colour range, tone range, and the subject, the background and the sky, found on the phone). A colour mask's
  colour can be picked from the photo itself, with a magnifier; the eight fixed swatches can be hidden. Edits stay
  editable and can be kept as named versions. Saving happens in the background: an edit that could not be saved
  stays marked on its thumbnail and in the viewer until it is tried again or given up, and is never lost unseen.
- **Looks**: 16 built-in filters, your own, Lightroom presets (`.xmp`) and 3D `.cube` LUTs imported (LUTs on
  stills).
- **Shows HDR photos as HDR** in the viewer, on a phone with an HDR screen: an Ultra HDR JPEG with its gain map, or
  a 10-bit HEIF in HLG or PQ. Editing stays SDR: the editor says so when an Ultra HDR photo opens, and the edit is
  saved without the gain map. The original keeps it.
- **Three tabs**: Library, Import (camera, card or drive, and the location record) and Map. Thumbnails say in
  fixed corners what kind of file a photo is, whether it is picked, rejected or rated, and whether it is edited.
- **Keeps a library** by day, with ratings, pick and reject flags, albums, search by Exif, date and place, a
  compare view, a trash, sharing at a size and JPEG quality (with the place left out if you choose; a HEIF picture
  then goes out as a JPEG made from it), and the phone's own photos listed from the folders you choose, without
  ever changing them.
- **Backs up and restores** what lives only in the app's database (albums, ratings and flags, edits and versions,
  your filters and LUTs, masks, the timeline) into one zip you keep, for a new phone or a reinstall. A photo that
  moved is found by its name and size only if it was taken at the same moment, so a backup's edit never lands on
  another photo that took the same file name a year later.
- **Shows photos on a map** (OpenStreetMap), once online maps are turned on. Its credit opens OpenStreetMap's
  copyright page.
- Latent's own colours (an amber accent and neutral greys, the same on every phone) in light and dark, and a dark,
  neutral workspace around photos in the viewer and the editor whichever theme is chosen.
- Fits phones, foldables, tablets and laptop windows, with keyboard and mouse. English, Korean and Japanese.

### Good to know

- Android 15 (API 35) or later, on a phone with a 64-bit ARM processor (`arm64-v8a`), as every recent phone has:
  32-bit phones and x86 Chromebooks cannot install it. The APK is about 40 MB, half of it the sky's model.
- The app is `com.latentimage.latent`. Nightlies until 22 October 2026 were `com.byeonggon.latent`, which Android
  takes for another app: move across once with a backup and its restore (see
  [`docs/RELEASES.md`](https://github.com/byeonggonlee/Latent/blob/main/docs/RELEASES.md), "Reinstalling a phone").
  The new app takes over the originals the old one kept of the JPEGs it edited.
- Tried with a Sony ZV-E10 and Galaxy phones on Android 17, and with public sample files of other cameras on an
  Android 16 emulator. Other cameras that offer MTP may work. Raw
  development reads Sony ARW, DNG mosaics and linear DNGs (a Galaxy's Pro mode and Expert RAW, and a Pixel's, which
  shows as one with its cover JPEG; not tried with a Pixel yet), uncompressed, in lossless JPEG or in JPEG XL. The
  Galaxy files tried so far carry no distortion correction, so theirs is not corrected.
- Everything stays on the phone: no account, no analytics, and the internet only for the optional map, which
  is off until you turn it on and is told the app's name and version. See
  [`PRIVACY.md`](https://github.com/byeonggonlee/Latent/blob/main/PRIVACY.md).
- Export is JPEG only, from an 8-bit pipeline: an edit is SDR, even of a photo the viewer shows as HDR.
- Moving a photo to the trash, saving an edit, exporting and writing a place in are done one at a time, and a new
  file takes its name only once it is whole, so a photo is not left half-written when the app is stopped.
- The sky mask, the HDR viewer, the card import, the comparison of look-alike files, DNG distortion correction,
  subject and background masks, LUTs, colour range masks and the other newest tools have been tested on the JVM and
  are still being judged on phones; see
  [`docs/DEVICE_CHECKS.md`](https://github.com/byeonggonlee/Latent/blob/main/docs/DEVICE_CHECKS.md).
- Uninstalling the app takes its database (edits, albums, ratings) along; photos are ordinary files in
  `DCIM/Latent` and stay. Back up first (Settings, Backup and restore) to bring the rest back.
- The APK on the release page is signed with the project's own key; the app on Google Play is signed by Google.
  A phone updates only to builds signed with the same key, so one installed from the release page and one from
  Play do not replace each other: move across once with a backup and its restore. Raw files of a camera Latent
  cannot develop yet still show the picture the camera stored in them, and the editor says why it cannot open them.

### Fixed since the nightlies

For those who tried a nightly before this release:

- Mirrored photos (Exif orientation 2, 4, 5 or 7) open, edit and save the way their thumbnails show them.
- Closing the viewer while a raw file is being developed no longer holds the screen, and moving on to the next
  photo, or closing the editor, stops the development of the one before.
- A JPEG XL raw file that is cut short fails at once instead of loading for ever, and a damaged preview inside a
  JPEG no longer breaks its thumbnail.
- Saving a crop, a healing spot or a mask on a JPEG whose Exif block points outside itself no longer crashes the
  app; the saved picture leaves that block out.
- Importing a Timeline with an impossible date, or a stay that spans centuries, no longer crashes or fills the
  phone.
- A healing spot whose source is dragged over it copies the picture as it was, without smearing.
- Sharing a HEIF picture with "Leave out the place when sharing" on no longer fails.
- A camera file of the same name and size as a library photo of unknown origin is no longer skipped without
  being read, and a restore no longer gives a missing photo's edit, rating and albums to another one of the same
  name and size.
