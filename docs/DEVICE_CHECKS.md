# Checks that need a real phone

Everything here passes on the JVM (Robolectric), but only a phone can say how it feels, looks and
performs. Tick them off as they are tried; add what a try turns up. Last updated 2026-10-15.

## First run on a phone that has the library folder (S24 Ultra, after the move)

- [ ] Open Latent. The setup card says "사진 보관함 설정" and asks for photo access, then the folder.
- [ ] "폴더 선택" opens the picker on `DCIM/Latent`; "이 폴더 사용" is accepted, and a wrong folder is refused with a message.
- [ ] The 140 files in `DCIM/Latent` are listed (70 shots, raw and JPEG stacked). The two with a kept original in `.originals` show as edited and can be put back.
- [ ] Nothing was lost: 210 files in `DCIM/Latent` before and after.
- [ ] Same on the S26: a fresh install, no photos, first-run card, then a sync.

## Library

- [ ] The fast scroller handle appears when scrolling a long library, follows it, and dragging it names the month. Tapping elsewhere on the right edge still opens photos.
- [ ] "Show photos from" (everything, camera and Latent, this phone) filters as named.
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

## Editor

- [ ] Tap a slider's number: the box to type a value opens with the number selected and the keyboard up; `±` flips the sign; OK applies; Reset puts it back; two taps on the number reset it.
- [ ] "Swipe to edit" (editor menu): up and down pick a setting, left and right change it. Is a swipe across the whole width (the setting's full reach) too much or too little? Is 40 dp a good step between settings? Does a second finger still pinch to zoom?
- [ ] Clipping marks (menu, or `J`): red where bright detail is gone, blue where dark is, on a real photo.
- [ ] "Auto level" in the crop tool on photos with a horizon or building edges; on one with none it says so.
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

- Edit versions (virtual copies): how they should look and be stored. Albums are done.
