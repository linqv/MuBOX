# MuBOX Libadwaita-Style UI Refresh Design

## Goal

Refresh MuBOX's app UI and video player UI into a libadwaita-inspired visual language while preserving the existing Android app structure, media workflows, playback behavior, WebDAV behavior, reader behavior, and settings/data models.

The approved direction is **design-system-first Adwaita styling**:

- Make the default theme Adwaita-style.
- Keep colors selectable instead of forcing a single palette.
- Preserve the current cinematic dark theme as a legacy selectable palette.
- Apply the Adwaita-style language to both the main app UI and the full-screen video player.
- Keep the implementation in Jetpack Compose and Material 3 primitives; do not add GTK/libadwaita dependencies.

## Approved Decisions

- Use approach 2: design system first.
- Default palette becomes Adwaita-style.
- The current "影院深色" look remains available as a legacy option.
- The video player is included in scope, not just the main app screens.
- Color is not fixed. Users can choose between Adwaita-style palettes such as the default dark-neutral palette, light, blue-gray, purple, and the legacy cinema palette.
- Stop after the written spec and self-review; do not transition into implementation planning until the user reviews the spec.

## Reference Direction

This is not a GTK port. The app remains Android/Compose, but the design should follow the relevant GNOME/libadwaita patterns where they translate well:

- GNOME HIG Header Bars: compact top bars with a small number of relevant controls, arranged start/center/end.
  <https://developer.gnome.org/hig/patterns/containers/header-bars.html>
- GNOME HIG Boxed Lists: grouped rows for preferences, short static lists, action rows, and property rows.
  <https://developer.gnome.org/hig/patterns/containers/boxed-lists.html>
- libadwaita style classes, especially `.osd`: dark, partially transparent overlay controls for media/UI overlays.
  <https://gnome.pages.gitlab.gnome.org/libadwaita/doc/1.9/style-classes.html>
- libadwaita styles and appearance: use accent variables and semantic styling rather than raw per-widget color overrides.
  <https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/styles-and-appearance.html>

## Current Code Reality

MuBOX already has the right architectural seams for this work:

- `AppColorPalette` in `AppSettingsStore.kt` stores the selected palette.
- `ComicDavTheme.kt` maps palettes to Material 3 `ColorScheme`, `Typography`, and `Shapes`.
- `MuBoxDesignSystem.kt` and `MuBoxComponents.kt` provide shared app tokens and components.
- Feature screens already derive many local color objects from `muBoxColorsFor(colorScheme)`.
- `VideoPlayerControls.kt` still contains hard-coded player colors and shape constants that need to move toward shared player tokens.
- The current app is the result of an earlier "cinematic media console" refresh, so this is a direction change, not a small color swap.

## Non-Goals

- Do not redesign the information architecture.
- Do not change tab order, navigation destinations, or screen ownership.
- Do not change ViewModels, Room schemas, WebDAV networking/proxy logic, mpv playback logic, reader logic, media detection, or download behavior.
- Do not add GTK, libadwaita, or other UI framework dependencies.
- Do not remove Material 3 as the Compose primitive layer.
- Do not replace Android touch expectations with desktop-only behavior.
- Do not remove high contrast or accessibility-oriented palettes.
- Do not weaken existing player gesture behavior or auto-hide behavior.

## Theme And Palette Model

Extend the existing `AppColorPalette` model rather than adding a separate setting.

Proposed palette set and settings labels:

- `DEFAULT`: label "Adwaita 深色（默认）". This becomes the default visual identity and uses a neutral dark Adwaita-style base suitable for media playback.
- `ADWAITA_LIGHT`: label "Adwaita 浅色". This provides the light Adwaita-style palette.
- `ADWAITA_BLUE_GRAY`: label "Adwaita 蓝灰". This is a MuBOX Adwaita variant with a restrained cool blue-gray accent.
- `ADWAITA_PURPLE`: label "Adwaita 紫色". This is a MuBOX Adwaita variant with a restrained purple accent.
- `CINEMA_DARK`: label "影院深色（旧）". This preserves the current cinematic dark palette.
- `SEPIA`: label "纸张护眼". Keep as a reading-friendly palette, adapted to the new component structure.
- `NIGHT`: label "夜间深色". Keep as an alternate dark palette, adapted to the new component structure.
- `HIGH_CONTRAST`: label "高对比". Keep as the accessibility/high-contrast palette.

Migration behavior:

- Existing stored `DEFAULT` values automatically render as the new Adwaita default after the update. This is intentional because the user approved changing the default theme.
- Users who prefer the current look can manually select `CINEMA_DARK`.
- Unknown stored palette names should continue to fall back to `DEFAULT`.

Color token requirements:

- Background, view background, headerbar, boxed list, raised surface, popover/sheet, row selected, border, separator, accent, accent soft, text, muted text, error container, and error text.
- Player-specific OSD tokens: OSD surface, OSD border, OSD pressed surface, OSD selected surface, OSD text, progress track, progress fill, HUD surface, and overlay scrim.
- Avoid per-screen raw accent colors where a semantic token exists.

Shape and elevation requirements:

- Use restrained radii closer to Adwaita: headerbars and lists should feel calm, not bubbly.
- Boxed list containers can use medium rounded corners.
- Rows should have no nested card chrome.
- Shadows should be minimal. Prefer separators, borders, and surface layering over glowing or deep shadows.
- Player OSD controls can retain round icon buttons where touch ergonomics require it.

Typography requirements:

- Keep Material typography roles but reduce decorative tracking and oversized titles in compact screens.
- Header titles use compact title roles.
- Row title/subtitle hierarchy should come from weight, size, and muted text, not color alone.
- Player time labels should use stable-width or compact label treatment to avoid layout shifts.

## Shared Component Direction

Evolve `MuBoxDesignSystem.kt` and `MuBoxComponents.kt` into the primary adapter between Material Compose and the Adwaita-style app.

Required component families:

- App shell:
  - `MuBoxHeaderBar`
  - `MuBoxBottomNavigation`
  - `MuBoxSelectionBar`
  - `MuBoxPage`
  - `MuBoxMessageBanner`
- Boxed lists:
  - `MuBoxBoxedList`
  - `MuBoxActionRow`
  - `MuBoxSwitchRow`
  - `MuBoxDropdownRow`
  - `MuBoxSliderRow`
  - `MuBoxPropertyRow`
- Media browsing:
  - `MuBoxMediaRow`
  - `MuBoxMediaTypeIcon`
  - `MuBoxPathRow`
  - `MuBoxSourceBadge`
- Media grids:
  - `MuBoxMediaTile`
  - `MuBoxPosterFallback`
  - `MuBoxThumbnailOverlay`
- Player:
  - `MuBoxPlayerOsdButton`
  - `MuBoxPlayerOsdPanel`
  - `MuBoxPlayerOsdChip`
  - `MuBoxPlayerProgress`
  - `MuBoxPlayerHud`
  - `MuBoxPlayerPopover`

Component APIs should stay presentation-focused. They should receive labels, subtitles, icons, selected/enabled state, supporting content, and callbacks. They should not know about repositories, ViewModels, WebDAV clients, mpv internals, or Room entities.

## Main App Shell

Keep the existing bottom-tab app structure:

- Sources
- Library
- Video Library
- Settings

Design changes:

- Each top-level page gets an Adwaita-style headerbar area rather than a large cinematic title block.
- Headerbar controls should be few and contextual.
- Bottom navigation remains because this is an Android app. It should be restyled with Adwaita tokens: flat surface, subtle top separator, clear selected pill, muted unselected labels.
- Selection mode bottom bar remains. Its visual treatment should match the normal navigation surface and use selected/accent tokens.
- Data-folder gate becomes an Adwaita-style onboarding panel: simple icon, clear title, concise text, one primary action.

Behavior preserved:

- Tab order.
- Tab labels and icon content descriptions.
- Selection action labels/callbacks.
- Data-folder selection behavior.
- Screen rotation lock behavior.

## Sources And WebDAV Browsing

Sources, local directories, and WebDAV browsing remain file-manager-first.

Design changes:

- Replace card-like panels and strong gradients with headerbar plus boxed-list rows.
- Rows should be dense, scannable, and maintain 44dp+ touch targets.
- Use symbolic-style icons in small colored containers for folders, comics, videos, audio, subtitles, and unknown files.
- Path display becomes a compact boxed row or headerbar subtitle with ellipsis.
- Loading/progress uses Adwaita-style linear progress and small status banners.
- Transfer panels become boxed status rows or compact banners, not large floating cards.
- Selected rows use accent border/background plus selected semantics.

Behavior preserved:

- Directory opens directory.
- Comic opens reader.
- Video opens player.
- Subtitle/audio/unknown handling stays as currently defined.
- Long-press add/download/select actions stay unchanged.
- WebDAV save-directory, back-to-sources, download, and cancel behavior stay unchanged.
- Source management actions stay unchanged.

## Library And Video Library

Keep media grids, but flatten and calm the visual treatment.

Design changes:

- Library cards become Adwaita-style media tiles with light surface layering and minimal shadows.
- Comic covers keep vertical aspect ratio.
- Video thumbnails keep 16:9 aspect ratio.
- Missing covers/thumbnails use calm fallback surfaces rather than strong linear gradients.
- Source badges use shared badge styling.
- Play affordance on videos remains visible but less neon.
- Selected state uses accent border and soft fill.
- Empty states become Adwaita-style empty views: icon, title, concise body, one action.

Behavior preserved:

- Cover/thumbnail loading.
- Long-press item management.
- Open item behavior.
- Add/remove/refresh thumbnail actions.
- Grid adaptive sizing.

## Settings

Settings should be the most direct application of boxed-list design.

Design changes:

- Root and subpages use headerbar plus boxed-list sections.
- Each settings group becomes a boxed list with optional heading.
- Rows follow action/switch/combo/property row patterns.
- Keep Android `Switch`, `DropdownMenu`, `Slider`, and buttons, but style the surrounding rows and containers through MuBOX tokens.
- Download records and cache rows should use the same row model as settings.

Behavior preserved:

- Root/subpage structure.
- Back behavior.
- All setting labels and callbacks.
- Download-record selection and clearing.
- Cache clearing.
- Disk cache limit and WebDAV prefetch coercion behavior.

## Video Player

The player follows the approved "full Adwaita-style OSD" direction while respecting Android/media constraints.

Design changes:

- Video remains full-screen and unobstructed when controls are hidden.
- Top overlay becomes a compact translucent OSD headerbar with close button, title, and source.
- Center play/pause keeps the existing 80dp touch target and 64dp visual size unless tests are intentionally updated. The visual treatment becomes an Adwaita OSD round button.
- Bottom controls become a translucent OSD toolbar/sheet containing:
  - progress
  - current time
  - duration
  - speed
  - scale mode
  - decoder mode
- Right-side controls remain, but icon buttons use OSD styling and selected state tokens.
- Option sheets become Adwaita-style popover/sheet surfaces: compact header, close action, grouped rows/chips, minimal borders.
- Gesture HUD becomes a small OSD toast.
- Player errors use semantic error tokens instead of hard-coded dark red surfaces.
- Progress fill follows the selected theme accent.

Behavior preserved:

- mpv initialization and lifecycle.
- Auto-hide timing.
- Lock button behavior and locked overlay behavior.
- Gesture hit zones.
- Volume/brightness gestures.
- Double-tap seek.
- Horizontal swipe seek.
- Temporary speed.
- Zoom gesture.
- Track/subtitle selection.
- Decoder/output/gpu behavior.
- Player statistics panel.
- Orientation behavior.

Player layout constraints:

- Controls must not overlap incoherently in portrait or landscape.
- Right-side popover must fit narrow landscape screens.
- Bottom sheet must leave safe padding and not block Android system gestures more than the current UI.
- Touch targets stay at least 44dp.
- Icon-only buttons keep content descriptions.
- Text inside chips/buttons must not overflow; use fixed dimensions, ellipsis, or responsive wrapping where needed.

## Compatibility And Migration

This work should be visually broad but behaviorally conservative.

Data compatibility:

- No Room migration.
- No DataStore key migration required unless implementation chooses to rename stored enum values. Prefer adding enum values and keeping `DEFAULT`.
- Existing unknown-palette fallback remains.

Test compatibility:

- Some tests that assert old labels/colors must change intentionally:
  - `SettingsScreenUiTest.defaultPaletteLabelMatchesCinematicTheme`
  - tests expecting default cyan/purple/amber cinematic roles
  - player UI tests that reference visual constants if constants move into token helpers
- Tests for behavior, route decisions, media actions, and playback logic should not be weakened.

User compatibility:

- Existing users will see the new Adwaita default after the update.
- Users can switch to the legacy cinema palette through Settings > 配色方案.
- High contrast remains available.

## Testing Plan

Use focused JVM tests for pure contracts and a debug build for Compose/Kotlin integration.

Recommended test updates/additions:

- `AppColorPalette` labels include new Adwaita palettes and legacy cinema label.
- `comicDavColorSchemeFor(AppColorPalette.DEFAULT)` verifies an Adwaita-style default: neutral surfaces, readable text, restrained accent.
- Legacy `CINEMA_DARK` verifies current cinematic color roles are still available.
- `muBoxColorsFor` maps all required app/player tokens from the selected color scheme.
- Headerbar/boxed-list component API order and semantics remain stable.
- Source/WebDAV click and long-press action tests remain unchanged.
- Settings layout tests still show "配色方案" in the root group.
- Player UI tests verify:
  - center play button touch/visual sizing
  - right-side control descriptions
  - quick control labels
  - option panel labels
  - gesture constants and hit area behavior
  - no behavior regression in controller tests

Verification commands:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

Manual verification:

- Open Sources, WebDAV browser, Library, Video Library, Settings.
- Switch between Adwaita default, Adwaita light, Adwaita blue-gray, Adwaita purple, high contrast, and legacy cinema.
- Play local video and WebDAV video.
- Check portrait and landscape player overlays.
- Check locked controls, auto-hide, right option sheet, gesture HUD, subtitles/audio track panel, and error panel.

## Implementation Boundaries

Recommended implementation phases:

1. Extend palette enum, labels, and theme mapping.
2. Add/adjust shared Adwaita-style MuBOX tokens and components.
3. Migrate app shell, selection bar, data-folder gate.
4. Migrate settings to boxed-list rows.
5. Migrate Sources and WebDAV browsing.
6. Migrate Library and Video Library tiles.
7. Migrate player controls to shared OSD tokens/components.
8. Update focused tests and run verification.

Do not start by rewriting feature screens independently. The shared token/component layer should come first so the visual language stays consistent and the player can reuse the same OSD tokens.

## Risks

- If only colors are changed, the UI will still look like the current cinema refresh rather than Adwaita.
- If desktop GNOME patterns are copied too literally, the Android player may lose touch ergonomics.
- Player overlay changes have the highest regression risk because they combine gestures, auto-hide, orientation, and constrained layouts.
- Adding too many palettes can make tests and design review noisy. Keep variants small and token-driven.
- Existing screenshots, if any are added later, will need updates for the new default.

## Success Criteria

- Default MuBOX opens into an Adwaita-style UI, not the old cinema shell.
- Users can switch among Adwaita-style palettes and the legacy cinema palette.
- Main app screens use headerbar/boxed-list/media-tile structure consistently.
- Video player uses OSD-style controls and no longer depends on hard-coded pink/cinema colors.
- Existing media workflows and playback interactions behave the same.
- Tests and debug build pass after implementation.
