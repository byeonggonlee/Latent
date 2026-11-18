# Development guide

How code is written for Latent, by every agent that writes it: Claude Code, Codex and Antigravity (Gemini 4
Hydrogen). Set by the owner on 2026-11-18. Each agent also has a page of its own under [`agents/`](agents/), for
what its own work tends to need; where the two differ, this page and [`AGENTS.md`](../AGENTS.md) win.

What is not repeated here: worktrees, branches, commits, getting to `main` and who reviews what are in
[`AGENTS.md`](../AGENTS.md); how bad a review finding is, in [`REVIEW_GUIDE.md`](REVIEW_GUIDE.md); the board, in
[`HANDOFF.md`](HANDOFF.md).

## Scope

- **One problem per change.** Fix the cause, and the callers that the cause reaches. Other things found on the way
  go to the board or the queue of the agent whose area they are, not into the same pull request.
- **Say so before it grows.** When a change turns out larger than claimed (as a rule of thumb, more than about ten
  files or 500 lines outside tests, or a second area), stop and note it on the board's *In progress* row, with
  whether it can be split, before going on.
- **Another agent's area** is changed only with its owner as reviewer, and a new implementation of something that
  area already does (a parser, a validator, a path rule) is asked about first.

## Reuse before new

- Use the code that already does the job. A second parser, validator or path calculation drifts from the first:
  where one is unavoidable, the pull request says why, and a test holds the two to the same answers.
- Match the code around the change: its naming, comment density, idiom and formatting (official Kotlin style,
  `name = value` with spaces, one statement per line). No formatter checks this, so the reviewer does.

## Evidence

- **Reproduce first.** A bug gets a test that fails before the fix and passes after; say in the pull request that the
  test was seen failing.
- **Constants have a source.** Read limits, levels and calibrations from the file or a published standard. A value
  that is provisional, or fitted to results, says so in its name or comment, with what it was fitted to.
- **Colour, decoders and the look** are compared with an independent reference (LibRaw, Adobe DNG Converter, the
  camera's own JPEG, a chart) in the first draft, not after review asks for it.
- **Checks are run fresh.** A result restored from a build cache is not evidence for a change; rerun what the change
  touches (`--rerun-tasks` or the focused test classes) and say which results were fresh.

## Untrusted input

Files from cameras, cards, other apps and the network are untrusted. Bound sizes, counts, offsets and repetition
before allocating or looping, so that a small hand-made file cannot exhaust memory or time, and test with such a
file.

## Data the owner keeps

Photos, originals, sidecars and edits are never overwritten or left behind silently. A sequence of moves either
completes or puts back what it moved; a name is chosen free for everything that travels with the file, not only the
file itself.

## Writing it down

- Lasting decisions go in [`architecture/decisions.md`](architecture/decisions.md) or the investigation note of
  the feature. A task's plan stays in the pull request description; plan and spec files are not committed unless
  the owner asks.
- Pull requests, reviews and board rows lead with the outcome, then what changed, the checks and the limits. A board
  row is three or four sentences.
