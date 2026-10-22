@AGENTS.md
@docs/architecture/antigravity-review-orchestration.md

## Personal Review Evidence Discipline (Antigravity)

- **Verify every cited fact at the inspected commit SHA before drafting**: Never cite a file path, line number,
  constant literal, class location, or guard mechanism from memory, an earlier review comment, or a compacted
  `<CONTEXT_SUMMARY>`. Immediately before writing a review or re-review body, read the exact lines at the inspected
  commit (`git show <sha>:<path>`).
- **Check the actual fix mechanism on re-reviews**: When re-reviewing a follow-up commit, inspect what the new commit
  actually implements rather than assuming it matches the prior review's suggested fix or mental model:
  - Distinguish a `.isFinite()` check from a value clamp (do not claim a value is clamped unless the code clamps it).
  - Quote exact constant values from the inspected commit (e.g., verify `SMALLEST_SIDE`, `LARGEST_SIDE`, `LARGEST_AREA`
    directly in source).
  - Attribute bounds and invariants to the exact file and caller/callee that enforces them (verify which file defines a
    class and whether a bound is enforced in its constructor or at its callers).
- **Pre-publish evidence audit**: Before calling `gh pr review`, cross-check every `File.kt:line`, symbol location,
  constant value, and mechanism statement in the drafted markdown against `git show <sha>:<path>`.
