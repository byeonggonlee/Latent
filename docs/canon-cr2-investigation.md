# Canon CR2 investigation (work in progress)

This is an EOS 7D full-resolution CR2 prototype, not general Canon CR2 support. Other camera models, CR3, and reduced-resolution sRAW/mRAW must not be presented as supported. The branch still needs colour validation, wider camera coverage, a qualified cross-family review, and real-phone testing before it can be considered complete.

## External samples and reproducibility

Two EOS 7D full-resolution images from [raw.pixls.us](https://raw.pixls.us/) are individually marked CC0 in the site's file table. They are not committed to this repository:

- File 131, SHA-256 `b5e47c5fcf7332ac03e0134926f17a338a42e68c1fd7f83e16f45f4b767544e8` (scene).
- File 1183, SHA-256 `b303a596a7d8888b56a265d3b9ec78ae4f3fa3dd68c20c8276a50250393fdb39` (colour chart).

Both contain a 5360 × 3516 14-bit sensor mosaic, a 5184 × 3456 crop and a lossless JPEG with four components. The measured crop phase is GBRG and the optical-black samples have means near 2048. The prototype requires Canon colour-data version 7 and equal green white-balance gains. It rejects unknown models instead of applying the 7D calibration to them.

The provisional 7D matrix was independently fitted using 20 colour-chart patches in file 1183. Per patch, black was removed, as-shot white balance applied and RGB was normalized by its sum; camera JPEG patch values were transformed from sRGB to linear light and normalized likewise. A constrained least-squares fit maintained row sums of one. The chart's relative linear chromaticity RMS was approximately 0.031 (identity: 0.113); on the separate scene, approximately 0.056 (identity: 0.118). This is not a perceptual colour accuracy score or proof of calibration across illuminants.

## Open colour issue

On the scene sample, the app's rendered image looks noticeably yellower than its embedded camera JPEG. At a common 864 × 576 resolution, mean sRGB values were approximately (0.618, 0.392, 0.081) for the app and (0.594, 0.308, 0.103) for the JPEG. Camera JPEG rendering includes its own tone and colour processing, so this comparison alone does not identify whether white balance, the provisional matrix, the app's rendering, or another factor causes the difference. As a further independent rendering, macOS `sips` converted the same CR2 to PNG, with mean sRGB approximately (0.555, 0.332, 0.129). On 99,078 scene pixels selected using the JPEG, the relative linear chromaticity RMS was 0.0585 between the app and JPEG, 0.0357 between the app and macOS, and 0.0352 between macOS and JPEG. These rendering differences do not identify a ground truth or establish the cause. The chromaticity test passing does not settle it. Investigate using known neutral and colour references under multiple illuminants, and validate on a phone; do not treat the current matrix as production-ready merely because the sample test passes.

## Checks so far

Synthetic tests cover slice reordering, MakerNote bounds and white balance, lossless-JPEG geometry, dispatch and the four Bayer phases. On an API 36 emulator, the sample decoded, generated a thumbnail, loaded its embedded preview, and exported at full resolution; the measured full-resolution export was about 9.8 seconds in that run. This is not a physical-device performance result. The sample test is optional and requires `CANON_7D_SAMPLE` to point to file 131; the image must remain outside the repository.

## Review follow-up: highlight white level

Claude Opus 5.5's review of `30cbeb3` found that the fixed 16383 white level made fully saturated highlights
magenta. The scene has a plateau at 12648–12651; a fully clipped 2 × 2 block developed as
(16383, 7463, 16383). New synthetic and external-sample regressions reproduced this before the change.

The decoder now uses the shot's `SpecularWhiteLevel`, unsigned SHORT index 720 (`0x2d0`) of ColorData version 7,
as documented in [ExifTool's Canon ColorData4 tags](https://exiftool.org/TagNames/Canon.html#ColorData4).
It rejects a missing/zero level, one less than 16 counts above the fixed 2048 black level, or a value above the
14-bit encoding range, instead of silently reverting to 16383. It does not estimate white from a scene's maximum
or guess an ISO table. The regression checks the resulting `ArwImage` level and neutral highlights through both
preview and full demosaicing; the external scene test covers the 100 known fully clipped 2 × 2 blocks instead of
filtering highlights out. A separate optional `CANON_7D_CHART_SAMPLE` test uses file 1183 to ensure that an
unclipped scene retains its metadata white level rather than being stretched to the brightest photosite.

This is a **conservative metadata cutoff, not a measurement of the true sensor saturation point**. In the
ISO 1250 sample it is 11222, below the observed plateau at 12651; this may discard about 0.21 stops of top-end
headroom after black subtraction. In the ISO 100 chart it is 12279, above every photosite in the active image, which
therefore cannot validate that ISO's saturation point. The relation between Canon's field and true saturation
still needs measurements at more ISOs. The independent reviewer must re-check this tradeoff; the draft and its
colour-validation gate remain in place. The provisional colour matrix was not changed by this fix.

The 0.2.0 changelog now distinguishes CR2 import/preview support from the limited EOS 7D raw development.
The review's optional suggestion to avoid holding two mosaics during slice reordering remains deferred.

Follow-up validation (2026-10-28): with both sample environment variables set,
`testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest assembleDebug` passed: 842 JVM tests,
0 failures/errors, 4 unrelated sample-dependent skips; the CR2 sample tests all ran. Lint reported 0 errors and
14 warnings. `git diff --exit-code -- app/schemas` and `git diff --check` passed. The corrected build also passed
all three CR2 instrumentation tests on the API 36 ARM64 emulator: thumbnail, embedded preview and a
5184 × 3456 export (about 7.6 seconds; 576 MiB heap cap in this run). The S24 Ultra was not connected, so this is
not a real-phone colour or memory/performance result.
