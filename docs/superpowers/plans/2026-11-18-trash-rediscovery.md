# Trash Rediscovery Implementation Plan

> **For agentic workers:** Execute inline with superpowers:executing-plans. Independent Claude Code/Opus review is required before merge.

**Goal:** Rediscover orphaned trash bundles without exposing them as active photos or automatically deleting them.
**Architecture:** Register existing bundle paths under the library move mutex; reuse restore/empty and keep the existing schema/layout. SAF enumeration covers previous-installation files.
**Tech Stack:** Kotlin, Room, coroutines, Android DocumentsContract; Robolectric and instrumentation.
**Spec:** docs/superpowers/specs/2026-11-18-trash-rediscovery-design.md

## Constraints and review focus

Keep photo bytes and companions; no schema/backup change, no automatic deletion of recovered photos. Check interrupted/ambiguous bundles, duplicate discovery, occupied numeric folders after IDs restart, revoked storage access, restore collisions and failed deletion. Use the existing moving lock. Cross-family review remains required.

## Task 1: Rediscover and safely operate on recovered trash

- [x] Add failing tests to `data/TrashTest.kt` (or a focused sibling) for disk-only bundle registration, idempotence, expiry protection, restore/empty, name collision, ambiguous bundles, occupied trash folders and access loss.
- [x] Add granted-folder child enumeration in `data/GrantedFolder.kt`, exposed by `LibraryStorage`; guard directory boundaries and provider errors.
- [x] Add `Trash.rediscover()` using existing MediaDao insert/query APIs and a documented never-expire timestamp. Keep all processing under storage.moving.
- [x] Allocate unoccupied trash bundle folders; preserve unexpected companions on restore; only drop database rows after successful delete.
- [x] Call rediscovery before expiry in `LibraryViewModel.refresh()` and document restored-location and metadata limitations in existing user documentation.
- [x] Verify red-to-green, run `testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest`, check schemas/whitespace, and test on an available emulator (no uninstall/data clear).
- [x] Commit with Agent: Codex, push one focused PR and hand it to Claude Code/Opus. Do not merge.

## Ledger

- Base: origin/main cab105e. Existing trash bundles have no persistent original path or deletion timestamp; discovery must not infer either from a restarted database ID.
- Prior conversation approved rediscovery via existing UI and excluding recovered photos from immediate automatic deletion. Implementation retains recovered photos until explicit owner action.

- RED: initial tests reproduced orphan invisibility and overwrite of an old bundle by a restarted DB ID; additional root-symlink test failed before the boundary guard.
- GREEN: 26 focused JVM checks pass (13 new recovery tests, existing trash/move checks). Full JVM suite: 912 cases, 0 failures, 19 pre-existing opt-in/external skips. Both Lints: 0 errors/9 existing warnings; schemas unchanged. Initial concurrent KSP/Lint run failed on missing generated release files; the same tasks pass with --max-workers=1.
- Emulator: one API 36 instrumentation case passes with real SAF and a scratch database; original/XMP restore and explicit empty verified. First fixture used a forbidden direct XMP write; corrected to the production SAF API before passing. No actual app uninstall/clear or physical-phone test.
- Claude Code Opus independent review dispatched via a fresh read-only CLI session (--model opus, --effort high); initial findings addressed below.

- Initial Opus 5.5 review (model confirmed by CLI modelUsage) found hidden takeover remnants, background-expiry exception handling, camera-zone consistency and discovery-only directory creation. All were reproduced with failing tests, then fixed; the Japanese spacing nit was corrected. Matching takeover companions are allowed beside an intact main file, but temporary-only bundles remain untouched.
- Correction-only Opus 5.5 re-review completed LGTM; unchanged full-review coverage is reused. Final full suite after corrections: 912 cases / 0 failures / 19 fixture/opt-in skips; both Lints remain 0 errors/9 warnings, instrumentation/debug APKs compile and schemas are unchanged.

- Published review: https://github.com/noctilane/Latent/pull/58#pullrequestreview-5953678677 (code commit 0b71b44). Final API 36 instrumentation rerun: OK (1 test), 0.188 s; real SAF and in-memory DB, without actual reinstall or phone use. PR remains unmerged.
- Non-blocking review notes retained as limits: temporary-only bundles are deliberately untouched; harmless copy remnants may remain after restore; no actual old-installation ownership test was performed.
