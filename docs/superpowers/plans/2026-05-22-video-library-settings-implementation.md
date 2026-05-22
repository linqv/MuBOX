# Video Library and Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a video favorites library, reorganize settings into common/comic/video groups, fix video orientation leakage, decode WebDAV path labels, and support explicit WebDAV video downloads.

**Architecture:** Keep comics and videos separate. Add a Room-backed video library domain and a video-specific download/thumbnail layer, then wire it into the existing Compose shell and source browsers. Keep WebDAV request paths encoded internally, but decode path labels at display boundaries.

**Tech Stack:** Android Kotlin, Jetpack Compose, Room, DataStore Preferences, Android SAF, WebDAV client/proxy, JUnit unit tests.

---

## File Structure

- Create `app/src/main/java/com/example/comicdav/data/videolibrary/VideoLibraryEntities.kt`: Room entities, relation DTO, converters if needed.
- Create `app/src/main/java/com/example/comicdav/data/videolibrary/VideoLibraryDao.kt`: insert/find/update/delete/observe DAO methods.
- Create `app/src/main/java/com/example/comicdav/data/videolibrary/VideoLibraryRepository.kt`: duplicate-safe add/open/remove/update-thumbnail API.
- Modify `app/src/main/java/com/example/comicdav/data/library/LibraryDatabase.kt`: include video entities, DAO, migration from version 4 to 5.
- Modify `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`: add `videoLibraryThumbnailsEnabled` setting and update method.
- Create `app/src/main/java/com/example/comicdav/data/VideoDownloadStore.kt`: video download record DataStore.
- Create `app/src/main/java/com/example/comicdav/data/VideoDownloadCache.kt`: write WebDAV videos to the selected MuBOX data folder using temp-file finalization.
- Create `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryViewModel.kt`: observe video library and expose messages.
- Create `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt`: video favorite grid/list with long-press selection.
- Create `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoThumbnailExtractor.kt`: local/WebDAV thumbnail extraction with non-fatal failures.
- Modify `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`: long-press video actions and video selection naming.
- Modify `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`: long-press video actions and decoded path labels.
- Modify `app/src/main/java/com/example/comicdav/feature/webdav/WebDavViewModel.kt`: decoded display path helper if most appropriate there.
- Modify `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`: common/comic/video settings grouping and new thumbnail switch.
- Modify `app/src/main/java/com/example/comicdav/MainActivity.kt`: new tab, repositories/viewmodels, video library open/add/download/thumbnail actions, selection bottom bar, orientation policy reapply.
- Modify `app/src/main/java/com/example/comicdav/ui/ComicDavCopy.kt`: add video library copy.
- Add tests under `app/src/test/java/com/example/comicdav/...` for each behavior listed below.

---

### Task 1: Data Model, Settings, and Pure Helpers

**Files:**
- Create: `app/src/main/java/com/example/comicdav/data/videolibrary/VideoLibraryEntities.kt`
- Create: `app/src/main/java/com/example/comicdav/data/videolibrary/VideoLibraryDao.kt`
- Create: `app/src/main/java/com/example/comicdav/data/videolibrary/VideoLibraryRepository.kt`
- Modify: `app/src/main/java/com/example/comicdav/data/library/LibraryDatabase.kt`
- Modify: `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Test: `app/src/test/java/com/example/comicdav/data/videolibrary/VideoLibraryRepositoryTest.kt`
- Test: `app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt`

- [ ] **Step 1: Write failing tests for repository uniqueness**

Create `VideoLibraryRepositoryTest` with a fake DAO or in-memory fake catalog that verifies:

```kotlin
@Test
fun addLocalVideoReturnsExistingIdForSameUri() = runTest {
    val dao = FakeVideoLibraryDao()
    val repository = VideoLibraryRepository(dao, clock = { 10L })

    val first = repository.addLocalVideo("content://video/1", "电影.mp4", 100L, 20L)
    val second = repository.addLocalVideo("content://video/1", "电影.mp4", 100L, 20L)

    assertEquals(first, second)
    assertEquals(1, dao.insertedItems)
}

@Test
fun addWebDavVideoReturnsExistingIdForSameAccountAndPath() = runTest {
    val dao = FakeVideoLibraryDao()
    val repository = VideoLibraryRepository(dao, clock = { 10L })

    val first = repository.addWebDavVideo("account", "/%E8%A7%86%E9%A2%91/movie.mp4", "movie.mp4", 100L, "etag", 20L)
    val second = repository.addWebDavVideo("account", "/%E8%A7%86%E9%A2%91/movie.mp4", "movie.mp4", 100L, "etag", 20L)

    assertEquals(first, second)
    assertEquals(1, dao.insertedItems)
}
```

- [ ] **Step 2: Write failing tests for settings default and update**

Create or extend `AppSettingsStoreTest` to verify `videoLibraryThumbnailsEnabled` defaults to `true` and can be updated to `false`.

- [ ] **Step 3: Write failing tests for WebDAV path display**

Add tests in `WebDavBrowserScreenTest`:

```kotlin
@Test
fun displayPathDecodesUtf8PercentEncodedPath() {
    assertEquals("路径 /漫画/视频/", webDavDisplayPathLabel("/%E6%BC%AB%E7%94%BB/%E8%A7%86%E9%A2%91/"))
}

@Test
fun displayPathLeavesInvalidEscapesUsable() {
    assertEquals("路径 /bad%ZZ/", webDavDisplayPathLabel("/bad%ZZ/"))
}
```

- [ ] **Step 4: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VideoLibraryRepositoryTest' --tests '*AppSettingsStoreTest' --tests '*WebDavBrowserScreenTest'`

Expected: FAIL because video library types/settings/display helper do not exist.

- [ ] **Step 5: Implement minimal data/settings/helper code**

Implement the new video library entities/DAO/repository with these public names:

```kotlin
enum class VideoSourceType { LOCAL, WEBDAV }

data class VideoLibraryItemWithSources(
    val item: VideoLibraryItemEntity,
    val localSource: LocalVideoSourceEntity?,
    val webDavSource: WebDavVideoSourceEntity?,
)

interface VideoLibraryCatalog {
    fun observeVideoLibrary(): Flow<List<VideoLibraryItemWithSources>>
    suspend fun addLocalVideo(uri: String, fileName: String, size: Long?, lastModified: Long?, thumbnailPath: String? = null): Long
    suspend fun addWebDavVideo(accountId: String, remotePath: String, fileName: String, size: Long?, etag: String?, lastModified: Long?, thumbnailPath: String? = null): Long
    suspend fun markOpened(videoLibraryItemId: Long)
    suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?)
    suspend fun removeVideo(videoLibraryItemId: Long)
}
```

Update `LibraryDatabase` to version `5`, include the three video entities, add `videoLibraryDao()`, and add migration SQL for the new tables and indexes.

Add `videoLibraryThumbnailsEnabled: Boolean = true` to `AppSettings`, read key `video_library_thumbnails_enabled`, and add `updateVideoLibraryThumbnailsEnabled`.

Add `internal fun webDavDisplayPathLabel(path: String): String` in `WebDavBrowserScreen.kt`; decode with `URLDecoder.decode(path, UTF_8)` inside `runCatching`, fallback to original path.

- [ ] **Step 6: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VideoLibraryRepositoryTest' --tests '*AppSettingsStoreTest' --tests '*WebDavBrowserScreenTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/comicdav/data app/src/main/java/com/example/comicdav/feature/webdav app/src/test/java/com/example/comicdav
git commit -m "feat: add video library data model"
```

---

### Task 2: Source Browser Selection Actions and Settings UI

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/ui/ComicDavCopy.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreenTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`

- [ ] **Step 1: Write failing action-list tests**

Add tests that assert:

```kotlin
assertEquals(
    listOf(FileDirectoryEntryMenuAction.AddToVideoLibrary),
    fileDirectoryEntryLongPressActions(localVideoItem),
)
assertEquals(
    listOf(WebDavFileMenuAction.AddToVideoLibrary, WebDavFileMenuAction.DownloadToLocal),
    webDavItemLongPressActions(webDavVideoItem),
)
assertEquals(
    listOf(WebDavFileMenuAction.AddToLibrary, WebDavFileMenuAction.DownloadToLocal),
    webDavItemLongPressActions(webDavComicItem),
)
```

- [ ] **Step 2: Write failing settings grouping test**

Add pure helper functions if needed, then test that `settingsGroupLayout()` returns group titles containing `显示`, `漫画`, `视频`, `下载记录`, `缓存`, and that `"播放器方向"` and `"提取加入影视库的视频缩略图作为封面"` belong to `视频`.

- [ ] **Step 3: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*FileDirectoryScreenTest' --tests '*WebDavBrowserScreenTest' --tests '*SettingsScreenUiTest'`

Expected: FAIL because new actions/group helper do not exist.

- [ ] **Step 4: Implement long-press action enums**

Update file-directory enum:

```kotlin
internal enum class FileDirectoryEntryMenuAction {
    AddToLibrary,
    AddToVideoLibrary,
}
```

Return `AddToLibrary` for comics and `AddToVideoLibrary` for videos.

Update WebDAV enum:

```kotlin
internal enum class WebDavFileMenuAction {
    AddToLibrary,
    AddToVideoLibrary,
    DownloadToLocal,
}
```

Return comic actions unchanged, and video actions as `AddToVideoLibrary, DownloadToLocal`.

- [ ] **Step 5: Implement settings grouping**

Move rows so common display settings stay in `显示`, comic settings live in `漫画`, video settings live in `视频`, and add a switch:

```kotlin
SwitchRow(
    title = "提取加入影视库的视频缩略图作为封面",
    subtitle = "收藏视频时自动提取一帧作为影视库封面",
    checked = settings.videoLibraryThumbnailsEnabled,
    onCheckedChange = onVideoLibraryThumbnailsEnabledChange,
)
```

- [ ] **Step 6: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*FileDirectoryScreenTest' --tests '*WebDavBrowserScreenTest' --tests '*SettingsScreenUiTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/filedirectory app/src/main/java/com/example/comicdav/feature/webdav app/src/main/java/com/example/comicdav/feature/settings app/src/main/java/com/example/comicdav/ui app/src/test/java/com/example/comicdav
git commit -m "feat: update source and settings actions"
```

---

### Task 3: Video Library Screen and ViewModel

**Files:**
- Create: `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryViewModel.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/videolibrary/VideoLibraryViewModelTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreenTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Verify the ViewModel observes catalog items, marks loading false, surfaces messages, and clears messages.

- [ ] **Step 2: Write failing UI helper tests**

Add pure helper tests for:

```kotlin
videoLibraryCountLabel(0) == "还没有视频"
videoLibraryCountLabel(2) == "2 个视频"
videoSourceLabel(VideoSourceType.LOCAL) == "本地"
videoSourceLabel(VideoSourceType.WEBDAV) == "WebDAV"
videoSourceMeta(webDavItem).contains(remotePath)
```

- [ ] **Step 3: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VideoLibraryViewModelTest' --tests '*VideoLibraryScreenTest'`

Expected: FAIL because files do not exist.

- [ ] **Step 4: Implement ViewModel and screen**

Implement `VideoLibraryUiState`, `VideoLibraryViewModel`, and `VideoLibraryScreen` following the shape of `LibraryViewModel` and `LibraryScreen`, but with video copy and thumbnail support. The screen API must include:

```kotlin
fun VideoLibraryScreen(
    uiState: VideoLibraryUiState,
    onOpenItem: (VideoLibraryItemWithSources) -> Unit,
    onSelectItem: (VideoLibraryItemWithSources) -> Unit,
    onOpenDirectories: () -> Unit,
    onDismissMessage: () -> Unit,
    thumbnailsEnabled: Boolean = true,
    selectedItemId: Long? = null,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 5: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VideoLibraryViewModelTest' --tests '*VideoLibraryScreenTest'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/comicdav/feature/videolibrary app/src/test/java/com/example/comicdav/feature/videolibrary
git commit -m "feat: add video library screen"
```

---

### Task 4: Thumbnail Extraction and WebDAV Video Download

**Files:**
- Create: `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoThumbnailExtractor.kt`
- Create: `app/src/main/java/com/example/comicdav/data/VideoDownloadStore.kt`
- Create: `app/src/main/java/com/example/comicdav/data/VideoDownloadCache.kt`
- Test: `app/src/test/java/com/example/comicdav/data/VideoDownloadStoreTest.kt`
- Test: `app/src/test/java/com/example/comicdav/data/VideoDownloadCacheTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/videolibrary/VideoThumbnailExtractorTest.kt`

- [ ] **Step 1: Write failing download store/cache tests**

Test that video download records encode/decode `fileName`, `accountId`, `remotePath`, `localUri`, `sizeBytes`, and `downloadedAtMillis`.

Test that `VideoDownloadCache.downloadWebDavVideo` writes to a temp file first, deletes temp on failure, and returns the final file/URI on success.

- [ ] **Step 2: Write thumbnail extractor tests for failure tolerance**

Test a pure wrapper/helper where extraction failure returns `null` and does not throw to the caller.

- [ ] **Step 3: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VideoDownloadStoreTest' --tests '*VideoDownloadCacheTest' --tests '*VideoThumbnailExtractorTest'`

Expected: FAIL because files do not exist.

- [ ] **Step 4: Implement download store/cache**

Create `VideoDownloadRecord` and `VideoDownloadStore` similar to `DownloadRecordStore`, but keyed for videos and with `localUri`.

Create `VideoDownloadCache` that accepts a target directory `File`, a `WebDavClient`, remote path, file name, expected size, and progress callback. Sanitize the file name for local storage, write `*.tmp`, verify byte count when known, and finalize by rename/copy.

- [ ] **Step 5: Implement thumbnail extractor**

Use `MediaMetadataRetriever` on `content://`, file path, or URL sources. Save thumbnails as JPEG files under `cacheDir/video-library-thumbnails/<stable-key>.jpg`. Wrap extraction with `runCatching`; public methods return `String?` and do not throw.

- [ ] **Step 6: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VideoDownloadStoreTest' --tests '*VideoDownloadCacheTest' --tests '*VideoThumbnailExtractorTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/comicdav/data app/src/main/java/com/example/comicdav/feature/videolibrary app/src/test/java/com/example/comicdav
git commit -m "feat: add video thumbnails and downloads"
```

---

### Task 5: MainActivity Integration and Orientation Fix

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/ui/ComicDavCopy.kt`
- Test: `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`

- [ ] **Step 1: Write failing tests for app tab and selection actions**

Add tests for pure helpers extracted from `MainActivity.kt`:

```kotlin
assertEquals(listOf("来源", "书架", "影视库", "设置"), appTabLabels())
assertEquals(listOf("加入影视库", "下载", "取消"), selectionActionLabelsForWebDavVideo())
assertEquals(listOf("加入影视库", "取消"), selectionActionLabelsForLocalVideo())
assertEquals(listOf("重新提取缩略图", "移除", "删除缩略图", "取消"), selectionActionLabelsForVideoLibraryItem())
```

- [ ] **Step 2: Write failing test for orientation policy**

Add a pure helper:

```kotlin
assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, mainAppRequestedOrientation(screenRotationLockEnabled = false))
assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED, mainAppRequestedOrientation(screenRotationLockEnabled = true))
```

- [ ] **Step 3: Run tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*MainActivityUiLogicTest'`

Expected: FAIL because helpers/integration do not exist.

- [ ] **Step 4: Wire repositories, tab, screen, actions**

Update `MainActivity.kt`:

- Instantiate `VideoLibraryRepository`, `VideoLibraryViewModel`, `VideoThumbnailExtractor`, `VideoDownloadStore`, and `VideoDownloadCache`.
- Add `AppTab.VIDEO_LIBRARY` with copy `影视库`.
- Track `selectedDirectoryVideo`, `selectedWebDavFile` media kind, and `selectedVideoLibraryItem`.
- Add handlers to add local/WebDAV video references without copying/downloading.
- Add explicit `downloadWebDavVideoToLocal` that writes to the selected data folder and stores a video download record.
- Add open handlers for local/WebDAV video library items using existing video player intents/proxy.
- Add thumbnail refresh/delete handlers.
- Pass `onVideoLibraryThumbnailsEnabledChange` into `SettingsScreen`.

- [ ] **Step 5: Reapply main app orientation on resume/settings**

Extract:

```kotlin
internal fun mainAppRequestedOrientation(screenRotationLockEnabled: Boolean): Int =
    if (screenRotationLockEnabled) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
```

Use it in `LaunchedEffect(appSettings.screenRotationLockEnabled)` and in a lifecycle observer or `DisposableEffect` that reapplies the value on `ON_RESUME`.

- [ ] **Step 6: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*MainActivityUiLogicTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/comicdav/MainActivity.kt app/src/main/java/com/example/comicdav/ui/ComicDavCopy.kt app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt
git commit -m "feat: integrate video library"
```

---

### Task 6: Final Integration Verification

**Files:**
- Modify as needed only to resolve integration issues from previous tasks.

- [ ] **Step 1: Run full app unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 2: Run build compile check**

Run: `./gradlew :app:assembleDebug -PtargetAbi=x86_64`

Expected: PASS.

- [ ] **Step 3: Review git diff against design**

Run:

```bash
git diff master...HEAD --stat
git diff master...HEAD -- app/src/main/java app/src/test/java docs/superpowers/plans
```

Check every requirement in `docs/superpowers/specs/2026-05-22-video-library-settings-design.md` has a corresponding implementation or test.

- [ ] **Step 4: Commit any final integration fixes**

If files changed:

```bash
git add <changed-files>
git commit -m "fix: complete video library integration"
```

If no files changed, do not create an empty commit.
