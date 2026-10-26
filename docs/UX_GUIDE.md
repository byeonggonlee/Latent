# Latent UX guide

**Status:** proposal for review, written at the owner's request on 2026-10-26. This document proposes a shared
UX reference for design, implementation and review. It does not claim that every recommendation is implemented,
change release gates, or authorize a UI redesign. Existing ownership and review rules remain in [AGENTS.md](../AGENTS.md).

**Basis:** official Android and Samsung One UI web guidance consulted on 2026-10-26, and Latent source at
`9d30ee6`. Samsung's cited pages are not tied to a numbered One UI release; treat them as published design
principles, not a promise about every Galaxy system screen. Recheck links when platform behavior changes.

**Reading key:** *Observed* means verified in source or repository documentation at that revision, not tested on a
phone for this document. *Proposed* means a Latent design decision for review. Within proposed guidance, **must**
marks a correctness, accessibility or safety requirement; **should** marks a preferred design default. Product
behavior and storage/privacy promises must continue to match [PRIVACY.md](../PRIVACY.md) and
[BACKUP_FORMAT.md](BACKUP_FORMAT.md).

## 1. Direction and scope

Latent helps a photographer bring files from a camera, select pictures, develop them and place them on a map,
without an account or a cloud workflow. Optimize for confidence about the photo being changed, the work already
saved, and what will leave the device.

Three approaches were considered for this proposal:

- **Material defaults alone:** consistent Android behavior, but insufficient guidance for Galaxy reachability
  and photo-specific workflows.
- **Reproduce Samsung's applications:** visually familiar on Galaxy, but requires custom components and can
  conflict with other Android devices, accessibility and the existing Compose UI.
- **Proposed: Android behavior with One UI ergonomics.** Keep platform navigation, permission flows and Material 3
  components; apply Samsung's emphasis on reachable actions, readable grouping and adaptable layouts. Preserve
  Latent's photo workspace and data contracts. This is the author's recommendation, pending PR review.

Android's navigation patterns and One UI's task focus/reachability provide the starting point [A1], [S1], [S2]. The
following photo-specific choices are Latent proposals, not Samsung or Android requirements:

1. Give the picture the most useful space. Tools support evaluating a photo; decorative headings must not displace it.
2. Make the operation and its scope clear: one file, a RAW/JPEG pair, selected photos, an album or the whole library.
3. Preserve a way back from reversible work, and explain permanent changes before committing them.
4. Give useful status during long operations and a concrete next action after a failure.
5. Keep offline use, permissions, location disclosure and file ownership understandable at the point of action.

## 2. Current application baseline

The following are observations, not an accessibility or usability certification:

- [AppRoot.kt](../app/src/main/java/com/byeonggon/latent/ui/AppRoot.kt) exposes **Library, Camera, Map, Location**.
  Settings, Viewer, Compare and Editor are separate task screens. Library scroll state is retained while the viewer
  is open. A USB entry can start on Camera.
- [WindowLayout.kt](../app/src/main/java/com/byeonggon/latent/ui/common/WindowLayout.kt) chooses side navigation at
  600 dp width; a side panel needs at least 560 dp and a width/height ratio of at least 0.9. Text-heavy screens have
  a 720 dp maximum reading width. These are existing app choices, not universal platform breakpoints.
  [AppRoot.kt](../app/src/main/java/com/byeonggon/latent/ui/AppRoot.kt) also respects the saved tab-position preference.
- [Common.kt](../app/src/main/java/com/byeonggon/latent/ui/common/Common.kt) uses system-derived Material colors and
  System/Light/Dark preferences. [PhotoTheme.kt](../app/src/main/java/com/byeonggon/latent/ui/common/PhotoTheme.kt)
  keeps the photo workspace dark with a near-neutral background.
- [EditorScreen.kt](../app/src/main/java/com/byeonggon/latent/ui/editor/EditorScreen.kt) provides tool groups, undo/redo,
  versions, a Done action and a changed-edit discard prompt.
  [EditorViewModel.kt](../app/src/main/java/com/byeonggon/latent/ui/editor/EditorViewModel.kt) hands Done to background
  saving and closes the editor immediately; closing therefore does not prove the file has finished saving.
  Its `BackHandler` is not evidence that predictive-back
  animations or every gesture conflict have been verified.
- [SyncScreen.kt](../app/src/main/java/com/byeonggon/latent/ui/sync/SyncScreen.kt) distinguishes scanning, transfer,
  cancelling, completion and failure. It exposes the separate copy-and-compare step for apparent duplicates.
- [BackupSection.kt](../app/src/main/java/com/byeonggon/latent/ui/BackupSection.kt) uses system document pickers,
  previews a restore, and displays progress. The archive contains metadata and supporting edit assets, **not photos
  or videos**. Restoring does not turn online maps on.
- [app/build.gradle.kts](../app/build.gradle.kts) sets min SDK 35 and target/compile SDK 37. Resources support English,
  Korean and Japanese. The [SC-57G report](device-results/2026-10-23-sc-57g.md), including its follow-up, records
  31 of 33 summary checks working; it does not establish TalkBack, 200% text, predictive back or all window sizes.

## 3. Navigation and Back

Android recommends familiar navigation for peer destinations and adapting its placement to available space [A1].

**Proposed:**

- Retain the four existing peer destinations and their order. Keep **Map** for browsing located photos and
  **Location** for recording/importing location history; explain the difference in their empty states.
- Keep Settings as a secondary destination. Editor tool categories are controls within editing, not additional
  top-level destinations. Do not turn a frequent command such as Import into a destination merely to fill a bar.
- On compact windows, prefer labelled bottom navigation. On wider windows, prefer a rail while honoring the existing
  position preference. Adapt to the app window, not the device's model name.
- Returning from Viewer or Editor should restore the relevant library filter, scroll anchor and photo. Deleting the
  current photo should select a valid neighbor, or return to the library when none remains.
- Back must dismiss the active keyboard/overlay or leave the current task at the appropriate layer. It must not
  unexpectedly clear selection and leave a screen in one step. After dismissing a sheet/dialog, restore focus to
  its opener when that control still exists.
- Editor Back must follow the existing changed-edit confirmation contract; cancelling that prompt preserves work.
  Navigation away from a running import must not be presented as cancellation unless it actually cancels the job.
- Support system Back and predictive-back behavior using supported components. Starting and then abandoning a Back
  gesture must not discard edits, delete files or start an export. Keep brush, crop and photo-page gestures clear
  of system gesture regions. Verify actual behavior rather than assuming `BackHandler` provides the animations [A2].

## 4. Reachability, layout and window changes

One UI distinguishes a viewing area above a reachable interaction area [S2]. For Latent, apply the principle to
frequent tools and primary actions; do not reserve half the photo editor for a large title. Samsung's grid guidance
uses at least 24 dp side margins [S3]; that is not a universal Android requirement.

**Proposed layout defaults:**

- Use 24 dp horizontal padding for text/form groups in Camera, Location and Settings where space permits. A
  full-bleed photo/map or dense thumbnail grid may use different gutters. Keep interactive overlays inside safe
  bounds and document exceptions; do not invent app-level palm-rejection zones that swallow normal input.
- Use consistent Material spacing, with an 8 dp rhythm for new groups and 4 dp for small internal adjustments.
  These are proposed Latent values. Touch area, large text and available space take priority over visual density.
- In a portrait editor, put repeated adjustments below the picture. In a wide, short window, use a side panel so the
  image keeps useful height. Keep save/exit controls discoverable when panels scroll or the keyboard opens.
- Keep the familiar toolbar for Back and occasional actions. A giant header or a second competing primary button
  is not required for One UI compatibility. Any relocation of the existing Done button needs a separate UI change
  with reachability and accessibility checks.

Use the current window dimensions and height constraints [A3]. Samsung's published compact/medium/expanded width
ranges start at below 600, 600 and 840 dp [S4]; use these as review cases, not proof that every window at a threshold
can fit two panes. Preserve photo identity, edit values, selection and meaningful scroll position through rotation,
folding, split screen and DeX resizing. Keep controls and critical photo details away from an occluding hinge.

Backgrounds can extend edge-to-edge, but actionable content must account for status/navigation bars, cutouts,
caption bars, keyboard and gesture insets [A4]. Apply insets once per edge; an extra status-bar inset on the bottom
bar creates an empty band. Check gesture and three-button navigation. Avoid locking orientation to conceal a layout
problem, and do not stretch a phone form across a tablet.

## 5. Color, type, touch and accessible input

Android specifies at least 48 dp touch targets, scalable text, sufficient contrast and alternatives to gesture-only
operations [A5]. Samsung explicitly calls for text to remain usable at 200% and for complex interactions to have
alternatives [S5], [S6].

**Proposed:**

- Use at least **48 × 48 dp effective hit areas**, even for smaller icons. Keep adjacent hit areas distinguishable;
  expanding a target must not cause the wrong neighboring action to fire. Do not judge accessibility from icon size.
- Use semantic Material type styles and `sp`. Start new body copy at 16 sp where practical; this is a Latent default.
  At 200% text and increased display size, allow wrapping/scrolling instead of shrinking text or clipping decisions.
- Check **4.5:1 text contrast** and **3:1 essential icon/control contrast** against the actual surface [A5]. Overlays
  on arbitrary photos need a stable backing or scrim. Selection, errors, picks and rejects need a shape, icon or text
  in addition to color.
- Keep the existing near-neutral photo surround while general screens follow the user's theme preference. Theme
  colors must not tint the photo, its comparison or histogram data. This preserves a current Latent design choice;
  it is not a claim of calibrated viewing or a Samsung requirement.
- Expose an accessible name, role, state and value for controls. Announce rating/selection changes and major progress
  milestones without speaking every byte or slider frame. Decorative icons accompanying a labelled control should
  not be read twice. Do not put private coordinates into routine thumbnail announcements.
- Provide non-gesture access to zoom, before/after, rating and editing adjustments. For a new custom canvas tool,
  plan focusable actions or numeric/step controls for its meaningful operations; merely describing the canvas is
  insufficient. Keep an accessible equivalent of a press-and-hold original preview.
- Test keyboard focus and visible focus indicators. Existing shortcuts supplement buttons; letter shortcuts must
  not steal text while entering album names or searching. Mouse, stylus and S Pen interactions must retain a touch
  alternative and must not depend on hover to reveal an essential command.

## 6. Components, feedback and writing

Samsung favors copy that helps a person decide, act or understand status [S7]. Use short, concrete action labels
and localized plurals, times and sizes. Avoid exposing implementation terms such as coroutine, SAF or inference
backend in normal UI. Keep technical detail available only where it helps diagnose a problem.

**Proposed component choices:**

- Use a bottom sheet for contextual tools, photo details or filters that benefit from seeing the underlying photo.
  Prefer a regular screen for a long settings task. Avoid stacking modal sheets and confirmation dialogs.
- Use a confirmation dialog for permanent deletion, discarding unsaved work or applying a restore with replacements.
  Name the affected content and give explicit verbs rather than Yes/No. Routine viewing/filtering needs no prompt.
- One UI places choice/confirmation dialogs low on the screen [S8]. Latent currently uses Material `AlertDialog`;
  retain the standard accessible component as the baseline. Bottom placement is an optional ergonomic proposal,
  not an instruction to restyle every dialog or alter system permission dialogs. It must remain usable with the
  keyboard, large text and short windows.
- Use inline help for a field error, a persistent result area for unfinished/failed work, and a snackbar for brief
  confirmation or an actually supported Undo. A disappearing message must not be the only recovery path.
- Show determinate progress only with a meaningful total. Otherwise show the current phase. Once cancellation is
  requested, say what is still finishing; do not show success before work is committed.
- Motion should connect the selected thumbnail, viewer and tools without obscuring the photo. Avoid flashes between
  images and excessive animation during adjustments, following Samsung's continuity guidance [S9]. Respect reduced
  animation preferences; haptic feedback is optional reinforcement, never the sole indication of success/failure.

Copy examples below are proposals, not shipped translations:

- Camera disconnect: **“Camera disconnected. 24 files were imported. Reconnect to import the remaining files.”**
  Use counts and a recovery instruction only when the job result supports them.
- Cancellation: **“Stopping after the current file…”** when that is the actual cancellation boundary.
- Permanent deletion: **“Delete 3 photos permanently?”**, with **Cancel** and **Delete permanently**.
- Backup: **“Back up edits, albums and location history. Photos and videos are not included.”**
- Korean example: **“사진 3장을 영구 삭제할까요?”**, **“취소” / “영구 삭제”**. Match the existing translation vocabulary;
  review English, Korean and Japanese together when changing a resource.

## 7. Camera import

Observed entry points and status live in
[SyncScreen.kt](../app/src/main/java/com/byeonggon/latent/ui/sync/SyncScreen.kt). Proposed flow:

1. Distinguish **no camera**, **permission needed**, **connected/ready**, **scanning** and **transferring**. Offer the
   existing card/drive entry alongside USB guidance. Show source and destination before an unfamiliar operation.
2. Keep a useful file/count/byte summary during transfer. Let the user request cancellation and explain its boundary.
   Leaving the screen, switching apps and turning the display off must not imply the operation has stopped.
3. Keep the copy-and-compare decision separate from importing known-new files. Name/size resemblance is not proof
   of a duplicate. Explain the transfer cost and that declining leaves the candidate unverified, not imported.
4. Show imported, failed and not-yet-verified outcomes distinctly. “Nothing new” must not conceal comparison work
   waiting for consent. Let users find the newly imported photos without searching the whole library.
5. After a disconnect or storage failure, keep the completed count and explain how to retry the remaining work.
   Do not promise byte-level resume or automatic retry unless supported. Never suggest deleting the camera's
   originals to resolve an import failure.

**Review scenario:** start a multi-file import, disconnect, reconnect and retry; repeat with insufficient storage.
Already committed files must remain identifiable, and the UI must explain what still needs work. These device
cases remain unverified in the latest SC-57G summary; this guide does not close them.

## 8. Library, Viewer, Compare and trash

Proposed behavior builds on
[LibraryScreen.kt](../app/src/main/java/com/byeonggon/latent/ui/library/LibraryScreen.kt),
[ViewerScreen.kt](../app/src/main/java/com/byeonggon/latent/ui/viewer/ViewerScreen.kt) and
[CompareScreen.kt](../app/src/main/java/com/byeonggon/latent/ui/compare/CompareScreen.kt):

- Empty states distinguish no library content, denied/limited access, no search matches and empty trash. Offer one
  relevant next step, such as choosing phone folders or clearing filters. Do not call an unreadable library empty.
- Keep filter scope visible. Selection mode shows a count and explicit exit; long-press and right-click shortcuts
  are additional entry points. Batch commands explain RAW/JPEG pairing and whether both files are affected.
- Preserve the distinction between imported files and photos owned by another app. A phone-photo edit is retained
  as app metadata; saving a JPEG copy creates a new file. An Android deletion request is not complete until the
  system accepts it; cancellation must leave the photo visible.
- Give Viewer a discoverable way to reveal its controls. Zoom/pan must not unexpectedly switch photos; comparison
  should make the current image and linked/unlinked zoom state understandable. Preserve photo orientation.
- Describe an HDR display as a display capability, not evidence that every export preserves HDR. Maintain a clear
  route back to the library after video playback, loading failure or removal of the last visible photo.
- Distinguish moving to trash, restoring, removing from an album and deleting permanently. Display the configured
  retention period (7, 30 or 90 days), not a hard-coded 30-day promise. Permanent deletion must have explicit scope
  and confirmation. Offer Undo only for an operation the data layer can actually reverse.

## 9. Editing and saving

**Proposed:** keep Light, Color, Effects, Crop, Masks, Healing, Detail and Filters as understandable tool groups,
with labels and current values. Reveal advanced controls progressively without hiding the way to undo a change.

- Keep the selected tool/mask and current brush/erase state visible. A finger drag must have one clear purpose:
  adjusting, moving the canvas or painting. Do not change that purpose silently mid-gesture.
- Maintain undo/redo, original comparison, reset and named versions as distinct concepts. Resetting one control,
  one mask and the entire edit must have visibly different scope. An unavailable action should have an explanation
  where its absence would otherwise look like a fault.
- Explain what **Done**, **Save a copy as JPEG**, export and restoring the original actually do for that file type
  and ownership. Do not describe Done as a JPEG export for every photo. Show whether work is changed, saving,
  saved or failed; retain recoverable work on failure and do not announce Saved before the data/file result commits.
- Distinguish editing parameters from changing an original file. Preserve the current original-retention contract;
  any future change to it requires a separate reviewed implementation and accurate user-facing explanation.
- Preview and saved output must agree about orientation, crop and mask placement. A lower-resolution preview is
  acceptable while processing if the UI does not imply it is the final export. Assess Detail at a meaningful zoom.
- Subject/background/sky generation runs on-device. Show processing, cancellation where supported, and recoverable
  failure; keep manual masks usable if generation fails. Do not claim a perfect selection or send a photo elsewhere.
- Explain the SDR editing/output limitation before the user commits an affected HDR edit. The existing gain-map
  notice is one implementation, not proof that every HDR format/path has sufficient disclosure.

The [device checklist](DEVICE_CHECKS.md) remains the source for image-quality, GPU, RAW and HDR validation. This
UX document does not establish color accuracy, export fidelity or memory/performance budgets.

## 10. Permissions, location, maps and sharing

Request permissions at the point a feature needs them, explain the consequence of refusal and keep unrelated
features usable [A6]. Use Android's permission UI and document pickers; app copy prepares the user for the request
but must not imitate a system dialog. Handle limited access, revoked folder grants and a denied notification
permission with honest feature-specific status.

**Latent proposals, constrained by the existing privacy contract:**

- Do not require location recording or online maps to browse/edit photos. Explain camera timezone matching in plain
  language before applying locations; recorded, imported and photo-embedded locations are distinct sources.
- Recording needs an explicit on/off state, a clear stop action and the existing ongoing notification. Explain when
  recording stopped or permission is insufficient. Do not silently resume collection after a restore.
- Online maps remain an opt-in. Explain that fetching map tiles reveals the viewed map area and IP address to the
  map service, preserve visible attribution, and offer a way to turn maps off. Offline or consent-disabled states
  must not look like missing photos.
- Use Android's Sharesheet for sharing destinations [A7]. Before handoff, make format and location treatment
  understandable. Choosing a recipient is not proof of successful delivery.
- Do not promise “all location removed” or “anonymous” for the current sharing switch. The
  [sharing checks](DEVICE_CHECKS.md#7-sharing) explicitly limit it to EXIF GPS cleanup: JPEG XMP is not searched,
  raw/video originals are passed through, and external-editor handoff is separate. HEIF can be converted to JPEG
  for the cleaned share. Future UX must disclose unsupported cases, or improve and verify the implementation
  before making a broader promise; it must not silently fall back to an original carrying location.

## 11. Backup, restore and long operations

Follow the existing [backup contract](BACKUP_FORMAT.md) and
[privacy policy](../PRIVACY.md#backups). These are Latent-specific requirements:

- Say **photos and videos are not included** before creation and restore. Explain that the archive includes edits,
  albums and private location history. A cloud provider selected in Android's file picker handles that file under
  its own terms; “Latent does not upload” is not a promise that the chosen provider is local.
- Before restoring, show the archive date, matching/missing photo counts and replacement/merge consequences.
  The user must be able to cancel the inspection. Explain that permissions must be granted on the destination
  phone and that online maps remain off unless separately enabled.
- While working, show the phase and useful progress without inventing a total or completion time. Keep cancellation
  and result feedback available on large text and short windows. Explain temporary non-cancellable finalization
  only if the implementation actually has such a boundary.
- On cancellation/failure, state what committed, what stayed unchanged and any incomplete file the user must remove.
  Do not use a generic success message for a partial result. Never recommend uninstalling as the first recovery step.

For any long operation, distinguish screen lifetime from job lifetime. Preserve truthful status on returning to
Latent; do not imply background completion or automatic resume after process death unless implemented and verified.

## 12. Applying and reviewing this guide

This is a proposed UX reference, not an automatic refactor list. Use it when changing the relevant screen. Existing
[AGENTS.md](../AGENTS.md) decides authoring/review responsibilities; this document does not add a new review gate.

**First implementation checks to consider after review:**

1. Verify disclosure and recovery for saving, destructive actions, partial imports, metadata-only backup and location
   sharing. The known sharing limitations above are a concrete disclosure requirement, not a new claim of cleanup.
2. Audit actual touch targets, TalkBack semantics/focus, 200% text, gesture conflicts and predictive Back. Source
   inspection here establishes candidates to inspect, not a list of confirmed defects or passing device tests.
3. Check reachability, dialog behavior, motion and spacing after preserving task correctness. Do not mass-restyle
   every Material component to match an old Samsung screenshot.

For a future UI PR, describe the task and before/after behavior, distinguish an observed issue from a proposed
improvement, and include screenshots/recordings only of screens actually changed. Record which of these were tried:

- **Compact touch:** portrait and landscape, gesture and three-button navigation, keyboard open, light/dark themes,
  left/right hand reachability, and retained library context after viewing/editing.
- **Accessible input:** TalkBack focus/actions, keyboard-only completion, 200% text and increased display size;
  selections, warnings and values remain understandable without color or a timed message alone.
- **Adaptive:** widths around 560/600/840 dp, short split windows, resizing while editing/importing, fold/unfold and
  DeX or another desktop window; hinge/cutout and caption-bar areas do not cover controls. Test the chosen bottom
  or side navigation preference too.
- **Failure and recovery:** denied/revoked access, empty/filter-empty states, disconnect, low storage, failed save,
  restore cancellation, and the actual supported metadata-removal paths. Use disposable test data for destructive
  cases, not the owner's library.
- **Evidence:** build/commit, device/emulator and OS/One UI version, settings and scenario, observed result and
  remaining limitations. A screenshot proves layout only; a synthetic test does not prove one-handed usability.

No device, accessibility or visual checks were run for this documentation PR. Keep the existing
[DEVICE_CHECKS.md](DEVICE_CHECKS.md) and [SMOKE_TEST.md](SMOKE_TEST.md) results separate from this proposed review list.

## Sources

All external references below are official publisher pages, consulted **2026-10-26**. Section citations show which
principles are external; the concrete Latent workflows, exceptions and proposed defaults are this document's
application of those principles. No Samsung artwork, font package or UI assets are copied into the repository.

- [A1 — Android: Layouts and navigation patterns][A1]. Peer navigation, actions and adaptive navigation.
- [A2 — Android: Predictive back design][A2]. Back preview/cancellation and system gesture space.
- [A3 — Android: Use window size classes][A3]. Layout decisions based on the current app window.
- [A4 — Android: System bars][A4]. Edge-to-edge, insets, cutouts and navigation modes.
- [A5 — Android: Accessibility][A5]. Touch targets, contrast, semantics and alternative input.
- [A6 — Android: Request runtime permissions][A6]. Contextual requests and denial handling.
- [A7 — Android: Send data to other apps][A7]. Sharesheet and inter-app sharing.
- [S1 — Samsung: One UI overview][S1]. Task focus, comfort and responsive design.
- [S2 — Samsung: Basic layout][S2]. Viewing and interaction areas.
- [S3 — Samsung: Grid system][S3]. Margins and display geometry.
- [S4 — Samsung: Layout design for large screens][S4]. Flexible windows, navigation and content layout.
- [S5 — Samsung: Layout and typography][S5]. Scalable text and touch spacing.
- [S6 — Samsung: Interaction and control][S6]. Accessible alternatives, error recovery and consistent controls.
- [S7 — Samsung: Focused and purposeful][S7]. Decision-oriented, useful interface copy.
- [S8 — Samsung: Dialog][S8]. Confirmation/choice placement; a documented platform-style difference.
- [S9 — Samsung: Motion basics and usage][S9]. Continuity and feedback.

[A1]: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns
[A2]: https://developer.android.com/design/ui/mobile/guides/patterns/predictive-back
[A3]: https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
[A4]: https://developer.android.com/design/ui/mobile/guides/foundations/system-bars
[A5]: https://developer.android.com/design/ui/mobile/guides/foundations/accessibility
[A6]: https://developer.android.com/training/permissions/requesting
[A7]: https://developer.android.com/develop/ui/compose/sharing/send
[S1]: https://developer.samsung.com/one-ui/index.html
[S2]: https://developer.samsung.com/one-ui/layout/basic.html
[S3]: https://developer.samsung.com/one-ui/layout/grid.html
[S4]: https://developer.samsung.com/one-ui/largescreen-and-foldable/large_screen_layout.html
[S5]: https://developer.samsung.com/one-ui/accessibility/layout-and-typo.html
[S6]: https://developer.samsung.com/one-ui/accessibility/interaction-and-control.html
[S7]: https://developer.samsung.com/one-ui/writing/focused-and-purposeful.html
[S8]: https://developer.samsung.com/one-ui/comp/dialog.html
[S9]: https://developer.samsung.com/one-ui/motion/basic.html
