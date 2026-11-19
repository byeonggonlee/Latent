# Gemini 4 Hydrogen (via Antigravity)

What Hydrogen's work needs besides [`DEVELOPMENT_GUIDE.md`](../DEVELOPMENT_GUIDE.md), from its first pull request
([#57](https://github.com/noctilane/Latent/pull/57), the `.dng.tiff` and thumbnail fixes, a deliberately large
trial) and its candidate review of #58. Set by the owner on 2026-11-18.

## What it does well

It finds every caller a cause reaches, writes failing reproductions first, takes a reviewer's findings in full and
re-checks them with bounded probes, and states its model and unverified effort plainly. Keep those.

## Writing code

- **Wide search, narrow change.** List everything the cause reaches in the pull request, but change only what the
  fix needs; the rest goes to the board or the owning agent's queue. In #57 a file-name fix grew to 24 files and
  about 2,000 lines across three areas.
- **Reuse the decoder, the path rules and the caches that exist.** #57 added a 376-line DNG validator beside
  `Dng.kt`'s own reading; both of Codex's findings came from that second implementation. Before writing one,
  ask the area's owner on the board.
- **Past ten files or 500 lines outside tests, stop and propose a split** before going on.
- **Work only on what the owner assigns**, on an `agy/` branch, with `Agent: Antigravity` in each commit. Every
  pull request gets a cross-family deep review (Codex, or Claude Opus 5.5 when Codex is out), whatever its size.

## Reviewing

- **Your level** is set in `AGENTS.md`: the dispatch note on every pull request (the template that was Gemini 3.8
  Flash's, under your own name), and the one deep review of a mandatory-review PR of at most about 200 changed lines
  outside tests that touches none of colour and precision, the decoder seam, buffers/threads/locks/cancellation, the
  Room schema and migrations, or signing, release workflows and CI. On any other PR you are a second, candidate
  reviewer, and say so at the top.

- Rate findings by [`REVIEW_GUIDE.md`](../REVIEW_GUIDE.md). In #58 a restore-naming defect whose overwritten files
  belonged to no photo was rated P2 and said to be made worse by the change without a new path; give the lower
  level when unsure, and show the path that is new before calling a defect worse.
- The evidence discipline in `GEMINI.md` (verify every cited line at the inspected SHA) applies to code you write as
  much as to reviews.
