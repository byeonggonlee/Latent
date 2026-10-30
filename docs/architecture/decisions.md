# Decisions

What the owner has decided about how Latent is built and how the agents work on it, newest first. Each entry says
what was decided, why, what it means for a change, and what would open it again. An entry changes only when the
owner decides again; then the old text stays, marked as replaced. Proposals that wait for a decision live in the
assessments in this folder (for example [`codex-assessment.md`](codex-assessment.md)) and in `docs/TODO.md`.

## 2026-10-29 · Gemini 3.8 Flash's part on a pull request is the dispatch

**Decided.** On a pull request, Gemini 3.8 Flash posts a dispatch note of about three lines: the commit it read,
whether the change needs mandatory review and under which categories, and who reviews it. It does not summarise the
change or list candidate findings, and looks for bugs only when the owner asks. When the owner has already named a
PR's reviewer, Flash posts nothing on it. This narrows its part in the
[entry of 2026-10-22](#2026-10-22--antigravity-reviews-and-orchestrates-models-claude-code-and-codex-write-the-code);
the rest of that entry stands. The rules are in
[`antigravity-review-orchestration.md`](antigravity-review-orchestration.md) §3.1.

**Why.** On PRs #52 and #53 Flash's triage found none of the defects the deep reviewers found, repeated one PR's
wrong claim about its own samples, and raised one false candidate. Its dispatch was right on both. The rest cost the
owner's reading and Antigravity's tokens, and a long "passed" triage can read as clearance. The evidence is in
[`flash-review-evaluation.md`](flash-review-evaluation.md).

**What it means for a change:**

- The deep reviewer does not wait for a Flash pass, and there are no Flash candidates to validate unless the owner
  asked for a search.
- Classification stays one-way: Flash escalates and never downgrades, and its note never satisfies a mandatory
  review.
- A surface check that fails (a missing trailer, a failing CI check, a missing translation) still gets one line in
  the note.

**Opens again** when the owner wants Flash's finding search back on pull requests, for example after a benchmark of
the kind [`flash-review-evaluation.md`](flash-review-evaluation.md) suggests.

## 2026-10-29 · Edits keep their look from 0.2.0 on

**Decided.** Edits saved with 0.2.0 or later keep the look they were saved with when development changes later.
Edits saved with 0.1.0 do not: until 0.2.0 is released, development may change freely, and a photo edited under 0.1.0
then shows as 0.2.0 renders it. This answers question 3 of the owner's decisions in
[PR #51's discussion summary](https://github.com/byeonggonlee/Latent/pull/51#issuecomment-6489430172).

**Why.** 0.1.0 has been out for two days. Holding back 0.2.0's colour work for its few edits (the CR2 profile, auto
colour, the colour-pipeline stages discussed on PR #51) is not worth the code it would take. From 0.2.0 on, the
owner's library and anyone else's carry edits that should look tomorrow as they did when they were saved.

**What it means for a change:**

- From 0.2.0, every saved edit records the render version it was made with: 1 for 0.2.0. An edit without one was
  saved by 0.1.0 and takes the current rendering.
- A change that alters how an existing edit renders raises the render version: the colour matrix or profile, white
  balance, the curve, saturation, demosaicing, clipping, or what an adjustment does. Edits with an older version keep
  rendering through the old path; new edits use the new one. Offering to bring an old edit up to date, as Lightroom's
  process versions do, is a later choice.
- A plain bug, where the old output was wrong (a crash, a wrong Bayer phase, magenta highlights), is fixed for every
  edit and said in the changelog. Whether a change is a bug fix or a new look is the reviewer's question, and the
  owner's when they disagree.
- A decoder for files Latent could not develop before needs no new version, since no saved edit renders differently.
- Changes that land before 0.2.0 is released need no version.

**Opens again** when keeping old paths costs more than it is worth, judged by the owner.

## 2026-10-22 · Antigravity reviews and orchestrates models; Claude Code and Codex write the code

**Decided.** Code authoring stays strictly with **Claude Code** (editor, renderers and shaders, library, backup, sync,
location, map, UI, CI and docs) and **Codex** (image decoders, their tests and fixtures). **Antigravity** never
authors or modifies production code, tests, schemas, build files or CI; instead, it is dedicated to **code review,
multi-model review/task orchestration, and documentation** across its hosted models (**Gemini 3.8 Flash**,
**Gemini 4 Argon**, and **Claude Opus 5.5**), alongside reviews by Claude Code and Codex. Detailed operating rules
live in [`antigravity-review-orchestration.md`](antigravity-review-orchestration.md).

**Why.** Separating code authoring from cross-cutting multi-model review lets each authoring agent stay focused on
its domain while Antigravity provides an independent review pass matched to the change's risk (fast triage and
contract checks with Gemini 3.8 Flash; numerical, concurrency and seam checks with Gemini 4 Argon; deep data
durability, lifecycle and architecture checks with Claude Opus 5.5) and routes actionable findings back to the
responsible authoring agent.

**What it means for a change:**

- Antigravity never writes or edits code, tests, build scripts or CI workflows. Any defect or missing test it finds
  is published in the PR review and/or added to the responsible authoring agent's queue in `docs/HANDOFF.md`.
- **Gemini 3.8 Flash** serves as the mechanical dispatcher ("when unsure, escalate"): it matches changed paths and
  topics against `AGENTS.md`, assigns the deep reviewer by author family, and keeps `docs/HANDOFF.md` up to date,
  without judging whether a mandatory-review change is safe.
- **Claude Sonnet 5.5** may author code and tests in Claude Code's area at a minimum reasoning effort of `high`
  (extended thinking); for changes on the `AGENTS.md` mandatory-review list, **Claude Opus 5.5** is preferred and
  **Sonnet 5.5** requires `high` or `max` extended thinking.
- Every PR review published from Antigravity explicitly states both the reviewing model and the agent at the top of
  the review (`Reviewed by <Model> (via Antigravity)`), anchored to the inspected commit SHA, and uses `--comment`
  because all agents currently share the owner's `gh` account.
- Important changes (as listed in `AGENTS.md`) require a deep review rather than **Gemini 3.8 Flash** alone,
  preferring a different model family from the PR author (**Gemini 4 Argon** or **Codex** for a Claude Code PR;
  **Gemini 4 Argon**, **Claude Opus 5.5**, or **Claude Code** for a Codex PR). When **Gemini 4 Argon** is out of
  tokens, the review falls back first to the non-author model family (**Codex** for a Claude Code PR; **Claude Opus
  5.5** via Antigravity or **Claude Code** for a Codex PR); when cross-family deep reviewers are also exhausted on a
  Claude Code PR, the same-family fallback order is **Claude Opus 5.5** (via Antigravity, in its own session) first,
  and **Claude Sonnet 5.5** at minimum `high` (extended thinking) reasoning effort from a **fresh session** second,
  stating at the top that it shares the author's model family along with any unverified Argon checks; if no qualified
  deep reviewer is available, the PR waits under "Waiting for the owner" in `docs/TODO.md`.

**Opens again** if the owner changes the agent roster, model assignments, or authoring boundaries.

## 2026-10-21 · Precision: the camera's look first, colorimetry later

**Decided.** For now the goal of raw development and of the editor is that a photo looks as the camera's own JPEG of
it does: the same brightness, contrast, colour and highlight roll-off, before any edit. Reproducing the scene's
colours colorimetrically (a colour chart measured right, scene-referred values kept, a wide-gamut working space,
ICC conversions, HDR or float output) is a later goal, not today's. This answers question 3 of
`codex-assessment.md` §11.

**Why.** Latent is first a way to bring a camera's photos in and develop them as the camera would, then to edit
them; a faithful look is what the owner judges a photo by, and it can be checked against the camera's JPEG. A
colorimetric pipeline needs references, budgets for memory on a phone, and float buffers, none of which exist yet.

**What it means for a change:**

- The working picture stays `LinearImage`: three planes of 14-bit levels (0 to 16383), clipped where they are
  clipped today, including values below 0 and above white after white balance and the colour matrix.
- The look is what defines a correct result: `CameraCurve` (measured on the ZV-E10), the saturation of the colour
  stage (`1.45` for Sony ARW, `1.0` for DNG), and the camera's JPEG next to a raw file. For Sony ARW,
  `RawSampleTest` compares against the camera's JPEG; for phone DNGs the comparison is the owner's, in
  `docs/DEVICE_CHECKS.md` ("Phone raw files").
- A change that moves pixels without meaning to change the look (moving code, a faster loop, a new decoder backend)
  gives the same levels, bit for bit, as before it.
- A change that is meant to change the look or the colour (the curve, the saturation, the colour matrix, white
  balance, demosaicing, precision) is a change that needs review (see `AGENTS.md`): a pull request, the other code
  agent's review, and the owner's judgement on photos. It never rides along with another change.
- JPEG and HEIF pictures keep coming in through the inverse of the camera's curve; they are an editing
  approximation, not scene light, and are not described as such.
- Nothing is presented as colour-managed or HDR: the app writes 8-bit sRGB JPEGs.

**Opens again** when the 10-bit and HDR items of `docs/ROADMAP.md` (10 to 12) are taken up, or when a precision
prototype (milestone M5-A in `codex-assessment.md`) has numbers from a phone to decide by. The owner decides then.

## 2026-10-21 · Review only where it matters

**Decided.** A change is reviewed by the other code agent only when it is important; small patches are not. What
counts as important is listed in `AGENTS.md` ("Getting to main"): the seam between decoders and the rest, colour
and precision, buffers and threads, data that could be lost, privacy, signing and CI, dependencies and native code,
and another agent's area. The milestones and reviews of `codex-assessment.md` apply to those changes, not to every
patch.

**Why.** Latent is a project of one owner; a review for every small fix would cost more than the mistakes it finds.

## 2026-10-21 · Small changes straight to main, reviewed ones through a pull request

**Decided.** An agent that can run the checks pushes a small change straight to `main` after them. A change that
needs review goes through a pull request and is merged once CI is green and the review is done. An agent without
the Android SDK always opens a pull request.

## 2026-10-21 · Each agent in its own worktree

**Decided.** Every agent, Claude Code included, works in a git worktree and on a branch of its own, made from
`origin/main`. The owner's checkout is left to the owner.

**Why.** On 2026-10-21 two agents worked in the same checkout: one's branch switches and staging could take in the
other's files, and commits landed on a `main` that had not been pushed.

## 2026-10-21 · Antigravity reads and documents *(replaced on 2026-10-22 above)*

**Decided.** Antigravity reads the code and writes documents only (under `docs/`); it changes no source, test,
build file or CI. Its findings go to the agent whose area they are in. Its assessment stays separate from Codex's,
and the two are compared on the same revision before anything is decided from them.

## Still open

From `codex-assessment.md` §11, waiting for the owner: whether the first scope is the existing decoders with an
explicit contract (and no `image-core` or `image-contract` module); whether standalone JPEG XL, JPEG or HEIF come
under the decoders; the phones, largest picture, memory and time budgets; the rights to sample photos and who checks
on a phone; and the final approver of contract changes.
