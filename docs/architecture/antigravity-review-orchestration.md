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

- **Claude Sonnet 5.5 authoring & reasoning-effort rules (in Claude Code)**:
  - **Claude Sonnet 5.5** may author code and tests within Claude Code's area only when running with a **minimum
    reasoning effort of `high` (extended thinking enabled)**—never at `low` or `medium`.
  - For changes touching any of the **mandatory-review categories** in [`AGENTS.md`](../../AGENTS.md) (the
    decoder seam, colour/precision, buffers/locks/cancellation, Room migrations/backup format/data-loss paths,
    privacy/permissions, signing/CI, dependencies/ABIs, another agent's area, and review governance), **Claude Opus
    5.5** is preferred; if authored by **Claude Sonnet 5.5**, **`high` or `max` (extended thinking)** reasoning effort is strictly required, and the PR description
    must note the authoring model and reasoning effort.

### 2.2 Code Review & Orchestration

Reviews are conducted by **Claude Code**, **Codex**, and **Antigravity**:

- **Antigravity** serves as the cross-cutting review and model-orchestration hub. Because it has no authoring stake in
  either codebase partition, it dispatches reviews mechanically through **Gemini 3.8 Flash** and conducts deep reviews
  across **Gemini 4 Argon** and **Claude Opus 5.5**. Antigravity keeps mechanical results, unverified candidate
  findings and deep-review conclusions distinct, and routes only confirmed actionable findings as required fixes.
- **Codex** and **Claude Code** continue to review each other's pull requests—especially on the decoder seam,
  domain-specific invariants, and cross-agent handoffs—either independently or alongside Antigravity's reviews.
- **The owner** remains the final arbiter when reviewers disagree, and decides package identity, keys/secrets,
  release gating, phone verification judgements, and open architecture questions in [`docs/TODO.md`](../TODO.md).

---

## 3. Multi-Model Roles Inside Antigravity

Antigravity hosts three distinct model tiers. Orchestration and status upkeep are separated from deep safety review so
that high-reasoning token budgets are spent where bugs cost user data:

### Review scope, evidence and cost

- **One deep reviewer by default.** Select one qualified reviewer under §3.4 who can inspect the full diff and all
  applicable mandatory-review risks. Focus expensive analysis and tests on the relevant invariants and their callers;
  do not reduce the review to a path-only scan. Add a second reviewer for a named coverage gap or conflicting findings,
  and record that reason; a second full review is not routine. Uncovered mandatory risks keep the review pending.
- **Roles are routing defaults, not demonstrated model rankings.** The specialties below guide assignment; a model
  name, higher effort setting or different model family does not establish correctness. Reviewers inspect the source
  and evidence independently, using the PR description as context rather than proof. Revisit routing with the owner
  using reproducible findings, false positives and exposed usage/cost from existing reviews; no benchmark or new
  measurement service is required by this policy.
- **Reuse mechanical results.** Prefer CLI/API queries or scripts for head-SHA comparisons, CI status and duplicate
  detection. Key completed review coverage to the inspected head SHA and base/diff scope. Do not repeat an unchanged
  review or its unchanged merge; a changed head or base requires checking what changed and retaining only still-valid
  coverage. A CI status change alone calls for a status update, not another model review. This does not start or resume
  any monitor; monitoring still needs owner authorization.

### 3.1 Gemini 3.8 Flash — Mechanical Dispatcher & First-Pass Candidate Finder

- **Primary focus (Orchestration & Dispatch)**:
  - **Intake and status tracking**: Scanning open PRs (`gh pr list`, `gh pr view`, `gh pr diff --name-only`), checking
    CI status (`gh pr checks`), and keeping `docs/HANDOFF.md` claims and status tables up to date.
  - **Mechanical classification against `AGENTS.md`**: Matching touched paths and topics against the mandatory
    review categories in [`AGENTS.md`](../../AGENTS.md) ("Getting to main") and selecting the required deep reviewer
    according to the author's model family (§3.4).
  - **One-way escalation ("when unsure, escalate")**: Classification is strictly mechanical. Any path or topic on the
    `AGENTS.md` mandatory list is escalated to a deep reviewer regardless of what Flash thinks of the diff. **Flash
    does not judge whether a mandatory-review change is safe, and may only escalate—never downgrade—a review.**
  - **Surface and contract checks**: Checking caller/callee alignment (`ArwImage` callers), commit trailers,
    `docs/DEVICE_CHECKS.md` entries, and resource translation completeness (`values/`, `values-ko/`, `values-ja/`).
- **Optional first-pass finding search**: Flash may flag suspected bugs while checking the surface contracts, but
  this is not a required extra pass on every PR. Its findings are **candidates**, not confirmed defects or merge
  clearance. Label unverified candidates explicitly; do not present them as required fixes or a safety verdict.
- **Deep-review validation duty**: A qualified deep reviewer independently checks each candidate's mechanism against
  the base and inspected head, recomputes numerical examples and bounds, checks failure and cancellation paths,
  assesses actual user impact and severity, and verifies that the proposed fix is sufficient. Do not assume a helper
  succeeds because it did not throw: check Boolean/error return contracts as well. Reuse validated candidates in the
  one deep review; Flash's pass does not require a second qualified reviewer by itself.
- **No inflated finding counts**: Fold weak-test observations and helper/design comments into the underlying defect
  when they describe the same failure. Redundancy or a design smell alone is not an actionable bug; require a concrete
  failing scenario. Distinguish newly introduced regressions from existing problems and route the latter separately.
- **Reviewer limit**: **Flash is never the sole code reviewer and never a fallback for a deep reviewer**, even at
  `high` effort. Its triage alone never satisfies the mandatory review requirement in `AGENTS.md`. Classification
  remains one-way escalation, and does not impose a review on small changes outside the mandatory list.

#### Evidence and possible follow-up evaluation

The owner supplied a comparison of Flash's review of draft [PR #36](https://github.com/byeonggonlee/Latent/pull/36)
at `af7d12c3f193e44846a5bf0f9e615443c29b905d` with an answer key kept outside the repository: four of five seeded
defects were detected, and one decoy was reported as a defect. The chat evaluations also corrected numerical
overstatements and severity judgements, and the initial independent evaluation also had a material omission.
This is one small, deliberately flawed PR, not an established detection rate or a comparison with other models.
It supports candidate finding with verification, not promotion to sole deep reviewer. It did not test Flash's primary
job of dispatch. No answer key or per-seed scoring details belong in this document.

If the owner requests another benchmark, the suggested scope is:

- **Dispatch first**: Three or four small PRs covering a mandatory-review trigger, plain docs, cancellation, and
  location data. Score required escalation, no downgrades, and no safety clearance of mandatory-review changes.
  Plain docs still require escalation when they change review governance or another mandatory topic.
- **Finding quality separately**: Include cancellation through broad exception handlers and helpers whose names do
  not suggest coroutine work, low-impact/no-caller defects beside data-loss risks, and harmless design-smell decoys.
  Score detection, numerical accuracy, user impact and severity ordering, fix sufficiency, false positives and decoy
  hits separately. Predeclare acceptable severity per defect rather than accepting every result within one level.
- **Benchmark integrity**: Existing tests, lint and CI stay green with the seeds present; added PR tests do not expose
  them. Use a neutral body with a benchmark/never-merge notice and the required authoring identity/settings line.
  Keep the answer key outside the repo, pinned to exact pushed-head lines, with precise conditional claims and one
  predeclared scoring rule per defect. A benchmark is never merged, and is closed after the authorized evaluation.

These are follow-up suggestions, not authorization to create benchmarks, seed bugs, or resume monitoring.

### 3.2 Gemini 4 Argon — Systems, Numerical, Concurrency & Seam Deep Reviewer

- **Primary focus**:
  - **Numerical and parser safety**: Integer overflow and wrap-around in TIFF/EXIF/MPF offsets, unsigned-to-signed
    narrowing, `NaN`/`Infinity` propagation in edits/curves/LUTs/framing, and decompression/allocation bounds (ZIP,
    Timeline, lossless JPEG, JPEG XL).
  - **Pixel and colour invariants**: 14-bit `LinearImage` (`0..16383`) level preservation, camera-look fidelity
    (`CameraCurve`, Sony `1.45` vs. DNG `1.0` saturation, two-illuminant calibration interpolation, `WarpRectilinear`),
    and bit-for-bit equivalence on refactoring changes.
  - **Concurrency and buffer ownership**: Lock ordering (`moving`, `takingOver`, `RawCache`), main-thread safety,
    shared buffer mutation, and coroutine cancellation boundaries across decode, render, sync, and export paths.
  - **Governance rules & review synthesis**: Editing rule documents when asked by the owner and resolving conflicting
    reviews alongside Claude Opus 5.5 and the owner (routine intake/dispatch stays with Gemini 3.8 Flash).
- **When to use**: PRs touching decoders, the shared seam, image processing (`enhance/`), external-input parsers,
  or concurrency/cancellation paths.
- **Token budget & fallback**: Gemini 4 Argon has a tight token budget. When it is out of tokens, the review it was
  assigned follows the fallback order in §3.4.

### 3.3 Claude Opus 5.5 — Deep Architecture, Data Durability & Lifecycle Reviewer

- **Primary focus**:
  - **Data durability and crash/interruption recovery**: Ensuring photos, `.originals`, edits, and backups cannot be
    lost or corrupted across interrupted SAF document moves, partial restores, duplicate sync candidates, or Room
    database migrations (`app/schemas/`).
  - **Cross-module state machines**: Multi-step interactions across `SyncEngine`, `Restorer`, `LibraryRepository`,
    `EditSaver`, and Compose UI state lifecycles.
  - **Security, privacy, and CI hardening**: Verifying zero network/tracking regressions against `PRIVACY.md`,
    auditing GitHub Actions job permissions (`release.yml`, `nightly.yml`), and reviewing signing/artifact boundaries.
  - **Governance rules & multi-review synthesis**: Editing rule documents when asked by the owner and synthesizing
    competing review findings into clear options for the owner.
- **When to use**: High-blast-radius PRs affecting user photos/data, Room schemas, backup formats, sync identity,
  privacy/security boundaries, or multi-module architectural changes.

### 3.4 Model-Family Independence, Claude Sonnet 5.5 Eligibility, and Quota Fallbacks

1. **Cross-family review by default**:
   - A mandatory review must normally be performed by a **different model family** than the PR's author:
     - **PR authored by Claude Code**: primary mandatory reviewers are **Gemini 4 Argon** (via Antigravity) or
       **Codex** (GPT). Because `Claude Opus 5.5 (via Antigravity)` shares the Claude model family with the author, it
       provides supplemental architectural review in normal operation and does not count as the primary cross-agent
       reviewer when Argon or Codex is available.
     - **PR authored by Codex**: primary mandatory reviewers are **Gemini 4 Argon** (via Antigravity),
       **Claude Opus 5.5** (via Antigravity), or **Claude Code**; a GPT-family review does not count as a cross-agent
       review of a Codex PR.
2. **Quota and token fallback order**:
   - **Gemini 4 Argon**, **Claude**, and **Codex** all have finite token/review quotas.
   - If **Gemini 4 Argon** is out of tokens, the review it was assigned falls back first to the non-author model family:
     - **Codex** for a **Claude Code** PR;
     - **Claude Opus 5.5** (via Antigravity) or **Claude Code** (**Opus 5.5**) for a **Codex** PR.
   - If all cross-family deep reviewers are out of quota on a **Claude Code** PR (both Argon and Codex exhausted), the
     same-family fallback order is:
     1. **Claude Opus 5.5 (via Antigravity)** first, because it runs in its own harness and session (independent of the
        authoring session's context, though sharing the Claude model family).
     2. **Claude Sonnet 5.5** second (when Opus 5.5 is also unavailable), **strictly requiring a fresh session** (no
        shared context with the session that authored the PR) and a **minimum reasoning effort of `high` (extended
        thinking enabled)**.
   - On a **Codex** PR when Argon, Opus 5.5, and Claude Code (Opus 5.5) are all out of quota, **Claude Sonnet 5.5** in
     a fresh session at **minimum `high` (extended thinking)** reasoning effort may likewise serve as a fallback deep
     reviewer.
   - Every fallback review **must name the fallback and its reasoning effort at the top**, state whether it shares the
     author's model family (`not independent of the author's model family; independent session`), and state explicitly
     which of the primary reviewer's checks (such as Argon's numerical bounds, colour invariants, locks and
     cancellation) it did and did not cover.
   - **Gemini 3.8 Flash** and any **Claude Sonnet 5.5 run below `high` reasoning effort** never satisfy a mandatory
     review. If no qualified deep reviewer is available, the PR waits and the item goes under `"Waiting for the owner"`
     in [`docs/TODO.md`](../TODO.md).

---

## 4. Orchestration & Review Protocol

### Step 1: Mechanical Intake and Reviewer Dispatch (Gemini 3.8 Flash)

1. Read [`docs/HANDOFF.md`](../HANDOFF.md) (*In progress*, agent queues) and inspect open PRs via `gh pr list`.
2. Record the exact head commit SHA of the PR being inspected (`gh pr view <id> --json headRefOid`).
3. Mechanically match the PR's changed paths and topics against the mandatory review triggers in
   [`AGENTS.md`](../../AGENTS.md):
   - Decoder seam (`ArwImage`, `LensProfile`, `RawDeveloper`, `LensCorrection`, `RawRenderer`, `RawCache`, `LinearImage`)
   - Colour, precision, white balance, demosaicing, camera look, crop, or orientation
   - Buffer sharing, threads, locks, or coroutine cancellation
   - Room schema/migrations, backup format, or any path that could lose photos or user data
   - Permissions, network access, privacy, or location data
   - Signing, release/nightly workflows, or CI checks
   - New dependencies, native code, ABIs, or `third_party/` updates
   - Cross-area changes touching another agent's partition
   - Review governance: reviewer eligibility, mandatory triggers, fallback/independence rules or merge gates,
     including changes only to docs or agent instructions. Judge the PR under the target branch's existing rules;
     the proposed change cannot relax the qualifications for its own review.
4. **Escalate unconditionally**: If any path or topic matches the list above—or if there is any doubt ("when unsure,
   escalate")—assign the PR to the appropriate deep reviewer under §3.2–§3.4 without judging whether the diff is safe.

### Step 2: Deep Inspection (Gemini 4 Argon / Claude Opus 5.5 / Peer Agent / Qualified Fallback)

1. Inspect the full diff (`gh pr diff <id>`) and surrounding source context in read-only mode.
2. Check CI status on the exact head commit (`gh pr checks <id>`) and note what was verified by CI versus what
   remains a phone-only check in [`docs/DEVICE_CHECKS.md`](../DEVICE_CHECKS.md).
3. Independently validate any Flash candidates under §3.1 without limiting the review to those candidates. Inspect
   the relevant callers and helper contracts, including error returns and cancellation propagation, and compare the
   base behavior. Record accepted findings, rejected candidates and material unresolved questions in the synthesis;
   report only distinct confirmed defects as actionable findings. An unresolved mandatory risk keeps review pending.

### Step 3: Identity-Transparent Review Publication

When publishing a review via `gh pr review`, always comply with these formatting and transparency rules:

1. **Explicit model, agent, reasoning-effort & fallback header**: Every review or PR comment must begin by stating the
   exact model that performed the review (and its reasoning effort / fallback / family-independence status when
   applicable):
   ```markdown
   Reviewed by **<Model Name>** (via <Agent>) — inspected commit `<short-sha>`.
   ```
   Synthetic examples:
   - `Reviewed by **Gemini 3.8 Flash** (via Antigravity, mechanical triage & contract check) — inspected commit 1111111.`
   - `Reviewed by **Gemini 4 Argon** (via Antigravity) — inspected commit 2222222.`
   - `Reviewed by **Claude Opus 5.5** (via Antigravity, fallback for Gemini 4 Argon; same model family as author, independent session) — inspected commit 3333333.`
   - `Reviewed by **Claude Sonnet 5.5 (high thinking)** (via Claude Code, fresh session, fallback for Gemini 4 Argon; same model family as author) — inspected commit 4444444.`
   Report settings only when exposed by the harness or explicitly supplied by the owner, and identify that source.
   When the exact model or reasoning effort cannot be verified, label it `unverified` rather than guessing. A review
   with unverified effort does not satisfy a minimum-effort requirement (§3.4); choose a qualified reviewer or keep
   the required review pending. The same reporting rule applies to authoring settings in a PR description (§2.1).
2. **Structured body**:
   - **Scope & fallback coverage**: Brief statement of the files and invariants inspected; when acting as a fallback,
     state which of the primary reviewer's checks (numerical bounds, colour invariants, locks and cancellation) were
     and were not covered.
   - **Findings**: Prioritize by demonstrated impact, reachability and urgency, not just labels such as "overflow"
     or "data loss". `P1` requires urgent action for a concrete serious impact; `P2` is a normal-priority correctness
     or contract defect; `P3` is a low-priority issue. A no-caller display helper does not become P1 just because its
     arithmetic is wrong. Check the relative ordering of data-integrity and display-only failures. Give exact paths,
     lines, concrete reproduction inputs and expected behavior, or a concise `LGTM — no actionable issues found.`
     when clean. Keep unverified candidates distinct from the confirmed finding count.
   - **Verification & limits**: Which CI checks or local read-only verifications were confirmed on the head SHA, and
     which device/hardware checks remain unverified.
3. **GitHub review event (`--comment` always)**:
   - Every agent currently posts through the owner's single GitHub account (`gh`), and GitHub forbids approving or
     requesting changes on one's own PR. Therefore, **always publish with `gh pr review <id> --comment`**, and state
     the review outcome (`LGTM` or prioritized findings) explicitly in the body so nobody reads a missing approval into
     a `COMMENT` event.
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
