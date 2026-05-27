# MuBOX Media Design System Refactor Design

## Goal

Refactor MuBOX from a Material 3 default-component look into a Compose-based, MuBOX-owned media application design system.

The approved visual direction is **Cinema Console with file-manager capability preserved**:

- Use the existing cinematic dark media shell as the primary product identity.
- Keep source browsing, local folders, and WebDAV dense and functional like a file manager.
- Cover the full app UI, including the video player control overlay.
- Preserve all existing business behavior, data flow, navigation, playback behavior, WebDAV behavior, settings storage, and media routing.

## Approved Scope

This refactor covers:

- App shell, bottom navigation, selection action bar, and data-folder gate.
- Local source browser and WebDAV browser.
- Library and video library.
- Settings root page, settings subpages, download records, cache rows, switches, choices, dropdowns, and sliders.
- Video player overlay: top bar, center play/pause, lock button, bottom controls, progress, quick-control chips, side rail, option sheets, HUD, and player error panel.
- Shared media UI tokens and reusable Compose components.
- Focused unit tests for token/component contracts, layout helper contracts, player control contracts, and existing action lists.

## Non-Goals

- Do not redesign the app information architecture.
- Do not add recommendations, filters, dashboards, continue-watching shelves, or new media library features.
- Do not change ViewModels, Room schemas, WebDAV networking/proxy behavior, mpv controller logic, reader logic, or media detection behavior except where a UI helper already derives presentation data.
- Do not add new dependencies.
- Do not remove Compose Material 3 as a technical dependency. Material 3 can remain the base primitive layer, but the app should no longer expose default Material component styling as the product design.
- Do not sacrifice file-manager actions, list density, long-press management, download actions, add-to-library actions, or source management in pursuit of a poster-only media-center UI.

## Design Direction

MuBOX should feel like a media control console:

- Default app shell uses a near-black/deep-ink background.
- Surface layers use dark blue/slate containers with visible hierarchy.
- Cyan is the primary media accent for active navigation, selected states, progress, playback affordances, and primary focused controls.
- Purple remains a secondary media accent for comic/supporting media.
- Amber is limited to status emphasis and warning-like informational elements.
- File and WebDAV screens remain dense, scannable, and row-first.
- Library screens use cover and thumbnail presentation without turning all screens into large card grids.
- Player controls use translucent dark floating panels over video and share the same accent, shape, and type language as the rest of the app.

## Architecture

Keep the existing feature package boundaries and introduce a shared design-system layer under the existing UI area.

Proposed ownership:

- `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`
  - Continue to provide Material 3 `ColorScheme`, `Typography`, and `Shapes`.
  - Add or expose MuBOX-specific media tokens through pure Kotlin/Compose helpers.
- `app/src/main/java/com/example/comicdav/ui/`
  - Add shared MuBOX media UI components and token helpers.
  - Keep components pure and stateless where possible.
- Feature screens
  - Continue to own their business state wiring and callbacks.
  - Replace direct default-looking Material combinations with MuBOX components.
- Player UI
  - Move player visual constants into the same token language.
  - Keep playback state, gestures, and mpv commands unchanged.

The refactor should avoid a large composition-root rewrite. Existing screen functions should keep their public parameters unless a local extraction is necessary and low-risk.

## Design-System Tokens

Add a compact token layer that can be consumed by feature screens and player controls.

Required token groups:

- `MuBoxColors`
  - App background, panel, panel high, row, selected row, border, selected border.
  - Primary media accent, on-accent, accent-soft, on-accent-soft.
  - Comic/support accent, video/playback accent, status accent.
  - Text, muted text, inverse/video overlay text.
  - Player overlay, player sheet, player chip, player selected chip, progress track, progress fill, HUD surface.
  - Error surface and error text.
- `MuBoxSpacing`
  - Page horizontal/vertical padding.
  - Row padding and gap.
  - Card inner padding.
  - Player overlay margins and panel padding.
- `MuBoxShapes`
  - Dense rows around 14dp.
  - Panels around 18-22dp.
  - Poster/card media surfaces around 12-16dp.
  - Player icon buttons as circles.
- `MuBoxType`
  - Use existing `MaterialTheme.typography` roles, but standardize which roles are used by title, row title, row metadata, chip, section label, player time, and settings rows.

The token layer may derive from `MaterialTheme.colorScheme` so existing palettes continue to work. The default palette should be the canonical cinema-console experience.

## Shared Components

Create reusable Compose components where they reduce duplication and make the product style consistent.

Required component families:

- App shell components
  - `MuBoxBottomNavigation`
  - `MuBoxSelectionBar`
  - `MuBoxPageHeader`
  - `MuBoxMessagePanel`
  - `MuBoxEmptyState`
- Media browser components
  - `MuBoxDenseMediaRow`
  - `MuBoxMediaTypeIcon`
  - `MuBoxPathBar`
  - `MuBoxSourceBadge`
- Library components
  - `MuBoxPosterCard`
  - `MuBoxPosterFallback`
  - `MuBoxThumbnailScrim`
- Settings components
  - `MuBoxSettingsPageShell`
  - `MuBoxSettingsGroup`
  - `MuBoxSettingsNavigationRow`
  - `MuBoxSwitchRow`
  - `MuBoxChoiceRow`
  - `MuBoxDropdownRow`
  - `MuBoxSliderRow`
- Player components
  - `MuBoxPlayerIconButton`
  - `MuBoxPlayerPanel`
  - `MuBoxPlayerChip`
  - `MuBoxPlayerProgress`
  - `MuBoxPlayerHud`

Component APIs should stay presentation-focused. They should accept labels, icons, selected/enabled state, supporting text, and callbacks, not feature-specific repositories or ViewModels.

## Screen Migration

### App Shell

Replace the current Material default-looking bottom navigation treatment with a custom MuBOX navigation surface.

Requirements:

- Keep tab order: Sources, Library, Video Library, Settings.
- Keep icon content descriptions.
- Keep text single-line and readable.
- Keep selection action labels and callbacks unchanged.
- Use the same visual language for normal navigation and selection mode.
- Keep the data-folder gate as a product-branded entry screen, not a generic Material prompt.

### Sources And WebDAV

These screens must preserve file-manager function and density.

Requirements:

- Use dense media rows with stable row height and 44dp+ touch targets.
- Keep current click behavior:
  - Directory opens directory.
  - Comic opens reader.
  - Video opens player.
  - Subtitle and unsupported files do not open directly.
- Keep long-press behavior for adding comics/videos and WebDAV downloads.
- Keep source management actions.
- Keep path bars compact and ellipsized.
- Keep transfer/progress panels readable on dark surfaces.
- Use the same media icon vocabulary for local and WebDAV rows.

### Library And Video Library

Use a shared poster-card model with different aspect ratios.

Requirements:

- Comics keep vertical cover treatment.
- Videos keep 16:9 thumbnail treatment.
- Missing media art uses a dark media fallback rather than a generic gradient card.
- Selected states use the same cyan border/fill system.
- Source badges use shared badge styling.
- Empty states stay concise and action-oriented.
- Existing cover/thumbnail loading, long-press management, and open behavior remain unchanged.

### Settings

Settings should read as MuBOX media control panels, not default system rows.

Requirements:

- Preserve the existing root/subpage structure.
- Preserve all setting rows and labels.
- Keep `Switch`, choice, dropdown, slider, download records, and cache clearing behavior.
- Use shared group, row, and message panel styling.
- Keep rows dense enough for repeated settings use.
- Keep back behavior and download-record selection behavior unchanged.

### Video Player Overlay

The player must use the same design system while preserving all playback interactions.

Requirements:

- Video remains full-screen and unobstructed when controls are hidden.
- Top bar keeps close button, title, and source.
- Center play/pause remains a large 80dp touch target with 64dp visual control unless tests intentionally change.
- Lock button behavior, auto-hide, gestures, and locked overlay stay unchanged.
- Bottom controls keep progress, current time, duration, speed, scale, and decoder quick controls.
- Side rail keeps orientation, tracks/subtitles, and info controls.
- Option sheet keeps audio/subtitle selection and info content.
- HUD and error panel use shared player overlay tokens.
- Existing content descriptions and player UI logic tests remain meaningful.

## Data Flow

This work should not introduce new screen state models.

Existing state remains authoritative:

- `FileDirectoryUiState`
- `WebDavUiState`
- `LibraryUiState`
- `VideoLibraryUiState`
- `AppSettings`
- `MpvPlayerState`
- `VideoPlayerMediaContext`

New design components receive derived presentation data from the current screen layer. They should not fetch data, mutate state directly, or own app workflows.

## Error, Loading, And Empty States

Use one shared visual language for feedback:

- Informational messages use dark raised media panels with cyan or neutral borders.
- Errors use dark-compatible red surfaces/text and remain close to the affected workflow.
- Loading states remain existing progress indicators or skeleton-free simple indicators unless already present.
- Empty states include one primary recovery action and concise text.
- No error strings or recovery behavior need to change unless text no longer fits.

## Accessibility And Interaction

Requirements:

- Keep primary touch targets at least 44dp.
- Keep icon-only buttons with content descriptions.
- Preserve selected, disabled, loading, and pressed state clarity.
- Maintain readable contrast on dark surfaces.
- Do not rely on color alone where media type or state can use icon/text/border.
- Keep text from overflowing buttons, chips, rows, cards, and player panels.
- Keep player controls stable so time labels, mode labels, and track labels do not resize the overlay.
- Avoid horizontal scroll on mobile.

## Testing Strategy

Use TDD for implementation:

- Add failing tests for token and component contracts before implementation.
- Verify the failures are meaningful before production code changes.
- Keep tests focused on stable behavior and structural contracts rather than screenshot-perfect styling.

Test areas:

- Theme/design-token tests:
  - Default palette remains a dark cinema shell.
  - Accent roles map consistently.
  - Compact typography does not use decorative negative tracking.
- App shell tests:
  - Tab labels and action labels remain unchanged.
  - Navigation/selection container helpers use media surface roles.
- Browser tests:
  - File/WebDAV click and long-press action mapping remains unchanged.
  - Media row/icon color helpers use the design token roles.
  - Path and size formatting remain unchanged.
- Library tests:
  - Count labels, source labels, source metadata remain unchanged.
  - Shared poster-card contracts expose correct aspect-ratio intent for comic vs video.
- Settings tests:
  - Root/comic/video settings group row coverage remains unchanged.
  - New settings component helper lists do not drop rows.
- Player tests:
  - Right-side controls, bottom quick controls, panel labels, sizes, gesture hit areas, and gesture routing remain unchanged unless intentionally updated.
  - Player visual constants come from design tokens or helper contracts.

Verification commands:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

If the full build is blocked by Android/NDK environment issues, run the focused JVM tests and report the exact blocker.

## Parallel Agent Execution Strategy

Implementation should be split into staged, file-owned work so subagents can operate in parallel without conflicting.

### Wave 0: Foundation

One foundation agent owns shared UI files and tests:

- Add token and shared component skeletons.
- Add initial RED tests for token/component contracts.
- Avoid migrating all screens in this wave.

Likely files:

- `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`
- New files under `app/src/main/java/com/example/comicdav/ui/`
- Shared UI tests under `app/src/test/java/com/example/comicdav/ui/`

### Wave 1: Parallel Screen Migration

After foundation is green, split screen migration across independent agents:

- Agent A: App shell and data-folder gate.
- Agent B: Local source browser and WebDAV browser.
- Agent C: Library and video library cards.
- Agent D: Settings pages and settings rows.
- Agent E: Video player overlay.

Each agent must own a narrow file set and add/update tests for its own area. Agents should not edit each other's files.

### Wave 2: Integration

One integration agent or the coordinator:

- Reviews all agent summaries.
- Resolves shared token/component API mismatches.
- Runs the full JVM test suite.
- Runs debug build if the environment supports it.
- Performs final UI consistency pass.

## Implementation Boundaries

The implementation plan should be staged:

1. Create shared design-system tokens/components and tests.
2. Migrate app shell, sources/WebDAV, library/video library, settings, and player overlay as parallel screen tracks after the shared foundation is available.
3. Integrate the parallel tracks, resolve shared component API mismatches, and verify.

Do not merge unrelated refactors into this work. If a screen is too tangled to migrate cleanly, extract only the smallest local helper needed to use the new component.

## Open Questions Resolved

- Visual direction: Cinema Console.
- File-manager functionality: preserved.
- Player overlay scope: included.
- Execution style: staged parallel subagents after the design-system foundation is in place.
