# Checks that need a real phone

Everything here passes on the JVM (Robolectric), but only a phone can say how it feels, looks and
performs. Tick them off as they are tried; add what a try turns up. Last updated 2026-10-15.

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
- [ ] Online maps switch: turning it on asks first, off does not; Photos on this phone opens the folder question in the library.

## Languages

- [ ] Set the phone to Japanese: no text is cut off or English where Japanese should be (longest: the setup card, the Map tab's explanation, the editor's panel tabs), and the wording reads naturally to a Japanese speaker (the translation was written without one checking it; the Lightroom terms for the sliders are used).

## Foldables

- [ ] Galaxy Z Fold 8, unfolded, in portrait and in landscape, and folded: nothing (top bar buttons, the side panel, the map's menu, the viewer's bar) is under the camera. The screens now stay out of the display cutout as well as the system bars; check that landscape did not gain an odd margin on the camera's side. The side tab rail's buttons (Library, Camera, Map, Location) now sit in the middle of the side, not at the top: the Map button was under the camera. If the camera still covers something, say which screen; the next step would be a setting to put the tabs along the bottom.

## Library

- [ ] The fast scroller handle appears when scrolling a long library, follows it, and dragging it names the month. Tapping elsewhere on the right edge still opens photos.
- [ ] "Show photos from" (everything, camera and Latent, this phone) filters as named.
- [ ] Cull: `P`, `X`, `U` and `1`–`5` in the viewer with a keyboard; on the phone the Pick and Reject chips next to the stars; the flag badge and the dimmed rejected photos in the grid; "Flag" in the selection bar; "Show flagged" filters; "Trash rejected photos…" asks first and sends them to the trash (phone photos go through the system's question).
- [ ] "About Latent" in the library menu shows the version; both links open in the browser.
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
- [ ] "Versions…" (editor menu): keep the edit under a name (the name box with the keyboard), open it later, delete it; opening one is a single undoable change and counts after Done.
- [ ] Speed with every tool, glow, painted mask and healing at once: preview about 340 ms a step, a JPEG saved in about 4 s, a raw exported in about 6 s on the S24 Ultra (measured); the S26 not yet.

## Location

- [ ] "Camera clock" steps (±1 min, ±10 min, ±1 h) and the sentence under it; "Match again" for photos without a place uses the offset.
- [ ] Import the Timeline export again in the new app (the old app's copy is gone).

## Map (online maps)

- [ ] The Map tab starts out explaining; "Turn on online maps" asks first; Cancel leaves it off; nothing is downloaded until yes (check with the phone's data monitor or a proxy).
- [ ] With it on: tiles load, attribution shows, pinch, double tap and the buttons zoom, markers show counts, a marker with few photos opens a strip of them, one with many zooms in.
- [ ] How the map copes with about 9,000 located photos (marker drawing while panning).
- [ ] Turning online maps off from the map's menu returns to the explanation.

## Camera and sync (needs the ZV-E10)

- [ ] A sync that runs out of space stops with the message and the next one carries on from there.
- [ ] Auto-start when the camera is connected; thumbnails picked from the camera's list.
- [ ] A file over 4 GB (one on the card): does it transfer, or fail cleanly and be reported?
- [ ] Longer recordings of location in the background (hours).
- [ ] EXIF rewritten into a JPEG keeps Sony's MakerNote intact (open it in another app).

## Release build

- [ ] The R8 build (`./gradlew assembleRelease -PdebugSignedRelease`) runs through all of the above: no screen crashes, no missing classes (the S24 launch showed none; the screens themselves were not driven).
- [ ] Install over the debug build keeps the data (same key).

## Not yet decided

- Versions as separate photos in the grid (Lightroom's virtual copies). Versions are kept as named looks of one photo for now; albums are done.
