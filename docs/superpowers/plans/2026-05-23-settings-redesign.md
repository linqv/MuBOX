# Settings Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the settings screen so common settings stay on the first-level page while comic and video settings move to second-level pages.

**Architecture:** Keep `SettingsScreen` as the only app-level settings destination and add internal page state for root, comic, video, and download records. Reuse the existing row controls and callbacks so persistence and business behavior stay unchanged.

**Tech Stack:** Kotlin, Android Jetpack Compose, Material 3, JUnit 4, Gradle Android test tasks.

---

## File Structure

- Modify `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`.
  - Owns settings UI hierarchy, internal settings page state, page shell, root page, comic page, video page, download records page, and reusable settings row components.
- Modify `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`.
  - Documents root, comic, and video settings layout metadata.
- Existing `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenTest.kt` remains unchanged.
  - Continues covering coercion helpers and labels.

### Task 1: Update Layout Metadata Tests

**Files:**
- Modify: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`
- Later modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`

- [ ] **Step 1: Write the failing tests**

Replace `SettingsScreenUiTest.kt` with:

```kotlin
package com.example.comicdav.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenUiTest {
    @Test
    fun rootSettingsLayoutKeepsOnlyCommonAndManagementGroups() {
        val layout = rootSettingsGroupLayout()

        assertEquals(
            listOf("通用", "内容设置", "下载记录", "缓存"),
            layout.map { it.title },
        )
    }

    @Test
    fun rootSettingsLayoutLinksToComicAndVideoSettings() {
        val contentRows = rootSettingsGroupLayout().rowsInGroup("内容设置")

        assertEquals(listOf("漫画设置", "视频设置"), contentRows)
    }

    @Test
    fun rootSettingsLayoutDoesNotExposeMediaSpecificRows() {
        val rootRows = rootSettingsGroupLayout().flatMap { it.rows }

        assertFalse(rootRows.contains("阅读方向"))
        assertFalse(rootRows.contains("恢复播放位置"))
        assertFalse(rootRows.contains("MPV Profile"))
        assertTrue(rootRows.contains("配色方案"))
        assertTrue(rootRows.contains("屏幕旋转锁定"))
    }

    @Test
    fun comicSettingsLayoutContainsComicSpecificSettings() {
        val comicRows = comicSettingsGroupLayout().rowsInGroup("漫画设置")

        assertEquals(
            listOf(
                "阅读方向",
                "音量键翻页",
                "WebDAV 预取页数",
                "诊断日志",
                "书架封面",
                "启用自动翻页",
                "翻页速度",
            ),
            comicRows,
        )
    }

    @Test
    fun videoSettingsLayoutContainsVideoSpecificSettings() {
        val videoRows = videoSettingsGroupLayout().rowsInGroup("视频设置")

        assertEquals(
            listOf(
                "恢复播放位置",
                "WebDAV 视频 seek 优化",
                "向前预读",
                "视频代理诊断日志",
                "视频输出 (VO)",
                "GPU API",
                "默认解码器",
                "MPV Profile",
                "控制自动隐藏",
                "播放器方向",
                "提取加入影视库的视频缩略图作为封面",
            ),
            videoRows,
        )
    }

    private fun List<SettingsGroupLayout>.rowsInGroup(title: String): List<String> =
        single { it.title == title }.rows
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: failure because `rootSettingsGroupLayout`, `comicSettingsGroupLayout`, and `videoSettingsGroupLayout` do not exist yet.

### Task 2: Add Layout Metadata Functions

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`

- [ ] **Step 1: Implement the metadata functions**

Replace the existing `settingsGroupLayout()` function with:

```kotlin
internal fun rootSettingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "通用",
            rows = listOf("配色方案", "屏幕旋转锁定"),
        ),
        SettingsGroupLayout(
            title = "内容设置",
            rows = listOf("漫画设置", "视频设置"),
        ),
        SettingsGroupLayout(
            title = "下载记录",
            rows = listOf("下载记录"),
        ),
        SettingsGroupLayout(
            title = "缓存",
            rows = listOf("缓存占用", "远程整本缓存", "WebDAV 索引缓存", "页面图片缓存", "书架封面缓存", "磁盘缓存上限"),
        ),
    )

internal fun comicSettingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "漫画设置",
            rows = listOf(
                "阅读方向",
                "音量键翻页",
                "WebDAV 预取页数",
                "诊断日志",
                "书架封面",
                "启用自动翻页",
                "翻页速度",
            ),
        ),
    )

internal fun videoSettingsGroupLayout(): List<SettingsGroupLayout> =
    listOf(
        SettingsGroupLayout(
            title = "视频设置",
            rows = listOf(
                "恢复播放位置",
                "WebDAV 视频 seek 优化",
                "向前预读",
                "视频代理诊断日志",
                "视频输出 (VO)",
                "GPU API",
                "默认解码器",
                "MPV Profile",
                "控制自动隐藏",
                "播放器方向",
                "提取加入影视库的视频缩略图作为封面",
            ),
        ),
    )
```

- [ ] **Step 2: Run the focused test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: `SettingsScreenUiTest` passes.

### Task 3: Split SettingsScreen Into Root, Comic, Video, And Records Pages

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`

- [ ] **Step 1: Add internal page state**

Add near the layout constants:

```kotlin
private enum class SettingsPage {
    ROOT,
    COMIC,
    VIDEO,
    DOWNLOAD_RECORDS,
}
```

In `SettingsScreen`, replace `var isDownloadRecordsOpen by remember { mutableStateOf(false) }` with:

```kotlin
var currentPage by remember { mutableStateOf(SettingsPage.ROOT) }
```

- [ ] **Step 2: Route pages before rendering root**

At the top of `SettingsScreen`, after `currentPage`, add:

```kotlin
when (currentPage) {
    SettingsPage.DOWNLOAD_RECORDS -> {
        DownloadRecordsScreen(
            records = downloadRecords,
            selectedRecord = selectedDownloadRecord,
            onSelectRecord = onSelectDownloadRecord,
            onBack = {
                onClearSelectedDownloadRecord()
                currentPage = SettingsPage.ROOT
            },
            modifier = modifier,
        )
        return
    }
    SettingsPage.COMIC -> {
        ComicSettingsPage(
            settings = settings,
            onReadingDirectionChange = onReadingDirectionChange,
            onReaderLoggingModeChange = onReaderLoggingModeChange,
            onAutoPageEnabledChange = onAutoPageEnabledChange,
            onAutoPageSpeedChange = onAutoPageSpeedChange,
            onVolumeKeysTurnPagesChange = onVolumeKeysTurnPagesChange,
            onWebDavPrefetchPageCountChange = onWebDavPrefetchPageCountChange,
            onLibraryCoversEnabledChange = onLibraryCoversEnabledChange,
            onBack = { currentPage = SettingsPage.ROOT },
            modifier = modifier,
        )
        return
    }
    SettingsPage.VIDEO -> {
        VideoSettingsPage(
            settings = settings,
            onVideoResumeEnabledChange = onVideoResumeEnabledChange,
            onVideoSeekOptimizationEnabledChange = onVideoSeekOptimizationEnabledChange,
            onVideoForwardPrefetchModeChange = onVideoForwardPrefetchModeChange,
            onVideoProxyDiagnosticsModeChange = onVideoProxyDiagnosticsModeChange,
            onVideoOutputModeChange = onVideoOutputModeChange,
            onGpuApiModeChange = onGpuApiModeChange,
            onVideoDecoderModeChange = onVideoDecoderModeChange,
            onMpvProfileModeChange = onMpvProfileModeChange,
            onVideoControlsAutoHideMillisChange = onVideoControlsAutoHideMillisChange,
            onVideoPlayerOrientationModeChange = onVideoPlayerOrientationModeChange,
            onVideoLibraryThumbnailsEnabledChange = onVideoLibraryThumbnailsEnabledChange,
            onBack = { currentPage = SettingsPage.ROOT },
            modifier = modifier,
        )
        return
    }
    SettingsPage.ROOT -> Unit
}
```

- [ ] **Step 3: Extract shared page shell and header**

Add these helpers below `DownloadRecordRow` and before the pure helper functions:

```kotlin
@Composable
private fun SettingsPageShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun SettingsPageHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "进入",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}
```

- [ ] **Step 4: Keep only root groups in the root page**

Replace the root `Column` in `SettingsScreen` with `SettingsPageShell`. The root content starts with:

```kotlin
SettingsPageShell(modifier = modifier) {
    SettingsPageHeader(
        title = "设置",
        subtitle = "通用设置、内容偏好、缓存和记录",
    )

    SettingsGroup(title = "通用") {
        DropdownRow(
            title = "配色方案",
            selected = settings.colorPalette,
            options = AppColorPalette.entries,
            label = AppColorPalette::label,
            onSelected = onColorPaletteChange,
        )
        SwitchRow(
            title = "屏幕旋转锁定",
            subtitle = "锁定当前屏幕方向",
            checked = settings.screenRotationLockEnabled,
            onCheckedChange = onScreenRotationLockChange,
        )
    }

    SettingsGroup(title = "内容设置") {
        NavigationRow(
            title = "漫画设置",
            subtitle = "阅读方向、翻页、预取、封面和诊断",
            onClick = { currentPage = SettingsPage.COMIC },
        )
        NavigationRow(
            title = "视频设置",
            subtitle = "播放、WebDAV 流式读取、解码和封面",
            onClick = { currentPage = SettingsPage.VIDEO },
        )
    }

    SettingsGroup(title = "下载记录") {
        if (downloadRecords.isEmpty()) {
            StaticInfoRow(
                title = "暂无下载记录",
                subtitle = "从 WebDAV 下载到本地后会显示在这里",
            )
        } else {
            ClickableInfoRow(
                title = "下载记录",
                subtitle = "${downloadRecords.size} 本漫画，点开查看完整记录",
                onClick = { currentPage = SettingsPage.DOWNLOAD_RECORDS },
            )
        }
    }

    SettingsGroup(title = "缓存") {
        StaticInfoRow(
            title = "缓存占用",
            subtitle = formatCacheSize(cacheAnalysis.totalBytes),
        )
        CacheActionRow(
            title = "远程整本缓存",
            subtitle = formatCacheSize(cacheAnalysis.remoteDownloadsBytes),
            enabled = cacheAnalysis.remoteDownloadsBytes > 0L,
            onClear = { onClearCacheCategory(ComicCacheCategory.REMOTE_DOWNLOADS) },
        )
        CacheActionRow(
            title = "WebDAV 索引缓存",
            subtitle = formatCacheSize(cacheAnalysis.remoteIndexBytes),
            enabled = cacheAnalysis.remoteIndexBytes > 0L,
            onClear = { onClearCacheCategory(ComicCacheCategory.REMOTE_INDEX) },
        )
        CacheActionRow(
            title = "页面图片缓存",
            subtitle = formatCacheSize(cacheAnalysis.readerPagesBytes),
            enabled = cacheAnalysis.readerPagesBytes > 0L,
            onClear = { onClearCacheCategory(ComicCacheCategory.READER_PAGES) },
        )
        CacheActionRow(
            title = "书架封面缓存",
            subtitle = formatCacheSize(cacheAnalysis.libraryCoversBytes),
            enabled = cacheAnalysis.libraryCoversBytes > 0L,
            onClear = { onClearCacheCategory(ComicCacheCategory.LIBRARY_COVERS) },
        )
        DiskCacheLimitRow(
            limitMb = settings.diskCacheLimitMb,
            onLimitChange = onDiskCacheLimitChange,
        )
        Text(
            text = cacheActionMessage ?: "清理缓存不会删除书架记录和设置",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

- [ ] **Step 5: Add the subpage composables**

Add `ComicSettingsPage` and `VideoSettingsPage` as private composables using the exact rows that were previously in the root `漫画` and `视频` groups. The signatures must be:

```kotlin
@Composable
private fun ComicSettingsPage(
    settings: AppSettings,
    onReadingDirectionChange: (ReadingDirection) -> Unit,
    onReaderLoggingModeChange: (ReaderLoggingMode) -> Unit,
    onAutoPageEnabledChange: (Boolean) -> Unit,
    onAutoPageSpeedChange: (Int) -> Unit,
    onVolumeKeysTurnPagesChange: (Boolean) -> Unit,
    onWebDavPrefetchPageCountChange: (Int) -> Unit,
    onLibraryCoversEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
private fun VideoSettingsPage(
    settings: AppSettings,
    onVideoResumeEnabledChange: (Boolean) -> Unit,
    onVideoSeekOptimizationEnabledChange: (Boolean) -> Unit,
    onVideoForwardPrefetchModeChange: (VideoForwardPrefetchMode) -> Unit,
    onVideoProxyDiagnosticsModeChange: (VideoProxyDiagnosticsMode) -> Unit,
    onVideoOutputModeChange: (VideoOutputMode) -> Unit,
    onGpuApiModeChange: (GpuApiMode) -> Unit,
    onVideoDecoderModeChange: (VideoDecoderMode) -> Unit,
    onMpvProfileModeChange: (MpvProfileMode) -> Unit,
    onVideoControlsAutoHideMillisChange: (Int) -> Unit,
    onVideoPlayerOrientationModeChange: (VideoPlayerOrientationMode) -> Unit,
    onVideoLibraryThumbnailsEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`ComicSettingsPage` starts with:

```kotlin
SettingsPageShell(modifier = modifier) {
    SettingsPageHeader(
        title = "漫画设置",
        subtitle = "阅读方向、翻页、预取、封面和诊断",
        onBack = onBack,
    )
    SettingsGroup(title = "漫画设置") {
        ChoiceRow(
            title = "阅读方向",
            options = ReadingDirection.entries,
            selected = settings.readingDirection,
            label = ReadingDirection::label,
            onSelected = onReadingDirectionChange,
        )
        SwitchRow(
            title = "音量键翻页",
            subtitle = "使用音量键向前或向后翻页",
            checked = settings.volumeKeysTurnPagesEnabled,
            onCheckedChange = onVolumeKeysTurnPagesChange,
        )
        DropdownRow(
            title = "WebDAV 预取页数",
            selected = settings.webDavPrefetchPageCount,
            options = SupportedWebDavPrefetchPageCounts,
            label = ::webDavPrefetchPageCountLabel,
            onSelected = onWebDavPrefetchPageCountChange,
        )
        ChoiceRow(
            title = "诊断日志",
            options = ReaderLoggingMode.entries,
            selected = settings.readerLoggingMode,
            label = ReaderLoggingMode::label,
            onSelected = onReaderLoggingModeChange,
        )
        SwitchRow(
            title = "书架封面",
            subtitle = "从 WebDAV 漫画提取首图并显示在书架",
            checked = settings.libraryCoversEnabled,
            onCheckedChange = onLibraryCoversEnabledChange,
        )
        SwitchRow(
            title = "启用自动翻页",
            subtitle = "按固定间隔前进到下一页",
            checked = settings.autoPageEnabled,
            onCheckedChange = onAutoPageEnabledChange,
        )
        AutoPageSpeedRow(
            speedMillis = settings.autoPageSpeedMillis,
            onSpeedChange = onAutoPageSpeedChange,
        )
    }
}
```

`VideoSettingsPage` starts with:

```kotlin
SettingsPageShell(modifier = modifier) {
    SettingsPageHeader(
        title = "视频设置",
        subtitle = "播放、WebDAV 流式读取、解码和封面",
        onBack = onBack,
    )
    SettingsGroup(title = "视频设置") {
        SwitchRow(
            title = "恢复播放位置",
            subtitle = "再次打开同一视频时从上次退出位置继续",
            checked = settings.videoResumeEnabled,
            onCheckedChange = onVideoResumeEnabledChange,
        )
        SwitchRow(
            title = "WebDAV 视频 seek 优化",
            subtitle = "缓存小段视频并合并重复 seek 请求",
            checked = settings.videoSeekOptimizationEnabled,
            onCheckedChange = onVideoSeekOptimizationEnabledChange,
        )
        DropdownRow(
            title = "向前预读",
            selected = settings.videoForwardPrefetchMode,
            options = VideoForwardPrefetchMode.entries,
            label = VideoForwardPrefetchMode::label,
            onSelected = onVideoForwardPrefetchModeChange,
        )
        DropdownRow(
            title = "视频代理诊断日志",
            selected = settings.videoProxyDiagnosticsMode,
            options = VideoProxyDiagnosticsMode.entries,
            label = VideoProxyDiagnosticsMode::label,
            onSelected = onVideoProxyDiagnosticsModeChange,
        )
        DropdownRow(
            title = "视频输出 (VO)",
            selected = settings.videoOutputMode,
            options = VideoOutputMode.entries,
            label = ::videoOutputModeLabel,
            onSelected = onVideoOutputModeChange,
        )
        DropdownRow(
            title = "GPU API",
            selected = settings.gpuApiMode,
            options = GpuApiMode.entries,
            label = ::gpuApiModeLabel,
            onSelected = onGpuApiModeChange,
        )
        DropdownRow(
            title = "默认解码器",
            selected = settings.videoDecoderMode,
            options = VideoDecoderMode.entries,
            label = ::videoDecoderModeLabel,
            onSelected = onVideoDecoderModeChange,
        )
        DropdownRow(
            title = "MPV Profile",
            selected = settings.mpvProfileMode,
            options = MpvProfileMode.entries,
            label = ::mpvProfileModeLabel,
            onSelected = onMpvProfileModeChange,
        )
        DropdownRow(
            title = "控制自动隐藏",
            selected = settings.videoControlsAutoHideMillis,
            options = playerControlAutoHideOptionsMillis(),
            label = ::playerControlAutoHideLabel,
            onSelected = onVideoControlsAutoHideMillisChange,
        )
        DropdownRow(
            title = "播放器方向",
            selected = settings.videoPlayerOrientationMode,
            options = VideoPlayerOrientationMode.entries,
            label = ::videoPlayerOrientationModeLabel,
            onSelected = onVideoPlayerOrientationModeChange,
        )
        SwitchRow(
            title = "提取加入影视库的视频缩略图作为封面",
            subtitle = "收藏视频时自动提取一帧作为影视库封面",
            checked = settings.videoLibraryThumbnailsEnabled,
            onCheckedChange = onVideoLibraryThumbnailsEnabledChange,
        )
    }
}
```

- [ ] **Step 6: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest --tests com.example.comicdav.feature.settings.SettingsScreenTest
```

Expected: both focused settings test classes pass.

### Task 4: Compile And Verify The App Tests

**Files:**
- Verify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Verify: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`

- [ ] **Step 1: Run the app unit test task**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: task exits with code 0.

- [ ] **Step 2: Inspect the final diff**

Run:

```bash
git diff -- app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt
```

Expected: diff only reorganizes settings UI hierarchy and updates layout tests.

- [ ] **Step 3: Commit the implementation**

Run:

```bash
git add app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt
git add -f docs/superpowers/plans/2026-05-23-settings-redesign.md
git commit -m "feat: redesign settings hierarchy"
```
