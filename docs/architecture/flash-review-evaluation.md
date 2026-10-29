# Flash review evaluation

This file records evidence and suggestions, not reviewer eligibility or operating policy; those rules live in
[`antigravity-review-orchestration.md`](antigravity-review-orchestration.md).

## PR #36 (2026-10-23)

Historical assessment recorded on 2026-10-23 at the owner's request.
The owner supplied a comparison of Flash's review of [PR #36](https://github.com/byeonggonlee/Latent/pull/36)
at `af7d12c3f193e44846a5bf0f9e615443c29b905d` with an answer key kept outside the repository: four of five seeded
defects were detected, and one decoy was reported as a defect. This is an owner-supplied summary of the private
Claude Sonnet 5.5 / Codex chat evaluation, not an independently reproduced benchmark score. The evaluation also
identified numerical overstatements, severity misjudgements and a material omission in the initial Codex assessment.
This is one small, deliberately flawed PR, not an established detection rate or a comparison with other models.
It supports candidate finding with verification, not promotion to sole deep reviewer. It did not test Flash's primary
job of dispatch. PR #36 was closed without merging on 2026-10-22 (UTC); its inspected commit is identified through the PR.
The private answer key and per-seed scoring details are not stored here.

## The triage of PRs #52 and #53 (2026-10-29)

Flash, at `high` thinking by the owner's setting, posted a mechanical triage on
[PR #52](https://github.com/byeonggonlee/Latent/pull/52#pullrequestreview-5693968467) (Codex's phone DNG tests, at
`c99588e`) and [PR #53](https://github.com/byeonggonlee/Latent/pull/53#pullrequestreview-5693971292) (Claude Code's
render version, at `b0daafe`). Each ran to about thirty lines: a summary of the change, contract checks, candidate
findings and an outcome. Against the deep reviews of the same commits:

- **Dispatch was right on both.** It named Claude Code for the Codex PR and Codex or Gemini 4 Argon for the Claude
  Code PR, and found #53 to be mandatory review (edit data and the backup format).
- **It found none of the defects.** On #52 it listed no candidates. Claude Code's review found that `Dng.kt` read
  OpcodeList3 at `0xC742` instead of `0xC74E`, so WarpRectilinear never reached a real DNG; Codex fixed it in
  `18ee5e0`. On #53 it walked through the restore comparison by `PhotoEdit.fromJson` and passed it. Codex's review found
  that parsing drops keys this version does not know, so a restore that removes a later app's setting gave no
  overwrite warning; Claude Code fixed it in `61ec0a6`.
- **It repeated a PR's wrong claim.** On #52 it restated that the public samples carry no OpcodeList3. Both Open
  Camera files carry an empty one at `0xC74E`.
- **Its one candidate was false.** C-1 on #53 said restoring no edit over a stored `"{}"` counts as replacing the
  person's edit. `replacesOwn` skips a stored edit equal to `UNKNOWN_EDIT`, which is `"{}"`, and Codex's re-review
  said the same.

Two PRs are not a detection rate. What they show is that the part that held was the dispatch, three of those thirty
lines; the summaries and contract checks found nothing the deep reviewers did not, and repeated what each PR said of
itself. On this the owner cut Flash's part on a pull request to the dispatch note
([decision of 2026-10-29](decisions.md)).

## Possible follow-up evaluation

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

