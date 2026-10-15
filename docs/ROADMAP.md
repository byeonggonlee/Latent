# Roadmap

What to build next, ranked by how often the feature is used in everyday photo editing, how much of it
the app already has, and what it costs. Goal of the app: between Snapseed and Lightroom Mobile, plus
camera sync, all on the phone. Last revised 2026-10-15 (export options done).

## Where the app stands, by stage of a typical edit

| Stage | Lightroom Mobile | Snapseed | Latent today |
|---|---|---|---|
| Import | Photos, camera card | Open | USB sync (MTP), phone folders, Quick Share copies |
| **Cull** | Flags (pick, reject), stars, compare | – | Flags, stars, trash. **No compare** |
| Global tone and colour | Light, colour mixer, grading, curves, detail, effects | Tune, curves, white balance, details, vignette, grain | All of these, with raw development |
| Crop and geometry | Crop, upright, perspective | Crop, rotate, perspective | All of these, with auto level |
| **Local edits** | Masks: **subject, sky, background**, linear, radial, brush, **colour range**, luminance range, healing | Selective, brush, healing | Linear, radial, brush, luminance range, healing. **No AI masks, no colour range** |
| **Looks** | Presets (own and **imported**), profiles, **LUTs** | Looks, HDR, glow, drama | 16 built-in looks, own looks. **No import** |
| **Export** | Size, quality, format, metadata, watermark | Save, export, share | Share at a size, save a copy. **No format, quality or metadata choice** |
| **Organise** | Albums, search, keywords, flags | – | Albums, ratings, versions. **No search** |
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
5. **Import Lightroom presets (`.xmp`)** and share one's own looks as files. Presets are the habit of
   Lightroom Mobile users; the settings map onto ours (exposure, contrast, highlights, shadows, whites,
   blacks, texture, clarity, dehaze, vibrance, saturation, HSL, colour grading, curves, vignette, grain).
   What does not map is skipped and said so. Medium.

## Larger, to be settled first

6. **Sky and subject masks** (on-device segmentation). The most used Lightroom mask, and what makes quick
   local edits quick. Needs a model (size, licence, speed on the phone, no network): start with a spike that
   answers those, then decide. Large. **For Opus.**
7. **LUTs (`.cube`)** as looks with an intensity slider, first for stills, then the same stage for video
   (Log footage). A 3D lookup with trilinear interpolation as a late colour stage, in both the CPU and the
   GPU renderer, with an import that checks the file and tells what it could not read. Also the base for
   Sony S-Log and Creative Look LUTs. Medium to large. **For Opus**, as the owner suggested.
8. **Compare and survey view** (two or four photos side by side, zoomed together) for choosing between
   similar shots. Medium.
9. **Point colour and colour calibration.** Used by those who grade by eye; the colour mixer covers most
   of it. Medium.

## Later, or only if asked

- HDR merge, panorama, focus stacking (large; the camera or phone can do these).
- Video: trim, then LUT and grading.
- Double exposure, text, frames (Snapseed's casual tools).
- Keywords and captions; place names (needs a network service).
- Not planned: generative fill or remove, cloud sync, accounts.

## How the work is split

- Small and medium items: done in the main line, one commit each, tests first, pushed only when they pass.
- Opus (as a subagent, in its own worktree, with the checklist of the item as its brief): the sky and
  subject mask spike, and LUTs. Both touch the renderers and need care with the CPU/GPU agreement.
- Anything only a phone can judge goes to [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md).
