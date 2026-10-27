# Photo colour picker and optional colour swatches

Status: approved by the owner in conversation; implemented on `codex/colour-picker-design`. Qualified cross-family review and device checks remain pending.
Agent: Codex. Baseline: main `14d3890`.

## Intent and agreed choices

Make colour-range masking easier by picking a colour from the photograph. The owner agreed that existing colour swatches remain, with a persistent setting to hide them, and that the setting belongs in the colour section of the mask panel rather than the app Settings screen. Swatches are shown by default. The picker remains available when swatches are hidden.

This is colour selection, not object segmentation: the same hue elsewhere in the photograph can also be affected. Existing geometric/detected masks continue to constrain their own colour range.

## Approaches considered

- Replace the eight swatches with a picker: less panel content, but removes a quick existing input. The owner chose to retain swatches with an optional hide setting.
- Put the preference in app Settings: centralised, but interrupts editing. The owner chose a contextual panel menu.
- Sample the final displayed bitmap: cheap, but red overlays, clipping marks, local adjustments and LUTs can change the sampled hue. Implementation proposal: sample the exact input to the selected local mask's colour predicate and display that input while picking. This technical choice was included in the approved design.

## Scope and interaction

Keep the existing Add Colour action and its initial default colour/history behaviour. In the shared colour section of any selected local mask, add an explicitly labelled Pick from photo action and a menu containing a checked Show colour swatches item. Hiding swatches hides only the eight fixed hue choices; Any colour, the current hue indication, picker and applicable range control remain. The existing rule that a whole-photo colour mask cannot choose Any colour remains.

Picking is an explicit temporary mode:

1. Preserve the selected mask's stable session identity, previous hue and current edit revision. Prepare a bounded input preview asynchronously. Until ready, do not accept samples; cancellation remains available.
2. Show the mask-input preview without mask paint, clipping warnings or handles. Briefly explain that it shows the colours used for selection. Existing final colour effects return on leaving this mode.
3. A tap positions a crosshair; dragging moves it and previews the candidate hue without changing the committed edit. Show an offset magnifier that does not cover the sampled point, plus a textual hue value. Clamp its position to the available picture region and keep Apply/Cancel reachable in portrait and the side-panel layout.
4. Apply changes only the selected mask's hue and creates one undoable history step. Keep its spread, adjustments, geometry, detection and brush strokes. Applying an unchanged hue creates no new step. Editor Done still controls the normal eventual background save.
5. Cancel or Back leaves the original edit/history unchanged. Back exits picking before invoking the editor's existing unsaved-edit navigation. Changing photo/tool/selected mask, source reload or lens correction invalidates the picker session and cancels its draft; results from stale asynchronous work are ignored. Window changes retain the cursor in normalised image coordinates and the uncommitted candidate when the same session survives; process recreation may safely cancel the draft.

These Apply/Cancel and magnifier details are part of the approved design. A cancelled picker does not undo a mask that Add Colour already created.

## Colour and coordinate contract

The current CPU renderer invokes `colourWeight` after global tone shading and, for a later mask, after the RGB contributions of preceding local masks. Mixer/monochrome, grading, LUT, vignette/grain and final display encoding follow. Preserve this existing order. Sampling all-locals-disabled or final-display RGB would not match later masks.

Expose a read-only sampling view from the same rendering stage immediately before the selected mask, including healing, the active source/lens correction, global tone and preceding local contributions. Reuse colour maths; do not copy an approximate display-bitmap HSV calculation or alter CPU/shader colour invariants. Its display encoding is only for the temporary photo/magnifier; compute hue from its working RGB, as `LocalEdit.colourWeight` does. Tests must establish that the picked colour is actually accepted by that predicate.

Use the existing bounded editor proxy, not a full-resolution decode or one render per pointer event. Freeze its sampling buffer for the picker session. Clamp negative channels consistently with the predicate, average a 5 by 5 proxy-pixel neighbourhood clipped at image edges, then derive hue. Reject samples with no meaningful chroma under the existing colour-mask thresholds: explain why, retain the previous candidate, and do not enable Apply without a valid candidate. Do not invent an arbitrary hue for gray/black pixels.

Mask editing currently displays the whole upright, unframed image. Use its existing `MaskLayout` fit and inverse mapping; reject taps in letterboxing instead of sampling an edge. Crop/straighten/perspective are therefore not baked into the picking view. EXIF orientation and source lens correction already applied to the proxy must match the view. No new pan/zoom system is introduced in this feature. Cursor/magnifier and sampling must refer to the same revision and normalised point.

## Preference, accessibility and safety

Add an on-device DataStore boolean, default true when absent. It applies to the shared colour section across masks/photos and survives reopening. It changes no photo edit, undo history, Room schema or backup format. First version keeps this panel-display preference local to the device; photo and edit backups are unchanged.

Provide named controls, checked menu state, 48 dp effective targets, readable double-edge crosshair and 200% text support per UX_GUIDE/UI_GUIDE. Expose non-gesture sampling: keyboard/D-pad arrows and accessibility move-left/right/up/down actions move the normalised cursor by 1% of image width/height, bounded to the image. Initialise that cursor at the centre only when those actions are used; do not silently choose a colour on entering. Announce candidate hue and invalid samples without speaking every drag event. Apply/Cancel remain normal focusable controls. Provide EN/KO/JA strings.

No new model, dependency, permission, network request or original-file write. No changes to how an existing edit renders or saves merely by opening/cancelling the picker.

## Verification and delivery

- Generated colour patches: primary hues, wraparound red, gray/black rejection, edge neighbourhoods, and same-hue selection outside an object.
- Renderer parity: picked hue matches selected-mask predicate with earlier local edits, global tone changes, strong final LUT and overlay/clipping controls. CPU and GPU mask results remain within existing tolerances.
- Coordinates/state: letterboxing, portrait/landscape images, EXIF rotation, lens correction, non-default framing, resize, stale source/session and cancelled preparation.
- Flow: picker remains when swatches hidden; Any colour rules remain; preference survives reopening; apply is one undo step; cancel/Back/no-op apply preserve history.
- Input/layout: drag, keyboard/accessibility movement, 200% type, phone portrait/landscape and side panel. On-device check of magnifier occlusion, actual picked region and responsiveness; synthetic JVM checks alone do not establish these.
- CI-equivalent JVM tests, both Lints, instrumentation compilation and clean schema diff. Implementation changes colour/UI in Claude Code's area and requires the repository's qualified cross-family review; the design is suitable for a Claude Code implementation handoff. No merge follows from design approval alone.

Builder may settle internal helper names, buffer representation, caching details, exact magnifier size/placement and resource wording within the guides. Changes to scope, colour-stage semantics or the agreed preference behaviour must return to the owner.
