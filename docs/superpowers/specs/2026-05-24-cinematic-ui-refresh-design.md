# MuBOX Cinematic UI Refresh Design

## Goal

Refresh MuBOX with a bounded, cinematic visual treatment across the app and player while preserving the existing navigation, data model, playback engine, WebDAV behavior, and media routing.

The approved scope is visual polish plus lightweight interaction improvements:

- Apply a global dark media-app direction to the default app experience.
- Make the video player overlay feel like floating cinema controls.
- Improve video library cards, source/file lists, WebDAV browser rows, bottom navigation, selected states, empty states, messages, and progress panels.
- Keep all existing features and primary workflows intact.

## Non-Goals

- Do not change WebDAV protocol behavior, range streaming, video proxy logic, mpv playback core, database schema, media type detection, or route decisions.
- Do not redesign the app information architecture.
- Do not add recent-playback shelves, categories, recommendations, new menus, or new dependencies.
- Do not replace Material 3 components or the existing icon set.
- Do not make purely decorative effects that reduce scanning speed or text contrast.

## Design Direction

Use a bounded "Full Cinema Shell" direction:

- Default app surfaces move to a deep ink-blue background with clear surface layers.
- Playback actions use cyan as the primary media accent.
- Purple remains a secondary media accent, and amber stays limited to small status or emphasis use.
- The player uses semi-transparent dark floating panels over the video.
- Lists remain practical and readable, even though the global shell is darker.

This direction should feel visually unified with the player without turning every source row into a large video poster.

## Architecture

The implementation stays inside the existing Compose UI structure:

- `ComicDavTheme.kt` owns global Material 3 colors, shapes, and typography adjustments.
- `AppNavigation.kt` owns the bottom navigation and selection action bar treatment.
- `VideoLibraryScreen.kt` owns media cards, video empty state, selected state, and metadata hierarchy.
- `FileDirectoryScreen.kt` owns local/WebDAV source home, browse headers, source rows, file rows, and empty states.
- `WebDavBrowserScreen.kt` owns WebDAV browsing rows, path bar, transfer panel, selected state, and media type icons.
- `VideoPlayerControls.kt` owns player overlay colors, buttons, bottom floating controls, side rail, option sheet, progress bar, HUD, and player error treatment.
- `VideoPlayerActivity.kt` should only need minor layout wiring if existing overlay placement requires adjustment.

No new UI framework or shared design-system module is required. Small local helper composables are acceptable when they reduce duplication in the files being changed.

## Global Theme

The default palette changes from light indigo to a dark cinematic palette:

- Background: deep ink-blue.
- Surfaces: layered dark blue/slate containers with enough contrast between page, rows, panels, and modals.
- Primary: cyan media accent for selected navigation, progress, focused actions, and playback-related affordances.
- Secondary: purple for supporting media surfaces and subtle gradients.
- Tertiary: amber for limited status emphasis.
- Error: high-contrast red container/text pairs suitable for dark backgrounds.

Typography should keep the existing scale but remove any overly decorative tracking in compact UI. Text contrast must remain strong on dark surfaces. Buttons and rows should retain 44dp minimum touch targets.

Sepia, night, and high-contrast palettes remain available. This work focuses on the default app look and player-specific palette; other palettes should not be removed.

## App Shell

The app shell should use a dark background and a media-style bottom navigation:

- Bottom navigation uses a dark surface instead of a bright bar.
- Selected tabs use cyan icon/text and a subtle cyan container.
- Unselected tabs stay readable with muted foreground.
- Selection action bars follow the same dark surface and selected/disabled color rules.
- Dividers should be subtle and not create a heavy frame.

The existing tab structure remains unchanged: Sources, Library, Video Library, Settings.

## Video Library

The video library becomes the most media-forward main screen:

- Keep adaptive 16:9 video cards.
- Strengthen thumbnail overlays so titles, source labels, and play affordances remain readable.
- Use a clearer central play affordance with dark translucent backing.
- Use cyan selected borders/shadows rather than light containers.
- Keep metadata but lower its visual weight below title and thumbnail.
- For missing thumbnails, render a dark gradient poster-like fallback with a play marker and compact title treatment.
- Keep the empty state simple: icon, short title, supporting text, and one clear Sources action.

The grid density should improve slightly by tuning spacing and card inner padding, not by shrinking touch targets below usable sizes.

## Sources And WebDAV Lists

Sources and file browsing stay list-first:

- Use dark rows with clear media-type icon containers.
- Distinguish folders, comics, videos, and subtitles through icon and container color.
- Use stronger selected states for long-pressed files and managed sources.
- Make browse path bars dark, legible, and compact.
- Keep long paths ellipsized.
- Keep source management actions and long-press behavior unchanged.
- WebDAV transfer panels use dark surfaces, cyan progress, and clear error text.

The refresh should improve scanability and selected-state confidence without adding new menus or changing click behavior.

## Player Overlay

The player uses the approved "Floating Cinema" layout:

- Video remains full-screen and unobstructed when controls auto-hide.
- Top overlay keeps close button, title, and source label with a smoother dark gradient.
- Bottom controls become a semi-transparent floating panel containing progress, time labels, and quick controls.
- Center play/pause uses a more polished translucent circular backing.
- Right-side rail keeps orientation, audio/subtitle, and information controls.
- Active rail buttons and selected chips use cyan accent.
- Option sheets use deep blue translucent surfaces, stable rounded corners, and predictable button sizing.
- Gesture HUD, lock button, playback errors, and progress color align with the same palette.

Interaction behavior remains unchanged: auto-hide timing, lock mode, gestures, seek, speed selection, scale selection, decoder selection, subtitle/audio track selection, and information panel behavior stay intact.

## Data Flow

This refresh does not introduce new state or data flow. Existing screen state models continue to drive the UI:

- `VideoLibraryUiState` continues to provide library items, loading, error, and message state.
- `FileDirectoryUiState` continues to provide source lists, entries, current title, loading, error, and message state.
- `WebDavUiState` continues to provide current path, items, loading, error, and message state.
- `MpvPlayerState` continues to provide playback position, duration, pause state, tracks, decoder state, gesture HUD, lock state, and errors.

Any new visual helpers must be pure composables or pure formatting functions derived from existing inputs.

## Error And Empty States

Errors should remain direct and visible:

- App-level errors use dark error containers or high-contrast red text on dark panels.
- Player errors stay close to the playback controls and remain readable over video.
- Transfer errors remain in the WebDAV transfer panel.
- Empty states should be concise and action-oriented.

No error strings or recovery behavior need to change unless existing text no longer fits the redesigned containers.

## Accessibility And Interaction Requirements

- Maintain at least 44dp touch targets for primary buttons, icon buttons, player controls, and bottom navigation.
- Keep icon-only buttons with content descriptions.
- Preserve visible selected, pressed, disabled, and loading states.
- Avoid relying on color alone where an icon or text already exists.
- Ensure dark-surface text has sufficient contrast.
- Avoid horizontal overflow on narrow screens.
- Keep player controls stable in size so text changes do not shift the overlay.

## Testing Plan

Use focused tests for behavior and structural UI contracts:

- Update existing player UI tests when constants or control dimensions intentionally change.
- Keep tests covering player control labels, right-side controls, gesture hit area, and interaction routing.
- Add or adjust pure-function tests only where formatting, labels, selection actions, or media-row decisions change.
- Do not add brittle tests that assert raw color values unless the color value is part of a public or behavioral contract.

Verification should include:

- Relevant JVM tests for changed UI helper contracts.
- A debug build to catch Compose/Kotlin compilation issues.
- Manual or screenshot inspection if a runnable Android environment is available.

## Implementation Boundaries

Implementation should be incremental:

1. Update theme tokens and app shell colors.
2. Refresh video library cards and empty state.
3. Refresh sources and WebDAV browser rows/panels.
4. Refresh player overlay components.
5. Update focused tests and run verification.

Each step should preserve existing workflows and avoid unrelated refactors.
