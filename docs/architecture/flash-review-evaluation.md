# Flash review evaluation — PR #36

Historical assessment recorded on 2026-10-23 at the owner's request. This file records evidence and suggestions,
not reviewer eligibility or operating policy; those rules live in
[`antigravity-review-orchestration.md`](antigravity-review-orchestration.md).

The owner supplied a comparison of Flash's review of [PR #36](https://github.com/byeonggonlee/Latent/pull/36)
at `af7d12c3f193e44846a5bf0f9e615443c29b905d` with an answer key kept outside the repository: four of five seeded
defects were detected, and one decoy was reported as a defect. This is an owner-supplied summary of the private
Claude Sonnet 5.5 / Codex chat evaluation, not an independently reproduced benchmark score. The evaluation also
identified numerical overstatements, severity misjudgements and a material omission in the initial Codex assessment.
This is one small, deliberately flawed PR, not an established detection rate or a comparison with other models.
It supports candidate finding with verification, not promotion to sole deep reviewer. It did not test Flash's primary
job of dispatch. PR #36 was closed without merging on 2026-10-22 (UTC); its inspected commit is identified through the PR.
The private answer key and per-seed scoring details are not stored here.

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

