# A quarter of an hour on a phone

After each nightly, on the phone that has it, in the order of [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md), the long list
behind each step: install, import, library, editor, backup, sharing, settings. Tick what was seen, write down what
was not as expected with the time and what was on screen. **New** marks a step for something no nightly up to
`nightly-20261022-219` had.

To read the phone's log from the computer while doing it (USB debugging on):

```bash
adb logcat -c; adb logcat -v time SubjectMask:V GpuTone:V GpuDetail:V Graphics:V RawDevelop:V EditSave:V CameraSync:V AndroidRuntime:E '*:S'
```

1. **After the update (1 min).** The release page had the APK with its `.sha256` beside it (**new**: the first
   nightly published by a job of its own). The app opens, the library and albums are as before, nothing was lost;
   the About dialog shows this build's version (`0.1.0-nightly.<date>`). The tab bar has no wide empty band over
   its buttons. *DEVICE_CHECKS 1.*
2. **Import (1 min, new, if the camera or a card reader is at hand).** Camera tab, *Card or drive*, choose the
   card, *Import*: what is new comes in, what came over USB before does not come again. Or connect the camera over
   USB: the sync starts and brings only what is new. *DEVICE_CHECKS 2.*
3. **Search (1 min).** The magnifying glass: type letters that match nothing, the sheet stays; correct them, it still
   stays; the camera and lens chips are there; *Clear* brings everything back. *DEVICE_CHECKS 3.*
4. **Compare (30 s).** Select two photos, *Compare*: both show, a pinch moves both, *Pick* and stars work.
5. **Video (1 min).** Play: the picture shows at once, nothing flashes or stretches, seeking works, rotating the
   phone does not stop it.
6. **Editor, a JPEG of the camera (3 min).** Exposure and a curve: the preview follows the finger without lag.
   *Masks, + Colour:* pick the blue swatch, exposure down: only the sky darkens, the grays stay. Done, open the photo
   again: the saved picture is the one that was previewed. Log: `GpuTone` says whether the graphics processor did
   it or the CPU took over, and why. *DEVICE_CHECKS 4.*
7. **LUT (1 min, if a `.cube` is at hand).** Filters, *Import LUT…*, pick it, move *LUT intensity*: the preview moves,
   0 is the photo as it was. Done: the saved JPEG shows the look.
8. **Subject mask (2 min).** *Masks, + Subject* on a photo of a person: red covers the person, not the background,
   in about a second; *+ Background* straight after comes at once (no second model run). Log: `SubjectMask` gives
   GPU or CPU, the first and the second time, and the reason for any fallback. Done, reopen: the mask is still there.
9. **Sky mask (1 min, new).** *Masks, + Sky* on a landscape: red covers the sky up to the horizon and between the
   branches; exposure down darkens only the sky. Log: `SubjectMask` gives the sky model's set-up (GPU or CPU) and
   times. Done, reopen: the mask is still there. *DEVICE_CHECKS 4, "Sky masks".*
10. **HDR (1 min, new).** In the viewer, an Ultra HDR photo from the phone's camera (under *Photos on this phone*):
    its highlights shine brighter than the screen around them; the next, ordinary photo does not. Open it in the
    editor: a note says the edit is saved without its HDR brightness. *DEVICE_CHECKS 4, "HDR photos".*
11. **Backup (1 min).** Settings, *Back up…* into Downloads: the message gives counts. *Restore…* the same file: the
    summary finds every photo and says none is left out; *Cancel*. (The file holds places: keep it private.)
    *DEVICE_CHECKS 5.*
12. **Sharing and place (1 min).** Settings, *Leave out the place when sharing* on, share a located photo to Files or
    Messages: its details show no position. Turn it off again. *DEVICE_CHECKS 7.*
13. **Settings (30 s).** Light and dark follow the choice and the bars' icons stay readable; the tab position moves
    in a wide window (landscape); the language list opens. *DEVICE_CHECKS 8.*

Anything wrong in one of these: `adb logcat -d > log.txt` straight afterwards, and tell what was tapped.
