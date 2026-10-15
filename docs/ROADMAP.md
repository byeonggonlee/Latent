# Roadmap

What to build next, ranked by how often the feature is used in everyday photo editing, how much of it
the app already has, and what it costs. Goal of the app: between Snapseed and Lightroom Mobile, plus
camera sync, all on the phone. Last revised 2026-10-15 (items 1 to 5 and 8 done, 7 for stills; HDR and 10-bit placed).

## Where the app stands, by stage of a typical edit

| Stage | Lightroom Mobile | Snapseed | Latent today |
|---|---|---|---|
| Import | Photos, camera card | Open | USB sync (MTP), phone folders, Quick Share copies |
| **Cull** | Flags (pick, reject), stars, compare | – | Flags, stars, compare, trash |
| Global tone and colour | Light, colour mixer, grading, curves, detail, effects | Tune, curves, white balance, details, vignette, grain | All of these, with raw development |
| Crop and geometry | Crop, upright, perspective | Crop, rotate, perspective | All of these, with auto level |
| **Local edits** | Masks: **subject, sky, background**, linear, radial, brush, colour range, luminance range, healing | Selective, brush, healing | Subject and background (on the phone), linear, radial, brush, colour range, luminance range, healing. **No sky mask yet** |
| **Looks** | Presets (own and imported), profiles, **LUTs** | Looks, HDR, glow, drama | 16 built-in looks, own looks, Lightroom presets and `.cube` LUTs imported (LUTs on stills only) |
| **Export** | Size, quality, format, metadata, watermark | Save, export, share | Share at a size, JPEG quality, place left out, save a copy. **JPEG only** |
| **Organise** | Albums, search, keywords, flags | – | Albums, ratings, versions, search by Exif, date, place |
| **Bit depth, HDR** | 10-bit HEIC and Ultra HDR in and out, HDR display | HDR look (tone mapping, not HDR output) | **8-bit pictures in and out, SDR screen** (inside, light is 16-bit) |
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

## Larger, to be settled first

6. **Sky and subject masks** (on-device segmentation). The most used Lightroom mask, and what makes quick
   local edits quick. *Spike done 2026-10-15, see [`AI_MASKS.md`](AI_MASKS.md):* possible without a network,
   with LiteRT and models shipped in the app (about 9 MB of runtime and 14 to 30 MB of models, no new
   permission; Google's ML Kit and MediaPipe Tasks libraries are ruled out by their telemetry and Play services
   download). Stages: a bake-off on the owner's photos, then subject and background (U2-Netp), sky (EfficientViT
   ADE20K), tap to select (MagicTouch). The owner has answered the questions (2026-10-15): training-data risk
   accepted, bundled models only, subject and background first. Large. **For Opus.**
   *Stage 1 (subject and background) built 2026-10-16, to be judged on a phone:* "+ Subject" and "+ Background"
   in the Masks panel run U²-Netp (converted from the authors' weights, 4.8 MB, in the APK) with LiteRT 2.3.0,
   on the GPU or else the CPU, once per photo and only when tapped; the outline is refined along the picture's
   edges and kept in a file of its own, rendered through the brush mask's path in both renderers, and can be
   painted on with the brush. About +14.5 MB installed on a 64-bit phone. Next: the bake-off on the owner's
   photos (stage 0, which was skipped), then sky (stage 2). Details and provenance in `AI_MASKS.md`.
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
10. **HDR in the viewer.** Show Ultra HDR JPEGs and 10-bit HEIF as HDR (window colour mode and headroom, 16-bit
    float decoding): viewing only, no change to editing. No native code is needed (minimum Android is 15).
    Small to medium.
11. **Keep the gain map, or say it is lost.** Editing and saving a copy of an Ultra HDR photo from the phone drops
    its gain map today and leaves an SDR picture. Either carry the gain map through (it can be edited along, or
    kept for an untouched picture) or tell the person. Medium.
12. **10-bit video with LUTs and grading** (Log, HLG) on Media3 (ExoPlayer, Transformer and its OpenGL effects),
    together with the video stage of item 7 (the parser, `LutStore` and the lookup can be taken over as they are). Large; the one place where 10-bit has real demand, since the ZV-E10
    shoots stills in 8-bit JPEG and raw only.

## Later, or only if asked

- HDR merge, panorama, focus stacking (large; the camera or phone can do these).
- **HDR stills pipeline** (16-bit float throughout, HDR output as Ultra HDR JPEG or 10-bit HEIC): large, and every
  slider, curve and mask assumes SDR 0 to 1 today, so tone mapping has to be designed first. Memory is the first
  wall (a 24 MP float picture is about 190 MB, so strips and tiles). Only worth it once HDR sources are common among
  the owner's photos. Native code (`libavif`, `libjxl`) would come in only to write AVIF or JPEG XL, not for this.
- Video: trim, then LUT and grading.
- Double exposure, text, frames (Snapseed's casual tools).
- Keywords and captions; place names (needs a network service).
- Not planned: generative fill or remove, cloud sync, accounts.

## How the work is split

- Small and medium items: done in the main line, one commit each, tests first, pushed only when they pass.
- Opus (as a subagent, in its own worktree, with the checklist of the item as its brief): the sky and
  subject mask spike, and LUTs. Both touch the renderers and need care with the CPU/GPU agreement.
- Anything only a phone can judge goes to [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md).
