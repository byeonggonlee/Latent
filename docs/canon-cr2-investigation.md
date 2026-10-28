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
