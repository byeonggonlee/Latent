# Latent UI guide

**Status:** proposal for review, written at the owner's request on 2026-10-26 and revised after the Codex and
Gemini 4 Argon design reviews of PR #42. This is the visual companion to [UX_GUIDE.md](UX_GUIDE.md): the UX guide
says what a screen must let a photographer do and know; this guide says how Latent should look and feel while doing
it. It is written from a designer's view of where Latent should go, not as a description of the current code. It
does not authorize a redesign by itself; each change still lands as its own reviewed UI change.

**Basis:** the same official Android and Samsung One UI sources as the UX guide ([Sources](#sources)), W3C contrast
and Korean/Japanese layout guidance, the Latent brand already in the app icon, and the app on `main` at `6fe7593` for
the few places where it is compared.

**Reading key:** **must** marks an accessibility, legibility or photo-fidelity requirement; **should** marks the
preferred design default; values marked *proposed* are Latent decisions for review, not platform rules. Sizes are
minimums at default text size unless stated otherwise: components grow with text, they do not clip it. Where this
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
   escalates only for errors and permanent loss. A failure is never hidden to make room for something else.
6. **Comfortable on a phone held in one hand.** Frequent controls sit in the lower part of the screen, targets are
   generous, and layouts adapt to the space actually available rather than to a device model [S2], [A3].

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
container behind them. Anything drawn **over a photo** (in either family) follows the over-photo rules in §3.3.

## 3. Colour

### 3.1 Brand

The app icon already defines Latent's brand: an amber mark (`#E8A23A`) and cream (`#F2EDE4`) on near-black
(`#17171A`). The UI accent comes from that amber, so the app and its icon read as one product.

**Decided: Latent's own palette replaces wallpaper-derived dynamic colour** (the owner's decision, 2026-10-26).
Dynamic colour tints surfaces and accents from the wallpaper; next to photos that tint biases colour judgement and
changes the app's identity per phone. Both reviews of this guide recommended the same; it is a product choice, not
an Android requirement. The palette below is the target; its values remain proposals for review, and the switch
lands as its own UI change. Light/Dark/System and the person's font and accessibility settings stay.

### 3.2 App surface tokens (proposed)

All greys are **equal-channel** (R = G = B), so they carry no hue (CIELAB a\* = b\* = 0 against the display's white).
A grey with even a slightly higher blue channel reads as cool slate next to a photo and makes the picture look warmer.
Ratios are against the listed background.

| Role | Light | Dark | Use |
|---|---|---|---|
| `surface` | `#F7F7F7` | `#131313` | Screen background |
| `surfaceContainer` | `#EFEFEF` | `#1F1F1F` | Cards, sections |
| `surfaceContainerHigh` | `#E6E6E6` | `#2A2A2A` | Sheets, selected rows, menus |
| `onSurface` | `#1B1B1B` (16.1:1) | `#E6E6E6` (14.9:1) | Primary text and icons |
| `onSurfaceVariant` | `#4A4A4A` (8.3:1; 7.1:1 on high) | `#B4B4B4` (9.0:1; 6.9:1 on high) | Secondary text, hints |
| `outline` | `#7A7A7A` (4.0:1; 3.4:1 on high) | `#8E8E8E` (5.7:1; 4.4:1 on high) | Control boundaries, meaningful dividers |
| `outlineVariant` | `#D4D4D4` | `#3A3A3A` | Decorative dividers only |
| `primary` (accent) | `#8A5800` (5.6:1; 4.8:1 on high) | `#F2B95C` (10.5:1; 8.1:1 on high) | Active, selected, primary action, focus ring |
| `onPrimary` | `#FFFFFF` (6.0:1) | `#432C00` (7.4:1) | Text/icons on the accent |
| `primaryContainer` | `#FFDDB0` | `#5E3F00` | Selected chips, active indicator |
| `onPrimaryContainer` | `#2B1700` (13.2:1) | `#FFDDB0` (7.4:1) | Content on it |
| `error` | `#B3261E` (6.1:1) | `#F2B8B5` (10.9:1) | Errors and permanent deletion only |
| `onError` | `#FFFFFF` (6.5:1) | `#601410` (7.7:1) | Text/icons on a filled error element |

Rules:

- Text **must** meet 4.5:1 and essential icons/control boundaries 3:1 against their **final rendered background**
  [A5], [W1], [W2]. The table covers opaque pairs only; translucent layers, scrims and dimmed states are checked as
  composites (§3.5).
- The light accent is a darker amber than the brand mark so it passes as text; the brand `#E8A23A` itself is for
  the icon, the store graphics and large decorative brand moments, not for text on light surfaces.
- No "success green" or "warning yellow" families. A finished import is stated in text with a neutral check icon;
  only errors and destructive actions get colour. This keeps the accent meaningful and avoids a traffic-light UI.

### 3.3 Photo workspace and over-photo tokens (proposed)

| Role | Value | Use |
|---|---|---|
| `workspaceSurround` | `#0F0F0F` | Behind the photo; truly neutral |
| `workspaceChrome` | `#1A1A1A` | Toolbars, panels, tool tabs |
| `onWorkspaceChrome` | `#EDEDED` (14.9:1) | Labels, icons |
| `onWorkspaceChromeVariant` | `#A6A6A6` (7.2:1) | Values, secondary labels |
| `workspaceAccent` | `#F2B95C` (9.8:1) | Active tool, slider fill, selected mask |
| `workspaceOutline` | `#6E6E6E` (3.8:1 on surround) | Panel edges where needed; not for lines drawn on the photo |
| `photoScrim` | black at **≥ 60 %** | Backing for badges and controls laid over a photo |
| `onPhotoScrim` | `#FFFFFF` (5.7:1 over the worst case) | The only text/icon colour on a scrim; `#EDEDED` (4.9:1) also passes |

- The surround **must** stay neutral and dark in every theme; theme colours **must not** tint the photo, its
  comparison or the histogram (as the UX guide already requires).
- **Over-photo controls and badges** (thumbnail badges, Viewer controls, video controls, map-pin counts) sit on
  `photoScrim` and use `onPhotoScrim`, **whatever the system theme**. Secondary greys and the accent are **not**
  used as text on a scrim: `#A6A6A6` falls to 2.4:1 and amber to 3.3:1 over a white sky. A scrim of 50 % is not
  enough even for white (4.0:1).
- **Status over a photo:** a failure is a white error icon in a filled `#B3261E` pill with a 1 dp white edge (6.5:1
  for the icon; the pill stands out from bright photos and its white edge from dark ones). Saving is a white
  progress ring on `photoScrim`. A selected check is a filled `primary` circle with a white check and a white edge.
- **Lines drawn on the photo itself** (crop frame, grid, brush and healing cursors, mask edges) do **not** get a
  scrim, which would hide the pixels being judged. They use a **double stroke**: 1 dp white inside 1 dp black, so
  one of the two always contrasts with the picture.
- Accent area in the workspace stays small: an underline, a fill, a dot. A large amber panel next to a photo would
  shift colour perception, the same reason the surround is neutral.
- *Later, optional:* a mid-grey surround (`#777777`) for judging tones the way a print is judged. Not a default.

### 3.4 Data colours

- **Histogram:** luminance as a `#A6A6A6` fill at 60 %; R, G, B channels as fills of `#FF5A52`, `#4CD964` and
  `#4A90FF` at 50 % so overlaps read as their mixes, **each with a 1 dp full-opacity outline** on top (5.7:1,
  9.5:1 and 5.6:1 on `workspaceChrome`); the fills alone fall to about 2.3:1 and are not relied on. Channels can be
  toggled and are labelled R, G, B in a legend, so they are not told apart by hue alone. Clipping is shown by a
  small triangle at each end that fills when clipped, plus the optional on-photo clipping overlay (§8.10).
- **Mask overlay:** red `#FF3B30` at 45 % by default, with the colour choosable when the photo itself is red. The
  overlay is a visualization of the mask, not text, and is exempt from the scrim rule.
- **Ratings, picks and rejects:** neutral (`onSurface`, `onWorkspaceChrome` or `onPhotoScrim`), told apart by
  shape: filled star, flag, struck-through circle. The accent is not spent on ratings, so "selected" stays
  unambiguous.
- **Map:** see §8.11.

### 3.5 Checking composites

- Compute contrast with the WCAG relative-luminance formula on the **final composite** in sRGB, assuming ordinary
  alpha compositing of the encoded values. For a scrim or a translucent layer over a photo, check the worst case:
  over pure white and over pure black.
- Check every state that changes colour or opacity: pressed, focused, selected, disabled, dragging, dimmed,
  loading, error.
- These are sRGB calculations. They are not measurements on an HDR or wide-gamut display, which are checked on a
  device.

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

- **Numbers:** slider values, counts, file sizes and times use tabular figures (`tnum`). The value sits in a
  right-aligned field wide enough for its sign, longest value and unit (`−4.00 EV`), so neither a sign change nor
  a new digit moves the label or the slider. Signed values show a real minus sign (−0.35); zero has no sign.
- **Weight:** two weights only, regular and medium; no bold in the UI. Emphasis comes from size and colour role.
- **Case:** no all-caps styling in any language (it means nothing in Korean or Japanese and hurts English
  legibility). Abbreviations and file names keep their own case (`RAW`, `DSC00412.ARW`).

### 4.1 Korean, Japanese and large text

- Keep the platform's default line heights for Korean and Japanese; do not tighten them, and use no negative
  letter-spacing. These are starting points: the real fonts are checked at 100 % and 200 % on a phone.
- **Line breaking:** Korean titles, buttons and labels break between words (word-phrase / `keep-all` style),
  not inside a word; long Korean prose may break by character where needed. Japanese follows its line-breaking
  rules for punctuation (no line starting with closing brackets or 。、, none ending with opening brackets) [W3],
  [W4]. Do not force Latin-style word wrapping on every script.
- **Space:** fit the actual English, Korean and Japanese strings at 100 % and 200 %, not an estimate. Korean and
  Japanese labels are often shorter than English, but scaled text grows in both directions. Labels wrap to two
  lines before anything is truncated; if two lines are not enough, the component grows (§5) or the content scrolls.
  An exclusive choice that no longer fits as a segmented button becomes a vertical list of radio rows.
- **File names:** truncate in the middle, keeping the end that distinguishes files (the last digits and the
  extension): `DSC0…0412.ARW`, `IMG_2026…_0412.HEIC`. The full name is always available on demand (details,
  long-press, accessibility label).

## 5. Spacing, layout and grid

- **Base unit 4 dp, rhythm 8 dp** (as proposed in the UX guide). Common values: 4, 8, 12, 16, 24, 32.
- **Screen margins:** 16 dp on compact windows, 24 dp from medium width [S3], [S4]; text columns stop at 720 dp.
- **Cards and sections:** 16 dp inner padding, 12 dp between items inside, 16 dp between cards.
- **List rows:** at least 56 dp for one line and 72 dp for two, growing with wrapped or scaled text; the whole row
  is the target when it has one action.
- **Measure the space you actually have:** layout decisions use the width and height left after the navigation rail
  or bar, system insets, the hinge and the keyboard, not the window class alone.
- **Library grid:** square **1:1 cells, centre-cropped**, with **2 dp gutters**, edge to edge and unrounded (§6).
  Square cells give an even scan for culling; the uncropped picture is in the Viewer and Compare, and an optional
  "show whole photos" grid mode may come later. Column count comes from the usable width:
  `columns = floor((width + 2) / (target + 2))`, where the grid-size setting chooses the target cell (small 96 dp,
  medium 112 dp, large 128 dp).
- **Editor, portrait:** photo on top; the action strip (§8.9), tool tabs and the active panel below in the
  reachable area [S2]. The panel defaults to about 40 % of the height; with large text or the keyboard open it
  scrolls inside itself, and can be expanded on purpose, rather than hiding tools.
- **Editor, wide:** a side panel 280–360 dp wide, at most 40 % of the usable width, on the side of the person's
  chosen tab position (the right when the tabs are at the bottom). If the photo would be left narrower than 320 dp,
  the editor uses the portrait arrangement instead.
- **Large screens and foldables:** two panes only where each keeps a useful width [S4]; nothing interactive or
  critical in a hinge area; respect insets of every kind [A4].

## 6. Shape and elevation

| Element | Corner radius (proposed) |
|---|---|
| Photos in Viewer, Editor, Compare | **0**: a picture's edges are part of the picture |
| Library thumbnails | **0**, separated by gutters; the selected state, not rounding, makes them distinct |
| Chips, segmented buttons, text fields | 8 dp |
| Cards and sections | 16 dp |
| Bottom sheets (top corners), dialogs | 28 dp |
| All buttons, the navigation active indicator, badges | Full (pill) |

- **Elevation is tonal on app surfaces:** a raised element is a higher `surfaceContainer` tone, not a shadow.
  Shadows only where something floats over a photo or map (map pins, a floating button), and then small.
- **The workspace is flat:** no shadows, no blur; separation by tone and, where needed, a 1 dp outline.

## 7. Iconography

- **One family.** Latent's own icons are outline icons on a 24 dp grid, 1.7 dp stroke, round caps and joins (as
  `ic_import` and the editor tool icons). Every icon follows that style; system icons that do not match (filled
  Material icons mixed in today) are redrawn or replaced by the matching outlined symbol.
- **Selected states:**
  - Icons with a **closed silhouette** (the three tab icons, star, flag, check) switch to a **filled** form when
    selected, together with the active indicator or label.
  - **Open-stroke** icons (crop, healing, sliders, detail, before/after) are not filled, which would lose their
    shape; they show selection with `primary`/`workspaceAccent` colour plus a 2 dp underline, or a tonal pill
    behind a standalone toggle.
- **Sizes and optical weight:** 24 dp standard, 20 dp in dense panels, 18 dp inline with text. Smaller icons keep
  a visible stroke of about 1.5–1.7 dp rather than a stroke scaled down with the icon; draw size-specific versions
  where scaling makes them thin. The touch target stays 48 dp [A5].
- **Meaning:** an icon alone is allowed only for universally known actions (back, close, more, search, share,
  delete) and always has an accessible label; tool tabs and the navigation bar always show text labels.
- **Tab icons (proposed):** Library, stacked photos; Import, an arrow into a tray; Map, a map pin. Location, now a
  screen, uses a location-dot icon on its entry card.

## 8. Components

### 8.1 Navigation

- **Bottom navigation bar** on compact windows, **rail** on wide ones, honouring the tab-position setting [A1].
  Three items, labels always visible, pill-shaped active indicator in `primaryContainer`.
- **Tab root screens** (Library, Import, Map) open with a large title (`headlineMedium`) in the viewing area that
  collapses into a small bar on scroll [S2]. All three behave the same; Map may overlay its title on the map with a
  scrim instead of pushing the map down.
- **Task screens** (Location, Settings, Compare) use a small top app bar with a back arrow and title. The Editor's
  top bar is defined in §8.9: its frequent actions are not in the top bar.

### 8.2 Buttons

- **One filled button per decision area** (the primary action of a card or dialog: "Start sync", "Import from this
  folder"). Secondary: tonal; tertiary: text button. Never two filled buttons side by side.
- **Destructive:** text in `error` for the confirming button of a permanent action; a filled `error` button with
  `onError` text only in the final confirmation of permanent deletion.
- At least 40 dp visual height inside a 48 dp target; a label that wraps makes the button taller, never clipped.
  Labels are verbs ("Delete permanently", not "OK"). In a narrow row, buttons stack vertically, primary first in
  reading order.

### 8.3 Cards, rows and settings

- Section cards: `surfaceContainer`, 16 dp radius, no outline, no shadow, `titleMedium` title.
- Settings: grouped rows with a short `labelLarge` group heading in `primary`; a row has its label left and its
  control right (switch, value, chevron); its hint below in `bodyMedium` `onSurfaceVariant`. When the label wraps,
  the control stays vertically centred on the label, not on the hint.
- **Choices:** a **segmented button** for 2–4 mutually exclusive options that fit in one line (Theme:
  System/Light/Dark; tab position); otherwise a vertical list of radio rows. **Filter chips** for toggles that
  combine; a list or menu for more than four.

### 8.4 Interaction states

One state model for every interactive component, using Material's state layers so platform behaviour is kept:

| State | App surfaces | Workspace |
|---|---|---|
| Hover (mouse, stylus) | `onSurface` layer at 8 % | `onWorkspaceChrome` layer at 8 % |
| Pressed | layer at 12 % plus the ripple | layer at 12 % |
| Keyboard focus | 2 dp `primary` ring, 2 dp outside the component | 2 dp `workspaceAccent` ring, same offset |
| Selected | `primaryContainer` fill, or the filled icon (§7) | `workspaceAccent` mark |
| Disabled | content at 38 %, container at 12 %; not focusable unless it explains itself | same |
| Busy | the control keeps its size; a small progress ring replaces its icon or sits before its label | same |
| Error | `error` outline or text plus an error icon and a message (§8.5) | same |

- A disabled control must look different from a quiet enabled one: an untouched slider (§8.6) is enabled and stays
  at full contrast; only a slider that cannot be used is at 38 %.
- Focus is never shown by colour change alone; the ring is always drawn.

### 8.5 Text fields and forms

Latent has a few inputs: album names, search, the camera clock offset, numeric slider entry.

- **Outlined field**, 8 dp radius: 1 dp `outline` at rest, 2 dp `primary` when focused. The label stays visible
  above the value (it floats up; it is never only a placeholder).
- **Helper and error text** sit below in `bodyMedium`: helper in `onSurfaceVariant`; an error in `error` with an
  error icon in the field and a 2 dp `error` outline, so it is not shown by colour alone. The message says how to
  fix it ("Use 1–60 characters").
- **Numbers:** a numeric keyboard, the unit shown as a suffix inside the field (`min`, `EV`), range in the helper.
- **Long text** scrolls inside a single-line field and wraps in a multi-line one; nothing is cut silently.
- In the workspace (numeric slider entry) the same field uses workspace tokens.

### 8.6 Sliders (editor)

- **Layout:** full-width track below its label row: label left (`bodyMedium`), value right in its reserved field
  (§4), the unit after the value.
- **Size:** track 4 dp; thumb 20 dp visual, 48 dp target. The thumb is a white disc with a 1 dp dark edge, so it
  stays visible on any track colour, including the colour tracks.
- **Reference and fill:** every slider has a **reference value**, and the fill runs from the reference to the thumb:
  - Bipolar adjustments (exposure in EV, contrast, highlights, shadows, clarity, the HSL shifts) have their
    reference at 0, in the centre, marked by a tick.
  - **Temperature and tint are relative to As Shot:** the centre tick is labelled "As Shot", the value is the
    offset, and for a raw file the resulting Kelvin is shown beside it as information. The midpoint therefore
    never means "neutral white".
  - Unipolar adjustments (vignette amount, sharpening, noise reduction) have their reference at the left.
- **Colour tracks** where the scale is a colour: temperature blue→amber, tint green→magenta, hue the hue band.
  These are the only gradients in the UI.
- **Changed values:** a slider whose value differs from its reference shows its label in `onWorkspaceChrome` and a
  small accent dot; one at its reference stays in `onWorkspaceChromeVariant`. The dot reflects the photo's current
  edit, so it is still there after saving and reopening. Double-tapping the label or value resets that one slider
  to its reference (an undoable step); the same reset is in the slider's accessibility actions, next to a value
  entry field (§8.5).
- **While dragging:** panels and controls unrelated to the active slider are hidden or collapsed, not dimmed while
  still usable; the active row stays exactly in place with its value at full contrast (UX guide §4).
- **States:** focus ring, and arrow keys move by a step (Shift for a larger step), as in §8.4.

### 8.7 Sheets, dialogs, menus, snackbars

- **Bottom sheets** for contextual choices over a photo; 28 dp top corners, a drag handle, no more than one level.
- **Dialogs** for decisions only (UX guide §6): the title states the question, the body names the affected items,
  buttons are verbs, the confirming one on the right (or last when stacked). One UI's low placement is an option for
  later, not a restyle of every dialog [S8].
- **Menus** for secondary actions behind "more"; never hide the primary action there.
- **Snackbars** for brief confirmations and Undo; never for a failure the person must act on.

### 8.8 Progress, status and thumbnails

- **Progress:** a linear determinate bar with "12 of 48 · 1.2 GB" when the total is known; indeterminate with the
  phase name ("Scanning the camera…") when it is not. A circular progress ring appears only as a compact status
  (inside a button, a thumbnail status badge or the Viewer's status slot), never as a page-blocking spinner.
- **Thumbnail slots.** A cell has four fixed slots; each holds one badge at a time:

| Slot | Holds, in order of precedence | Never hidden |
|---|---|---|
| Top-left: **status / kind** | failed save → saving → kind (RAW+JPEG, RAW, HDR) | failed, saving |
| Top-right: **selection** | selection check (in selection mode) | yes |
| Bottom-left: **decision** | reject → pick → rating (stars as a count, "★3") | yes |
| Bottom-right: **media** | video duration | no |

  - While a status is shown, the kind badge waits; it returns when the status clears.
  - On cells under 110 dp, or with text at 150 % or more, the kind and duration badges are dropped from the cell;
    the details and the accessibility label still give them. Status, selection and the decision always stay.
  - Badges are `labelSmall` (or a 16 dp icon) on `photoScrim` pills with `onPhotoScrim` content, at most 40 % of
    the cell's width each, with 4 dp from the cell's edge, so at least the centre half of the photo stays visible.
- **Selected:** the image insets by 8 dp inside its unchanged cell, a 3 dp `primary` border hugs the inset image,
  and the selection check fills (§3.3). Shape, inset and icon together, so selection never relies on colour [A5].
- **Status in the Viewer:** the same icons sit in a status slot at the right of the Viewer's top bar. A failed save
  also opens a persistent banner under the top bar with the reason and Retry (UX guide §9); it stays until the
  person acts or the save succeeds.
- **Placeholders** are flat `surfaceContainer` squares, no shimmer animation; the image fades in over 150 ms.
- **Recording location:** a small filled dot with "Recording" text on the Import card; the system notification
  remains the persistent signal (§8.13).

### 8.9 Editor layout

- **Top bar:** back/exit on the left; the photo's name and the versions menu in the middle; **Done** on the right.
  Nothing used repeatedly while adjusting lives up here.
- **Action strip:** directly above the active panel in portrait, or at the top of the side panel in a wide window:
  **Undo**, **Redo** and a labelled **Before/After** toggle, each 48 dp, with text labels where width allows. It
  sits on the side of the panel, so it follows the person's chosen tab side for left- or right-handed use.
  Unavailable Undo/Redo use the disabled state (§8.4); Before/After shows its active state.
- **Before:** while the original is shown, a "Before" label sits top-left over the photo on `photoScrim`, so the
  state is never ambiguous. Press-and-hold on the photo is a supplement to the toggle, never the only way.
- **Tool tabs:** icon + label below the strip; the active tab in `workspaceAccent` with a 2 dp underline.
- **Reset:** per slider (§8.6), per group at the top of each panel ("Reset light"), and for the whole edit in the
  versions menu: three scopes, three places, each undoable.
- **Crop:** L-shaped corner handles 24 dp long and 3 dp thick; the frame, handles and the thirds grid (shown only
  while dragging) use the double stroke (§3.3); outside the crop is dimmed with 60 % black.
- **Healing and brush cursors:** a double-ring cursor (§3.3); an offset loupe for precise placement (UX guide §9).

### 8.10 Photo information and clipping

- **Shooting information** (shutter, aperture, ISO, focal length) reads as one `bodyMedium` line with tabular
  figures: `1/250 s · f/2.8 · ISO 400 · 35 mm`. Over a photo it is on `photoScrim`.
- **Clipping overlay:** optional, toggled from the histogram's triangles; clipped highlights show as `#FF3B30` and
  clipped shadows as `#4A90FF`, at full opacity, only while the toggle is on.
- **Missing data** is said in words ("No shooting information"), never shown as an empty row or a zero.

### 8.11 Map

- Third-party map tiles are not restyled for brand consistency.
- **Photo pins:** a thumbnail with a 2 dp white border and a small shadow. **Clusters:** a neutral dark circle
  (`#1A1A1A` with a white edge) with a white count. **Selected** pin or cluster: a 3 dp `primary` border, scaled
  up by 1.15, plus a filled check, so selection is not colour alone.
- **Recorded track** (when shown): a 3 dp line in `primary` with a 1 dp white casing; the **accuracy circle** of a
  location is a 20 % `primary` fill with a 1 dp `primary` edge.
- **Off, offline or not yet allowed:** a quiet card in place of the map saying which ("Online maps are off") and
  how to turn them on; never an empty grey area that looks like "no photos".
- **Attribution:** always visible, on a small `photoScrim` pill with `onPhotoScrim` text, kept clear of controls.

### 8.12 Video playback

- Controls over the video use `photoScrim` and `onPhotoScrim`: a 56 dp play/pause button in the centre when
  paused; a bottom bar with the scrubber, time as `01:24 / 03:10` in tabular figures, and mute.
- The scrubber is visibly different from an edit slider: a full-width 4 dp track with buffered and played
  segments and no reference tick; its thumb appears on touch. Targets stay 48 dp.
- Controls hide after 3 seconds of playback and return on a tap; they never hide while paused or while a
  screen reader is on.

### 8.13 Notifications and widgets

- Notifications use the standard Android templates; Latent decides only the content and its order. The
  small icon is the single-colour Latent mark, legible on light and dark shades.
- **Location recording** (ongoing): "Recording location" as the title, the last fix time as the text, a **Stop**
  action.
- **Import** (ongoing): the camera or card name as the title; "12 of 48 · 1.2 GB" with a determinate bar; a
  **Cancel** action. The result replaces it: imported, failed and still-to-compare counts, opening the batch.
- **Failure:** a separate notification naming what failed and the next step, opening the screen that recovers it.
- **Widgets** are out of scope until a widget is planned.

### 8.14 Empty states

- One 48 dp outline icon, one sentence that says what is missing and why, one button for the next step. No
  illustrations or mascots: they would be the only decorative element in the app and age quickly. Use them only for
  the UX guide's actual conditions (empty, denied, filtered to nothing, empty trash).

## 9. Motion

- **Durations (proposed):** 100 ms for state changes (press, toggle), 200–250 ms for components (sheet, panel,
  chip), 300–400 ms for opening and closing screens. Standard easing for most, emphasized easing for screen
  changes [S9].
- **Culling speed:** moving from one photo to the next in the Viewer follows the finger directly; a keyboard key or
  auto-advance after a pick or reject switches in 150 ms or less, or cuts. A person rating fast never waits for a
  screen transition.
- **Continuity:** opening a photo expands the thumbnail into the Viewer and closing returns it to its cell; the
  editor's panel slides up from the tool tabs. Motion explains where things came from and went.
- **Never animate the photo's content:** no zoom-bounce, no fade on each slider step, no flash between images.
- **Reduced and disabled motion:** with animations turned off, transitions are cuts; with reduced motion, a short
  crossfade may remain in place of movement.
- **Haptics:** a light tick at a slider's reference detent and when a pick/reject is set; never the only feedback.

## 10. Dark and light

- App surfaces are designed in both themes at once; a screen is not done until both are checked.
- The workspace is the same in both themes; switching theme changes nothing around a photo. A change that only
  touches the workspace therefore needs only the dark check.
- Avoid pure black (`#000000`) for large app surfaces and pure white text on it; the tokens above are slightly
  off for comfort, while staying neutral.

## 11. Adaptive layouts

Window classes give the first guess; the usable size after rail, insets, hinge and keyboard decides (§5).

| Usable width | Navigation | Library | Editor | Settings / Location |
|---|---|---|---|---|
| Compact (< 600 dp) | Bottom bar | Columns from §5 (3–4) | Panel below photo | Single column |
| Medium (600–839 dp) | Rail | Columns from §5 (5–7) | Side panel if the photo keeps ≥ 320 dp, else below | Single column, ≤ 720 dp |
| Expanded (≥ 840 dp) | Rail | Columns from §5, optional detail pane | Side panel, wider histogram | Two columns for long settings |

Ranges follow Samsung's large-screen classes [S4] and are review cases, not guarantees. Check split screen,
DeX/desktop windows, fold/unfold and short landscape windows; nothing is stretched to fill a tablet.

## 12. Brand surfaces

- **App icon:** adaptive icon with the amber mark on `#17171A`, and the monochrome layer for themed icons; the
  notification icon is the single-colour mark.
- **Store graphics:** the same near-black, amber and cream, with real photos (the owner's own or licensed), never
  mock UI with fake content.
- **Splash:** the system splash with the icon on `#17171A` in dark and `#F7F7F7` in light; no custom splash screen.
- **Out of scope for now:** home-screen widgets, a Wear OS or TV interface, and custom map styles.

## 13. Where today's app differs (candidates, not defects)

Observed on `main` at `6fe7593`, for a designer planning work. Each is a separate, reviewed UI change if pursued.

- Colours come from the wallpaper (dynamic colour), which the owner decided to replace; the workspace accent is
  blue (`#7FA8FF`) while the brand is amber, and the workspace greys have a slight blue cast. → §3.
- Tab titles use different styles (Library and Map a bold `headlineLarge`, Import `headlineMedium`). → §4, §8.1.
- Icons mix the custom outline set with filled Material icons (place, location, close, delete). → §7.
- Exclusive choices such as theme and tab position use filter chips. → §8.3.
- Thumbnail badges, selection and status, interaction states and the editor's action strip have not been
  specified as one system. → §8.4, §8.8, §8.9.

## 14. Design review checklist

For a UI change, the reviewer ticks each item or marks it **N/A with a reason** (for example, "the photo is the
largest element" is N/A on Settings).

**Every UI change**

- [ ] Colours are §3 tokens; text 4.5:1 and essential graphics 3:1 hold on the final composite, in each state
      that changes colour or opacity (§3.5, §8.4).
- [ ] Type uses the §4 roles, the system font and `sp`; the real English, Korean and Japanese strings fit at 100 %
      and 200 %, wrapping or growing rather than clipping.
- [ ] Spacing is on the 4/8 dp grid with the §5 margins; targets are 48 dp; sizes are minimums that grow.
- [ ] Icons are from the one outline family, with the §7 selected treatment; navigation shows labels.
- [ ] Focus, pressed, disabled, busy and error states are designed (§8.4), and focus is visible.
- [ ] Motion follows §9, including culling speed and turned-off or reduced animation.

**App surfaces**

- [ ] Light and dark were both looked at.

**Photo workspace and anything over a photo**

- [ ] The photo is the largest, most prominent element; the accent stays small.
- [ ] Controls and badges over a photo use `photoScrim` and `onPhotoScrim`; lines on the photo use the double
      stroke; nothing is a scrim that hides pixels being judged.
- [ ] Badge precedence holds in a combined case (selected + failed; RAW+JPEG + pick + video) and at 150 %+ text.

**Evidence in the PR**

- [ ] Screenshots of each changed screen in the relevant theme(s) and window sizes, plus one combined-state
      example; the UX guide's §12 task checks still apply.

## Sources

The Android and Samsung pages are the same official publisher pages as the [UX guide](UX_GUIDE.md#sources),
consulted for that guide on 2026-10-26. They and the W3C pages support the platform and accessibility principles
cited above; the concrete values (colours, sizes, radii, durations, slots) are this guide's proposals. No Samsung
artwork, fonts or UI assets are copied into the repository.

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
- [W1 — W3C: Understanding Contrast (Minimum)][W1]
- [W2 — W3C: Understanding Non-text Contrast][W2]
- [W3 — W3C: Requirements for Hangul Text Layout and Typography][W3]
- [W4 — W3C: Requirements for Japanese Text Layout][W4]

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
[W1]: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
[W2]: https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html
[W3]: https://www.w3.org/International/klreq/
[W4]: https://www.w3.org/TR/jlreq/
