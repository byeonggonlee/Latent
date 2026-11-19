# Codex

What Codex's work needs besides [`DEVELOPMENT_GUIDE.md`](../DEVELOPMENT_GUIDE.md), from its pull requests and
reviews up to 2026-11-18. Set by the owner on 2026-11-18.

## What it does well

Bounds on untrusted input, reproductions, failure paths and thorough reviews that re-run the author's claims
(#52, #57, #58). These set the standard for every agent.

## Writing code

- **Match the surrounding style.** `CanonCr2.kt` passes `cfaPattern=CfaPattern.GBRG` and its tests write
  `val image=...`, `fun lin(v:Int):Double` and one-line `@Test fun`, where the rest of the code uses official
  Kotlin style; no formatter catches it.
- **Constants from the file first.** The first draft of #51 fixed the white level at 16383 (blown highlights came
  out magenta) and fitted the matrix to the camera's JPEG; both moved to the file's metadata and Adobe's profile only
  after review. Compare with an independent reference in the first draft.
- **No plan or spec files in the repository** (`docs/superpowers/`) unless the owner asks; the plan goes in the
  pull request.

## Reviewing and housekeeping

- **Rerun what a review relies on.** #57's review reported the full suite as restored FROM-CACHE; rerun the affected
  tests fresh, and say which were.
- **Remove worktrees when done.** On 2026-11-18 there were 49 Codex worktrees, 28 of them on branches already in
  `main` and 19 detached review checkouts. Remove a review checkout once its review is published, and a branch's
  worktree once it is merged.
- **Shorter reviews and board rows.** Lead with the outcome, findings, checks and limits; leave out workflow names
  ("Bugbot") the owner does not use; keep a board row to three or four sentences.
