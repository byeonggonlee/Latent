# Claude Code

What Claude Code's work needs besides [`DEVELOPMENT_GUIDE.md`](../DEVELOPMENT_GUIDE.md), from its commits, pull
requests and reviews up to 2026-11-18. Set by the owner on 2026-11-18.

## What it does well

Clear, small commits that set the codebase's Kotlin style and plain voice; broad Robolectric/Compose UI and seeded
fuzz coverage (`557c40a`, `7d3a1e8`); measured evaluations on real samples (`docs/auto-colour-evaluation.md` in
[#56](https://github.com/noctilane/Latent/pull/56), L* comparisons in [#55](https://github.com/noctilane/Latent/pull/55));
and reviews that check real camera and phone files against specifications (the clipped white level and Adobe profile
in [#51](https://github.com/noctilane/Latent/pull/51), `OpcodeList3` tag `0xC74E` in [#52](https://github.com/noctilane/Latent/pull/52)).

## Writing code

- **Do not take shortcuts on equality or identity.** `LA-01` ([#26](https://github.com/noctilane/Latent/pull/26))
  treated an unclaimed library file and a camera file of the same name and size as equal without reading the camera
  file; `LA-02` ([#22](https://github.com/noctilane/Latent/pull/22)) matched a lone name/size backup candidate across
  years; [#13](https://github.com/noctilane/Latent/pull/13) took four review rounds from metadata-only and 64 KiB
  tail checks to comparing the whole picture and EXIF; [#53](https://github.com/noctilane/Latent/pull/53) compared
  edits through `PhotoEdit.fromJson`, dropping unknown JSON keys from a newer app version (`61ec0a6`).
- **Check arithmetic wrap, resource budgets and negative or out-of-gamut values in the first draft.** `LA-03`/`LA-05`
  and [#20](https://github.com/noctilane/Latent/pull/20) (Timeline `toEpochMilli` overflow, unbounded visit expansion,
  and a `Long` subtraction wrap in `777ab77`), `LA-04` ([#21](https://github.com/noctilane/Latent/pull/21), signed
  EXIF offset wrap) and [#31](https://github.com/noctilane/Latent/pull/31) (unbounded `BackupWriter` and `Long` wrap
  in the first sum check) needed follow-up fixes. In [#55](https://github.com/noctilane/Latent/pull/55) (`e01ca5f`),
  `toneExponent` measured luminance on unclamped camera-matrix RGB, turning vivid blue pixels black (`4c1a183`); in
  [#27](https://github.com/noctilane/Latent/pull/27), `PhotoEnhancer.retoned` kept the source Ultra HDR `gainmap` on
  a retoned bitmap.
- **Close interrupted and fail-open paths before review.** [#11](https://github.com/noctilane/Latent/pull/11) left
  `.originals/` owned by the old package, [#12](https://github.com/noctilane/Latent/pull/12) needed three review
  rounds to wire interrupted `.copied` files into `keepsOriginal`/`keptOriginal`, and [#30](https://github.com/noctilane/Latent/pull/30)
  needed `NonCancellable` so a stopped save still recorded its edit. In `LA-07` ([#23](https://github.com/noctilane/Latent/pull/23),
  `check-privacy.sh`'s `|| true`) and [#39](https://github.com/noctilane/Latent/pull/39) (three fail-open paths in
  `check-licenses.py`), CI guards passed when the check itself failed; test every guard script against a failing input.

## Reviewing and housekeeping

- **Walk through mid-move failures and companions when reviewing storage changes.** On [#58](https://github.com/noctilane/Latent/pull/58)
  at `0b71b44`, the first Opus 5.5 review missed that `Trash.restore` checked only the main photo's name and did not
  roll back `kept` when moving its `.xmp` or `original-` failed (`7bd1463`, `453e21c`).
- **Update every governance file that states a rule in the same commit** (`AGENTS.md`, `CLAUDE.md`, `GEMINI.md`,
  `docs/architecture/antigravity-review-orchestration.md`, `docs/HANDOFF.md`) so they do not disagree.
- **Claim on `HANDOFF.md` before starting across computer and cloud sessions**, so two sessions do not build the same
  items at once (2026-10-21), and remove `.claude/worktrees/<topic>` once its branch is merged.
