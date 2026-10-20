# Ten minutes on a phone

After each nightly, on the phone that has it. In the order of what breaks most quietly; the long list is
[`DEVICE_CHECKS.md`](DEVICE_CHECKS.md). Tick what was seen, write down what was not as expected with the time and what
was on screen.

To read the phone's log from the computer while doing it (USB debugging on):

```bash
adb logcat -c; adb logcat -v time SubjectMask:V GpuTone:V GpuDetail:V Graphics:V RawDevelop:V EditSave:V CameraSync:V AndroidRuntime:E '*:S'
```

1. **After the update (1 min).** The app opens, the library and albums are as before, nothing was lost; the About
   dialog shows this build's version (`0.1.0-nightly.<date>`). The tab bar has no wide empty band over its buttons.
2. **Editor, a JPEG of the camera (3 min).** Exposure and a curve: the preview follows the finger without lag.
   *Masks, + Colour:* pick the blue swatch, exposure down: only the sky darkens, the grays stay. Done, open the photo
   again: the saved picture is the one that was previewed. Log: `GpuTone` says whether the graphics processor did
   it or the CPU took over, and why.
3. **LUT (1 min, if a `.cube` is at hand).** Filters, *Import LUT…*, pick it, move *LUT intensity*: the preview moves,
   0 is the photo as it was. Done: the saved JPEG shows the look.
4. **Subject mask (2 min).** *Masks, + Subject* on a photo of a person: red covers the person, not the background,
   in about a second; *+ Background* straight after comes at once (no second model run). Log: `SubjectMask` gives
   GPU or CPU, the first and the second time, and the reason for any fallback. Done, reopen: the mask is still there.
5. **Video (1 min).** Play: the picture shows at once, nothing flashes or stretches, seeking works, rotating the
   phone does not stop it.
6. **Search (1 min).** The magnifying glass: type letters that match nothing, the sheet stays; correct them, it still
   stays; the camera and lens chips are there; *Clear* brings everything back.
7. **Compare (30 s).** Select two photos, *Compare*: both show, a pinch moves both, *Pick* and stars work.
8. **Sharing and place (1 min).** Settings, *Leave out the place when sharing* on, share a located photo to Files or
   Messages: its details show no position. Turn it off again.
9. **Settings (30 s).** Light and dark follow the choice and the bars' icons stay readable; the tab position moves
   in a wide window (landscape); the language list opens.

Anything wrong in one of these: `adb logcat -d > log.txt` straight afterwards, and tell what was tapped.
