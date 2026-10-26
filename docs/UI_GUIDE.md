# Latent UI guide

**Status:** proposal for review, written at the owner's request on 2026-10-26. This is the visual companion to
[UX_GUIDE.md](UX_GUIDE.md): the UX guide says what a screen must let a photographer do and know; this guide says how
Latent should look and feel while doing it. It is written from a designer's view of where Latent should go, not as
a description of the current code. It does not authorize a redesign by itself; each change still lands as its own
reviewed UI change.

**Basis:** the same official Android and Samsung One UI sources as the UX guide ([Sources](#sources)), the
Latent brand already in the app icon, and the app on `main` at `6fe7593` for the few places where it is compared.

**Reading key:** **must** marks an accessibility, legibility or photo-fidelity requirement; **should** marks the
preferred design default; values marked *proposed* are Latent decisions for review, not platform rules. Where this
guide and the UX guide seem to disagree, the UX guide's task and safety rules win and this guide is corrected.

## 1. Design principles

1. **The photo is the loudest thing on screen.** Chrome is quiet, neutral and small next to the picture. No
   decoration competes with an image: no gradients, textures, coloured backgrounds or illustration behind photos.
2. **Neutral where colour is judged.** Anywhere a person evaluates colour (Viewer, Editor, Compare, the library grid)
   the surroundings are neutral grey, and app colour appears only as small state marks.
3. **One accent, used for meaning.** Latent's amber marks the active, selected or primary thing. It is not used for
   decoration, and it is never the only way a state is shown.
4. **Calm and consistent.** Three tabs, one title style, one icon family, one way to show progress, one way to
   show selection. A person should learn a pattern once.
5. **Quiet confidence.** Status (saving, importing, recording, failed) is visible without being alarming; colour
   escalates only for errors and permanent loss.
6. **Comfortable on a phone held in one hand.** Frequent controls sit in the lower part of the screen, targets are
   generous, and layouts adapt to the window rather than to a device model [S2], [A3].

## 2. Two surface families

Latent has two kinds of screens, and they are designed differently.

| | **App surfaces** | **Photo workspace** |
|---|---|---|
| Screens | Library (browsing), Import, Map chrome, Location, Settings, dialogs and sheets over them | Viewer, Editor, Compare, crop/mask/healing overlays |
| Theme | Follows the person's Light/Dark/System choice | Always dark, whatever the system theme |
| Colour | Neutral greys + amber accent | Neutral surround; accent only on the active control |
| Elevation | Tonal containers | None: flat chrome over a flat surround |
| Goal | Find, organize, set up | Judge and change a picture |

The library grid is an app surface, but its thumbnails sit directly on the neutral surface with no tinted
container behind them.

## 3. Colour

### 3.1 Brand

The app icon already defines Latent's brand: an amber mark (`#E8A23A`) and cream (`#F2EDE4`) on near-black
(`#17171A`). The UI accent should come from that amber, so the app and its icon read as one product.

**Decided: Latent's own palette replaces wallpaper-derived dynamic colour** (the owner's decision, 2026-10-26).
Dynamic colour tints surfaces and accents from the wallpaper; next to photos that tint biases colour judgement and
changes the app's identity per phone. The palette below is the target; the values themselves remain proposals for
review, and the switch lands as its own UI change.

### 3.2 App surface tokens (proposed)

Neutral greys have no visible hue (a*, b* ≈ 0). Contrast ratios are against the listed surface.

| Role | Light | Dark | Use |
|---|---|---|---|
| `surface` | `#F7F7F7` | `#131314` | Screen background |
| `surfaceContainer` | `#EFEFEF` | `#1F1F21` | Cards, sections |
| `surfaceContainerHigh` | `#E6E6E7` | `#2A2A2D` | Sheets, selected rows, menus |
| `onSurface` | `#1B1B1C` (16.1:1) | `#E6E6E7` (14.9:1) | Primary text and icons |
| `onSurfaceVariant` | `#4A4A4D` (8.2:1; 7.1:1 on high) | `#B4B4B8` (9.0:1; 6.9:1 on high) | Secondary text, hints |
| `outline` | `#7A7A7E` (4.0:1) | `#8E8E93` (5.7:1) | Control boundaries, dividers that carry meaning |
| `outlineVariant` | `#D4D4D6` | `#3A3A3D` | Decorative dividers only |
| `primary` (accent) | `#8A5800` (5.6:1; 4.8:1 on high) | `#F2B95C` (10.5:1; 8.1:1 on high) | Active, selected, primary action |
| `onPrimary` | `#FFFFFF` (6.0:1) | `#432C00` (7.4:1) | Text/icons on the accent |
| `primaryContainer` | `#FFDDB0` | `#5E3F00` | Selected chips, active indicator |
| `onPrimaryContainer` | `#2B1700` (13.2:1) | `#FFDDB0` | Content on it |
| `error` | `#B3261E` (6.1:1) | `#F2B8B5` (10.9:1) | Errors and permanent deletion only |

Rules:

- Text **must** meet 4.5:1 and essential icons/control boundaries 3:1 against their actual background [A5]. The
  table meets both; any new pair is checked the same way, in light and dark.
- The light accent is a darker amber than the brand mark so it passes as text; the brand `#E8A23A` itself is for
  the icon, the store graphics and large decorative brand moments, not for text on light surfaces.
- No "success green" or "warning yellow" families. A finished import is stated in text with a neutral check icon;
  only errors and destructive actions get colour. This keeps the accent meaningful and avoids a traffic-light UI.

### 3.3 Photo workspace tokens (proposed)

| Role | Value | Use |
|---|---|---|
| `workspaceSurround` | `#0F0F10` | Behind the photo; truly neutral |
| `workspaceChrome` | `#1A1A1C` | Toolbars, panels, tool tabs |
| `onWorkspaceChrome` | `#EDEDED` (14.8:1) | Labels, icons |
| `onWorkspaceChromeVariant` | `#A6A6AA` (7.2:1) | Values, secondary labels |
| `workspaceAccent` | `#F2B95C` (9.8:1) | Active tool, slider fill, selected mask |
| `workspaceOutline` | `#6E6E73` (3.8:1 on surround) | Crop frame, panel edges where needed |
| `photoScrim` | black at **≥ 60 %** | Badges and controls laid over any photo |

- The surround **must** stay neutral and dark in every theme; theme colours **must not** tint the photo, its
  comparison or the histogram (as the UX guide already requires).
- Anything drawn over a photo (badges, the controls on Viewer, map photo pins) **must** sit on `photoScrim` or an
  equivalent backing. At 60 % black, white text keeps 5.7:1 even over a pure white sky; 50 % falls to 4.0:1 and is
  not enough.
- Accent area in the workspace stays small: an underline, a fill, a dot. A large amber panel next to a photo would
  shift colour perception, the same reason the surround is neutral.
- *Later, optional:* a mid-grey surround (`#777777`) for judging tones the way a print is judged. Not a default.

### 3.4 Data colours

- **Histogram:** luminance in `onWorkspaceChromeVariant` at 60 %; R, G, B channels as `#FF5A52`, `#4CD964`,
  `#4A90FF` at 50 % so overlaps read as their mixes; clipped ends marked by a small triangle, not by colour alone.
- **Mask overlay:** red `#FF3B30` at 45 % by default, with the colour choosable when the photo itself is red. The
  brush cursor is a 1 dp white ring inside a 1 dp black ring, visible on any photo.
- **Ratings, picks and rejects:** neutral (`onSurface`/`onWorkspaceChrome`), told apart by shape: filled star,
  flag, struck-through circle. The accent is not spent on ratings, so "selected" stays unambiguous.
- **Map:** photo pins are thumbnails with a 2 dp white border and a small shadow; clusters are neutral dark
  circles with a count. The map's own colours are left alone.

## 4. Typography

- **Typeface: the system font.** On Galaxy this is Samsung's system sans, elsewhere Roboto, with the platform's
  Korean and Japanese fonts. No bundled typeface: it would need three scripts, and system fonts track the person's
  font and size settings. Text **must** use `sp` and stay usable at 200 % [S5].
- **Scale (Material 3 roles, proposed mapping):**

| Role | Size / line | Where |
|---|---|---|
| `headlineMedium` | 28 / 36 | The large title of each of the three tabs (one style for all three) |
| `titleLarge` | 22 / 28 | Top app bar of task screens (Location, Settings, Editor) |
| `titleMedium` | 16 / 24, medium weight | Card and section titles |
| `bodyLarge` | 16 / 24 | Prose, settings labels, dialog text |
| `bodyMedium` | 14 / 20 | Dense metadata, secondary lines, slider labels |
| `labelLarge` | 14 / 20, medium weight | Buttons, tabs, chips |
| `labelSmall` | 11 / 16, medium weight | Badges and counters only; never sentences |

- **Numbers:** slider values, counts, file sizes and times use tabular figures (`tnum`) so they do not jitter
  while changing. Signed values show a real minus sign (−0.35), and zero has no sign.
- **Korean and Japanese:** keep the default line heights (do not tighten for CJK), no negative letter-spacing, and
  no all-caps styling in any language (it has no meaning in Korean or Japanese and hurts English legibility).
  Labels are designed to wrap to two lines, not to truncate; give every label 30 % more width than English needs.
- **Truncation:** file names truncate in the middle (`DSC0…0412.ARW`) so the number stays visible; sentences
  wrap instead of truncating.
- **Weight:** two weights only, regular and medium; no bold in the UI. Emphasis comes from size and colour role.

## 5. Spacing, layout and grid

- **Base unit 4 dp, rhythm 8 dp** (as proposed in the UX guide). Common values: 4, 8, 12, 16, 24, 32.
- **Screen margins:** 16 dp on compact windows, 24 dp from medium width [S3], [S4]; text columns stop at 720 dp.
- **Cards and sections:** 16 dp inner padding, 12 dp between items inside, 16 dp between cards.
- **List rows:** 56 dp for one line, 72 dp for two; the whole row is the target when it has one action.
- **Library grid:** square thumbnails with **2 dp gutters**, edge to edge, no rounding (§6). Columns follow width:
  aim for 96–128 dp cells, so 3–4 columns on a phone, more on a tablet, with the small/medium/large grid setting
  choosing within that range.
- **Editor, portrait:** photo on top, tool tabs and the active panel below in the reachable area [S2]; the panel is
  at most about 40 % of the height so the photo stays the larger part.
- **Editor, wide or landscape:** photo left, a 360 dp panel on the side of the person's chosen tab position.
- **Large screens and foldables:** two panes only where each keeps a useful width [S4]; nothing interactive or
  critical in a hinge area; respect insets of every kind [A4].

## 6. Shape and elevation

| Element | Corner radius (proposed) |
|---|---|
| Photos in Viewer, Editor, Compare | **0** — a picture's edges are part of the picture |
| Library thumbnails | **0**, separated by gutters; the selected state, not rounding, makes them distinct |
| Chips, text fields, small buttons in panels | 8 dp |
| Cards and sections | 16 dp |
| Bottom sheets (top corners), dialogs | 28 dp |
| Buttons, navigation active indicator | Full (pill) |

- **Elevation is tonal on app surfaces:** a raised element is a higher `surfaceContainer` tone, not a shadow.
  Shadows only where something floats over a photo or map (map pins, a floating button), and then small.
- **The workspace is flat:** no shadows, no blur; separation by tone and, where needed, a 1 dp outline.

## 7. Iconography

- **One family.** Latent's own icons are outline icons on a 24 dp grid, 1.7 dp stroke, round caps and joins (as
  `ic_import` and the editor tool icons). Every icon follows that style; system icons that do not match (filled
  Material icons mixed in today) are redrawn or replaced by the matching outlined symbol.
- **Outline at rest, filled when selected.** The three tab icons and on/off tools have an outlined and a filled
  form; the filled one is the selected state, together with the active indicator and label.
- **Sizes:** 24 dp standard, 20 dp in dense panels, 18 dp inline with text. The touch target stays 48 dp [A5].
- **Meaning:** an icon alone is allowed only for universally known actions (back, close, more, search, share,
  delete) and always has an accessible label; tool tabs and the navigation bar always show text labels.
- **Tab icons (proposed):** Library — stacked photos; Import — arrow into a tray; Map — a map pin. Location, now a
  screen, uses a location-dot icon on its entry card.

## 8. Components

### 8.1 Navigation

- **Bottom navigation bar** on compact windows, **rail** on wide ones, honouring the tab-position setting [A1].
  Three items, labels always visible, pill-shaped active indicator in `primaryContainer`.
- **Tab root screens** (Library, Import, Map) open with a large title (`headlineMedium`) in the viewing area that
  collapses into a small bar on scroll [S2]. All three behave the same; Map may overlay its title on the map with a
  scrim instead of pushing the map down.
- **Task screens** (Location, Settings, Editor, Compare) use a small top app bar with a back arrow and title.

### 8.2 Buttons

- **One filled button per decision area** (the primary action of a card or dialog: "Start sync", "Import from this
  folder"). Secondary: tonal; tertiary: text button. Never two filled buttons side by side.
- **Destructive:** text in `error` for the confirming button of a permanent action; a filled error button only in
  the final confirmation of permanent deletion.
- Height 40 dp visual inside a 48 dp target; labels are verbs ("Delete permanently", not "OK").

### 8.3 Cards, rows and settings

- Section cards: `surfaceContainer`, 16 dp radius, no outline, no shadow, `titleMedium` title.
- Settings: grouped rows with a short `labelLarge` group heading in `primary`; a row has its label left and its
  control right (switch, value, chevron); its hint below in `bodyMedium` `onSurfaceVariant`.
- **Choices:** a **segmented button** for 2–4 mutually exclusive options that fit (Theme: System/Light/Dark; tab
  position); **filter chips** for toggles that combine; a list or menu for more than four.

### 8.4 Sliders (editor)

- Full-width track below its label row: label left (`bodyMedium`), value right (tabular, `onWorkspaceChromeVariant`).
- Track 4 dp, thumb 20 dp visual / 48 dp target. **Centred sliders** (exposure, temperature, tint, the HSL
  adjustments) draw the fill from the centre tick, not from the left; unipolar sliders fill from the left.
- **Colour tracks** where the scale is a colour: temperature blue→amber, tint green→magenta, hue the hue band.
  These are the only gradients in the UI.
- A changed value shows its label in `onWorkspaceChrome` and a small accent dot; an untouched slider stays muted.
  This makes "what did I change" visible at a glance.
- During a drag, unrelated chrome dims to 40 % and the active row stays exactly in place (UX guide §4).

### 8.5 Sheets, dialogs, menus, snackbars

- **Bottom sheets** for contextual choices over a photo; 28 dp top corners, a drag handle, no more than one level.
- **Dialogs** for decisions only (UX guide §6): title states the question, body names the affected items,
  buttons are verbs, the confirming one on the right (or bottom when stacked). One UI's low placement is an option
  for later, not a restyle of every dialog [S8].
- **Menus** for secondary actions behind "more"; never hide the primary action there.
- **Snackbars** for brief confirmations and Undo; never for a failure the person must act on.

### 8.6 Progress and status

- **Linear determinate** bar with "12 of 48 · 1.2 GB" when the total is known; **indeterminate** with the phase
  name ("Scanning the camera…") when it is not. Circular spinners only inside a button or a thumbnail.
- **Status on a photo** (saving, failed): a small badge on the thumbnail and the same badge in the Viewer's top bar
  — a 16 dp circular progress for saving, an error icon on a scrim for failed. The badge looks identical wherever
  the photo appears.
- **Recording location:** a small filled dot with "Recording" text on the Import card; the system notification
  remains the persistent signal.

### 8.7 Thumbnails and overlays

- **Badge positions are fixed:** top-left the kind (RAW+JPEG, RAW, HDR), bottom-right video duration, bottom-left
  rating/pick/reject, top-right the selection check. Badges are `labelSmall` on `photoScrim` pills.
- **Selected:** the thumbnail insets by 8 dp, a 3 dp accent border appears, and a filled check circle in the
  corner. Shape, inset and icon together, so selection never relies on colour [A5].
- **Placeholders** are flat `surfaceContainer` squares, no shimmer animation; the image fades in over 150 ms.

### 8.8 Empty states

- One 48 dp outline icon, one sentence that says what is missing and why, one button for the next step. No
  illustrations or mascots: they would be the only decorative element in the app and age quickly.

### 8.9 Editor specifics

- **Tool tabs:** icon + label, the active one in `workspaceAccent` with a 2 dp underline.
- **Crop:** L-shaped corner handles 24 dp long, 3 dp thick, in white with a black edge; the thirds grid shows only
  while dragging; outside the crop is dimmed to 60 % black.
- **Before/after:** a labelled toggle in the toolbar, plus press-and-hold on the photo; while showing the original, a
  "Before" label sits top-left on a scrim so the state is never ambiguous.
- **Healing and brush cursors:** double-ring cursor (§3.4); an offset loupe for precise placement (UX guide §9).

## 9. Motion

- **Durations (proposed):** 100 ms for state changes (press, toggle), 200–250 ms for components (sheet, panel,
  chip), 300–400 ms for screen transitions. Standard easing for most, emphasized easing for screen changes [S9].
- **Continuity:** opening a photo expands the thumbnail into the Viewer and closing returns it to its cell; the
  editor's panel slides up from the tool tabs. Motion explains where things came from and went.
- **Never animate the photo's content:** no zoom-bounce, no fade on each slider step, no flash between images.
- **Reduced motion:** with the system's animation setting off or reduced, transitions become crossfades or cuts.
- **Haptics:** a light tick at a slider's centre detent and when a pick/reject is set; never the only feedback.

## 10. Dark and light

- App surfaces are designed in both themes at once; a screen is not done until both are checked.
- The workspace is the same in both themes; switching theme changes nothing around a photo.
- Avoid pure black (`#000000`) for large app surfaces and pure white text on it; the tokens above are slightly
  off for comfort, while staying neutral.

## 11. Adaptive layouts

| Window (width) | Navigation | Library | Editor | Settings / Location |
|---|---|---|---|---|
| Compact (< 600 dp) | Bottom bar | 3–4 columns | Panel below photo | Single column |
| Medium (600–839 dp) | Rail | 5–6 columns | Side panel if height allows | Single column, centred, ≤ 720 dp |
| Expanded (≥ 840 dp) | Rail | 6+ columns, optional detail pane | Side panel, wider histogram | Two columns for long settings |

Ranges follow Samsung's large-screen classes [S4] and are review cases, not guarantees. Check split screen,
DeX/desktop windows, fold/unfold and short landscape windows; nothing is stretched to fill a tablet.

## 12. Brand surfaces

- **App icon:** adaptive icon with the amber mark on `#17171A`, and the monochrome layer for themed icons; the
  notification icon is the single-colour mark.
- **Store graphics:** the same near-black, amber and cream, with real photos (the owner's own or licensed), never
  mock UI with fake content.
- **Splash:** the system splash with the icon on `#17171A` in dark and `#F7F7F7` in light; no custom splash screen.

## 13. Where today's app differs (candidates, not defects)

Observed on `main` at `6fe7593`, for a designer planning work. Each is a separate, reviewed UI change if pursued.

- Colours come from the wallpaper (dynamic colour), which the owner decided to replace; the workspace accent is
  blue (`#7FA8FF`) while the brand is amber. → §3.
- Tab titles use different styles (Library and Map a bold `headlineLarge`, Import `headlineMedium`). → §4, §8.1.
- Icons mix the custom outline set with filled Material icons (place, location, close, delete). → §7.
- Exclusive choices such as theme and tab position use filter chips. → §8.3.
- Thumbnail badges, selection and status styles have not been specified as one system. → §8.6, §8.7.

## 14. Design review checklist

For a UI change, the reviewer checks:

- [ ] The photo is the largest, most prominent element; nothing decorative competes with it.
- [ ] Workspace screens are neutral; accent appears only on the active/selected control.
- [ ] All colours are tokens from §3; new pairs meet 4.5:1 text / 3:1 essential icons, in light and dark.
- [ ] Type uses the §4 roles, system font, `sp`; it works at 200 % and in Korean and Japanese.
- [ ] Spacing is on the 4/8 dp grid with the §5 margins; targets are 48 dp.
- [ ] Icons are from the one outline family; selected forms are filled; labels accompany icons in navigation.
- [ ] Selection and status use shape or text as well as colour; overlays on photos sit on a ≥ 60 % scrim.
- [ ] Motion follows §9 and respects reduced motion.
- [ ] Compact, medium and expanded layouts, and both themes, were looked at — with screenshots of each changed
      screen in the PR (UX guide §12 covers the task checks).

## Sources

The same official publisher pages as the [UX guide](UX_GUIDE.md#sources), consulted for that guide on 2026-10-26.
They support the platform principles cited above; the concrete values (colours, sizes, radii, durations) are this
guide's proposals. No Samsung artwork, fonts or UI assets are copied into the repository.

- [A1 — Android: Layouts and navigation patterns][A1]
- [A3 — Android: Use window size classes][A3]
- [A4 — Android: System bars][A4]
- [A5 — Android: Accessibility][A5]
- [S2 — Samsung: Basic layout][S2]
- [S3 — Samsung: Grid system][S3]
- [S4 — Samsung: Layout design for large screens][S4]
- [S5 — Samsung: Layout and typography][S5]
- [S8 — Samsung: Dialog][S8]
- [S9 — Samsung: Motion basics and usage][S9]

[A1]: https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns
[A3]: https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
[A4]: https://developer.android.com/design/ui/mobile/guides/foundations/system-bars
[A5]: https://developer.android.com/design/ui/mobile/guides/foundations/accessibility
[S2]: https://developer.samsung.com/one-ui/layout/basic.html
[S3]: https://developer.samsung.com/one-ui/layout/grid.html
[S4]: https://developer.samsung.com/one-ui/largescreen-and-foldable/large_screen_layout.html
[S5]: https://developer.samsung.com/one-ui/accessibility/layout-and-typo.html
[S8]: https://developer.samsung.com/one-ui/comp/dialog.html
[S9]: https://developer.samsung.com/one-ui/motion/basic.html
