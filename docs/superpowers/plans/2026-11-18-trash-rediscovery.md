# Trash Rediscovery Implementation Plan

> **For agentic workers:** Execute inline with superpowers:executing-plans. Independent Claude Code/Opus review is required before merge.

**Goal:** Rediscover orphaned trash bundles without exposing them as active photos or automatically deleting them.
**Architecture:** Register existing bundle paths under the library move mutex; reuse restore/empty and keep the existing schema/layout. SAF enumeration covers previous-installation files.
**Tech Stack:** Kotlin, Room, coroutines, Android DocumentsContract; Robolectric and instrumentation.
**Spec:** docs/superpowers/specs/2026-11-18-trash-rediscovery-design.md

## Constraints and review focus

Keep photo bytes and companions; no schema/backup change, no automatic deletion of recovered photos. Check interrupted/ambiguous bundles, duplicate discovery, occupied numeric folders after IDs restart, revoked storage access, restore collisions and failed deletion. Use the existing moving lock. Cross-family review remains required.

## Task 1: Rediscover and safely operate on recovered trash

- [ ] Add failing tests to `data/TrashTest.kt` (or a focused sibling) for disk-only bundle registration, idempotence, expiry protection, restore/empty, name collision, ambiguous bundles, occupied trash folders and access loss.
- [ ] Add granted-folder child enumeration in `data/GrantedFolder.kt`, exposed by `LibraryStorage`; guard directory boundaries and provider errors.
- [ ] Add `Trash.rediscover()` using existing MediaDao insert/query APIs and a documented never-expire timestamp. Keep all processing under storage.moving.
- [ ] Allocate unoccupied trash bundle folders; preserve unexpected companions on restore; only drop database rows after successful delete.
- [ ] Call rediscovery before expiry in `LibraryViewModel.refresh()` and document restored-location and metadata limitations in existing user documentation.
- [ ] Verify red-to-green, run `testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest`, check schemas/whitespace, and test on an available emulator (no uninstall/data clear).
- [ ] Commit with Agent: Codex, push one focused PR and hand it to Claude Code/Opus. Do not merge.

## Ledger

- Base: origin/main cab105e. Existing trash bundles have no persistent original path or deletion timestamp; discovery must not infer either from a restarted database ID.
- Prior conversation approved rediscovery via existing UI and excluding recovered photos from immediate automatic deletion. Implementation retains recovered photos until explicit owner action.
