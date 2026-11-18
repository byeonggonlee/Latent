# Review guide: severity

Shared by every reviewer of Latent: Claude Code, Codex and Antigravity (Gemini and Claude models alike). Set by the
owner on 2026-11-18, after reviews of the same pull request rated one defect P2 and another reviewer would have rated
it P3. Who reviews what, and when, stays in [`AGENTS.md`](../AGENTS.md) and
[`architecture/antigravity-review-orchestration.md`](architecture/antigravity-review-orchestration.md); this page
only says how bad a finding is.

## The levels

A finding's level is what it does to the owner or a user **at the head inspected**, on the path a real phone takes,
not the worst thing the code could do in theory.

| Level | What happens | Examples | Merge |
|---|---|---|---|
| **P0** | A photo, an original or the owner's data is lost or corrupted for good, or private data leaves the phone, on an ordinary path | an original overwritten on save; a location sent to a service without the opt-in | blocks; fix in this pull request |
| **P1** | The same harm on a path that is uncommon but reachable without anything unusual; or the app crashes, hangs, or shows a wrong picture (colour, crop, orientation) on ordinary photos | a cancelled export deletes the source; every ARW from one body developed magenta | blocks; fix in this pull request |
| **P2** | Harm that can be undone (the file is still somewhere, the edit can be redone), or lasting harm that needs a rare but real precondition; a regression in a feature that works on main | a sidecar stranded in the trash folder after a failed move; Auto pulling a meant colour further than before | fix before merging, unless the owner accepts it as a follow-up |
| **P3** | Little harm: what is lost belongs to no photo any more, or needs a precondition reached only by crashes, outside tools or another app's files; misleading text; a weak or missing test | an orphaned `.xmp` with no photo overwritten; a test that relies on row order | optional; may become a follow-up |
| **Nit** | Wording, naming, style; nothing a user would notice | | never blocks |

## How to decide

1. **Name the precondition.** Every finding says what has to be true for it to happen: which files exist, which
   failure occurs, which device or setting. A finding without a reachable precondition is a candidate, not a defect.
2. **Weigh impact and reach separately**, then take the level both support:
   - impact: lost for good > lost but recoverable > wrong but visible and fixable by the user > cosmetic;
   - reach: ordinary use > uncommon use > a crash or kill at a precise moment, another app's files, a hand-made file.
   A permanent loss on a rare path is P2 at most unless the path is part of an ordinary flow; a recoverable harm on an
   ordinary path is P2 at most.
3. **Say whose data it is.** Overwriting or stranding something that still belongs to a photo (its original, its
   sidecar, its edit) counts fully. Something whose photo is already gone counts as P3 at most.
4. **Before and after.** Say whether the defect is on the base as well. A defect already on main is labelled
   *pre-existing* and does not block this pull request unless the change makes it newly reachable or more likely;
   claiming it does needs the path that is new, not only that the code is touched.
5. **When unsure between two levels, give the lower one** and the evidence that would raise it. Do not raise a level
   to make sure a finding is read.
6. **Device-only questions** (scoped storage across reinstalls, a provider's behaviour, GPU drivers) are reported as
   unverified candidates with the check that would settle them, not as P-levelled defects.

## In the review

Each finding carries: the level, *new* or *pre-existing*, the precondition, who is harmed and whether it can be
undone, the evidence at the inspected head, and the smallest fix. A reviewer who disagrees with another's level says
so with these same points; the owner decides between them.
