# Photo Colour Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pick a colour directly from the photo for a local mask, retaining optional persistent colour swatches.

**Architecture:** Produce an immutable bounded sampling plane from the renderer immediately before the selected local mask. A cancellable editor session owns that plane and its draft candidate; Apply makes one history change. A contextual panel preference controls only fixed swatch visibility.

**Tech Stack:** Existing Kotlin, Compose, DataStore, JVM/Robolectric/Compose tests and Android instrumentation. No new libraries.

**Spec:** `docs/superpowers/specs/2026-10-27-photo-colour-picker-design.md` (approved by the owner in this conversation).

## Global Constraints

- Swatches are shown by default. The picker remains available when swatches are hidden.
- The setting belongs in the colour section of the mask panel rather than the app Settings screen.
- Average a 5 by 5 proxy-pixel neighbourhood clipped at image edges, then derive hue from working RGB.
- Keyboard/D-pad and accessibility movement is 1% of image width/height; initialise the cursor at centre only on their first use.
- Preserve existing rendering, local-mask order, hue-spread range, Add Colour behaviour, originals and eventual Editor Done save.
- One Apply is one undo step; Cancel/Back/stale work never changes the edit/history.
- Keep the preference on-device in DataStore: no Room/backup-format change. EN/KO/JA, 48 dp hit areas and 200% text.
- No new model, permission, network access, full-resolution decode or pointer-event rendering.
- Work only in the Codex worktree; explicit-path staging and `Agent: Codex` trailers. Mandatory qualified cross-family review before merge.

## Review Focus

- A later local mask sees RGB changed by earlier masks, while final LUT/overlay colours do not contaminate sampling: Task 1 prefix-stage tests.
- Gray/black and non-finite samples must not produce a seemingly valid arbitrary hue: Task 1 validity/edge fixtures.
- A mask or source changes while asynchronous preparation is finishing: Task 2 stale completion and close/reopen tests.
- Resizing or tapping letterboxing must not silently choose another pixel: Task 4 geometry/resize tests.
- Hidden swatches must not remove Any colour, the current value or the only accessible route to picking: Tasks 3–4 visibility and accessible-action tests.

## File Structure

- `enhance/ToneRenderer.kt`: shared prefix shading used by normal CPU rendering and sampling, preserving current colour and luma rules.
- New `enhance/ColourSampling.kt`: immutable `ColourPickPlane`, `PickedColour` and bounded neighbourhood sampling.
- `ui/editor/EditorViewModel.kt`: source coordination, draft picker lifecycle, history and preference projection.
- New `ui/editor/ColourPicker.kt`: `ColourPickerState` and picker canvas/controls, separate from the already large editor screen.
- `ui/editor/EditorScreen.kt`, `LocalPanel.kt`: route picker UI, Back/tool/selection lifecycle and contextual controls.
- `data/SettingsRepository.kt`: boolean persistence only.
- `res/values{,-ko,-ja}/strings.xml`: instructions, menu, accessible movement, error and action copy.
- Tests under `app/src/test/java/com/byeonggon/latent/`: new `enhance/ColourSamplingTest.kt`, `ui/editor/ColourPickerLayoutTest.kt`, `ColourPickerFlowTest.kt`; extend `SettingsTest.kt`, `enhance/ToneRendererTest.kt` and existing GPU/device checks where needed.

Paths above are relative to `app/src/main/java/com/byeonggon/latent/` unless a full path is given. Shared DataStore/renderer changes are kept focused; no unrelated extraction or shader rewrite.

### Task 1: Matching, bounded colour sampling

**Interfaces:** `internal data class PickedColour(val hue: Float, val argb: Int)`; `internal class ColourPickPlane(val picture: ShownPicture)` with `fun sample(x: Float, y: Float): PickedColour?` for normalised coordinates and `fun pixels(): IntArray` for its matching display preview. `internal fun ToneRenderer.colourInput(source: LinearImage, localIndex: Int): ColourPickPlane` requires a valid selected index and whole unframed source. Returned arrays are frozen and owned by that picker plane.

- [ ] Write `ColourSamplingTest`: literal red/green/blue patches yield 0/120/240 degrees; reds around 0/360 stay red; gray/black/non-finite/out-of-image samples yield null; 5×5 averaging and clipped corner neighbourhood have hand-computed expected RGB/hue; negative-channel handling matches the existing predicate.
- [ ] Write renderer tests with two masks: a preceding mask changes the selected mask's input, selected/following masks do not; global tone is included; extreme LUT/mixer/grading, framing and clipping display state do not enter this plane. Assert the picked hue receives nonzero colour weight for the corresponding valid patch. Keep normal renderer regression expectations unchanged, including the current pre-local luma behaviour.
- [ ] Run `./gradlew testDebugUnitTest --tests '*ColourSamplingTest' --tests '*ToneRendererTest'`: observe the new failures before implementation.
- [ ] Implement prefix shading shared with the existing CPU loop, preserving its exact local contribution order and existing normal-output/GPU selection. Do not use 8-bit final pixels to compute hue. Extract only the RGB→hue/validity maths needed to keep the sampler and predicate consistent; do not change old edits' weight curves.
- [ ] Run those tests plus `'*LocalEditTest'` and `'*ToneRendererLookTest'`. Commit the sampler, renderer and tests with an agent trailer.

### Task 2: Safe editor picker session and history

**Interfaces:** `EditorUiState.colourPicker: ColourPickerState?`. The state exposes preparation status, matching preview, normalised cursor, candidate and invalid-sample feedback. Add `beginColourPick(index: Int)`, `pickColour(x: Float, y: Float)`, `moveColourCursor(across: Float, down: Float)`, `applyColourPick()`, `cancelColourPick()`. Cursor moves are normalised deltas; the UI supplies ±0.01 on an axis. No new persistent mask ID is needed: bind an ephemeral generation to item ID, Source identity, selected index and immutable edit snapshot.

- [ ] Add `ColourPickerFlowTest` cases using the existing real application/Room test setup: entering/moving/cancelling does not change edit/history; Apply changes hue only and one Undo restores it; no-op Apply creates no history entry; a gray sample cannot apply without a previous valid candidate; close/reopen/tool/selection changes invalidate the draft.
- [ ] Add a controlled asynchronous-preparation test where the original item/source/selection is invalidated before completion; releasing the work must not republish the old preview or hue. Add preparation failure/cancel tests.
- [ ] Run `./gradlew testDebugUnitTest --tests '*ColourPickerFlowTest'` and observe failure.
- [ ] Implement a generation-checked cancellable job. Serialize sampling preparation with Source's existing mutable render work using a shared coroutine Mutex, with cancellation-safe acquisition/release. Prepare healing and source/lens state from the same captured edit; publish immutable plane/preview together. Release the plane on exit. Do not mutate a bitmap already published for another frame or launch work per pointer event.
- [ ] Cancel draft on edit/source replacement, save/close and incompatible navigation. Apply first detaches its draft, then uses existing `changeOnce`; normal Back discards only the picker draft before the editor's existing confirmation. Ensure normal rendering and filter previews retain their current contracts.
- [ ] Run the new flow tests and `'*EditorFlowTest'`; commit focused lifecycle changes and tests.

### Task 3: Persistent swatch visibility and contextual controls

**Interfaces:** `AppSettings.showColourSwatches: Boolean = true`, DataStore key `show_colour_swatches`, `EditorUiState.showColourSwatches` and `EditorViewModel.setShowColourSwatches(show: Boolean)`. Extend `LocalPanel` with `showColourSwatches`, `onShowColourSwatches`, `onPickColour` callbacks.

- [ ] Extend `SettingsTest`/`ColourPickerFlowTest`: absent key means shown; toggle survives a new repository/editor session; unrelated settings remain; no edit/history changes. Verify restoring existing backup settings preserves this device-local boolean without adding a backup key.
- [ ] Add panel tests: hide removes only the eight fixed swatches; Any colour stays available for geometric/detected masks and stays disabled for whole-photo colour masks; current hue, picker, range slider and checked menu state remain. Existing Add Colour keeps its current default and history behaviour.
- [ ] Observe failures with `./gradlew testDebugUnitTest --tests '*SettingsTest' --tests '*ColourPickerFlowTest'`.
- [ ] Persist/project the boolean through existing settings flow. Preserve its current value through `EditorViewModel.close()`/UI reset, rather than waiting for a settings emission that may never recur. Add contextual menu and labelled picker entry with standard accessible controls; retain default swatch UI.
- [ ] Add EN/KO/JA resources, including Show colour swatches / 색상 견본 표시 / 色見本を表示, and action/instruction/error strings. Run the tests; commit.

### Task 4: Sampling canvas, magnifier and accessible input

**Interfaces:** `@Composable internal fun ColourPickerFrame(state: ColourPickerState, onPick: (Float, Float) -> Unit, onMove: (Float, Float) -> Unit, onApply: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier)`. Reuse `MaskLayout` fit/inverse mapping; add a narrowly scoped checked mapping helper if necessary. Reject out-of-picture positions rather than clamping them into the image.

- [ ] Add `ColourPickerLayoutTest`: literal fit coordinates for wide/tall images; outside taps reject; centre/edges map correctly; resize retains normalised cursor; magnifier remains within available bounds and offset from the selected point. Add source-orientation/framing integration fixtures to the flow tests.
- [ ] Add Compose flow assertions for tap/drag candidate, visible current hue, invalid-sample feedback, Apply/Cancel and Back. Test keyboard and accessibility moves of ±0.01, bounded at edges; first such action starts at centre and entering alone selects nothing. Hidden swatches still permit that route.
- [ ] Observe failures with `./gradlew testDebugUnitTest --tests '*ColourPickerLayoutTest' --tests '*ColourPickerFlowTest'`.
- [ ] Implement crosshair/magnifier over the matching preview, never over the old final image; omit mask handles/paint/clipping in this mode. Use double-edge readable crosshair, labelled candidate and controls, 48 dp targets, semantic move actions and keyboard handling. Coalesce announcements to deliberate samples/input, not every drag event.
- [ ] Wire through EditorScreen and its normal/side panel layout. Disable incompatible editing while the mode is active; leave cancellation available during preparation. Test 200% text and landscape/side panel in Compose without clipping action labels; commit.

### Task 5: Whole-feature verification and reviewable delivery

- [ ] Run `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest`, then `git diff --exit-code -- app/schemas` and `git diff --check`. Resolve actual regressions; keep existing test strength.
- [ ] Run/update meaningful GPU parity coverage with earlier local edits and picked hue, preserving existing tolerances. For device tests use serial-specific `adb shell am instrument`, never `connectedAndroidTest` and never uninstall/clear personal app data. If no suitable device is connected, explicitly leave GPU/device interaction checks unverified.
- [ ] On the S24 Debug app when available, use generated colour patches to check actual selected region, portrait/landscape alignment, magnifier occlusion, one-hand controls, TalkBack/keyboard route, 200% text, preference reopen, one-step Undo and Cancel. Do not claim real-photo quality from patches.
- [ ] Update ROADMAP item 4 and DEVICE_CHECKS with exact built/remaining scope, record evidence/limits, and move the HANDOFF claim to Done when implementation is completed. Stage only task paths and commit with `Agent: Codex`.
- [ ] Publish the implementation branch/PR and attach it to this task. Request one qualified cross-family review covering the full colour, state, cancellation and UI diff; no self-review satisfies that gate and no merge is implied.

## Execution choice pending

Recommend native execution in this session: the renderer/session/UI tasks share tight interfaces and one implementation context reduces handoff overhead. A fresh whole-branch reviewer still checks the final implementation, and the mandatory cross-family gate remains. The owner has approved the design and requested progress; this plan is now ready for their execution-method choice under the writing-plans workflow.
