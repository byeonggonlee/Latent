# Antigravity Review and Model Orchestration Guidelines

Established on 2026-10-22 by the owner's decision in [`decisions.md`](decisions.md). This document defines the
operating rules, responsibility boundaries, model-tier assignments, and review/handoff protocols for **Antigravity**
and the models running inside it (**Gemini 3.8 Flash**, **Gemini 4 Argon**, and **Claude Opus 5.5**).

---

## 1. Core Mandate: Strict Non-Authoring Boundary

Antigravity is dedicated exclusively to **code review, multi-model orchestration, and governance/assessment
documentation**.

- **Zero code authoring**: Antigravity never writes, edits, or commits production code (`app/src/main/`), unit or
  instrumentation tests (`app/src/test/`, `app/src/androidTest/`), vendored code (`third_party/`), Room schemas
  (`app/schemas/`), Gradle build scripts, or CI workflows (`.github/workflows/`).
- **Allowed write scope**: Antigravity writes only to its own assessment and governance files under `docs/`, to the
  shared coordination sections of [`docs/HANDOFF.md`](../HANDOFF.md) and [`docs/TODO.md`](../TODO.md) ("Waiting for
  the owner"), and to agent instruction files ([`AGENTS.md`](../../AGENTS.md), [`GEMINI.md`](../../GEMINI.md)) when
  the owner asks to update governance rules.
- **No silent fixes**: When a review uncovers a bug, missing edge-case check, contract mismatch, or missing test,
  Antigravity never patches the branch itself. Instead, it documents the exact file, line range, failure mechanism,
  and required regression test in the PR review and routes the follow-up to the responsible authoring agent's queue
  in [`docs/HANDOFF.md`](../HANDOFF.md).

---

## 2. Responsibility Redistribution Across Agents

 Work is separated along two orthogonal axes: **Code Authoring** (strictly partitioned by subsystem) and **Code
Review & Orchestration** (shared across agents and orchestrated across models).

### 2.1 Code Authoring (Claude Code & Codex Only)

| Domain | Authoring Agent | Scope |
|---|---|---|
| **Image decoders & raw containers** | **Codex** | `app/src/main/java/com/byeonggon/latent/raw/` (`Tiff.kt`, `SonyArw.kt` reader, `Dng.kt`, `LosslessJpeg.kt`, `JpegXl.kt`), `third_party/jxlatte/` (with `latent.patch` and README), `data/MpfPreview.kt`, decoder unit tests (`app/src/test/.../raw/` except `RawRendererTest.kt`), raw test fixtures, and `RawOnDeviceTest.kt`. |
| **Shared decoder-to-app seam** | **Claude Code** or **Codex** (must claim first in `HANDOFF.md`) | `ArwImage` and `LensProfile` (in `raw/SonyArw.kt`), `RawDeveloper.kt`, `LensCorrection.kt`, `RawRenderer.kt`, `RawCache.kt`, and `RawRendererTest.kt`. Any change here requires a reviewed pull request and updates all callers in the same commit. |
| **Application, editor, storage, sync, UI & CI** | **Claude Code** | Editor, GPU/CPU renderers and shaders (`enhance/`), library, backup and restore (`backup/`), MTP and card sync (`camera/`), location/Timeline/map (`location/`), Compose UI and ViewModels (`ui/`), Room database and migrations (`data/`), CI/release workflows, and general documentation. |
| **Source code & tests** | **Antigravity** | **None.** Antigravity never authors code or tests in any domain. |

### 2.2 Code Review & Orchestration

Reviews are conducted by **Claude Code**, **Codex**, and **Antigravity**:

- **Antigravity** serves as the cross-cutting review and model-orchestration hub. Because it has no authoring stake in
  either codebase partition, it can review PRs from both Claude Code and Codex, audit the seam between them, and
  dispatch or synthesize reviews across its hosted models (**Gemini 3.8 Flash**, **Gemini 4 Argon**, and
  **Claude Opus 5.5**).
- **Codex** and **Claude Code** continue to review each other's pull requests—especially on the decoder seam,
  domain-specific invariants, and cross-agent handoffs—either independently or alongside Antigravity's reviews.
- **The owner** remains the final arbiter when reviewers disagree, and decides package identity, keys/secrets,
  release gating, phone verification judgements, and open architecture questions in [`docs/TODO.md`](../TODO.md).

---

## 3. Multi-Model Roles Inside Antigravity

Antigravity hosts three distinct model tiers. Each model has a defined review focus so that review depth matches the
risk of the change:

### 3.1 Gemini 3.8 Flash — Fast Triage, Surface & Contract Reviewer

- **Primary focus**:
  - Rapid scanning of open PRs (`gh pr list`, `gh pr view`, `gh pr diff`) and CI status (`gh pr checks`).
  - Classifying whether a diff triggers any of the eight mandatory review categories in `AGENTS.md`.
  - Checking caller/callee contract alignment (e.g., `ArwImage` field updates across `EditorViewModel`,
    `ViewerViewModel`, `LibraryEdits`, and `LatentApp`), commit trailer compliance, `docs/HANDOFF.md` claim/completion
    consistency, and `docs/DEVICE_CHECKS.md` updates.
  - Catching localized defects: missing resource translations (`values/`, `values-ko/`, `values-ja/`), obvious null/NaN
    oversights, and style/lint issues.
- **When to use**: Initial PR intake, status summaries, small or bounded PR reviews, and pre-screening before
  escalating high-risk diffs to Gemini 4 Argon or Claude Opus 5.5.

### 3.2 Gemini 4 Argon — Systems, Numerical, Concurrency & Seam Reviewer

- **Primary focus**:
  - **Numerical and parser safety**: Integer overflow and wrap-around in TIFF/EXIF/MPF offsets, unsigned-to-signed
    narrowing, `NaN`/`Infinity` propagation in edits/curves/LUTs/framing, and decompression/allocation bounds (ZIP,
    Timeline, lossless JPEG, JPEG XL).
  - **Pixel and colour invariants**: 14-bit `LinearImage` (`0..16383`) level preservation, camera-look fidelity
    (`CameraCurve`, Sony `1.45` vs. DNG `1.0` saturation, two-illuminant calibration interpolation, `WarpRectilinear`),
    and bit-for-bit equivalence on refactoring changes.
  - **Concurrency and buffer ownership**: Lock ordering (`moving`, `takingOver`, `RawCache`), main-thread safety,
    shared buffer mutation, and coroutine cancellation boundaries across decode, render, sync, and export paths.
  - **Workflow & governance orchestration**: Structuring multi-agent handoffs and maintaining repository guidelines.
- **When to use**: PRs touching decoders, the shared seam, image processing (`enhance/`), external-input parsers,
  or concurrency/cancellation paths.

### 3.3 Claude Opus 5.5 — Deep Architecture, Data Durability & Lifecycle Reviewer

- **Primary focus**:
  - **Data durability and crash/interruption recovery**: Ensuring photos, `.originals`, edits, and backups cannot be
    lost or corrupted across interrupted SAF document moves, partial restores, duplicate sync candidates, or Room
    database migrations (`app/schemas/`).
  - **Cross-module state machines**: Multi-step interactions across `SyncEngine`, `Restorer`, `LibraryRepository`,
    `EditSaver`, and Compose UI state lifecycles.
  - **Security, privacy, and CI hardening**: Verifying zero network/tracking regressions against `PRIVACY.md`,
    auditing GitHub Actions job permissions (`release.yml`, `nightly.yml`), and reviewing signing/artifact boundaries.
  - **Multi-review synthesis**: Resolving complex architectural trade-offs and preparing concise decision options for
    the owner when agents or models surface competing approaches.
- **When to use**: High-blast-radius PRs affecting user photos/data, Room schemas, backup formats, sync identity,
  privacy/security boundaries, or multi-module architectural changes.

---

## 4. Orchestration & Review Protocol

### Step 1: Intake and Scope Classification

1. Read [`docs/HANDOFF.md`](../HANDOFF.md) (*In progress*, agent queues) and inspect open PRs via `gh pr list`.
2. Record the exact head commit SHA of the PR being inspected (`gh pr view <id> --json headRefOid`).
3. Classify the PR against the mandatory review triggers in [`AGENTS.md`](../../AGENTS.md):
   - Decoder seam (`ArwImage`, `LensProfile`, `RawDeveloper`, `LensCorrection`, `RawRenderer`, `RawCache`, `LinearImage`)
   - Colour, precision, white balance, demosaicing, camera look, crop, or orientation
   - Buffer sharing, threads, locks, or coroutine cancellation
   - Room schema/migrations, backup format, or any path that could lose photos or user data
   - Permissions, network access, privacy, or location data
   - Signing, release/nightly workflows, or CI checks
   - New dependencies, native code, ABIs, or `third_party/` updates
   - Cross-area changes touching another agent's partition

### Step 2: Model Dispatch and Inspection

1. Match the PR's risk profile to the appropriate model tier (or run a tiered pass: fast structural check followed by
   deep domain inspection on seam/data-durability changes).
2. Inspect the full diff (`gh pr diff <id>`) and surrounding source context in read-only mode.
3. Check CI status on the exact head commit (`gh pr checks <id>`) and note what was verified by CI versus what
   remains a phone-only check in [`docs/DEVICE_CHECKS.md`](../DEVICE_CHECKS.md).

### Step 3: Identity-Transparent Review Publication

When publishing a review via `gh pr review`, always comply with these formatting and transparency rules:

1. **Explicit model & agent header**: Every review or PR comment posted from Antigravity must begin by stating the
   exact model that performed the review:
   ```markdown
   Reviewed by **<Model Name>** (via Antigravity) — inspected commit `<short-sha>`.
   ```
   Examples:
   - `Reviewed by **Gemini 3.8 Flash** (via Antigravity) — inspected commit 8e87f7e.`
   - `Reviewed by **Gemini 4 Argon** (via Antigravity) — inspected commit 07ba903.`
   - `Reviewed by **Claude Opus 5.5** (via Antigravity) — inspected commit 5555e10.`
2. **Structured body**:
   - **Scope**: Brief statement of the files and invariants inspected.
   - **Findings**: Prioritized list (`P1` data loss / crash / security, `P2` correctness / boundary / contract bug,
     `P3` minor edge case) with exact file paths, line numbers, concrete reproduction inputs, and expected behavior—or
     a concise `LGTM — no actionable issues found.` when clean.
   - **Verification & limits**: Which CI checks or local read-only verifications were confirmed on the head SHA, and
     which device/hardware checks remain unverified.
3. **GitHub review event**:
   - Use `--comment` when posting through the repository owner's `gh` account on a PR authored under the same GitHub
     account (since GitHub forbids self-approval), or `--approve` / `--request-changes` when account separation permits.
   - Link the published review URL in the chat response immediately after posting.

### Step 4: Routing Fixes to Authoring Agents

When a review identifies code changes that must be made:

1. **In-flight PRs**: Leave the actionable finding directly on the PR so the authoring agent (Claude Code or Codex)
   can address it on the PR branch.
2. **Follow-up or out-of-scope bugs**: Add an entry to `## For Codex` or `## For Claude Code` in
   [`docs/HANDOFF.md`](../HANDOFF.md) using the standard handover template:
   - **Why**: User-visible or data-integrity impact.
   - **Where**: Exact files and line numbers.
   - **Do**: Concrete specification of the fix (without writing the code).
   - **Test first**: The failing test case to write before fixing.
   - **Done when**: CI and `DEVICE_CHECKS.md` criteria.
   - **Risk / not to do**: Invariants and adjacent paths to preserve.
3. **Owner decisions**: If a finding exposes an unresolved product or architecture trade-off between agents, add the
   question under "Waiting for the owner" in [`docs/TODO.md`](../TODO.md) rather than deciding unilaterally.
