# Canon CR2 investigation (work in progress)

This is an EOS 7D full-resolution CR2 prototype, not general Canon CR2 support. Other camera models, CR3, and reduced-resolution sRAW/mRAW must not be presented as supported. Claude Opus 5.5 accepted the decoder-arithmetic evidence at `b65b9ed`. The current revision replaces the JPEG-fitted matrix with Adobe's Standard A/D65 profile through the existing DNG interpolation code. That production colour change requires a fresh review; camera/device coverage remains limited. The dated investigation below preserves the earlier measurements; the [2026-10-29 follow-up](#review-follow-up-adobe-two-illuminant-profile-2026-10-29) describes the current profile.

## External samples and reproducibility

Two EOS 7D full-resolution images from [raw.pixls.us](https://raw.pixls.us/) are individually marked CC0 in the site's file table. They are not committed to this repository:

- File 131, SHA-256 `b5e47c5fcf7332ac03e0134926f17a338a42e68c1fd7f83e16f45f4b767544e8` (scene).
- File 1183, SHA-256 `b303a596a7d8888b56a265d3b9ec78ae4f3fa3dd68c20c8276a50250393fdb39` (colour chart).

Both contain a 5360 × 3516 14-bit sensor mosaic, a 5184 × 3456 crop and a lossless JPEG with four components. The measured crop phase is GBRG and the optical-black samples have means near 2048. The prototype requires Canon colour-data version 7 and equal green white-balance gains. It rejects unknown models instead of applying the 7D calibration to them.

The provisional 7D matrix was independently fitted using 20 colour-chart patches in file 1183. Per patch, black was removed, as-shot white balance applied and RGB was normalized by its sum; camera JPEG patch values were transformed from sRGB to linear light and normalized likewise. A constrained least-squares fit maintained row sums of one. The chart's relative linear chromaticity RMS was approximately 0.031 (identity: 0.113); on the separate scene, approximately 0.056 (identity: 0.118). This is not a perceptual colour accuracy score or proof of calibration across illuminants.

## Initial JPEG comparison (historical observation, not a calibration reference)

On the scene sample, the app's rendered image looks noticeably yellower than its embedded camera JPEG. At a common 864 × 576 resolution, mean sRGB values were approximately (0.618, 0.392, 0.081) for the app and (0.594, 0.308, 0.103) for the JPEG. Camera JPEG rendering includes its own tone and colour processing, so this comparison alone does not identify whether white balance, the provisional matrix, the app's rendering, or another factor causes the difference. As a further independent rendering, macOS `sips` converted the same CR2 to PNG, with mean sRGB approximately (0.555, 0.332, 0.129). On 99,078 scene pixels selected using the JPEG, the relative linear chromaticity RMS was 0.0585 between the app and JPEG, 0.0357 between the app and macOS, and 0.0352 between macOS and JPEG. These rendering differences do not identify a ground truth or establish the cause. The chromaticity test passing does not settle it. Investigate using known neutral and colour references under multiple illuminants, and validate on a phone; the then-provisional matrix was not established as calibrated merely because that sample test passed.

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
still needs measurements at more ISOs. Claude accepted this conservative tradeoff at `4b1353c`; that cutoff
remains unchanged. The provisional colour matrix was not changed by that white-level fix.

The 0.2.0 changelog now distinguishes CR2 import/preview support from the limited EOS 7D raw development.
The review's optional suggestion to avoid holding two mosaics during slice reordering remains deferred.

Follow-up validation (2026-10-28): with both sample environment variables set,
`testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest assembleDebug` passed: 842 JVM tests,
0 failures/errors, 4 unrelated sample-dependent skips; the CR2 sample tests all ran. Lint reported 0 errors and
14 warnings. `git diff --exit-code -- app/schemas` and `git diff --check` passed. The corrected build also passed
all three CR2 instrumentation tests on the API 36 ARM64 emulator: thumbnail, embedded preview and a
5184 × 3456 export (about 7.6 seconds; 576 MiB heap cap in this run). The S24 Ultra was not connected, so this is
not a real-phone colour or memory/performance result.

## Colour investigation: independent comparisons (2026-10-28)

No production colour coefficients or WB gains were changed by the investigation at `b65b9ed`. It showed that
the decoder reproduced independent decoding and linear development under the same controls. Claude Opus 5.5
[accepted that decoder-arithmetic evidence](https://github.com/byeonggonlee/Latent/pull/51#pullrequestreview-5685224976).
It did not establish the JPEG-fitted matrix's physical colour accuracy, or authorize a merge. The matrix
recommendation from that review is implemented in the 2026-10-29 follow-up below.

### Sensor and white balance

rawpy 0.27.1 / LibRaw 0.22.1 decoded both CR2s independently. Every one of the 18,845,760 photosites in each
full sensor mosaic matched Latent's lossless-JPEG and slice decoding exactly (0 differing samples, maximum
absolute difference 0). The independently read as-shot gains matched too:

- Scene: R/G = 2166/1024, B/G = 1524/1024.
- Chart: R/G = 2427/1024, B/G = 1339/1024.

Adobe DNG Converter 18.7 (2743), downloaded through
[Adobe's official DNG page](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html),
converted both files with `-u -p0`. Inverting the DNGs' `AsShotNeutral` gave the same WB gains within 0.00002.
These two independent readers do not support the hypothesis that ColorData-v7 indices 63–66 were the wrong WB
fields. Both Adobe DNGs independently declare a uniform `BlackLevel` of 2048. The LibRaw linear reference
explicitly sets its black level and therefore cannot independently validate that choice. LibRaw's own masked-area
estimates of 2047–2049 per channel are supporting measurements. LibRaw/rawpy were used only as external
validation tools; no library, source or calibration data from them was added to the app or its dependencies.

### Grey patches

The chart is an Amateur Photographer printed target, not an identified ColorChecker with supplied reference
reflectances. We sampled 50 × 50 preview cells inside five grey patches, centred at (350, 930), (590, 930),
(830, 930), (1070, 930), (1310, 930) in the 1920 × 1280 embedded-JPEG view. A preview cell corresponds to a 2 × 2
sensor block; the crop begins at (168, 56). The lettering on the white and black patches was excluded.

With black = 2048, the first bright patch's median camera RGB was (1199, 2996, 2487). Its empirically neutral
R/G and B/G gains would be approximately (2.499, 1.205), not the shot's (2.370, 1.308). With as-shot WB, that
patch is blue-leaning, and its camera JPEG is also blue-leaning (mean sRGB about 0.747, 0.767, 0.834). The other
patches need different blue gains to become neutral (about 1.174, 1.184, 1.236 and 1.427 respectively). This
uncalibrated target does not justify imposing a single WB adjustment to force all five patches neutral.

In ColorData, the chart's As Shot and Auto entries agree exactly (2427, 1024, 1024, 1339); Measured is nearly
the same (2425, 1024, 1023, 1338); the associated temperature is 6245 K. A JPEG-selected set of near-neutral
pixels alone is insufficient to identify a sensor WB defect: Canon has already rendered those JPEG pixels.

### Adobe DNG metadata and shared-development comparison

`CanonCr2ReferenceTest` first checks that the DNG has the same as-shot WB and that the decoded sensor values
agree after the DNG's documented black/white normalization. It then renders the CR2 with the DNG's WB,
colour matrix, white level and crop so differences in those controls cannot be mistaken for a decoder defect.
The DNG path moves the crop down one photosite to begin on red; the controlled CR2 comparison uses that same
crop and RGGB phase. Both sides use Latent's `RawDeveloper`: these RGB numbers are a shared-code compatibility
check, not independent validation of development. The independent evidence from Adobe is the sensor/WB/black
metadata; LibRaw provides the independent development check below. The production CR2 crop and reviewed
conservative white cutoff are unchanged.

- Scene: sensor-normalization difference at most 1 count; linear RGB difference at most 5/16383, mean 0.911 count.
- Chart: sensor-normalization difference 0; linear RGB difference at most 6/16383, mean 0.956 count.

The small output differences come from quantization before WB in the DNG path and after WB in the CR2 path.
The tests allow at most 16 counts and a mean of 2 counts, and inspect all pixels, including neutrals and clipped
highlights. Adobe's DNG white levels are 12600 for the scene and 13600 for the chart; these are reference controls,
not replacements for the separately reviewed CR2 cutoff in this change.

The converted DNGs also provide authoritative matrix provenance for any later calibration work:
ColorMatrix1 (Standard A) is `[1.162, -0.6349, 0.0005; -0.2558, 1.0146, 0.2813; 0.0024, 0.0858, 0.6926]`;
ColorMatrix2 (D65) is `[0.6843, -0.0996, -0.0856; -0.3876, 1.1761, 0.2396; -0.0593, 0.1772, 0.6198]`.
The DNGs contain ForwardMatrix and per-shot CameraCalibration entries as well, so simply copying one
XYZ-to-camera matrix is not a reproduction of Adobe's complete rendering. None of these values was silently
substituted into the production decoder during diagnosis.

Generated DNG SHA-256 values (these files remain outside Git):

- Scene: `10649836e60b61d25f9ac1fbeab700ad5f2a336ad96efa83e24adba96aef0b9f`.
- Chart: `1c486c6ee9678265eff23adf69eebc14a35c95ffc5b6b7f6a15031facc293d00`.

### Independent linear development

`scripts/make_cr2_reference.py` uses LibRaw's half-size camera-RGB development with the file's independently
read as-shot WB, the same black/white controls, no automatic brightness, and linear gamma. It then applies the
explicitly supplied matrix using NumPy. It never uses Latent to produce the reference. The binary reference
includes the source photo hash and all controls; the Kotlin test checks these before comparing every RGB value.
LibRaw's `user_sat` override is a black-subtracted range: supplying the absolute white level here would introduce
an unrelated brightness scale. Both samples were measured with the corrected, matched range.

With the JPEG-fitted matrix at `b65b9ed` and the production CR2 cutoff:

- Scene: maximum difference **3/16383**, mean **0.805** count.
- Chart: maximum difference **3/16383**, mean **0.768** count.

The test bounds are 8 counts (<0.05% full scale) and mean 2. No pixels are excluded by hue, neutrality, brightness
or saturation. These results exclude a material extra cast introduced by the tested decoding/development
arithmetic under these controls. They do **not** validate the supplied matrix against calibrated physical
colours, or explain every difference from the camera's JPEG look.

### Reproduce the external checks

Use a temporary Python environment with `rawpy==0.27.1` and NumPy; these are developer tools, not Gradle or app
dependencies. Use Adobe DNG Converter's documented CLI with `-u -p0 -d <output-directory> <source-CR2s>`.
The current profile produces a different matrix for each shot's WB. For these exact two samples, generate the
independent references using the current matrices printed by the profile-comparison tests:

```sh
python scripts/make_cr2_reference.py /tmp/scene.cr2 /tmp/scene.rgb --white-level 11222 \
  --matrix 1.7970818 -0.7317299 -0.06535186 -0.24216631 1.7495457 -0.50737953 -0.009082833 -0.47137046 1.4804533
python scripts/make_cr2_reference.py /tmp/chart.cr2 /tmp/chart.rgb --white-level 12279 \
  --matrix 1.9203929 -1.041307 0.12091401 -0.24695428 1.7593473 -0.51239306 -0.006954564 -0.48955196 1.4965065
```

Set `CANON_7D_SAMPLE`, `CANON_7D_CHART_SAMPLE`,
`CANON_7D_DNG`, `CANON_7D_CHART_DNG`, `CANON_7D_LIBRAW_RGB` and `CANON_7D_CHART_LIBRAW_RGB` to the two CR2s,
their Adobe DNG conversions and the generated references. Then run:

```sh
./gradlew testDebugUnitTest --tests 'com.byeonggon.latent.raw.CanonCr2ReferenceTest'
```

Without the external files, the six reference cases (two sensor/WB, two profile and two independent development)
are skipped explicitly, like the existing camera-sample tests.
The original JPEG chromaticity check remains a limited historical guard; it is not the colour acceptance oracle.

Final investigation validation: with all six external-reference variables set,
`testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest` passed after merging `main` at `1e65ac6`:
851 JVM cases, 0 failures/errors, 4 unrelated external-fixture skips. All four new reference cases ran. The
Python generator ran successfully for both samples; schema and whitespace diffs are clean. Physical-device
checks were not repeated for these test/documentation additions, and absolute colour accuracy was not measured.

## Review follow-up: Adobe two-illuminant profile (2026-10-29)

The owner requested the changes from Claude's review of `b65b9ed`. The JPEG-fitted `EOS_7D_MATRIX` is now removed.
`CanonCr2` uses the Standard A and D65 XYZ-to-camera values read from the two Adobe DNG Converter 18.7 outputs
identified above. The code comment records that provenance. `Dng.Colors` is now internal so CR2 can reuse the
existing as-shot-neutral estimation, inverse-temperature interpolation, normalization and inversion as-is.
The DNG algorithm itself is unchanged. The CR2 keeps its original MakerNote WB gains; it does not force the
uncalibrated chart's grey patches neutral.

The current CR2 reader does not extract the per-unit `CameraCalibration` that Adobe writes into its DNGs, so
this path explicitly uses the DNG identity default. It does not copy either sample's per-unit factors to all 7Ds.
The scene's Adobe DNG has R/B calibration about 0.988/1.0151 and the chart's about 0.9653/1.039; a CR2 and its
Adobe conversion can therefore still have slightly different matrices. Tests match the available inputs by
omitting only those per-unit calibration tags from the DNG lookup, and confirm that the *production* CR2 matrix
agrees within 0.0001 per coefficient. This is an intentional, documented limit rather than exact Adobe rendering.

The two synthetic integration regressions independently compute the Standard A/D65 endpoints using NumPy's
inverse of row-normalized `(ColorMatrix * sRGB-to-XYZ)`. As-shot gains just outside each profile endpoint check
clamping and preservation of the original WB. Both failed with the old fixed matrix. The existing independent
references also failed their matrix-header checks after the change; they were regenerated with the new controls,
without loosening thresholds or excluding pixels.

With the current production matrices and the reviewed CR2 white cutoff, the regenerated independent LibRaw
comparisons cover every pixel, including neutrals and saturated highlights:

- Scene: maximum difference **3/16383**, mean **0.776** count.
- Chart: maximum difference **3/16383**, mean **0.754** count.

The test bounds remain 8 counts and mean 2. The separate Adobe DNG/shared-`RawDeveloper` compatibility numbers
remain maximum 5 and 6 counts; they must not be described as independent development evidence. The six optional
reference cases now include direct profile-input agreement as well as the earlier sensor/WB and independent
LibRaw checks. The old For-Claude review request on HANDOFF was removed, and the in-progress row distinguishes
the accepted arithmetic review from the new profile change awaiting review. Other ISOs/illuminants/cameras and
physical-screen colour judgement remain unverified; these results do not authorize merging or shipping.

Validation of this profile revision (2026-10-29): with all six external-reference variables set,
`testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest assembleDebug` passed: 855 JVM cases,
0 failures/errors, 4 unrelated external-fixture skips. All six reference cases and eight CR2 integration cases
ran. Schema and whitespace checks passed. The new build also passed all three API 36 ARM64 emulator tests:
thumbnail, embedded preview and full-resolution export. No S24 Ultra run or physical-screen colour judgement
was performed. Fresh cross-family review of this production colour change remains required.

### Integration for the owner-authorized merge — 2026-10-29

Gemini 4 Argon and Claude reviewed the Adobe-profile revision at `94c0c15` with LGTM. The owner then asked Codex
to merge its work. Integrating main after PR #52 changes only the standard OpcodeList3 tag in production code
relative to that reviewed revision; the Canon decoder, profile, CFA contract and development arithmetic are
unchanged. The changelog and HANDOFF conflicts were resolved by preserving both tasks and the other agents' rows.
Physical S24 colour judgement remains unverified; merge authorization is not evidence of a visual check.

The combined debug build passed all seven API 36 ARM64 emulator cases (Canon thumbnail, embedded preview and
full-resolution export, plus four Open Camera/MotionCam exports) in 10.761 seconds. This run reused public
fixtures already in app cache and did not uninstall or clear the app.

With all six Canon reference variables and `PHONE_DNG_SAMPLE_DIR` set, the combined
`testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest assembleDebug --offline` run passed:
862 JVM cases, zero failures/errors and four unrelated fixture skips. All six Canon reference, eight Canon
integration and four public phone-DNG cases ran. Both Lints reported zero errors and 11 warnings; schema and
whitespace checks passed.

## Brightness: the camera JPEG's midtones (2026-10-30)

On the owner's colour check, Claude Code exported both samples on the API 36 emulator through
`RawRenderer.export` with no edit. The 7D opened about 0.7 stop brighter and flatter than its camera JPEG. Against
the camera JPEG, over every pixel that neither image clips, the mean lightness difference was 13.7 L* on the scene
and 9.4 on the chart. The chart's five grey patches measured L* 83.6, 77.8, 69.5, 56.8 and 38.8; the camera JPEG
has 78.5, 72.1, 63.0, 46.1 and 27.6.

`CameraCurve` is fitted to a ZV-E10's JPEGs and applies to every raw file. The 7D's JPEGs hold midtones lower. On
the ISO 1250 scene, the conservative white cutoff above adds about 0.2 stop.

The colour itself was not the problem. With the exposure lowered by 0.7 stop, the chart's colour-only ΔE00 against
the camera JPEG (L* matched) was 3.6. Apple's raw engine (`sips`, sRGB) gives 4.0 on the same patches. Both differ
from the camera in the same places: the azure patch, the dark greys, the orange and the blue.

**Change.** `ArwImage.toneExponent`, applied in `RawDeveloper` after the colour matrix, raises each pixel's
brightness to a power. The brightness is Rec. 709 luminance in linear sRGB, and all three channels are scaled by
`Y^(p-1)` through a 16,384-entry table. Black, white and each pixel's channel ratios are kept. The 7D uses
`p = 1.24`; every other format keeps 1, which skips the step and leaves development bit-identical.

**Fit.** The fit used the LibRaw linear references through `CameraCurve`, compared with the camera JPEGs on every
seventh unclipped pixel. These were the candidates (mean |ΔL*|, scene / chart):

| Candidate | Scene | Chart | Note |
|---|---|---|---|
| Exposure alone, −0.8 stop | 2.7 | 3.9 | A clipped white no longer reaches white |
| A scale that keeps white | 2.6 | 3.8 | |
| A refitted three-parameter curve | 2.4 | 3.2 | |
| Luminance power | 2.8 | 3.0 | One parameter, residuals even across tones |

The luminance power leaves the scene +0 to +4 L* brighter than its JPEG and the chart −1 to −5 darker. Two shots
cannot support more than one parameter, and the camera adapts its rendering to each scene. The scene's
conservative white cutoff accounts for part of the remaining 5 L* between the two shots.

**Checked**

- The emulator exports of the change measure 2.75 L* (scene) and 2.86 (chart).
- The chart's ΔE00 against the camera JPEG falls from 9.43 to 5.70 (colour only, from 5.86 to 4.23).
- The LibRaw comparison above develops the decoded image with `toneExponent` 1, since its oracle is linear light. It
  still differs by at most 3 counts.
- `RawDeveloperTest` checks the step: grey goes to `MAX·(v/MAX)^1.24`, the colour ratios hold, black and white stay,
  and an exponent of 1 changes nothing. It fails when the step is skipped.

**Limits**

- Two shots from one body, in the camera's default picture style as far as the files show.
- Other ISOs, picture styles and Auto Lighting Optimizer settings are untested.
- The physical-screen judgement on the S24 is still the owner's.
