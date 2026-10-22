# Checks that need a real phone

Everything here passes on the JVM (Robolectric), but only a phone can say how it feels, looks and
performs. Tick them off as they are tried; add what a try turns up. Last updated 2026-10-20.

## First run on a phone that has the library folder (S24 Ultra, after the move)

- [ ] Open Latent. The setup card says "사진 보관함 설정" and asks for photo access, then the folder.
- [ ] "폴더 선택" opens the picker on `DCIM/Latent`; "이 폴더 사용" is accepted, and a wrong folder is refused with a message.
- [ ] The 140 files in `DCIM/Latent` are listed (70 shots, raw and JPEG stacked). The two with a kept original in `.originals` show as edited and can be put back.
- [ ] Nothing was lost: 210 files in `DCIM/Latent` before and after.
- [ ] Same on the S26: a fresh install, no photos, first-run card, then a sync.

## Settings

- [ ] Library menu → Settings. Tab position (Automatic, Left, Right, Bottom) on a wide window: landscape on a phone, an unfolded Fold. Right and bottom suit one hand or the other; a narrow screen keeps the tabs at the bottom whatever is chosen.
- [ ] Language → Change opens the phone's per-app language list with English, 한국어 and 日本語; the app follows.
- [ ] Light or dark (like the phone, light, dark): the app follows at once, status and navigation bar icons stay readable in each; dark in a light phone and light in a dark one in particular. Colours come from the wallpaper.
- [ ] Size of the pictures in the library (small, medium, large) changes the grid at once, on the phone and in a wide window.
- [ ] Trash: 7, 30 or 90 days. Choosing fewer days deletes older trash the next time the library opens (not tried on a real trash).
- [ ] Camera time zone: "Change" lists every zone (west to east), typing a city narrows the list (also in Korean or Japanese), and the first entry is "Same as the phone". Choose a zone other than the phone's, sync or adopt a photo from the camera, and check its time in the viewer and where the Location matching puts it. "Read the times of photos in the library again" changes the times of the photos already there; then "Match items without a location again" on the Location tab.
- [x] The ZV-E10 writes `OffsetTimeOriginal`, `OffsetTimeDigitized` and `OffsetTime` (+09:00) in its raw files too (seen in the details, 2026-10-15), so the zone comes from the file and the camera time zone setting is only for files with none.
- [ ] Does a video's creation time (UTC in the file) agree with the stills?
- [ ] "Every EXIF value": off, the photo's details show only camera, lens and exposure in one line; on, the sheet under the photo (phone) or the panel beside it (wide window) also lists the values in parts, scrolling on a phone. Compare a ZV-E10 JPEG and raw file with `exiftool`: values as recorded (codes such as Flash 16 are not spelled out), no row missing that matters, nothing odd on a phone's own photos. Sony's MakerNote is not listed.
- [ ] Sharing and saving: JPEG quality (80, 90, 95, 100) changes the size of what Share makes at a reduced size and of copies saved from raw files and phone photos (compare in a file manager). "Leave out the place when sharing" on: share a located photo to another app (Messages, a mail app) and look at its details there, or open the copy in a file manager / `exiftool`: no GPS. It cleans EXIF GPS tags only; a place written in a JPEG's XMP block is not looked for. Off, shares keep the place as before. A video and a raw file shared as they are are not changed either way.
- [ ] Online maps switch: turning it on asks first, off does not; Photos on this phone opens the folder question in the library.

## Backup and restore

Nothing of this has run on a phone yet; the format is in [`BACKUP_FORMAT.md`](BACKUP_FORMAT.md). None of these checks
needs the app uninstalled or its data cleared.

- [ ] S24, Settings → 백업과 복원 → 백업…: the system's save dialog suggests `latent-backup-YYYYMMDD-HHMM.zip`; save it
      in Downloads. Note how long it takes, what the progress line says on the way, the message's counts (photos,
      albums, versions, my filters, LUTs, masks, timeline places, camera imports) against what the app shows, and the
      **file's size** in a file manager. Open it on a computer: `manifest.json` first in a text editor, the entries
      listed in BACKUP_FORMAT, no photo or thumbnail in it.
- [ ] Restore that file onto the **same phone**, clearing nothing: the summary should find every photo (N of N, none
      left out), say 0 replaced, and after confirming change nothing visible; a second restore of the same file the same
      (versions, filters, places added: 0).
- [ ] Then give one photo another rating, take one out of an album, delete a filter of one's own, and restore again: the
      summary counts the rating as replaced; afterwards the rating is the backup's, the photo is back in the album, the
      filter is back. Photos added to albums after the backup stay in them.
- [ ] Cancel a restore while the timeline goes in (a long timeline makes this easy): the message says nothing was taken
      over, and albums, versions and the place count on the Location tab are as before.
- [ ] Restore the S24's file on the **second phone** (S26). Without copying any photos over, the S24's photos are listed
      as not found and only the rest (filters, LUTs, the timeline, camera imports, settings) comes in. If the S26 is to
      have some of the S24's library, copy a day's folder of `DCIM/Latent` (with the same folder under the hidden
      `.originals`) over first: photos found and the names of those not found make sense; raw edits,
      edited library JPEGs ("Back to original" offered, editor opens with the edit's sliders), phone photos' edits, LUTs
      and subject masks render as on the S24 (a missing mask outline would say so in the editor); albums and versions are
      there; the Location tab's place count equals the S24's.
- [ ] On the S26, photos of `DCIM/Camera` on the S24 are not there: they are listed as not found, the message points to
      "폰에서 지운 파일" under Camera, and nothing fails.
- [ ] After the restore on the S26, connect the ZV-E10 and sync: nothing that was imported on the S24 is downloaded
      again (logcat `CameraSync`: "… to import" counts only new files), also with the phone set to another time zone.
- [ ] Online maps on on the S24, off on the S26: after the restore they are still off, and the message says so. With
      them on on the S26, they stay on.
- [ ] A backup saved to and restored from Google Drive (or another cloud folder in the file dialog): does the save work,
      does the restore read it (it is copied into the app first), how long for a large file? Does cancelling a backup to
      Drive delete the unfinished file, or say that it could not?
- [ ] Big timeline: back up and restore with the real one (count on the Location tab, about how many years). Time of
      each, the file's size (the JVM test makes 150,000 places into 1.6 MB), and whether the app stays smooth meanwhile.
- [ ] Rotate the phone and leave Settings while a restore runs: it carries on, and its progress and message are there
      when Settings is opened again.
- [ ] Japanese and English: the section and the summary read well and nothing is cut off in the dialog.
- [ ] The move to `com.latentimage.latent` (RELEASES.md, "Reinstalling a phone"): the new app installs beside the old
      one, takes `DCIM/Latent` again and its restore finds every photo; once the old app is uninstalled, every file in
      `DCIM/Latent` is still there and the new app can still edit, rate, geotag and delete the photos the old one made.
      A JPEG the old app edited: the new one shows it as edited, its editor starts from the original (not from the
      edited picture), a new edit saves, and "Remove edit" gives the original back. In a file manager, nothing named
      `…-copying.…` or `…-copied.…` is left in `DCIM/Latent/.originals`.

## Languages

- [ ] Set the phone to Japanese: no text is cut off or English where Japanese should be (longest: the setup card, the Map tab's explanation, the editor's panel tabs), and the wording reads naturally to a Japanese speaker (the translation was written without one checking it; the Lightroom terms for the sliders are used).

## Foldables

- [ ] Galaxy Z Fold 8, unfolded, in portrait and in landscape, and folded: nothing (top bar buttons, the side panel, the map's menu, the viewer's bar) is under the camera. The screens now stay out of the display cutout as well as the system bars; check that landscape did not gain an odd margin on the camera's side. The side tab rail's buttons (Library, Camera, Map, Location) now sit in the middle of the side, not at the top: the Map button was under the camera. If the camera still covers something, say which screen; the next step would be a setting to put the tabs along the bottom.

## Library

- [ ] The fast scroller handle appears when scrolling a long library, follows it, and dragging it names the month. Tapping elsewhere on the right edge still opens photos.
- [ ] "Show photos from" (everything, camera and Latent, this phone) filters as named.
- [ ] Cull: `P`, `X`, `U` and `1`–`5` in the viewer with a keyboard; on the phone the Pick and Reject chips next to the stars; the flag badge and the dimmed rejected photos in the grid; "Flag" in the selection bar; "Show flagged" filters; "Trash rejected photos…" asks first and sends them to the trash (phone photos go through the system's question).
- [ ] "About Latent" in the library menu shows the version; both links open in the browser.
- [ ] Find (magnifying glass beside the library menu): the camera and lens lists fill in a moment after the library opens, the first time slowly on the S24 (9,000 phone photos read once in the background, 4 at a time: how long until `ZV-E10 (n)` and the phone's camera show, whether the library stays smooth meanwhile). Camera, lens, ISO and focal length (35 mm terms), when (7 and 30 days, years), with or without a place, edited or not, and part of the file name; the chip "Find · n conditions" beside the filters takes them off. A photo whose file says nothing (screenshots, downloads) is never found by a condition on it. Raw files are found by what their JPEG says; are Sony ARW's lens names read the same way as the JPEG's?
- [ ] HEIF (a newer Sony in HEIF mode, or a phone set to HEIC): `.HIF`/`.HEIC` files show in the library with thumbnails, open in the viewer, edit in the editor, and "Save a copy" makes an `_edited.jpg` with the camera's Exif; the HEIF file itself is never changed (size and date the same), and a place found for it goes in a `.xmp` sidecar. Not tried with a real HEIF file: ExifInterface reads HEIF but cannot write it; 10-bit HEIF is decoded to 8 bits.
- [ ] Compare (select two to four photos, "Compare" in the selection bar): two stand one above the other on a phone and side by side in a wide window, three or four in two rows; pinching or double tapping one zooms and moves all of them together; Pick, Reject and stars work under each photo (a raw and JPEG pair is flagged together); the picture is the file as it is, a raw file only by its thumbnail. Is a full-size 24 MP JPEG four times at once too heavy for the S24 (memory, how long until sharp)? Does zoom stay in step when the photos differ in shape?
- [ ] Selecting several photos: rating, paste edit, share, delete, RAW to JPEG.
- [ ] Right click menu, with a mouse on a tablet or laptop window: open, select, paste, share, delete.

- [ ] Albums: "Add to album" from the selection (also with the typed name of a new album: the name box could not be tried in the tests), "Albums…" in the menu to look at one, the chip that leaves it, "Take out of album", deleting an album.

## Photos on this phone (other apps' photos)

- [ ] "Photos on this phone…" lists the folders with counts; `DCIM/Camera/` is pre-ticked the first time. A folder sorted into years and days (like `2026/2026-10-08/`) counts as one choice.
- [ ] Listing `DCIM/Camera/` (9,172 photos on the S24) — how long until the grid is full, whether scrolling stays smooth while it runs (87 s one at a time before it was made four at a time).
- [ ] Thumbnails of phone photos keep up with scrolling. If not: try `ContentResolver.loadThumbnail`.
- [ ] Editing a phone photo and tapping Done changes nothing in the file (compare size and date in a file manager) and the grid thumbnail shows the edit.
- [ ] "Save a copy as JPEG" puts an edited JPEG in `DCIM/Latent`, with its location.
- [ ] Deleting a phone photo shows the system's own confirmation, and the photo goes to the system trash. The viewer moves on to the next photo afterwards.
- [ ] Sharing an edited phone photo sends the edited picture, an unedited one the file as it is.

## Viewer and video

- [x] Pressing play no longer leaves the video black until a seek (S26, 2026-10-15: the picture's surface was only made once playback ended; the video is now drawn on a texture). [x] No flash and no stretched first frames at the start (S26, 2026-10-15, a landscape 1080p video: the view stays hidden until the picture's size is known). Still to try: a portrait video, a 4K one, one with a rotation flag, and one with a non-square pixel shape.
- [ ] A video plays in the viewer with the player's controls; swiping to another photo stops it; rotating the phone keeps working.
- [ ] A video the phone cannot play is offered to another app instead of leaving the viewer stuck.
- [ ] Share and delete of a video.
- [ ] Plus, minus and 0 zoom with a keyboard; a "125%" label shows while zoomed; pinching and double tapping still work.

- [ ] "Edit in another app…" (viewer, More): the chooser lists Snapseed and Lightroom; the picture opens there; what they save (Snapseed: `Pictures/Snapseed`) shows up in Latent once that folder is ticked under "Photos on this phone…". Check that the library's own file was not touched.

## Editor

- [ ] Tap a slider's number: the box to type a value opens with the number selected and the keyboard up; `±` flips the sign; OK applies; Reset puts it back; two taps on the number reset it.
- [ ] "Swipe to edit" (editor menu): up and down pick a setting, left and right change it. Is a swipe across the whole width (the setting's full reach) too much or too little? Is 40 dp a good step between settings? Does a second finger still pinch to zoom?
- [ ] Clipping marks (menu, or `J`): red where bright detail is gone, blue where dark is, on a real photo.
- [ ] "Auto level" in the crop tool on photos with a horizon or building edges; on one with none it says so.
- [ ] A mirrored photo (flipped in another app, or a front camera that marks its pictures mirrored instead of turning them; Exif orientation 2, 4, 5 or 7): the thumbnail, the viewer, the editor, the full-size detail, a crop saved as JPEG and a share copy all show it the same way round. Since 2026-10-21 the editor mirrors it as the thumbnail does (before, it showed 2 and 4 unmirrored and 5 and 7 on their side).
- [ ] "Versions…" (editor menu): keep the edit under a name (the name box with the keyboard), open it later, delete it; opening one is a single undoable change and counts after Done.
- [ ] Colour range ("부분" / Masks → "+ Colour", and the swatches under "Only this colour" of any mask): the blue of a sky or the green of leaves is changed and nothing else; grays stay; the range slider widens and narrows it. The graphics processor's shader and the CPU renderer must agree: compare the editor's preview (graphics processor) with the saved picture, and watch for a shader that fails to compile on the phone (the editor would still work, slowly, on the CPU; look in logcat for GpuTone). Time of a full-size save with a colour range.
- [ ] Lightroom presets (editor → Filters → "Import Lightroom presets…"): pick one or several `.xmp` files (a preset exported from Lightroom Mobile or Classic, or a photo's sidecar); they show up under the user's own filters under their names, with the message of what was not taken over (white balance in kelvin, lens corrections, calibration, local adjustments...). Not tried with a file from Lightroom itself: the reader was written from the format and tested with a hand-made file. Compare the same photo in Lightroom and here with a preset applied: the look should be near, not equal (the processing differs); report which settings are far off. Other apps' file pickers may not offer `.xmp` by type; any file can be picked.
- [ ] LUTs (editor → Filters → "Import LUT…"): pick one or several `.cube` files, a usual 33-point one (a film look from a LUT pack) and a 65-point one. They show up in a row of their own under the filters, each on the picture; tapping one lays it over the photo, the "LUT intensity" slider doses it, and a filter chosen afterwards leaves it on. A 1D LUT, a file of another kind and a broken one are refused with the reason (and the line); other apps' file pickers may not offer `.cube` by type, so any file can be picked. Held down, a LUT leaves the list; a raw file or phone photo edited with it still shows it afterwards (the table stays while something uses it), a library JPEG has it baked in anyway.
- [ ] LUTs on the graphics processor: the shader and the CPU renderer must agree. Compare the editor's preview (graphics processor) with the saved JPEG and with a share copy of a raw file, and run `GpuToneOnDeviceTest` (`adb shell am instrument ... -e jpegSample <path>`): it compares both with a 33-point and a 65-point LUT and logs the times. The shader was written without a phone to compile it on: if it fails, logcat shows a `GpuTone` warning and the editor still works, slowly, on the CPU.
- [ ] LUT speed and memory: dragging a slider with a 65-point LUT on (the table, about 3.3 MB, is handed to the shader with every frame); a full-size save and a raw export with one; the filter strip with many large LUTs imported (each tile is drawn with its LUT; about 16 MB of tables are kept at hand, the rest read again from their files).
- [ ] Sony's S-Log3 → Rec.709 LUTs (for the ZV-E10's S-Log3 video) expect a flat Log picture: on an ordinary JPEG or raw file they come out far too contrasty and saturated. The panel says so before the first LUT is imported, when a LUT with "Log" in its name is chosen, and in the import message; does the wording make sense? Do Sony's Creative Look LUTs, if any are found as `.cube`, look as in the camera?
- [ ] Speed with every tool, glow, painted mask and healing at once: preview about 340 ms a step, a JPEG saved in about 4 s, a raw exported in about 6 s on the S24 Ultra (measured); the S26 not yet.

## Subject and background masks (U²-Netp on LiteRT)

The owner tried it on the phone on 2026-10-21 and found it fine overall, which lets the sky mask go ahead; the
items below stay open until each is looked at. Read the times with `adb logcat -s SubjectMask`.

- [ ] Run `SubjectMaskOnDeviceTest` (`adb shell am instrument -w -e class com.byeonggon.latent.enhance.SubjectMaskOnDeviceTest -e jpegSample <path> ...`): it reads the model's input and output shapes (1 × 320 × 320 × 3 in, 1 × 320 × 320 × 1 out, 0 to 1), finds a plain disc on both accelerators and logs set-up, first and warm run times for the GPU and the CPU, and how far the two disagree; with a photo, both outline it.
- [ ] First start: the first "+ Subject" after the app starts sets the model up (logged as "set up in … ms"). Does the GPU set up, or does the log say it fell back to the CPU, and why? How long from the tap to the mask, cold and the second time (logged "first (cold)" and "warm")? Is the GPU worth its 3.3 MB (if it is not faster, or not much, `litert-gpu` can go)?
- [ ] Latency, S24 Ultra and S26: cold and warm, GPU and CPU (`SegmenterAccelerator` order in `LiteRtSegmenter`), and the whole outline ("Outline … in … ms: picture, model, edges").
- [ ] Quality on people (hair against sky and against a busy background), pets (fur), food (a plate on a table), products (a thing on a plain ground), and pictures with no clear subject (a landscape: "Nothing stands out" or a sensible guess?), several people, a small subject far away. Do the edges follow the picture, or stop a few pixels short or long? Is an edge between two colours of the same brightness too soft?
- [ ] Background: is the same outline, inverted; tapped after Subject it appears at once (no second run). Painting on Background adds to the background, Erase takes from it; on Subject the same for the subject. Feather softens the outline's edge.
- [ ] Alignment: add a subject mask, then crop, rotate by 90°, straighten, flip and tilt (perspective): the mask stays on the subject in the preview and in the saved JPEG; the same on a raw file's export and on a phone photo's saved copy.
- [ ] Graphics processor against the CPU renderer: the preview (shader) and the saved JPEG (CPU or shader in strips) agree at the mask's edge; a shader that fails would show a `GpuTone` warning.
- [ ] Memory and time on a 24 MP raw: tap "+ Subject" on a ZV-E10 raw (the model sees the editor's 1500 px picture), then export the raw with a subject and a background mask and several others: time and whether memory holds (six masks keep six grids of 1024 cells at hand).
- [ ] Painting on a detected mask stays smooth (its grid is 1024 cells, a brush mask's 384).
- [ ] APK size: the release APK went from 5.5 to 38.7 MB with all ABIs, and to 20.3 MB since every build carries 64-bit ARM only (`nightly-20261022-213`); check what Play reports for an arm64 phone (estimate: +14.5 MB installed, +8.6 MB download).
- [ ] 16 KB pages: on a phone with 16 KB pages (or the 16 KB emulator image, or Settings → Developer options → "Boot with 16KB page size" on a Pixel), the app starts and "+ Subject" works. The built APK's 64-bit libraries are 16 KB aligned (`objdump -p`, `zipalign -c -P 16`), but no 16 KB device has loaded them.
- [ ] A phone where LiteRT cannot run (none known): the buttons disappear with one message, and every other mask works.
- [ ] A mask whose outline file is deleted (`adb shell run-as com.latentimage.latent rm files/masks/<id>.mask`, then reopen the photo): "The outline of this mask is missing" and "Find again".
- [ ] Release build: the R8 rules keep LiteRT's classes; "+ Subject" works in `assembleRelease -PdebugSignedRelease` (a missing class would show as a crash or "cannot run" in logcat).

## Sky masks (EfficientViT-Seg B1 on LiteRT)

Built 2026-10-22 on the subject masks' path; read the times with `adb logcat -s SubjectMask SkyMaskOnDeviceTest`.

- [ ] Run `SkyMaskOnDeviceTest` (`-e class com.byeonggon.latent.enhance.SkyMaskOnDeviceTest`, with `-e jpegSample <path>` of a
      photo with sky): 1 × 512 × 512 × 3 in, 1 × 64 × 64 × 1 out, a plain sky found on both accelerators. Does the GPU set up,
      or does the log say it fell back to the CPU (the model's `GATHER_ND` and `BROADCAST_TO`)? Cold and warm times.
- [ ] Quality: tree branches and wires against the sky, haze, sunsets, night, reflections in water, a sky seen through a
      window, a portrait photo (stretched to the square the model takes), a photo with no sky ("No sky was found").
- [ ] "+ Sky" on a photo, then darker exposure and more saturation on it, and "All but the sky" (Invert): the edges
      follow the horizon and the trees in the preview and the saved JPEG; brush add and erase work on it.
- [ ] Subject and sky on one photo: each model is set up once (two "set up in" lines), and both stay.
- [ ] APK size: the release APK grows by about the model's 19.5 MB (stored uncompressed).

## Ultra HDR photos (gain map)

- [ ] Open an Ultra HDR photo from the phone's camera in the editor: the note says it is saved without its HDR
      brightness, once. Darken it (tones only) and save: on an HDR screen the saved photo shows no HDR brightening, in
      Latent's viewer and in Google Photos (before 2026-10-22 a tone edit kept the original's gain map, made for other
      tones). Revert: the original, gain map and all, is back. A crop or straightening saves without one too.

## Phone raw files (DNG)

Tried with two Galaxy S26 files (2026-10-20): both modes write *linear* DNGs (three samples a pixel). Pro mode (lossless JPEG, 4080x2298) now develops, upright and in plausible colours, in about 0.6 s to read on a computer. Expert RAW (JPEG XL, 4000x3000) develops too, through jxlatte (MIT, `third_party/jxlatte`, patched to need 384 MB of heap instead of more than 1.2 GB), in about 1.3 s to read on a computer. Its samples stay under an eighth of white (the brightest 8026 of 65535) with a `BaselineExposure` of +3, which the reader now applies, as far as the brightest sample reaches white: it then looks as bright as the phone's JPEG. Pro mode's is 0.

- [ ] Pro mode on the S24 and the S26 with RAW (or RAW and JPEG) on: the `.dng` appears in "Photos on this phone" (`DCIM/Camera/`), with a thumbnail, and opens in the viewer and the editor. If it says it cannot be opened, keep the file for a test (`adb pull`) and note why from logcat (`adb logcat -s RawDevelop`, "Cannot develop …").
- [ ] Colours next to the phone's own JPEG of the same shot: white balance, skin, sky, greens. The DNG reader adds no extra saturation (`Dng.SATURATION`, 1.0, where Sony files get 1.45); is it too dull?
- [ ] A Pixel with RAW on (if one is at hand): its `PXL_….RAW-02.ORIGINAL.dng` and `PXL_….RAW-01.COVER.jpg` (or
      `.RAW-01.MP.COVER.jpg`) show as one photo with the raw badge, as a ZV-E10's pair does.
- [ ] Two-illuminant DNGs under tungsten/incandescent light: compare neutral whites/grays and skin with the phone's JPEG, automatic correction off; record Pro/Expert RAW mode and whether daylight shots still match.
- [ ] Corners and edges: no dark or tinted corners (the gain maps), on the main camera, the ultra wide and a telephoto. For DNGs with supported OpcodeList3 WarpRectilinear, compare lens correction on/off against the same shot's camera JPEG: straight grid/building lines, corners, optical centre, colour fringes and crop. Check preview versus full export in landscape, portrait and mirrored orientations. The warp assumes square pixels; unsupported/malformed geometry is unavailable, not proof of an undistorted lens.
- Host check (2026-10-22): owner-supplied 4080×2298 lossless-JPEG and 5712×4284 JPEG-XL linear DNGs decoded and produced upright previews. Both lack OpcodeList3; this does not verify lens geometry against a camera JPEG. Private samples stay outside Git.
- [ ] DNG warp performance: measure preview latency and full-export peak memory on the S26(M1), including a large three-plane linear DNG. Synthetic JVM tests do not establish a phone memory budget.
- [ ] The crop matches the phone's JPEG (no grey or black border, no colour fringe from a wrong mosaic start).
- [ ] Brightness next to the phone's JPEG, with automatic correction off: the reader applies a positive `BaselineExposure` in the development, only as far as the brightest sample reaches white (Expert RAW: +3, Pro mode: 0), and ignores a negative one. A file whose highlights stop it short comes out darker than the phone's JPEG; if that happens, the rest would go into the tone stage's exposure.
- [ ] Pro mode and Expert RAW DNGs on the phone: time to open and to save, memory (Expert RAW's JPEG XL needs about 400 MB while it is read; the app has a large heap). Logcat `RawDevelop` gives the decode time.
- [ ] Share → the RAW file itself, from a DNG: the chooser offers apps for DNG (sent as `image/x-adobe-dng`).
- [ ] A 50 MP Pro mode or Expert RAW file: how long the editor takes to open it, and whether it runs out of memory (the file and the mosaic are both held: about 200 MB).
- [ ] Save a copy as JPEG from a DNG: it lands in `DCIM/Latent` with the camera's Exif and the place.

## Location

- [ ] "Camera clock" steps (±1 min, ±10 min, ±1 h) and the sentence under it; "Match again" for photos without a place uses the offset.
- [ ] Import the Timeline export again in the new app (the old app's copy is gone).

## Map (online maps)

- [ ] The Map tab starts out explaining; "Turn on online maps" asks first; Cancel leaves it off; nothing is downloaded until yes (check with the phone's data monitor or a proxy).
- [ ] With it on: tiles load, attribution shows, pinch, double tap and the buttons zoom, markers show counts, a marker with few photos opens a strip of them, one with many zooms in.
- [ ] How the map copes with about 9,000 located photos (marker drawing while panning).
- [ ] Turning online maps off from the map's menu returns to the explanation.
- [ ] Tapping "© OpenStreetMap contributors" opens OpenStreetMap's copyright page in the browser.

## Camera and sync (needs the ZV-E10)

- [ ] A sync that runs out of space stops with the message and the next one carries on from there.
- [ ] Auto-start when the camera is connected; thumbnails picked from the camera's list.
- [ ] A file over 4 GB (one on the card): does it transfer, or fail cleanly and be reported?
- [ ] Longer recordings of location in the background (hours).
- [ ] EXIF rewritten into a JPEG keeps Sony's MakerNote intact (open it in another app).

- [ ] After a reinstall and a restore, or with photos copied into the library from another phone: the first sync
      finds camera files that look like the library's by name and size. Up to 20 of them (and 2 GB) are copied over
      and compared at once; more are left on the camera, and the result asks "Copy over and compare". Comparing claims
      those that hold the same (nothing new in the library) and imports the rest; the next sync skips them all. Note
      how long the comparison takes for a full card.

### From the card (card reader, or the ZV-E10 in Mass Storage mode)

- [ ] Camera tab → "Card or drive" → "Choose folder": with a card reader in, the dialog opens on the card; choose the
      card itself. Its name shows, and "Import" brings in what is new: photos, raw files and videos
      (`PRIVATE/M4ROOT/CLIP`), and none of the videos' thumbnails (`THMBNL`) or smaller copies (`SUB`).
- [ ] What came over MTP before is skipped, and the other way round: a sync over MTP after importing from the card
      brings nothing twice. A file that only looks the same (name, size, time) is copied over and compared, whole, with
      the library's copy (a JPEG's EXIF aside), then left out: the first such sync reads every one of them, so note
      how long it takes; the folders are remembered after it. A photo deleted from the phone has no copy to compare
      with, so the card brings it back. Compare the times in the viewer with the same
      shots imported over MTP (a card's file times may be read in another zone; capture times come from EXIF either way).
- [ ] The ZV-E10 set to Mass Storage: the Camera tab says so and points to the card section; the dialog opens on the
      camera's drive.
- [ ] Take the card out: the section says the folder cannot be read and offers another; an import started without the
      card fails with a message, and nothing half-copied is left in the library.
- [ ] A card with thousands of files: how long the listing takes compared with MTP.

## Release build

- [ ] The R8 build (`./gradlew assembleRelease -PdebugSignedRelease`) runs through all of the above: no screen crashes, no missing classes (the S24 launch showed none; the screens themselves were not driven).
- [ ] Install over the debug build keeps the data (same key).

## Not yet decided

- Versions as separate photos in the grid (Lightroom's virtual copies). Versions are kept as named looks of one photo for now; albums are done.
