# Settings Redesign Design

## Context

MuBOX currently renders all settings in one scrollable Compose screen. The page mixes common app settings, comic reader settings, video player settings, download records, and cache management. The current `SettingsScreen` already has reusable row components and layout metadata tests, so the redesign can focus on information architecture without changing settings storage or business behavior.

## Goals

- Give the settings area one unified visual style across root and subpages.
- Keep common settings visible on the first-level settings page.
- Move comic-specific settings into a second-level comic settings page.
- Move video-specific settings into a second-level video settings page.
- Preserve existing setting values, callbacks, cache behavior, and download record behavior.
- Keep the change localized to the settings UI unless tests reveal a required adjacent change.

## Non-Goals

- Do not add new settings.
- Do not change `AppSettingsStore` keys, defaults, or persistence behavior.
- Do not change bottom navigation tab structure.
- Do not introduce a new app-wide navigation framework.
- Do not redesign unrelated library, source, reader, or player screens.

## Recommended Approach

Implement second-level pages inside `SettingsScreen` with local UI state. The root settings page remains the only bottom-tab destination. Tapping `漫画设置` or `视频设置` switches the internal settings page to the relevant subpage, and the subpage back action returns to the settings root.

Alternatives considered:

- App-level routes for comic and video settings. This would make deep links easier later, but the app currently does not have a standalone navigation framework and this redesign does not need one.
- Collapsible groups on the root settings page. This is smaller, but it does not satisfy the requested second-level page structure.

## Root Page

The first-level settings page should show:

- Header:
  - Title: `设置`
  - Subtitle: common settings, content preferences, cache, and records.
- `通用` group:
  - `配色方案`
  - `屏幕旋转锁定`
- `内容设置` group:
  - `漫画设置`
  - `视频设置`
- `下载记录` group:
  - Existing empty or clickable download record row behavior.
- `缓存` group:
  - Existing cache total row.
  - Existing cache category clear rows.
  - Existing disk cache limit row.
  - Existing cache action message row.

The `漫画设置` and `视频设置` entries should be normal list-style settings rows, not large cards. They should visually match existing rows and clearly imply navigation with a trailing text indicator or chevron-style affordance.

## Comic Settings Page

The comic subpage should use the same page shell as the root page: background, horizontal padding, vertical spacing, header, and grouped surfaces.

Header:

- Title: `漫画设置`
- Subtitle: reader behavior, comic prefetch, covers, and diagnostics.
- Back action returns to root settings.

Rows:

- `阅读方向`
- `音量键翻页`
- `WebDAV 预取页数`
- `诊断日志`
- `书架封面`
- `启用自动翻页`
- `翻页速度`

Existing row controls remain unchanged: radio choices, switches, dropdowns, and slider behavior keep their current labels and callbacks.

## Video Settings Page

The video subpage should use the same page shell as the root page and comic subpage.

Header:

- Title: `视频设置`
- Subtitle: playback resume, WebDAV streaming, player backend, controls, and thumbnails.
- Back action returns to root settings.

Rows:

- `恢复播放位置`
- `WebDAV 视频 seek 优化`
- `向前预读`
- `视频代理诊断日志`
- `视频输出 (VO)`
- `GPU API`
- `默认解码器`
- `MPV Profile`
- `控制自动隐藏`
- `播放器方向`
- `提取加入影视库的视频缩略图作为封面`

Existing row controls remain unchanged.

## Download Records Page

Download records can continue to be a second-level page, but it should align with the same settings page shell and back behavior. The current record list, empty state, long-press selection behavior, and decoded remote path display should be preserved.

## State And Data Flow

Add an internal settings page state such as:

- `Root`
- `Comic`
- `Video`
- `DownloadRecords`

The state lives inside `SettingsScreen` and uses Compose state that survives normal recomposition. The screen continues to receive `AppSettings`, record lists, cache analysis, messages, and all existing callbacks from `MainActivity`.

No persistence layer changes are required. `MainActivity` should continue passing the same callback set to `SettingsScreen`.

## UI Components

Reuse the current building blocks where practical:

- `SettingsGroup`
- `SwitchRow`
- `StaticInfoRow`
- `ClickableInfoRow`
- `ChoiceRow`
- `DropdownRow`
- `AutoPageSpeedRow`
- `DiskCacheLimitRow`
- `CacheActionRow`

Add or adjust small shared helpers only when they remove duplication between root, comic, video, and download record pages. The page shell and header should be shared so all settings pages feel like one design family.

Text must remain readable on narrow screens. Long labels should wrap or ellipsize in the same way the existing rows already do, and touch targets should remain at least the current row heights.

## Testing

Update the settings layout metadata tests to reflect the new hierarchy:

- Root layout contains `通用`, `内容设置`, `下载记录`, and `缓存`.
- Root layout does not contain comic-only or video-only rows.
- Comic settings layout contains all comic-specific rows.
- Video settings layout contains all video-specific rows.
- Common display rows stay on the root page.

Existing unit tests for coercion helpers, labels, cache limit conversion, and settings persistence should continue to pass unchanged.

## Acceptance Criteria

- Opening the settings tab shows only common settings, content settings links, download records, and cache controls.
- Tapping `漫画设置` opens a second-level page with comic-specific settings.
- Tapping `视频设置` opens a second-level page with video-specific settings.
- Back from comic, video, or download records returns to the first-level settings page.
- Existing setting changes still call the same callbacks and persist through `AppSettingsStore`.
- Cache clearing and download record behavior are unchanged.
- Tests document the new first-level and second-level settings hierarchy.
