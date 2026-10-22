# DNG WarpRectilinear implementation plan

> Execution: inline, owner authorized the roadmap sequence on 2026-10-22.

Goal: support OpcodeList3 opcode 1 independently of Sony lens tables. Preserve the sensor samples and apply inverse geometry after development, before EXIF orientation, under the existing lens-correction toggle.

Design: immutable profile with one or three six-coefficient planes and active-area optical centre. Coordinates are integer pixel centres in the active rectangle, following Adobe DNG SDK dng_lens_correction.cpp (AOSP platform/external/dng_sdk). The existing default crop determines the available developed pixels. A common crop-centred zoom keeps all colour planes inside that rectangle. Preview uses the same full-resolution geometry and accounts for its block centres; its outer half-block and dropped remainder retain nearest-edge sampling approximation. Explicit nonsquare or invalid DefaultScale makes the new correction unavailable. Identity is a no-op. Unknown optional opcodes are skipped; unsupported required operations and malformed lists disable this optional correction rather than applying a partial chain. Multiple supported warps retain list order. Apply per-plane DNG warps to camera RGB before the colour matrix mixes channels; keep the existing matrix stage for every uncorrected/Sony path. No changes to Sony geometry, exposure, colour matrices, raw samples, dependencies or LinearImage meaning.

Tasks:
1. Add failing synthetic opcode/parser and independent coordinate/grid regressions.
2. Parse bounded big-endian opcode payloads; implement radial/tangential sampling, shared zoom, crop/preview coordinates and ordered transforms.
3. Carry the profile through ArwImage.part; connect proxy/full rendering and editor availability. Keep existing Sony tests unchanged.
4. Run decoder and renderer tests, then CI-equivalent checks and schema diff. Document objective fixture results and remaining phone/JPEG comparison.
5. Commit with Agent: Codex; obtain independent Claude review and open a PR for this seam/geometry change. Do not merge without the required review and green CI.

Validation includes malformed sizes/counts/planes/nonfinite coefficients, identity, hand-calculated radial and tangential positions, off-centre active area/default crop, per-plane behavior, preview/full parity, ordering and toggle/orientation. Real-photo matching and phone memory are evidence to collect, not inferred from synthetic tests.

Source: https://android.googlesource.com/platform/external/dng_sdk/+/refs/heads/master/source/dng_lens_correction.cpp

Decisions: explicit default-crop zoom follows HANDOFF item 2; no full-active RGB allocation is introduced. The parser advertises only a correction it can execute as a whole. RawCorrection records applied sample corrections only, so this pending geometry is not added there.

Execution evidence: the initial seven tests ran against a minimal contract and four failed for absent warp behavior; implementation made them pass. Internal independent review found an SDK pixel-centre mismatch. A new regression expected source-ramp value 800 and reproduced 790 before the integer-centre fix. The nonsquare and present-but-unreadable DefaultScale tests also failed before their availability guards. The reviewer classified outer-proxy edge replication as bounded block-sampling approximation rather than a new defect.

Claude CLI review was blocked by automatic approval review because it would send private repository diff/new files to an external service; no review payload was sent. The PR must retain Claude review as pending until the owner authorizes that transfer or reviews through their own Claude workflow. The owner supplied two private linear DNGs (4080×2298 lossless JPEG, 5712×4284 JPEG XL) and a camera JPEG. Downloaded locally with size/SHA-256 verification; both decoded and produced upright previews successfully. Neither DNG has OpcodeList3, so these prove codec/render compatibility, not warp-to-camera-JPEG matching or phone behavior. Photos and output artifacts remain outside Git.
