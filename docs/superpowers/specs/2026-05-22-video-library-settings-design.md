# Video Library and Settings Design

## Context

MuBOX currently has three bottom tabs: sources, library, and settings. The existing library is a comic bookshelf backed by Room tables for local and WebDAV comic sources. Sources already list both comics and videos, and video playback is handled by `VideoPlayerActivity`.

Two current issues need to be fixed as part of this work:

- When video player orientation is set to "video", the orientation effect can leak back to the main app, leaving the app unable to rotate normally.
- WebDAV directory paths display percent-encoded Chinese text such as `%E6...` in path labels.

## Goals

- Split settings into clearer common, comic, and video sections while keeping shared settings outside media-specific groups.
- Add a bottom navigation tab named "影视库" for video favorites.
- Allow both local directory videos and WebDAV videos to be added to the video library as references only.
- Do not download remote videos or copy local videos when adding to the video library.
- Add a setting to automatically extract thumbnails for videos added to the video library.
- Support long-press video library actions: refresh thumbnail, remove, delete thumbnail, cancel.
- Support long-press directory video actions:
  - Local video: add to video library, cancel.
  - WebDAV video: add to video library, download, cancel.
- Make the WebDAV video download action really download the video to disk storage.
- Preserve existing comic bookshelf behavior.

## Non-Goals

- Do not merge comics and videos into one visible library.
- Do not make video favorites imply offline availability.
- Do not delete source files when removing a video library item.
- Do not build a playback queue or playlist.
- Do not add cloud sync or metadata scraping.

## Recommended Approach

Add a dedicated Room-backed video library domain instead of reusing the comic library tables. The video library stores only stable source references and nullable cached thumbnail paths. This keeps comic-specific fields such as page count, last page, and comic cover extraction separate from video behavior.

Alternatives considered:

- Reuse comic library tables with a media type column. This has lower initial schema cost, but makes comic and video behavior leak into each other.
- Store video favorites as JSON in DataStore. This is fast to implement, but weak for uniqueness, migrations, sorting, and thumbnail state.

## Data Model

Add Room entities:

- `video_library_items`
  - `id`
  - `title`
  - `displayName`
  - `sourceType`
  - `thumbnailPath`
  - `addedAt`
  - `lastOpenedAt`
- `local_video_sources`
  - `videoLibraryItemId`
  - `uri`
  - `fileName`
  - `size`
  - `lastModified`
- `webdav_video_sources`
  - `videoLibraryItemId`
  - `accountId`
  - `remotePath`
  - `fileName`
  - `size`
  - `etag`
  - `lastModified`

Add a lightweight video download record store for explicit WebDAV video downloads:

- `fileName`
- `accountId`
- `remotePath`
- `localUri`
- `sizeBytes`
- `downloadedAtMillis`

The download record is separate from video favorites. Downloading a WebDAV video does not automatically add it to the video library, and adding a WebDAV video to the video library does not automatically download it.

Uniqueness:

- Local video favorites are unique by `uri`.
- WebDAV video favorites are unique by `accountId + remotePath`.

Removing a video library item deletes the Room record and any related local thumbnail reference, but not the original video file and not downloaded video files.

## Video Library Behavior

The new bottom tab order is:

1. 来源
2. 书架
3. 影视库
4. 设置

The video library displays video favorites in a grid or list consistent with the existing comic library card style. A card opens the stored video reference:

- Local favorites open `VideoPlayerActivity.localIntent`.
- WebDAV favorites resolve the saved account, start the existing localhost video proxy, then open `VideoPlayerActivity.webDavIntent`.

Long press enters selection mode and replaces the bottom navigation with:

- 重新提取缩略图
- 移除
- 删除缩略图
- 取消

Action behavior:

- `重新提取缩略图`: extract a new frame and update `thumbnailPath`.
- `移除`: remove only the video library record.
- `删除缩略图`: delete the cached thumbnail file if present and clear `thumbnailPath`.
- `取消`: clear selection.

## Directory Video Actions

Long-press behavior is extended from comics to videos.

Local directory video actions:

- `加入影视库`: save the `content://` reference, file name, size, and last modified metadata. Do not copy the file.
- `取消`: clear selection.

WebDAV video actions:

- `加入影视库`: save the WebDAV account id, remote path, file name, size, etag, and last modified metadata. Do not download the video.
- `下载`: download the remote video bytes to MuBOX disk storage.
- `取消`: clear selection.

The explicit WebDAV video download action is independent from adding to the video library. It streams the remote video to disk storage, reports progress, and shows success or failure. The target location is the selected MuBOX data folder under a video-specific directory such as `videos/`. If the data folder is unavailable, the action fails with a clear message instead of silently falling back to cache. A temporary file is written first and renamed or finalized only after the expected byte count is reached, so failed downloads do not leave corrupt final files.

## Thumbnail Extraction

Add a video setting:

- `提取加入影视库的视频缩略图作为封面`

When enabled, adding a video to the video library attempts to extract a thumbnail and stores it as the item cover. Failures should not block adding the item; they should produce a non-fatal message or log entry.

Thumbnail extraction should support:

- Local `content://` videos through Android media APIs.
- Downloaded local video files through their stored `localUri`.
- WebDAV videos by starting the existing video proxy and using Android media metadata APIs against the temporary localhost URL with a bounded timeout. If extraction fails, the item is still added and the UI shows a non-fatal message.

Cached thumbnails should live in a video-specific folder such as `video-library-thumbnails`.

## Settings Reorganization

Keep common settings in common groups:

- App display/theme settings.
- Cache status and cache clearing controls, except media-specific cache rows may be named clearly.
- Data/log folder controls if present.

Move comic-specific settings into a `漫画` group:

- 阅读方向
- 音量键翻页
- 屏幕旋转锁定
- WebDAV 预取页数
- 书架封面
- 自动翻页 settings
- 漫画/阅读 diagnostic logging

Move video-specific settings into a `视频` group:

- 恢复播放位置
- WebDAV 视频 seek 优化
- 向前预读
- 视频代理诊断日志
- 视频输出 (VO)
- GPU API
- 默认解码器
- MPV Profile
- 控制自动隐藏
- 播放器方向
- 提取加入影视库的视频缩略图作为封面

The settings screen may remain one scrollable page, but group titles and row placement should make the ownership clear.

## Orientation Fix

Root cause hypothesis: `VideoPlayerActivity` sets `requestedOrientation` based on the video mode, while the main activity also sets `requestedOrientation` from the reader rotation-lock setting. After returning from video playback, the main activity may not reapply its own orientation policy if the relevant setting value has not changed.

Fix design:

- Keep video orientation logic scoped to `VideoPlayerActivity`.
- When the main app resumes or recomposes after video playback, reapply the main app orientation policy:
  - `SCREEN_ORIENTATION_LOCKED` only when comic screen rotation lock is enabled.
  - `SCREEN_ORIENTATION_UNSPECIFIED` otherwise.
- Avoid storing player orientation state in any app-wide setting beyond the user's selected video player mode.

Acceptance:

- Setting video player orientation to "视频" affects the player only.
- Returning to the app leaves the app free to rotate when comic rotation lock is off.

## WebDAV Path Display Fix

Internal WebDAV paths should remain encoded if that is what the HTTP client and server interaction require. UI labels should decode path text for display.

Fix design:

- Add a display-path helper for WebDAV path labels.
- Use it in browser path labels and saved directory display names.
- Keep `WebDavItem.path`, WebDAV requests, and stored remote paths unchanged for this change.

Acceptance:

- A path like `/漫画/视频/` displays as Chinese text in the UI.
- Opening, saving, downloading, favoriting, and playback continue to use the original functional path.

## Error Handling

- Adding duplicate video favorites should return the existing record and show a success-style message.
- Thumbnail extraction failure should not prevent adding a favorite.
- WebDAV video download should show progress and a failure message without leaving a corrupt final file.
- Missing WebDAV credentials for a favorite should prompt the same reconnect behavior currently used by comic WebDAV opening.
- Deleting a thumbnail should tolerate the file already being missing.

## Testing

Add focused unit tests for:

- Video library repository uniqueness and remove behavior.
- Room migration from the current database version to the new version.
- Settings defaults and update method for video thumbnail extraction.
- WebDAV path display decoding without changing the stored/request path.
- Directory long-press action lists for local videos, WebDAV videos, and comics.
- Bottom selection action lists for video library items.
- Main app orientation policy helper so player orientation cannot leak into app orientation.

Manual verification:

- Add a local video to the video library; confirm no copy is created.
- Add a WebDAV video to the video library; confirm no download starts.
- Use WebDAV video `下载`; confirm bytes are written to disk and progress appears.
- Return from a "视频" orientation playback session; confirm the app rotates normally when rotation lock is off.
- Browse a Chinese WebDAV directory; confirm displayed path is decoded.
