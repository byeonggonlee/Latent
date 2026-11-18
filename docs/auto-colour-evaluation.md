# Auto colour on a raw: gray-world, gray-edge and white-patch

0.2.0 item 1 (2026-10-30). A raw opens with the camera's as-shot white balance. Auto used to take any picture half
the way to gray by gray-world: on a raw it pulled the temperature as far as its cap of 0.4. The roadmap asked to
start from as-shot, to move only a little, and to measure gray-edge and white-patch against gray-world on the same
photos, scenes with a meant colour included.

## What was measured

Six public raws, developed as the editor develops them (`RawDeveloper.developPreview`, then the 320-pixel overview
Auto judges), with the camera's white balance:

| Sample | Scene | As shot |
|---|---|---|
| EOS 7D, ISO 1250 (`CANON_7D_SAMPLE`) | a desk lit by a tungsten lamp | warm: a meant colour |
| EOS 7D, ISO 100 (`CANON_7D_CHART_SAMPLE`) | a colour chart with a row of grays | slightly blue, the only sample with a known neutral |
| `opencamera-2206.dng` (Pixel 2 XL) | a city under a blue sky, water | looks right |
| `opencamera-1877.dng` (Honor 6A) | autumn poplars under a hazy sky | looks right |
| `motioncam-0.dng`, `motioncam-1.dng` | the same dusk: dark trees, a low warm sky | warm: a meant colour |

The samples and their hashes are in [`device-results/2026-10-29-phone-dng.md`](device-results/2026-10-29-phone-dng.md)
and [`canon-cr2-investigation.md`](canon-cr2-investigation.md). No sunset with the sun in it was available; the dusk
pair stands in for it.

Each way estimates the light's red, green and blue from linear levels, leaving out pixels with a channel at 97% or
more (clipped) or none above 1% (noise):

- **gray-world:** the mean of the picture;
- **gray-edge:** the Minkowski mean (power 6) of each channel's gradient, after van de Weijer, Gevers and Gijsenij
  (2007); powers 1 and 6 were both tried, 6 is pulled less by the tungsten desk (−0.85 against −3.13);
- **white-patch:** the mean of the brightest 5% (1% was tried and was no better);
- shades-of-gray (power 6) was also tried and is left out: on the samples that look right it was misled more than
  gray-world (0.52 against 0.34 on average), and it asked to cool the tungsten desk twice as much as gray-edge.

The estimate is turned into the temperature and tint that would undo it, in the sliders' units
(`ToneRenderer.TEMPERATURE_STOPS` 0.5 and `TINT_STOPS` 0.35). Every one of these ways is unchanged by a cast of the
whole picture, so a cast added on purpose moves each estimate by exactly the cast; what tells them apart is how far
each is misled by what the scene holds.

## Results

What each way asks for, temperature and tint, on the picture as shot:

| Sample | Gray-world | Gray-edge | White-patch | Old Auto (cap 0.4) | Nudge |
|---|---|---|---|---|---|
| Tungsten desk | −3.83, +1.02 | −0.85, −0.38 | −3.18, +0.69 | −0.40, +0.04 | **−0.10, 0** |
| Colour chart | −0.29, +0.04 | −0.06, −0.42 | +0.28, −0.17 | −0.18, +0.11 | **0, 0** |
| City, blue sky | +0.55, +0.28 | −0.36, −0.05 | −0.17, +0.17 | +0.22, +0.12 | **0, 0** |
| Poplars | +0.20, +0.23 | +0.77, −0.22 | +0.52, +0.30 | −0.35, +0.11 | **+0.10, 0** |
| Dusk (MotionCam 0) | −0.28, −0.09 | −0.64, −1.95 | −0.54, −0.39 | −0.06, 0 | **−0.10, −0.05** |
| Dusk (MotionCam 1) | −0.32, −0.10 | −0.63, −1.99 | −0.57, −0.39 | −0.09, 0 | **−0.10, −0.05** |

The chart's light grays, measured in the same overview, ask for **+0.29 and −0.15**: the camera left it a little
blue and magenta. Only white-patch finds that (+0.28, −0.17); gray-world asks for the opposite (−0.29), and the old
Auto moved it the wrong way (−0.18, +0.11).

On the four samples that look right as shot, no way stays near zero: each is misled by 0.2 to 0.8 in temperature
somewhere, gray-world least on average (0.34), white-patch on the bright sky of the dusk and the haze of the poplars,
gray-edge on the poplars (+0.77) and in tint on the dusk (−1.95). On the meant colours, every way asks to remove most
of the tungsten light, and white-patch and gray-edge to cool the dusk.

## What Auto does now

None of the three is right often enough to be trusted alone, and each is misled by a different scene. For a raw,
Auto ([`WhiteEstimate.nudge`](../app/src/main/java/com/byeonggon/latent/enhance/WhiteEstimate.kt)):

1. measures all three;
2. on temperature and on tint separately, moves only where all three ask for the same way;
3. takes half of the smallest of the three, and at most **0.1** in temperature and **0.05** in tint (a red-to-blue
   change of about 7% and a green one of about 1%).

Where they disagree, as on the chart and the city, the camera's balance stays. The tungsten desk keeps nearly all of
its warmth (−0.10 of the −0.85 to −3.83 asked); the dusk is cooled about as much as before (−0.10 against −0.06 and
−0.09). Exposure, contrast and the other sliders are judged as before. A picture that is not a raw (a JPEG, a HEIF,
a phone's photo) keeps the old gray-world, half the way and up to 0.4: its colours are already a rendering, and item 1
asked only about raws.

The cost: where the camera was really wrong, Auto now corrects little. On the chart it leaves the 0.29 it could have
taken, because gray-world and gray-edge disagree with the grays. Trusting white-patch alone, within the same limits,
would have moved the chart +0.1 the right way, but also the city (about −0.08, +0.05) and the tint of the poplars and the
desk (+0.05), which look right or are meant.

## Checks

- `WhiteEstimateTest`: synthetic scenes; a neutral one is left alone, a blue cast is warmed by half up to 0.1, a
  lamp-lit room keeps all but 0.1, a scene where gray-world and white-patch disagree is left alone (and fails
  without the agreement check), a clipped picture is not judged.
- `AutoSettingsTest`: a raw is nudged less than the same picture as a JPEG, and the rest of Auto is unchanged.
- `WhiteEstimateSampleTest`, with the samples set: the table above, every nudge within its limits, and the chart's
  never against its grays.

Not checked: a sunset with the sun in the frame, a raw whose camera balance is badly wrong (a tungsten preset
outdoors), other cameras' raws (the Sony ARWs, whose look Auto is most used on) and the result on a phone.
