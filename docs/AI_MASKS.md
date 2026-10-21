# Subject, background and sky masks on the phone (spike)

Roadmap item 6. The spike below was research only, written 2026-10-15; every fact carries a source number
(list at the end). Sizes marked *measured* were read here from the published Maven AARs (ELF program headers
of the arm64 `.so`, read in a scratch folder outside the repo). Everything marked *estimate* is a guess to be
checked on the S24 Ultra. **Stage 1 (subject and background) is built**; what was built, and where the model
came from, is in the next section.

## Stage 1 as built (2026-10-16)

"+ Subject" and "+ Background" in the Masks panel (after "+ Colour"). Nothing runs at import or at start:
the model is set up the first time one of them is tapped, and kept while the app runs.

### The model

| | |
|---|---|
| Model | U²-Netp, the small U²-Net (Qin et al., *Pattern Recognition* 106, 2020), salient object detection |
| Licence | Apache 2.0 (repository licence, [22]); weights trained on DUTS-TR (owner's decision on training data, below) |
| Weights used | `u2netp.pth` from the Google Drive link in the official README (https://github.com/xuebinqin/U-2-Net, file id `1rbSTGKAE-MTxBYHd-51l2hMOQPT_7EPy`), 4,683,258 bytes, SHA-256 `e7567cde013fb64813973ce6e1ecc25a80c05c3ca7adbc5a54f3c3d90991b854` |
| Conversion | Done here, no ready-made `.tflite` with a clear source was found (PINTO's model zoo only as a 10 GB archive; the one Hugging Face copy has no licence or provenance). `litert-torch` 0.9.4 (torch 2.13.0, ai-edge-litert 2.2.0) in a throwaway venv outside the repo, model code `model/u2net.py` from the same repository, channels-last input and output (`to_channel_last_io`), only the fused output `d0` kept. Weights unchanged |
| File | `app/src/main/assets/u2netp.tflite`, 4,776,700 bytes, SHA-256 `f491640e47ddf93365f3961e8df2500b0bc3d44c2b2c0fa2065cc765dca8bd3b`, float32, stored uncompressed in the APK |
| Checked | Against PyTorch on random input: largest difference 7.9e-6. Against rembg's independent ONNX export of the same weights (`u2netp.onnx`, https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx, SHA-256 `309c8469…ddd8`) on a synthetic picture: largest difference 6.5e-6 |
| Input (read from the model) | `serving_default_args_0` (signature name `args_0`), `[1, 320, 320, 3]` float32, RGB, no quantisation |
| Output (read from the model) | `serving_default_output_0_output` (`output_0`), `[1, 320, 320, 1]` float32, **sigmoid already applied** (the graph ends in `LOGISTIC`), 0 to 1 |
| Ops | `CONV_2D` 119, `CONCATENATION` 51, `RESIZE_BILINEAR` 38, `MAX_POOL_2D` 33, `ADD` 11, `LOGISTIC` 1: all on the LiteRT GPU delegate's supported list; whether all run there is for the phone to say |
| Preparation (as `u2net_test.py`) | The picture stretched to 320 × 320 (not letterboxed), divided by its brightest value, then `(v − mean) / std` with ImageNet's mean `0.485, 0.456, 0.406` and std `0.229, 0.224, 0.225` |
| Reading the answer | As the authors' test script and rembg do: stretched so that its least and most certain cells are 0 and 1. Two choices of ours: if the most certain cell is below 0.2, "nothing stands out" is said instead of stretching noise; if all cells lie within 0.2 of each other (all subject, or all ground), the answer is taken as it is |

Conversion script (run with `python -I`, after `pip install litert-torch` in a venv):

```python
import sys, torch, litert_torch
sys.path.insert(0, CODE_DIR)            # a checkout of xuebinqin/U-2-Net/model
from u2net import U2NETP

class Fused(torch.nn.Module):
    def __init__(self, net): super().__init__(); self.net = net
    def forward(self, x): return self.net(x)[0]          # d0, sigmoid applied

net = U2NETP(3, 1)
net.load_state_dict(torch.load("u2netp.pth", map_location="cpu", weights_only=True))
model = litert_torch.to_channel_last_io(Fused(net.eval()).eval(), args=[0], outputs=[0])
litert_torch.convert(model, (torch.randn(1, 320, 320, 3),)).export("u2netp.tflite")
```

The licence texts ship in the APK under `assets/licenses/` (`NOTICE.txt` with the source, change and
checksums above, U²-Net's licence, LiteRT's licence and its third-party notices); the About dialog names
LiteRT and U²-Netp.

### Runtime

- LiteRT 2.3.0 `litert` and `litert-gpu`, `CompiledModel` API. `SegmenterAccelerator` (GPU, CPU) is the one
  place the choice is made: `LiteRtSegmenter` tries the GPU first and falls back to the CPU (XNNPACK) when
  the GPU model cannot be created, or when a run throws or gives non-finite values; the fallback lasts as long
  as that segmenter. If every accelerator fails, the next tap makes a fresh one. Where nothing can be set up
  (no native library for the phone's ABI, a LiteRT error), the buttons are hidden for the rest of the run with
  a one-time explanation.
- Logcat tag `SubjectMask`: set-up time per accelerator, `first (cold)` and `warm` run times, fallbacks, and
  the whole outline's time split into picture, model and edges.
- `litert-api` depends on Play's `ai-delivery` (AI packs), which brings `play-services-basement` and
  `-tasks`; it is excluded in `app/build.gradle.kts` (only `ModelProvider`/`AiPackModelProvider` use it,
  which Latent never calls). Release dependency tree checked: no `com.google.android.play`, no
  `com.google.android.gms`, no `datatransport`, no Firebase. Merged manifest: the same permissions as
  before (LiteRT's manifest asks for `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`, which the app
  had already); optional `uses-native-library` entries for OpenCL and vendor NPU libraries; LiteRT's
  `android.hardware.npu` feature is removed.
- R8: `-keep class com.google.ai.edge.litert.** { *; }` (the JNI library builds `TensorType`,
  `TensorBufferRequirements` and `LiteRtException` by name), `-dontwarn` for the excluded Play classes.
- 16 KB pages: all six 64-bit LiteRT libraries in the built release APK have `LOAD` alignment 2^14
  (`objdump -p`), and `zipalign -c -P 16 4` passes (stored uncompressed, page-aligned).
- Size: release APK (all ABIs, debug-signed) 5.54 MB before, 38.67 MB after: LiteRT's native code for
  arm64-v8a, armeabi-v7a and x86_64 (about 9.4, 6.3 and 12.2 MB), the model (4.78 MB) and the notices
  (0.23 MB compressed). Per 64-bit ARM phone (what Play delivers from a bundle) about +14.5 MB on disk and
  about +8.6 MB to download (gzip estimate). LiteRT ships no 32-bit x86 library: there the buttons hide.
  Since 2026-10-21 every build carries arm64-v8a only (`RELEASES.md`).

### How a mask is made

1. The editor's own picture (raw files from the raw path, JPEGs from `PhotoEnhancer.load`; whole, upright,
   uncropped, lens corrected as the edit says, blemishes not taken out) is rendered with default settings.
2. It is resampled to at most 1024 px on the long side (area average), and the model gets that, stretched
   to 320 × 320.
3. **Edges:** a guided filter (`GuidedMap`) works out, at the model's size, how its answer follows the
   picture's brightness (radius 1 % of the long side, about 3 cells; edge threshold 5e-4), and that slope and
   offset are applied to the brightness at the outline's size. On a synthetic edge that the model puts in the
   right place but spreads over one of its cells, this takes most of the softness out (the error against a
   hard step falls below 40 % in `SubjectMaskTest`, to about a fifth in a 1-D simulation); a blur of two or
   more model cells is only slightly improved. It cannot move an edge the model put in the wrong place, and
   colour edges of equal brightness stay as soft as the model's.
4. The outline is kept as 8-bit levels, deflated, in `files/masks/<32 hex>.mask` (magic `MSK1`, width,
   height): typically tens of kilobytes. `files/` is already out of cloud backup and goes along on a device
   transfer (`data_extraction_rules.xml`).

### In the edit and in the renderers

- `MaskShape.DETECTED` with a `Detection(kind = SUBJECT, raster = id, model = "u2netp", modelVersion = 1)`;
  the edit JSON holds only that (`"detected": {...}`). Background is the same outline with `inverted`; the
  model is asked once per photo and session (Background after Subject reuses the outline).
- Both renderers use the brush mask's grid (`BrushRaster`; GPU shape id 1, the shader's coverage buffer),
  so CPU and GPU read the same numbers. The grid now carries the inversion itself (for brush masks this is
  the same result as before); for a detected mask the inversion comes before the strokes, so painting on a
  Background mask adds to the background. Feather softens the outline's edge (up to 3 % of the long side)
  and, as for brushes, the strokes' edges. A new detected mask starts with feather 0.
- **Brush add/erase on top** is in: the panel shows Paint/Erase and the brush size for detected masks, and
  strokes are drawn onto the outline's grid (1024 cells, so a stroke costs more there than on a brush mask's
  384).
- A lost outline (file gone) or a kind this version does not know covers nothing, inverted or not, and the
  panel says so with "Find again", which keeps strokes and settings. Old edits read as before.
- Outlines that no photo's edit and no kept version name are deleted a day after they were made (checked when
  the editor first opens a photo). Copying a look (`withLookOf`), filters and presets never carry masks.

### Not verified (no phone was attached)

Whether LiteRT loads and the model runs on the S24/S26 at all, on the GPU and on the CPU; the times; the
memory; whether every op stays on the GPU; the quality on real photos; GPU and CPU agreement on the outline;
the shader with a detected mask. `SubjectMaskOnDeviceTest` checks the shapes, the sigmoid range and a plain
subject on both accelerators and logs their times; see `DEVICE_CHECKS.md`.

## The answer

**Yes, and without giving up "no network".** Latent can offer Lightroom-style Subject, Background, Sky and
tap-to-select masks with models shipped inside the app and run by **LiteRT** (the renamed TensorFlow Lite),
which has no telemetry dependency and declares no permission [14][15]. Nothing is downloaded at runtime,
no new permission is needed, the Data safety answers stay "nothing collected" [24].

Cost: about **9 MB** of native runtime (CPU + GPU, arm64, uncompressed; about 4 MB to download) [14][15],
plus **14 to 30 MB** of models for the three stages (*estimate*, by precision), plus a new mask type whose rendering reuses the brush
mask's raster path in both renderers. Each mask is computed once per photo, on demand, in well under a
second on a Snapdragon 8 Gen 3 (*estimate* from published Galaxy S24 numbers [16][19][20]).

**Not** recommended: Google's ready-made **ML Kit** and **MediaPipe Tasks** libraries. Both pull in Google's
`datatransport` uploader (it declares `INTERNET`) and their terms say they send usage metrics to Google
[6][7][9][10][11]; ML Kit's subject segmenter cannot be bundled at all, its model is fetched by Google Play
services [1][2]. Either would make PRIVACY.md's "makes no network request at all" untrue. Google's *model
files* (Apache 2.0) can still be used, run by LiteRT directly.

## What Lightroom offers (the target)

Subject, Sky, Background, Objects, People, and Landscape (sky, snow, architecture, vegetation, water,
natural ground, artificial ground, mountains), next to brush, linear, radial and range masks; a selection
is refined with Add or Subtract [30].

## How it fits Latent

Masks today: `Mask.weightAt` gives 0..1 per pixel in whole-upright-picture coordinates; a brush mask is a
`BrushRaster` (384 cells on the long side, bilinear lookup), and `GpuTone` already uploads that coverage
array with its width and height to the shader (`MaskShape.BRUSH -> 1`). `GuidedMap` is a guided filter whose
slope and offset are worked out on a small copy and applied at full size against brightness.

Proposed shape (for the implementing session to refine):

- **New `MaskShape.DETECTED`** (name open) with a `kind` (SUBJECT, SKY, OBJECT + tap points), the model's id
  and version, and the id of a stored raster. Background = Subject with `inverted = true` (already there).
- **Computed once, on demand** (when the person taps "Subject"), never at import. Input: the photo as developed
  with default settings (raw files included), upright and *uncropped*, so the raster lives in the same
  coordinates as brush strokes. About 512 px on the long side for the model input (U2-Netp takes 320 [22]).
- **Edges:** run `GuidedMap.of(brightness, mask, w, h, radius, eps)` on the model output at 512 px, with
  the photo's brightness as the guide. A low-resolution segmentation followed by guided-filter upsampling is
  how Google Research's low-light sky processing runs on a phone (under half a second end to end) [31].
  Two options:
  - **A (first):** store the refined mask as an 8-bit grey raster, 1024 px on the long side (*estimate*
    100 to 300 KB as PNG). Rendering is then exactly the brush path: `MaskWeights` on the CPU, the coverage
    buffer in `GpuTone`. No shader change; CPU and GPU agree because both read the same stored raster.
  - **B (if hair and branches look soft):** store slope and offset at 512 px and evaluate
    `slope * luma + offset` at full size in both renderers, the way stage maps already are. The guide luma
    must come from the *unedited* source pixels, or the mask would move when exposure changes.
- **Brush on top:** start the raster's coverage from the stored mask, then stamp add and erase strokes as
  `BrushRaster` does now. That gives Lightroom's add/subtract for free.
- **Storage:** `filesDir/masks/<mask id>.png`, referenced from the edit JSON. `data_extraction_rules.xml`
  already keeps `file` out of cloud backup and moves it on device transfer. If the file is lost and the model
  is present, recompute it from the kept kind, points and model id; keep the old raster when a newer model
  ships, so old edits do not change by themselves.
- **Tests:** the model sits behind an interface; Robolectric tests feed synthetic rasters through both
  renderers. Model input and output layout need an instrumented test on the phone (LiteRT is native code).

## Candidates

Licence is the first filter: Play distribution means commercial redistribution must be allowed.

### Ready-made Google libraries

| Library | Delivery | Network and data | Size in app | Verdict |
|---|---|---|---|---|
| ML Kit Subject Segmentation `play-services-mlkit-subject-segmentation:16.0.0-beta1` (people, pets, objects; several subjects; confidence mask) | Unbundled only: model downloaded by Google Play services at install or first use; requests before the download return nothing [1][2] | Terms: input stays on the device, but "metrics about the performance and utilization" go to Google; the developer must tell users [9]. Data disclosure lists device, app and performance data sent over HTTPS [8]. Depends on `datatransport` `transport-backend-cct`, which declares `INTERNET` [10] | About 200 KB; model size not published [1] | No. About 200 ms on a Pixel 7 Pro [3]. Beta, unchanged since 2023-11-07 [5]. No Play services, no feature |
| ML Kit Selfie Segmentation `segmentation-selfie:16.0.0-beta6` | Bundled, +4.5 MB [4] | Same telemetry dependencies [10] | 4.5 MB | No: people only, selfie-tuned |
| MediaPipe Tasks `tasks-vision:1.1.1` (Image and Interactive Segmenter) | Bundled; model in assets [12] | Terms: sends usage metrics and "will contact Google servers from time to time" for fixes, models, accelerator data; no opt-out described [11]. `tasks-core` depends on `transport-backend-cct` [10] | *Measured:* `tasks-core` 14.4 MB + `litertlm-android` (pulled by `tasks-vision` 1.1.1) 22.2 MB arm64 `.so` [10] | No as a library. Use its model files with LiteRT |

### Subject and background (salient object) models

| Model | Licence | File | Input | Speed evidence | Verdict |
|---|---|---|---|---|---|
| **U2-Netp** | Apache 2.0 [22] | 4.7 MB (fp32 `.pth`) [22] | 320×320 [22] | LiteRT GPU on Galaxy S24: `u2net_lite` 51.4 ms, `u2net_full` 98.3 ms (input size not stated) [16] | **Stage 1 default.** Small and clean licence; quality on real photos is the open risk |
| IS-Net (DIS, `isnet-general-use`) | Code Apache 2.0; weights' licence not stated; "academic version" [23]. A community ONNX copy is labelled AGPL-3.0 [26] | 176 MB fp32, 44 MB int8 ONNX [26] | 1024 | S24 GPU 43.1 ms [16] | Only with the authors' written OK; large even in int8 |
| BiRefNet_lite | MIT [27] | 44.4M params; 177.6 MB fp32, 114.5 MB fp16 ONNX [26][27] | 1024 [27] | No phone numbers published [27] | Best edges of the permissive ones; only as a later, optional download-with-the-app pack |
| RMBG-1.4 / RMBG-2.0 (BRIA) | Non-commercial (CC BY-NC); commercial needs an agreement [28] | 176 MB / 0.2B params [26][28] | 1024 | – | **Excluded** |
| MODNet | Apache 2.0 [29] | – | – | – | People (portrait matting) only |
| MediaPipe Selfie multiclass | Apache 2.0 (model card) [13] | 16.4 MB [13] | 256 (a 512 version exists) [13] | Pixel 6: CPU 218 ms, GPU 71 ms [12] | Later, for a "People" mask with hair, face skin, body skin, clothes |

### Tap to select (promptable)

| Model | Licence | File | Input | Speed evidence | Verdict |
|---|---|---|---|---|---|
| **MediaPipe MagicTouch v1** | Apache 2.0 (model card, 2023-03-13) [13] | 6.2 MB `.tflite` [13] | 512×512×4: RGB plus a one-hot "prior map" at the tapped point; 2-channel output [13] | "~30 FPS on a Pixel 7 via TFLite GPU" [13] | **Stage 3.** Single small network, runs on plain LiteRT |
| MagicTouch v2 (current guide) | (same card linked) | 30.5 MB `.task`, int8 [12][13] | 768×768 [12] | Pixel 10: CPU 208 ms, GPU 581 ms [12] | Needs the Tasks bundle format; v1 first |
| **EdgeTAM** (Meta) | Apache 2.0, code and weights [25] | 56.1 MB `.pt` [26]; image encoder 33 MB float [19] | 1024 [19] | AI Hub, Galaxy S24 NPU: encoder about 16 ms float, decoder about 4 ms [20] | Stage 4 if MagicTouch is not good enough |
| MobileSAM | Apache 2.0 [21] | 40.7 MB `.pt` [26] | 1024 | AI Hub S24: encoder 80 ms (QNN) or 490 ms (TFLite NPU), decoder 3.4 ms [18] | Possible, slower than EdgeTAM |
| EfficientSAM-Ti | Apache 2.0 [21] | 24.8 MB encoder + 16.6 MB decoder ONNX [26] | 1024 | – | Possible |
| SAM 2.1 tiny | Apache 2.0 [21] | 156 MB [26] | 1024 | – | Too big |
| SAM 3 | "SAM License", 848M params [21] | – | – | – | No |
| EdgeSAM | NTU S-Lab License 1.0: non-commercial only [21] | – | – | – | **Excluded** |
| FastSAM, Ultralytics YOLO-seg | AGPL-3.0 [21] | – | – | – | **Excluded** |

### Sky (and later the landscape set)

ADE20K's 150 classes include sky, tree, plant, grass, water, sea, river, lake, mountain, hill, building,
house, road, path, earth, sand, rock and field, and also person and animal [44]: nearly all of Lightroom's
Landscape list (no snow class). Using only the sky channel of one such model gives Select Sky; the rest can
come later at no extra size.

| Model | Licence | File | Input | Speed evidence | Verdict |
|---|---|---|---|---|---|
| **EfficientViT-Seg B1 (ADE20K)** | Apache 2.0 (code [21] and weights on Hugging Face [26]) | 19.3 MB fp32 `.pt` [26]; 4.8M params, 3.1 GMACs [32] | 512×512 [32] | Jetson Nano 110 ms [32]; phone: none published | **Stage 2.** Has a TFLite export script [32] |
| TF DeepLab MobileNetV2 (ADE20K) | Apache 2.0 [33] | 24.8 MB [33] | 257 [33] | – | Fallback sky model; old (2018), coarse |
| TopFormer | LICENSE holds both Apache 2.0 and MIT [34] | 1.4 to 5.1M params [34] | 512 | Snapdragon 865 chart only [34] | Alternative |
| SegFormer (NVIDIA) | Non-commercial (research or evaluation only) [35] | – | – | AI Hub S24 NPU 18 ms float [20] | **Excluded** |
| Sky-Segmentation-and-Post-processing | MIT code; ONNX on Baidu, the good model unpublished [36] | – | – | U-2-Net about 300 ms on Snapdragon 888 [36] | No |
| Classical (top-connected bright, low-texture, blue or grey region, then the guided filter) | Own code | 0 | – | – | Only as a "no model" fallback; fails on haze, sunsets, night |

MagicTouch's model card says it was trained on images that include consented photos of people taken with
phones in an AR app [13]. The academic models were trained on research datasets; ADE20K's images in
particular are offered for non-commercial research and education only [37]. The weights' own licences allow
commercial use, but whether the data's terms carry over is not settled. Owner's call (see open questions).

## Runtimes

| Runtime | Version (Oct 2026) | arm64 native code (*measured*) | 16 KB aligned (*measured*) | Notes |
|---|---|---|---|---|
| **LiteRT** `com.google.ai.edge.litert:litert` (+ `litert-gpu`) | 2.3.0 [15] | 5.63 MB (2.34 MB compressed) + GPU 3.25 MB (1.50 MB) [14][15] | Yes | No telemetry, no permissions in its manifest; `litert-api` pulls Play's `ai-delivery` (alpha, no permissions) [14]. CompiledModel API for CPU, GPU (OpenCL/OpenGL), NPU [15][16]. **Recommended** |
| ONNX Runtime `onnxruntime-android` | 1.31.0 [14] | 34.3 MB [14] | Yes | MIT. Four times LiteRT's size; a QNN build exists (`onnxruntime-android-qnn`) [14] |
| ExecuTorch `executorch-android` | 1.5.1 [14] | 9.06 MB (XNNPACK CPU) [14] | Yes | Natural for PyTorch models; Vulkan and QNN come as separate AARs [45] |
| MediaPipe Tasks | 1.1.1 | 36.5 MB with LiteRT-LM [10] | Yes | Telemetry (above) |

- **NPU is not needed.** A mask is made once per photo; GPU or even CPU time is fine. LiteRT does list the
  Snapdragon 8 Gen 3 (SM8650) for its Qualcomm NPU path [17], but that needs Qualcomm runtime libraries
  delivered with Play Feature Delivery, and either per-chip compiled models in AI packs or on-device
  compilation with "a higher first-run cost" [43].
- **16 KB pages:** Play will refuse updates without 16 KB page support for apps targeting Android 15+ from
  2027-02-01 [38]. Latent has no native code today; LiteRT would be the first. All AARs above are aligned
  (`LOAD` alignment 2^14), and AGP 8.5.1 or later (Latent uses 9.4.1) packs `.so` files uncompressed as
  required [38].
- **Conversion:** PyTorch models go to `.tflite` with LiteRT Torch (`google-ai-edge/litert-torch`, formerly
  `ai-edge-torch`, Apache 2.0) [21]. Each converted model must be checked to run fully on the GPU delegate
  (the LiteRT table marks some models "partially delegated" [16]).

## Shipping the model without `INTERNET`

| Way | Works offline | Play only | Notes |
|---|---|---|---|
| **Base APK `assets/`** (stored uncompressed) | Yes, always | No (debug and sideloaded builds too) | Counts towards the 500 MB base limit [39]. **Recommended while models stay under about 30 MB** |
| Install-time asset pack or AI pack | Yes, installed with the app | Yes | Read with `AssetManager`; AI packs up to 1.5 GB, need AGP 8.8+ [40][41] |
| Fast-follow or on-demand pack | After Play downloads it | Yes | Can be missing; files may be deleted; the app must check every launch [40][41] |

Downloads by Play are done by the Play Store, not the app, so no `INTERNET` is needed for them (not stated
in Google's pages; the AI delivery library declares no permission [14]).

## Privacy, Data safety, Play

- **Bundled LiteRT + bundled models:** photos are processed only on the phone, which Play does not count as
  collection [24]. No change to the Data safety form. PRIVACY.md could add one sentence ("Automatic masks
  are made on the phone by a model inside the app"); the listing can say "subject and sky masks, made on
  the phone".
- **ML Kit or MediaPipe Tasks:** SDK traffic counts as the app's collection "irrespective of whether data
  is transmitted to you or a third-party server" [24], so the form would need App info and performance /
  diagnostics as collected, and PRIVACY.md's "no analytics" and "no network request with maps off" would
  have to go. ML Kit also needs Google Play services and a first download.
- Play's AI-generated content policy covers generative AI made from prompts [42]; segmentation is not that.

## When the model is missing or fails

- Bundled in the base APK it is never missing. If LiteRT fails to load (unsupported device, out of memory),
  hide the Subject/Sky/Tap buttons and say why once; the rest of the editor is untouched.
- Masks already made keep rendering from their stored raster, model or not.
- With a pack or Play services: buttons show "not ready" until the model is there; a raster that has to be
  recomputed without a model renders as empty with a "detect again" note, never as a wrong mask.

## Staged plan

| Stage | What | Size | Added to the app | Only a phone can verify |
|---|---|---|---|---|
| 0 | Bake-off outside the repo: U2-Netp, IS-Net, BiRefNet_lite and EfficientViT-B1 on 30 to 50 of the owner's photos (ZV-E10 and phone), desktop Python | S | – | (desktop) which subject model is good enough |
| 1 | Pipeline + **Subject / Background**: LiteRT, `DETECTED` mask type, stored raster (option A), guided-filter refine, brush add/erase on top, UI buttons | M | about 9 MB runtime + 2.5 to 5 MB model (fp16 to fp32, *estimate*) | Latency (cold, warm, GPU vs CPU), first GPU start, quality on people, pets, food, products; mask still aligned after crop, rotate, perspective; memory on 24 MP raw |
| 2 | **Sky** from EfficientViT-B1 ADE20K, sky channel only | M | 5 to 19 MB (int8 to fp32, *estimate*) | Tree branches and wires against sky, haze, sunsets, night, reflections in water; edges with strong local exposure |
| 3 | **Tap to select** with MagicTouch v1; several taps add or subtract | M | 6.2 MB | Hitting small objects with a finger; speed per tap; agreement between preview and saved JPEG |
| 4 (optional) | Option B edges; Landscape set from the stage 2 model; People with parts (Selfie multiclass); EdgeTAM or BiRefNet_lite as a pack if quality asks for it | L | 0 to 120 MB | Hair edges at full size; whether people parts work on full-body travel shots |

Stages 1 to 3 together: about 23 to 40 MB installed (*estimate*), no new permission, no network.

## Recommendation

1. Run models with **bundled LiteRT 2.3.0** (CPU + GPU); do not use ML Kit or MediaPipe Tasks libraries.
2. Ship models in the **base APK** assets; no asset packs until models pass about 30 MB.
3. Start with a one-day **bake-off** on the owner's photos, then build **Subject/Background** (U2-Netp unless
   the bake-off says otherwise), then **Sky** (EfficientViT-B1 ADE20K), then **tap** (MagicTouch v1).
4. Store each detected mask as a 1024 px raster and render it through the existing brush path, so CPU and
   GPU agree by construction; keep guided upsampling at full size (option B) for later.
5. Keep the privacy promise word for word; add one sentence about on-device masks.

## Decisions of the owner (2026-10-15)

- **Training-data risk (question 3): accepted.** The app is not a commercial release; models with Apache or MIT
  weights trained on research datasets (DUTS, ADE20K) are used. If a rights holder asks for a model to be taken
  out, it is replaced then. Keep the model behind an interface and its source, licence and checksum written down
  here, so that this stays a one-file swap. This does *not* open the non-commercial models (RMBG, EdgeSAM,
  SegFormer) or the AGPL ones: the app's code is Apache 2.0 and public, and a free app can still be monetised
  later, so they stay out.
- Models are bundled in the base APK, no ML Kit and no MediaPipe Tasks library (question 1, as recommended).
- Order (question 4): as the stages below say, subject and background first, since they share the runtime,
  the mask type, the stored raster and the rendering with everything that follows.
- Size budget, mask resolution and builds without Google Play (questions 2, 5, 6): as recommended.

## Open questions for the owner

1. **Privacy line:** accept ML Kit's Play-services download and Google telemetry for its better-known
   subject model, or require bundled models only (recommended)?
2. **App size budget:** is +23 to 40 MB acceptable? Would a later optional pack (BiRefNet_lite, about
   60 to 115 MB) be acceptable for best edges?
3. **Training data risk:** are Apache/MIT weights trained on research datasets (ADE20K, DUTS) acceptable for
   a Play app, or only models trained on cleared data (Google's MediaPipe models)? The latter rules out an
   automatic sky model for now and leaves tap-to-select plus a classical sky.
4. **Order:** Subject first (as the roadmap says) or Sky first (lower model risk for landscapes and travel)?
5. **Edge quality:** is a 1024 px mask (soft at 24 MP) fine for the first version?
6. **Other stores and sideloading:** must every build work without Google Play? (Base APK assets do.)

## Sources

1. ML Kit subject segmentation for Android (delivery, SDK version, ~200 KB, beta): https://developers.google.com/ml-kit/vision/subject-segmentation/android
2. ML Kit installation paths (bundled vs unbundled, models in Play services): https://developers.google.com/ml-kit/tips/installation-paths
3. ML Kit subject segmentation overview (~200 ms Pixel 7 Pro, on-device): https://developers.google.com/ml-kit/vision/subject-segmentation
4. ML Kit selfie segmentation for Android (bundled, +4.5 MB, 256 px, 25 to 65 ms Pixel 4): https://developers.google.com/ml-kit/vision/selfie-segmentation/android
5. ML Kit release notes (subject segmentation 16.0.0-beta1, 2023-11-07): https://developers.google.com/ml-kit/release-notes
6. Google Maven metadata, `play-services-mlkit-subject-segmentation` (latest still 16.0.0-beta1): https://dl.google.com/android/maven2/com/google/android/gms/play-services-mlkit-subject-segmentation/maven-metadata.xml
7. ML Kit POM, subject segmentation dependencies (`transport-backend-cct`): https://dl.google.com/android/maven2/com/google/android/gms/play-services-mlkit-subject-segmentation/16.0.0-beta1/play-services-mlkit-subject-segmentation-16.0.0-beta1.pom
8. ML Kit Android data disclosure: https://developers.google.com/ml-kit/android-data-disclosure
9. ML Kit terms (metrics sent to Google, developer must inform users): https://developers.google.com/ml-kit/terms
10. Maven artifacts inspected here: `transport-backend-cct` 3.1.0 manifest (`INTERNET`, `ACCESS_NETWORK_STATE`) https://dl.google.com/android/maven2/com/google/android/datatransport/transport-backend-cct/3.1.0/transport-backend-cct-3.1.0.aar ; `tasks-vision` 1.1.1 POM https://dl.google.com/android/maven2/com/google/mediapipe/tasks-vision/1.1.1/tasks-vision-1.1.1.pom ; `tasks-core` 1.1.1 https://dl.google.com/android/maven2/com/google/mediapipe/tasks-core/1.1.1/tasks-core-1.1.1.aar ; `litertlm-android` 0.18.0 https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.aar ; `segmentation-selfie` 16.0.0-beta6 POM https://dl.google.com/android/maven2/com/google/mlkit/segmentation-selfie/16.0.0-beta6/segmentation-selfie-16.0.0-beta6.pom
11. MediaPipe APIs terms of service (metrics, contacting Google servers, consent; modified 2026-04-07): https://developers.google.com/edge/mediapipe/legal/tos
12. MediaPipe image segmenter (models, Pixel 6 latencies) https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter ; interactive segmenter (MagicTouch 768 int8, Pixel 10 latencies) https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter ; Android guide https://developers.google.com/edge/mediapipe/solutions/vision/interactive_segmenter/android
13. MediaPipe model cards and files (licence, input, data; sizes from HTTP headers): https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MagicTouch.pdf , https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Multiclass%20Segmentation.pdf , https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/latest/magic_touch.tflite , https://storage.googleapis.com/mediapipe-models/interactive_segmenter_v2/magic_touch/int8/latest/interactive_segmentation.task , https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite
14. Maven artifacts inspected here (sizes, alignment, dependencies): LiteRT https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert/2.3.0/litert-2.3.0.aar , LiteRT GPU https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert-gpu/2.3.0/litert-gpu-2.3.0.aar , `litert-api` POM https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert-api/2.3.0/litert-api-2.3.0.pom , Play AI delivery https://dl.google.com/android/maven2/com/google/android/play/ai-delivery/0.1.1-alpha01/ai-delivery-0.1.1-alpha01.aar , ONNX Runtime https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar , ONNX Runtime QNN https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/maven-metadata.xml , ExecuTorch https://repo1.maven.org/maven2/org/pytorch/executorch-android/1.5.1/executorch-android-1.5.1.aar
15. LiteRT for Android (artifacts, 2.3.0 in the quickstart, release table, CompiledModel): https://developers.google.com/edge/litert/android
16. LiteRT GPU (Galaxy S24 benchmark table: isnet, u2net, deeplab, "fully/partially delegated"): https://developers.google.com/edge/litert/next/gpu
17. LiteRT Qualcomm NPU (SM8650 supported): https://developers.google.com/edge/litert/next/qualcomm
18. Qualcomm AI Hub, MobileSAM performance (Galaxy S24): https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/models/mobilesam/perf.yaml
19. Qualcomm AI Hub model pages (EdgeTAM, MobileSAM: licence, sizes, input): https://aihub.qualcomm.com/models/edgetam , https://aihub.qualcomm.com/models/mobilesam
20. Qualcomm AI Hub performance files (EdgeTAM, Segformer-Base, Galaxy S24): https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/models/edgetam/perf.yaml , https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/models/segformer_base/perf.yaml
21. Repositories and licences (GitHub API, 2026-10-15): https://github.com/ChaoningZhang/MobileSAM , https://github.com/yformer/EfficientSAM , https://github.com/facebookresearch/sam2 , https://github.com/facebookresearch/sam3 , https://github.com/chongzhou96/EdgeSAM/blob/master/LICENSE , https://github.com/mit-han-lab/efficientvit , https://github.com/google-ai-edge/litert-torch , https://github.com/CASIA-IVA-Lab/FastSAM , https://github.com/ultralytics/ultralytics
22. U-2-Net (Apache 2.0, u2netp 4.7 MB, 320×320): https://github.com/xuebinqin/U-2-Net
23. DIS / IS-Net (code Apache 2.0, academic weights, DIS5K terms): https://github.com/xuebinqin/DIS
24. Play Data safety (what counts as collection, SDKs): https://support.google.com/googleplay/android-developer/answer/10787469
25. EdgeTAM (Apache 2.0 code and checkpoints, 16 FPS iPhone 15 Pro Max): https://github.com/facebookresearch/EdgeTAM
26. Hugging Face model listings (file sizes, licence tags): https://huggingface.co/han-cai/efficientvit-seg , https://huggingface.co/onnx-community/BiRefNet_lite-ONNX , https://huggingface.co/facebook/EdgeTAM , https://huggingface.co/facebook/sam2.1-hiera-tiny , https://huggingface.co/dhkim2810/MobileSAM , https://huggingface.co/onnx-community/ISNet-ONNX , https://huggingface.co/briaai/RMBG-1.4 , https://huggingface.co/yunyangx/EfficientSAM
27. BiRefNet (MIT) https://github.com/ZhengPeng7/BiRefNet and BiRefNet_lite (44.4M params, 1024) https://huggingface.co/ZhengPeng7/BiRefNet_lite
28. RMBG-2.0 licence (CC BY-NC 4.0) https://huggingface.co/briaai/RMBG-2.0 ; RMBG-1.4 https://huggingface.co/briaai/RMBG-1.4
29. MODNet (Apache 2.0, portrait matting): https://github.com/ZHKKKe/MODNet
30. Adobe Lightroom Classic masking help (mask types, Landscape features) https://helpx.adobe.com/lightroom-classic/desktop/process-and-develop-photos/masking.html ; Lightroom masking help (Add or Subtract) https://helpx.adobe.com/lightroom/desktop/edit-photos/masking.html
31. Liba et al., "Sky Optimization", CVPR Workshops 2020 (low-res segmentation, guided-filter upsampling, under 0.5 s on a phone): https://openaccess.thecvf.com/content_CVPRW_2020/html/w31/Liba_Sky_Optimization_Semantically_Aware_Image_Processing_of_Skies_in_Low-Light_CVPRW_2020_paper.html
32. EfficientViT segmentation (ADE20K B1: 4.8M params, 3.1 GMACs, 512, Jetson latency, TFLite export): https://github.com/mit-han-lab/efficientvit/blob/master/applications/efficientvit_seg/README.md
33. TensorFlow DeepLab model zoo (mobilenetv2_ade20k_train, 257, 24.8 MB) https://github.com/tensorflow/models/blob/master/research/deeplab/g3doc/model_zoo.md ; licence https://github.com/tensorflow/models/blob/master/LICENSE
34. TopFormer (ADE20K sizes; LICENSE with Apache 2.0 and MIT text): https://github.com/hustvl/TopFormer , https://github.com/hustvl/TopFormer/blob/main/LICENSE
35. SegFormer licence (non-commercial): https://github.com/NVlabs/SegFormer/blob/master/LICENSE
36. Sky-Segmentation-and-Post-processing (MIT, weights, speed): https://github.com/xiongzhu666/Sky-Segmentation-and-Post-processing
37. ADE20K terms as quoted by mirrors (images for non-commercial research and education): https://huggingface.co/datasets/1aurent/ADE20K/blob/main/README.md
38. Android 16 KB page sizes (Play deadline 2027-02-01 for updates, packaging): https://developer.android.com/guide/practices/page-sizes
39. Play maximum size limits (base 500 MB, packs, 200 MB mobile-data dialog): https://support.google.com/googleplay/android-developer/answer/9859372
40. Play Asset Delivery (install-time, fast-follow, on-demand): https://developer.android.com/guide/playcore/asset-delivery
41. Play for On-device AI (AI packs, beta, 1.5 GB, AGP 8.8+): https://developer.android.com/google/play/on-device-ai
42. Play AI-generated content policy (scope): https://support.google.com/googleplay/android-developer/answer/13985936
43. LiteRT NPU (AI packs, Play Feature Delivery for runtime libraries, on-device compilation): https://developers.google.com/edge/litert/next/npu
44. ADE20K class list (150 labels): https://huggingface.co/datasets/huggingface/label-files/blob/main/ade20k-id2label.json
45. ExecuTorch 1.0.0 release notes (Vulkan and QNN AARs on Maven): https://newreleases.io/project/pypi/executorch/release/1.0.0
