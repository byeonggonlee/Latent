# Roadmap

What to build next, ranked by how often the feature is used in everyday photo editing, how much of it
the app already has, and what it costs. Goal of the app: between Snapseed and Lightroom Mobile, plus
camera sync, all on the phone. Last revised 2026-10-22 (items 1 to 5, 8 and 10 done, 7 for stills, 6 up to the sky,
the telling half of 11; the card reader import and DNG distortion done).

## Where the app stands, by stage of a typical edit

| Stage | Lightroom Mobile | Snapseed | Latent today |
|---|---|---|---|
| Import | Photos, camera card | Open | USB sync (MTP), the camera's card (card reader or Mass Storage), phone folders, Quick Share copies |
| **Cull** | Flags (pick, reject), stars, compare | – | Flags, stars, compare, trash |
| Global tone and colour | Light, colour mixer, grading, curves, detail, effects | Tune, curves, white balance, details, vignette, grain | All of these, with raw development |
| Crop and geometry | Crop, upright, perspective | Crop, rotate, perspective | All of these, with auto level |
| **Local edits** | Masks: **subject, sky, background**, linear, radial, brush, colour range, luminance range, healing | Selective, brush, healing | Subject, background and sky (on the phone), linear, radial, brush, colour range, luminance range, healing. **No tap to select yet** |
| **Looks** | Presets (own and imported), profiles, **LUTs** | Looks, HDR, glow, drama | 16 built-in looks, own looks, Lightroom presets and `.cube` LUTs imported (LUTs on stills only) |
| **Export** | Size, quality, format, metadata, watermark | Save, export, share | Share at a size, JPEG quality, place left out, save a copy. **JPEG only** |
| **Organise** | Albums, search, keywords, flags | – | Albums, ratings, versions, search by Exif, date, place |
| **Bit depth, HDR** | 10-bit HEIC and Ultra HDR in and out, HDR display | HDR look (tone mapping, not HDR output) | **8-bit pictures in and out; edits on an SDR screen** (inside, light is kept in 14-bit levels). The viewer shows Ultra HDR and 10-bit HEIF photos as HDR |
| Beyond stills | HDR merge, panorama | Double exposure, text, frames | – |

## Next, in this order

Each is small or medium, works offline, and needs no new permission.

1. ~~**Pick and reject flags** (cull).~~ *Done 2026-10-15.* The step every shoot starts with; Lightroom users do it with `P`/`X`
   before touching a slider. Flags in the viewer and the selection bar, keys `P`, `X`, `U`, a filter, and
   "trash the rejected" (to the trash, so it can be undone). Small.
2. ~~**Export options.**~~ *Done 2026-10-15, without PNG:* JPEG quality (80/90/95/100) and "leave out the place when
   sharing", off by default (the owner wants places kept in photos), both in Settings; sizes were already in the
   Share menu. PNG left out: it would lose the camera's Exif, which is the point of a photo. A custom long side
   could come later. Small.
3. ~~**Find photos.**~~ *Done 2026-10-15:* by file name, camera, lens, ISO range, focal length (35 mm terms), when (7 and 30
   days, a year), with or without a place, edited or not; flags, stars, kinds and albums are filters of their own.
   The Exif is read once per file in the background into the database (`ExifIndex`). Not done: place names (a
   network service), a range of dates picked on a calendar, shutter speed and aperture.
4. ~~**Colour range mask.**~~ *Done 2026-10-15, to be judged on a phone:* "+ Colour" is a mask of the whole picture held to a
   hue (eight swatches, the mixer's) with a range slider; any mask can be held to one colour the same way, like the
   tone range. Picked from swatches, not yet by tapping the picture. Grays are left alone. CPU and shader versions
   are written to agree; only a phone can check the shader.
5. ~~**Import Lightroom presets (`.xmp`).**~~ *Done 2026-10-15 (import only):* the settings that map are taken (exposure to
   dehaze, vibrance, saturation, relative white balance, the eight colour bands, colour grading, point curves,
   vignette, grain, sharpening and noise reduction), the rest is skipped and said so. Not done: sharing one's own
   looks as `.xmp` files (the reverse mapping; Lightroom's reading of it cannot be tried here), `.lrtemplate`.

- ~~**Import from a card reader**~~ or a camera in Mass Storage mode, for those who cannot or do not use MTP. *Done
  2026-10-22 ([PR #13](https://github.com/byeonggonlee/Latent/pull/13)), to be tried with a card reader:* a folder
  picked once in the system's dialog ("Card or drive" on the Camera tab), imported as a sync from the camera is (what
  was imported is skipped, either way, dates, places). A file that only looks imported is copied over and compared
  first; more than 20 of them, or 2 GB, wait for "Copy over and compare"
  ([PR #26](https://github.com/byeonggonlee/Latent/pull/26)). No new permission.
- **Phone raw files (DNG)**, *stage 1 built 2026-10-19, to be judged on a phone:* Bayer DNGs (a Galaxy's Pro mode,
  Android's own writer, Adobe's converter), uncompressed or in lossless JPEG, with black and white levels,
  linearization, the daylight colour matrix with the camera's calibration, the as-shot white balance, the default
  crop and the gain maps (lens shading); linear DNGs, as a Galaxy writes in Pro mode (lossless JPEG) and in Expert
  RAW (JPEG XL, read by jxlatte in `third_party/`), with a positive `BaselineExposure` applied as far as white.
  Since done (2026-10-21): the colour matrix interpolated between the two illuminants (pull request #9), the editor's
  lens correction chip hidden for files without a lens profile, and a Pixel's `.RAW-02.ORIGINAL.dng` shown as one
  with its `.RAW-01.COVER.jpg`. Since done (2026-10-22): the lens's distortion from OpcodeList3 (WarpRectilinear,
  [PR #17](https://github.com/byeonggonlee/Latent/pull/17)), with the lens correction chip for the files that carry
  it; no Galaxy file tried so far does. Next: what is left of a `BaselineExposure` that highlights stopped short in
  the tone stage's exposure (with care not to add to the automatic correction twice), only if a phone's file comes
  out darker than its JPEG. Galaxy Log video belongs with item 12.

## Larger, to be settled first

6. **Sky and subject masks** (on-device segmentation). The most used Lightroom mask, and what makes quick
   local edits quick. *Spike done 2026-10-15, see [`AI_MASKS.md`](AI_MASKS.md):* possible without a network,
   with LiteRT and models shipped in the app (about 9 MB of runtime and 14 to 30 MB of models, no new
   permission; Google's ML Kit and MediaPipe Tasks libraries are ruled out by their telemetry and Play services
   download). Stages: a bake-off on the owner's photos, then subject and background (U2-Netp), sky (EfficientViT
   ADE20K), tap to select (MagicTouch). The owner has answered the questions (2026-10-15): training-data risk
   accepted, bundled models only, subject and background first. Large. **For Opus.**
   *Stage 1 (subject and background) built 2026-10-16, found fine on the owner's phone 2026-10-21:* "+ Subject" and "+ Background"
   in the Masks panel run U²-Netp (converted from the authors' weights, 4.8 MB, in the APK) with LiteRT 2.3.0,
   on the GPU or else the CPU, once per photo and only when tapped; the outline is refined along the picture's
   edges and kept in a file of its own, rendered through the brush mask's path in both renderers, and can be
   painted on with the brush. About +14.5 MB installed on a 64-bit phone. The bake-off on
   the owner's photos (stage 0) was skipped. Details and provenance in `AI_MASKS.md`.
   *Stage 2 (sky) built 2026-10-22 ([PR #29](https://github.com/byeonggonlee/Latent/pull/29)), to be judged on a
   phone:* "+ Sky" runs EfficientViT-Seg B1 (ADE20K, 19.5 MB
   in the APK) beside the subject's model, the sky's probability refined along the picture's edges as the subject's
   outline is. Next: tap to select (stage 3).
7. **LUTs (`.cube`)** as looks with an intensity slider. *Stills done 2026-10-15, to be judged on a phone;
   the same stage for video (Log footage) is next, with item 12.* "Import LUT…" in the filter panel reads
   Adobe's and Resolve's 3D `.cube` files (2 to 65 points a side, `DOMAIN_MIN`/`DOMAIN_MAX`, up to 20 MB) and
   says why a file cannot be used, with the line. 1D LUTs, alone or as a shaper in front of a 3D one, are
   refused: the curves do what a 1D LUT does, and a shaper is mostly for Log video. The table is kept in a
   file of its own (`files/luts/<digest>.lut`, plain floats, read once and held at hand); an edit names it
   and an intensity, so raw files and phone photos keep it as a setting and library JPEGs have it baked in.
   A LUT taken off the list stays on disk while a photo, version or filter uses it; one whose file is gone
   renders as no LUT. LUTs sit in a row of their own under the filters, chosen apart from them. The stage is
   a 3D lookup with trilinear interpolation on the colours as shown (0 to 1), after the mixer and grading and
   before vignette and grain, written the same way for the CPU and the GPU renderer. A LUT made for S-Log3
   (Sony's, to Rec.709) expects a Log picture, so it looks wrong on a JPEG; the panel says so. Not done:
   tetrahedral interpolation, 1D LUTs, choosing a LUT inside a mask, saving a look as a `.cube`, video. The
   video stage is also where 10-bit starts to matter (item 12).
8. ~~**Compare and survey view.**~~ *Done 2026-10-15:* two to four selected photos side by side, zoomed together, with
   pick, reject and stars under each. Not done: a survey of many small photos, edited versions shown rendered (the
   file as it is), raw files at full size.
9. **Point colour and colour calibration.** Used by those who grade by eye. The colour mixer (eight bands) and the
   colour range mask (any hue, with a range, and its own exposure, saturation, temperature and tint) cover most of
   it; what is left is a hue shift on a hue of one's choosing, and calibration of the primaries. Held back until
   someone misses it. Medium.
10. ~~**HDR in the viewer.**~~ Show Ultra HDR JPEGs and 10-bit HEIF as HDR (window colour mode and headroom, 16-bit
    float decoding): viewing only, no change to editing. No native code is needed (minimum Android is 15).
    Small to medium. *Done 2026-10-22 ([PR #28](https://github.com/byeonggonlee/Latent/pull/28)), to be judged on a
    phone:* the photo in front, unedited and told HDR by its
    header (an Ultra HDR gain map, or a HEIF's HLG or PQ colour space), is decoded with the system's ImageDecoder,
    which keeps its gain map or ten bits, and the window turns to HDR colour mode while it is shown. Headroom is the
    system's; edits and thumbnails stay SDR.
11. **Keep the gain map, or say it is lost.** Editing and saving a copy of an Ultra HDR photo from the phone drops
    its gain map today and leaves an SDR picture. Either carry the gain map through (it can be edited along, or
    kept for an untouched picture) or tell the person. Medium. *Telling done 2026-10-22
    ([PR #27](https://github.com/byeonggonlee/Latent/pull/27)), to be seen on a phone:* the
    editor says so once as such a photo opens (Ultra HDR's gain map XMP, or ISO 21496-1's mark, read from the JPEG's
    header), and the edit is saved without one: a tone edit no longer carries the original's gain map along, made for
    tones it changed. The original keeps its gain map. Carrying it through, edited along, is left for later.
12. **10-bit video with LUTs and grading** (Log, HLG) on Media3 (ExoPlayer, Transformer and its OpenGL effects),
    together with the video stage of item 7 (the parser, `LutStore` and the lookup can be taken over as they are). Large; the one place where 10-bit has real demand, since the ZV-E10
    shoots stills in 8-bit JPEG and raw only.

## Later, or only if asked

- HDR merge, panorama, focus stacking (large; the camera or phone can do these).
- **HDR stills pipeline** (16-bit float throughout, HDR output as Ultra HDR JPEG or 10-bit HEIC): large, and every
  slider, curve and mask assumes SDR 0 to 1 today, so tone mapping has to be designed first. Memory is the first
  wall (a 24 MP float picture is about 190 MB, so strips and tiles). Only worth it once HDR sources are common among
  the owner's photos. Native code (`libavif`, `libjxl`) would come in only to write AVIF or JPEG XL, not for this.
  The precision goal until then is the camera's look, not colorimetry: see
  [`architecture/decisions.md`](architecture/decisions.md) (2026-10-21).
- Video: trim, then LUT and grading.
- Double exposure, text, frames (Snapseed's casual tools).
- Keywords and captions; place names (needs a network service).
- Not planned: generative fill or remove, cloud sync, accounts.

## Outside the ranking

- ~~**Backup and restore**~~ of what lives only in the database, into one file the person keeps. *Done 2026-10-20, to be
  tried on the phones:* the way across a reinstall, a new phone, the release key and the package name
  ([`BACKUP_FORMAT.md`](BACKUP_FORMAT.md)). Not done: the trash (its files stay in `DCIM/Latent/.trash`, unseen after
  a reinstall), a backup made by itself now and then into a folder the person chose, a file with a password.

## How the work is split

- Who works on what (Claude Code, Codex, Antigravity), and which changes go through a reviewed pull request, is in
  [`AGENTS.md`](../AGENTS.md) and [`HANDOFF.md`](HANDOFF.md) (since 2026-10-21).
- Small and medium items: one commit each, tests first, pushed only when they pass; anything that touches colour,
  the renderers, the backup or the library's files goes through review.
- Anything only a phone can judge goes to [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md).
