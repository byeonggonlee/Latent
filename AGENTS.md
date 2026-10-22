# Repository Guidelines

## Working Alongside Other Agents

These rules bind every agent that works on Latent. The owner set them on 2026-10-21; the reasons are in
[`docs/architecture/decisions.md`](docs/architecture/decisions.md). Who looks after which files, what is claimed and
what is handed over is in [`docs/HANDOFF.md`](docs/HANDOFF.md): read it before starting, claim an item there before
you start it, and log it there when it is done.

### Who does what

- **Claude Code** (code authoring & review): authors everything but the image decoders—editor, renderers and
  shaders, library, backup, sync, location, map, UI, CI and docs—and reviews changes in Codex's area and the shared
  seam. It may run on the owner's computer and in cloud sessions at the same time.
- **Codex** (code authoring & review): authors the image decoders (TIFF, Sony ARW, DNG, lossless JPEG, JPEG XL with
  `third_party/jxlatte`, the MPF preview), their tests and fixtures, and reviews changes in Claude Code's area and
  the shared seam.
- **Antigravity** (code review, model orchestration & documentation; never authors code): conducts code reviews and
  orchestrates review/task routing across its hosted models (**Gemini 3.8 Flash**, **Gemini 4 Argon**, and
  **Claude Opus 5.5**) alongside Claude Code and Codex, following
  [`docs/architecture/antigravity-review-orchestration.md`](docs/architecture/antigravity-review-orchestration.md).
  It never changes source, tests, build files, schemas or CI. It writes only under `docs/` (its own files,
  `docs/HANDOFF.md`, and owner-decision queues in `docs/TODO.md`) and to agent governance files when asked by the
  owner. What it finds that needs code goes into the PR review or the queue of the authoring agent whose area it is.
- **The owner** decides the package name, keys and secrets, what ships, what waits for a phone, and between
  agents who disagree. No agent decides that for them: the question goes under "Waiting for the owner" in
  `docs/TODO.md`.

### Each agent in its own worktree

- Work in a git worktree of your own, on a branch of your own, made from `origin/main`
  (`git worktree add <dir> -b <prefix>/<topic> origin/main`): Claude Code under `.claude/worktrees/`, Codex and
  Antigravity where their tools put worktrees, or in a sibling folder such as `../CameraManager-codex`. Never inside
  another agent's worktree.
- The owner's checkout (`/Users/byeonggon/Sources/CameraManager` on their computer) is theirs: no agent edits,
  commits, stashes or switches branches there.
- Branch prefixes: `claude/` or `ccr-` for Claude Code, `codex/` for Codex, `agy/` for Antigravity.
- The stash is shared by every worktree: no bare `git stash`. Stage the paths of your task, never `git add -A`.
- A new worktree has no `local.properties` (the Android SDK's path, never committed): copy it from your own setup
  before building.
- Remove a worktree and its branch once its work is on `main`.

### Getting to main

- **Small changes go straight to `main`**: documentation, tests, a small fix inside your own area with its test.
  Before pushing, bring `origin/main` in, run what CI runs as far as your setup can (`testDebugUnitTest lintDebug
  lintRelease assembleDebugAndroidTest`, then `git diff --exit-code -- app/schemas`), and push a fast-forward. An
  agent without the Android SDK (a cloud session) cannot prove a change itself, so it always opens a pull request.
- **Changes that need review go through a pull request**, reviewed by another agent (Claude Code, Codex, or
  Antigravity), and are merged once CI is green and the review is done; the owner decides when reviewers disagree.
  That is any change to:
  - the seam between decoders and the rest (`ArwImage`, `LensProfile`, `RawDeveloper`, `LensCorrection`,
    `RawRenderer`, `RawCache`), or what `LinearImage` means;
  - colour, precision, white balance, demosaicing, the look, crop or orientation;
  - the sharing of buffers, threads, locks or cancellation;
  - the Room schema and migrations, the backup format, or anything else that could lose the owner's photos or data;
  - permissions, network access, privacy or location data;
  - signing, the release and nightly workflows, or what CI checks;
  - a new dependency, native code, the ABIs, or an update of `third_party/`;
  - another agent's area.
- Small changes are not reviewed: a reviewer is for the changes above, not for every patch.
- Never push with force to `main`, never rewrite another agent's commits, and never skip, disable or loosen a test
  to get green.
- Every commit names its agent: Claude Code's `Co-Authored-By: Claude ...` line, or a trailer line `Agent: Codex`
  or `Agent: Antigravity`.

### Publishing PR reviews

- When the owner asks for a PR review, publish the completed findings and conclusion on that PR as part of the
  request, then link the posted review in the chat. No separate publication confirmation is needed unless the
  owner explicitly asks for a private or chat-only review.
- Anchor the review to the commit actually inspected. State the scope, actionable findings (or that none were
  found), checks verified, and any unverified builds or device checks. Identify the reviewing agent and active
  model explicitly at the top of the review (for example, `Reviewed by Gemini 3.8 Flash (via Antigravity)`,
  `Reviewed by Gemini 4 Argon (via Antigravity)`, or `Reviewed by Claude Opus 5.5 (via Antigravity)`), as well as
  any fallback reviewer.
- A concise LGTM is enough when no actionable issues are found; keep the inspected commit and material validation
  limits clear.
- Post a review even when no bugs are found. Use a COMMENT review when reviewing through the PR author's own
  account, since that account cannot approve its own PR. Publishing a review does not authorize a merge.

## Project Structure & Module Organization

Latent is a Kotlin/Compose Android app for USB imports, editing, and geotagging.

- `app/src/main/java/com/byeonggon/latent/`: application code; `camera/` handles MTP sync, `data/` storage and Room, `raw/` decoding, `enhance/` image processing, and `ui/` screens and view models.
- `app/src/main/res/`: English, Korean, and Japanese resources. `app/src/main/assets/`: the subject and sky mask models and their licenses.
- `app/src/test/`: JVM tests; `app/src/androidTest/`: device tests; `app/schemas/`: committed Room schemas.
- `third_party/jxlatte/`: vendored Java JPEG XL decoder, built as `:jxlatte`.
- `docs/`: releases, backup format, roadmap, and device checklists. `store/`: store graphics.

## Build, Test, and Development Commands

Run the Gradle wrapper from the repository root. CI uses JDK 21; compile SDK is 37; devices require Android 15/API 35 or newer.

- `./gradlew assembleDebug`: build the debug APK.
- `./gradlew installDebug`: install on a device or emulator; launch "Latent Debug" there (`com.latentimage.latent.debug`, beside the nightly, with its own database and `DCIM/Latent Debug` library).
- `./gradlew testDebugUnitTest`: run JVM tests.
- `./gradlew lintDebug lintRelease assembleDebugAndroidTest`: run Android Lint and compile instrumentation tests, matching CI checks.
- `git diff --exit-code -- app/schemas`: verify committed schemas after building.
- `./gradlew assembleRelease -PdebugSignedRelease`: build a minified release APK signed with the local debug key.

## Coding Style & Naming Conventions

Follow official Kotlin style (`kotlin.code.style=official`), four-space indentation, and surrounding code. Use PascalCase for classes and composables, camelCase for functions and properties, and lowercase underscore names for Android resources. Keep feature code in its existing package. Android Lint is configured; no standalone formatter is configured.

## Testing Guidelines

Tests use JUnit 4, Robolectric, and Compose UI testing. Name classes `*Test` or `*OnDeviceTest`, with descriptive camelCase test methods. Add regression coverage for changed behavior; no numeric coverage threshold is configured. Room changes require a version bump, migration, and committed schema. Consult `docs/DEVICE_CHECKS.md` for GPU, USB, and real-photo checks. Run device tests through `adb shell am instrument`; see README for sample arguments. Avoid `connectedAndroidTest` on personal installations: its cleanup uninstalls the app and removes its database.

## Commit & Pull Request Guidelines

Commit your changes after each completed task, including documentation updates, with the trailer that names your agent (above). Stage only files belonging to your task; leave other agents' changes out.

History uses descriptive imperative subjects such as “Compile the tests that run on a phone in CI”; follow that style. Keep commits focused. PRs should explain the problem, resulting behavior, and checks performed; link relevant issues and include screenshots for UI changes. State which device checks remain unverified.

## Privacy & Configuration

Keep photos and location data on-device; online maps require explicit opt-in. Never commit signing keys, `keystore.properties`, or `local.properties`. Preserve original photos and migration paths when changing storage.
