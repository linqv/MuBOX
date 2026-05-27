# MuBOX Media Design System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace MuBOX's default Material 3 component look with a Compose-based MuBOX media design system while preserving existing source browsing, library, settings, and player behavior.

**Architecture:** Add a shared design-system layer under `com.example.comicdav.ui`, then migrate feature screens to consume shared tokens and components. Execute in waves: foundation first, screen migrations in parallel with disjoint file ownership, then integration verification.

**Tech Stack:** Android Kotlin, Jetpack Compose, Material 3 primitives, JUnit JVM tests, Gradle.

---

## File Structure

- Create: `app/src/main/java/com/example/comicdav/ui/MuBoxDesignSystem.kt`
  - Owns MuBOX token data classes, derived token helpers, media type labels, poster aspect-ratio helper, and player token helpers.
- Create: `app/src/main/java/com/example/comicdav/ui/MuBoxComponents.kt`
  - Owns stateless shared Compose components: page header, message panel, empty state, dense media row, media icon, poster card, settings group, settings rows, and player overlay primitives.
- Create: `app/src/test/java/com/example/comicdav/ui/MuBoxDesignSystemTest.kt`
  - Verifies token derivation, media labels, poster aspect ratios, and touch-size constants.
- Modify: `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`
  - Keeps Material 3 theme but exposes default palette and typography that support the new tokens.
- Modify: `app/src/main/java/com/example/comicdav/AppNavigation.kt`
  - Migrates bottom navigation, selection action bar, and data-folder gate to MuBOX components.
- Test: `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`
  - Extends current app-shell contracts without changing tab/action behavior.
- Modify: `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`
  - Migrates local source home, browse header, source rows, entry rows, badges, and source management presentation.
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
  - Migrates WebDAV app bar, path bar, item rows, media icons, transfer panel, and progress treatment.
- Test: `app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreenTest.kt`
  - Preserves local source helper contracts and verifies token usage.
- Test: `app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt`
  - Preserves WebDAV helper contracts and verifies token usage.
- Modify: `app/src/main/java/com/example/comicdav/feature/library/LibraryScreen.kt`
  - Migrates comic library to shared poster card and message/empty-state components.
- Modify: `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt`
  - Migrates video library to shared poster card and message/empty-state components.
- Test: `app/src/test/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreenTest.kt`
  - Preserves video labels/metadata and verifies poster-card contracts.
- Create: `app/src/test/java/com/example/comicdav/feature/library/LibraryScreenTest.kt`
  - Verifies comic library poster-card contracts.
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
  - Migrates settings shells, groups, rows, choices, dropdowns, sliders, cache rows, and download records to MuBOX setting components.
- Test: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`
  - Preserves settings group and row coverage.
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt`
  - Migrates player overlay color constants and primitives to shared MuBOX player tokens/components.
- Test: `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`
  - Preserves player labels, sizes, gesture hit areas, and routing contracts.

## Coordination Rules

- Wave 0 foundation must finish before screen agents start.
- Wave 1 agents may run in parallel only after Wave 0 passes tests.
- Each Wave 1 agent owns only its assigned files.
- If a shared component API is insufficient, the agent records the needed API change and stops; the coordinator updates shared files before the agent resumes.
- Do not edit ViewModels, repositories, Room entities, WebDAV networking/proxy code, mpv controller code, media detection code, or reader logic.
- Do not add dependencies.
- Use `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` for Gradle commands.
- Commit once per task after tests for that task pass.

---

### Task 1: Wave 0 Foundation Tokens And Shared Components

**Files:**
- Create: `app/src/test/java/com/example/comicdav/ui/MuBoxDesignSystemTest.kt`
- Create: `app/src/main/java/com/example/comicdav/ui/MuBoxDesignSystem.kt`
- Create: `app/src/main/java/com/example/comicdav/ui/MuBoxComponents.kt`
- Modify: `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`

- [ ] **Step 1: Write failing token tests**

Create `app/src/test/java/com/example/comicdav/ui/MuBoxDesignSystemTest.kt` with this content:

```kotlin
package com.example.comicdav.ui

import androidx.compose.ui.graphics.luminance
import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuBoxDesignSystemTest {
    @Test
    fun designTokensDeriveCinemaConsoleRolesFromTheme() {
        val scheme = comicDavColorSchemeFor(AppColorPalette.DEFAULT)
        val colors = muBoxColorsFor(scheme)

        assertEquals(scheme.background, colors.background)
        assertEquals(scheme.surfaceContainer, colors.panel)
        assertEquals(scheme.surfaceContainerHigh, colors.panelHigh)
        assertEquals(scheme.primary, colors.mediaAccent)
        assertEquals(scheme.secondary, colors.comicAccent)
        assertEquals(scheme.tertiary, colors.statusAccent)
        assertTrue(colors.background.luminance() < 0.08f)
        assertTrue(colors.text.luminance() > colors.background.luminance())
    }

    @Test
    fun mediaTypeLabelsRemainStableForRowsAndIcons() {
        assertEquals("文件夹", muBoxMediaKindLabel(MediaKind.Directory))
        assertEquals("漫画文件", muBoxMediaKindLabel(MediaKind.Comic))
        assertEquals("视频文件", muBoxMediaKindLabel(MediaKind.Video))
        assertEquals("字幕文件", muBoxMediaKindLabel(MediaKind.Subtitle))
        assertEquals("音频文件", muBoxMediaKindLabel(MediaKind.Audio))
        assertEquals("文件", muBoxMediaKindLabel(MediaKind.Unknown))
    }

    @Test
    fun posterKindsExposeStableAspectRatios() {
        assertEquals(0.72f, muBoxPosterAspectRatio(MuBoxPosterKind.Comic), 0.001f)
        assertEquals(16f / 9f, muBoxPosterAspectRatio(MuBoxPosterKind.Video), 0.001f)
    }

    @Test
    fun sharedTouchTargetsStayAccessible() {
        assertEquals(44, MuBoxMetrics.MinTouchTargetDp)
        assertEquals(14, MuBoxMetrics.DenseRowCornerDp)
        assertEquals(22, MuBoxMetrics.PlayerPanelCornerDp)
        assertEquals(64, MuBoxMetrics.PlayerCenterControlVisualDp)
        assertEquals(80, MuBoxMetrics.PlayerCenterControlTouchDp)
    }
}
```

- [ ] **Step 2: Run token test and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.ui.MuBoxDesignSystemTest
```

Expected: FAIL because `muBoxColorsFor`, `muBoxMediaKindLabel`, `MuBoxPosterKind`, `muBoxPosterAspectRatio`, and `MuBoxMetrics` do not exist.

- [ ] **Step 3: Add design-system token implementation**

Create `app/src/main/java/com/example/comicdav/ui/MuBoxDesignSystem.kt` with this content:

```kotlin
package com.example.comicdav.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.example.comicdav.video.MediaKind

internal data class MuBoxColors(
    val background: Color,
    val panel: Color,
    val panelHigh: Color,
    val row: Color,
    val rowSelected: Color,
    val border: Color,
    val selectedBorder: Color,
    val mediaAccent: Color,
    val onMediaAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val comicAccent: Color,
    val statusAccent: Color,
    val text: Color,
    val muted: Color,
    val overlayText: Color,
    val playerOverlay: Color,
    val playerSheet: Color,
    val playerChip: Color,
    val playerChipSelected: Color,
    val playerProgressTrack: Color,
    val playerProgress: Color,
    val playerHud: Color,
    val errorSurface: Color,
    val errorText: Color,
)

internal fun muBoxColorsFor(colorScheme: ColorScheme): MuBoxColors =
    MuBoxColors(
        background = colorScheme.background,
        panel = colorScheme.surfaceContainer,
        panelHigh = colorScheme.surfaceContainerHigh,
        row = colorScheme.surfaceContainer,
        rowSelected = colorScheme.primaryContainer,
        border = colorScheme.outlineVariant,
        selectedBorder = colorScheme.primary,
        mediaAccent = colorScheme.primary,
        onMediaAccent = colorScheme.onPrimary,
        accentSoft = colorScheme.primaryContainer,
        onAccentSoft = colorScheme.onPrimaryContainer,
        comicAccent = colorScheme.secondary,
        statusAccent = colorScheme.tertiary,
        text = colorScheme.onBackground,
        muted = colorScheme.onSurfaceVariant,
        overlayText = Color(0xFFEAF7FF),
        playerOverlay = Color(0xB30A1628),
        playerSheet = Color(0xE60B1729),
        playerChip = Color(0x66142A46),
        playerChipSelected = colorScheme.primary,
        playerProgressTrack = Color(0x4DE0F7FF),
        playerProgress = Color(0xFF38E8FF),
        playerHud = Color(0xE6071527),
        errorSurface = colorScheme.errorContainer,
        errorText = colorScheme.onErrorContainer,
    )

internal object MuBoxMetrics {
    const val MinTouchTargetDp = 44
    const val DenseRowCornerDp = 14
    const val PanelCornerDp = 20
    const val PlayerPanelCornerDp = 22
    const val PlayerCenterControlVisualDp = 64
    const val PlayerCenterControlTouchDp = 80
}

internal enum class MuBoxPosterKind {
    Comic,
    Video,
}

internal fun muBoxPosterAspectRatio(kind: MuBoxPosterKind): Float =
    when (kind) {
        MuBoxPosterKind.Comic -> 0.72f
        MuBoxPosterKind.Video -> 16f / 9f
    }

internal fun muBoxMediaKindLabel(mediaKind: MediaKind): String =
    when (mediaKind) {
        MediaKind.Directory -> "文件夹"
        MediaKind.Comic -> "漫画文件"
        MediaKind.Video -> "视频文件"
        MediaKind.Subtitle -> "字幕文件"
        MediaKind.Audio -> "音频文件"
        MediaKind.Unknown -> "文件"
    }
```

- [ ] **Step 4: Add shared component primitives**

Create `app/src/main/java/com/example/comicdav/ui/MuBoxComponents.kt` with this content:

```kotlin
package com.example.comicdav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comicdav.video.MediaKind

@Composable
internal fun rememberMuBoxColors(): MuBoxColors =
    muBoxColorsFor(MaterialTheme.colorScheme)

@Composable
internal fun MuBoxPageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MuBoxMetrics.PanelCornerDp.dp),
        color = colors.panel,
        contentColor = colors.text,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
internal fun MuBoxMessagePanel(
    text: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MuBoxMetrics.DenseRowCornerDp.dp),
        color = if (isError) colors.errorSurface else colors.panelHigh,
        contentColor = if (isError) colors.errorText else colors.text,
        border = BorderStroke(1.dp, if (isError) colors.errorText.copy(alpha = 0.35f) else colors.border),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = MuBoxMetrics.MinTouchTargetDp.dp)) {
                    Text("知道了")
                }
            }
        }
    }
}

@Composable
internal fun MuBoxEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 20.dp)
                .size(76.dp)
                .background(
                    brush = Brush.linearGradient(listOf(colors.panelHigh, colors.accentSoft)),
                    shape = CircleShape,
                )
                .border(1.dp, colors.mediaAccent.copy(alpha = 0.42f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.mediaAccent, modifier = Modifier.size(34.dp))
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = colors.text, fontWeight = FontWeight.SemiBold)
        Text(
            text = body,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text(actionLabel)
        }
    }
}

@Composable
internal fun MuBoxDenseMediaRow(
    title: String,
    subtitle: String?,
    mediaKind: MediaKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(MuBoxMetrics.DenseRowCornerDp.dp),
        color = if (selected) colors.rowSelected else colors.row,
        contentColor = colors.text,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) colors.selectedBorder else colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuBoxMediaTypeIcon(mediaKind = mediaKind)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
internal fun MuBoxMediaTypeIcon(mediaKind: MediaKind, modifier: Modifier = Modifier) {
    val colors = rememberMuBoxColors()
    val container = when (mediaKind) {
        MediaKind.Directory, MediaKind.Video -> colors.accentSoft
        MediaKind.Comic -> colors.panelHigh
        MediaKind.Subtitle -> colors.panelHigh
        MediaKind.Audio, MediaKind.Unknown -> colors.panelHigh
    }
    val content = when (mediaKind) {
        MediaKind.Directory, MediaKind.Video -> colors.onAccentSoft
        MediaKind.Comic -> colors.comicAccent
        MediaKind.Subtitle -> colors.mediaAccent
        MediaKind.Audio, MediaKind.Unknown -> colors.muted
    }
    val icon = when (mediaKind) {
        MediaKind.Directory -> Icons.Rounded.Folder
        MediaKind.Video -> Icons.Rounded.PlayCircle
        MediaKind.Subtitle -> Icons.Rounded.Subtitles
        MediaKind.Comic, MediaKind.Audio, MediaKind.Unknown -> Icons.AutoMirrored.Rounded.MenuBook
    }
    Box(
        modifier = modifier.size(MuBoxMetrics.MinTouchTargetDp.dp).background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = muBoxMediaKindLabel(mediaKind), tint = content, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun MuBoxSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = colors.mediaAccent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(MuBoxMetrics.DenseRowCornerDp.dp),
            color = colors.panel,
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp), content = { content() })
        }
    }
}

@Composable
internal fun MuBoxPlayerPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier,
        color = colors.playerSheet,
        contentColor = colors.overlayText,
        shape = RoundedCornerShape(MuBoxMetrics.PlayerPanelCornerDp.dp),
        border = BorderStroke(1.dp, colors.mediaAccent.copy(alpha = 0.2f)),
        content = { content() },
    )
}
```

- [ ] **Step 5: Run token tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.ui.MuBoxDesignSystemTest
```

Expected: PASS.

- [ ] **Step 6: Run existing app-shell tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: PASS.

- [ ] **Step 7: Commit foundation**

```bash
git add app/src/main/java/com/example/comicdav/ui/MuBoxDesignSystem.kt app/src/main/java/com/example/comicdav/ui/MuBoxComponents.kt app/src/test/java/com/example/comicdav/ui/MuBoxDesignSystemTest.kt app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt
git commit -m "feat: add mubox media design system foundation"
```

---

### Task 2: Wave 1A App Shell And Data-Folder Gate

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/AppNavigation.kt`
- Test: `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`

- [ ] **Step 1: Add failing app-shell token contract test**

In `app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt`, add this test inside `MainActivityUiLogicTest`:

```kotlin
@Test
fun appShellUsesMuBoxMediaSurfaceRoles() {
    val colors = comicDavColorSchemeFor(AppColorPalette.DEFAULT)
    val muBoxColors = com.example.comicdav.ui.muBoxColorsFor(colors)

    assertEquals(muBoxColors.background, appShellBackgroundColor(colors))
    assertEquals(muBoxColors.panel, appShellNavigationBarContainerColor(colors))
    assertEquals(muBoxColors.panelHigh, selectionNavigationBarContainerColor(colors))
}
```

- [ ] **Step 2: Run app-shell test and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: FAIL because `appShellBackgroundColor` does not exist, and existing navigation helpers still return old surface roles.

- [ ] **Step 3: Implement app-shell helper functions**

In `app/src/main/java/com/example/comicdav/AppNavigation.kt`, add the import:

```kotlin
import com.example.comicdav.ui.muBoxColorsFor
```

Replace the existing app-shell color helpers with:

```kotlin
internal fun appShellBackgroundColor(colorScheme: ColorScheme) =
    muBoxColorsFor(colorScheme).background

internal fun appShellNavigationBarContainerColor(colorScheme: ColorScheme) =
    muBoxColorsFor(colorScheme).panel

internal fun selectionNavigationBarContainerColor(colorScheme: ColorScheme) =
    muBoxColorsFor(colorScheme).panelHigh
```

- [ ] **Step 4: Use app-shell background helper**

In `ComicDavAppShell`, replace:

```kotlin
.background(MaterialTheme.colorScheme.background)
```

with:

```kotlin
.background(appShellBackgroundColor(MaterialTheme.colorScheme))
```

- [ ] **Step 5: Refresh data-folder gate with MuBOX colors**

In `DataFolderGateScreen`, add:

```kotlin
val colors = com.example.comicdav.ui.rememberMuBoxColors()
```

Then use:

```kotlin
.background(colors.background)
```

for the root background, and replace text colors with `colors.text` and `colors.muted`. Keep the existing button callback and text unchanged.

- [ ] **Step 6: Run app-shell tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.MainActivityUiLogicTest
```

Expected: PASS.

- [ ] **Step 7: Commit app shell**

```bash
git add app/src/main/java/com/example/comicdav/AppNavigation.kt app/src/test/java/com/example/comicdav/MainActivityUiLogicTest.kt
git commit -m "feat: migrate app shell to mubox media system"
```

---

### Task 3: Wave 1B Sources And WebDAV Browsers

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreenTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt`

- [ ] **Step 1: Add failing local browser media-label contract test**

In `FileDirectoryScreenTest`, add this test:

Add this import if it is not already present:

```kotlin
import com.example.comicdav.video.MediaKind
```

```kotlin
@Test
fun entryTypeContentDescriptionsUseSharedMediaLabels() {
    MediaKind.entries.forEach { mediaKind ->
        assertEquals(
            com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind),
            fileDirectoryEntryTypeContentDescription(mediaKind),
        )
    }
}
```

- [ ] **Step 2: Add failing WebDAV browser media-label contract test**

In `WebDavBrowserScreenTest`, add this test:

Add this import if it is not already present:

```kotlin
import com.example.comicdav.video.MediaKind
```

```kotlin
@Test
fun itemTypeContentDescriptionsUseSharedMediaLabels() {
    MediaKind.entries.forEach { mediaKind ->
        assertEquals(
            com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind),
            webDavItemTypeContentDescription(mediaKind),
        )
    }
}
```

- [ ] **Step 3: Run browser tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.filedirectory.FileDirectoryScreenTest --tests com.example.comicdav.feature.webdav.WebDavBrowserScreenTest
```

Expected: FAIL because `fileDirectoryEntryTypeContentDescription` and `webDavItemTypeContentDescription` do not exist.

- [ ] **Step 4: Update local browser color helper**

In `FileDirectoryScreen.kt`, add:

```kotlin
import com.example.comicdav.ui.muBoxColorsFor
```

Replace `fileDirectoryScreenColors` with:

```kotlin
internal fun fileDirectoryScreenColors(colorScheme: ColorScheme): FileDirectoryScreenColors {
    val tokens = muBoxColorsFor(colorScheme)
    return FileDirectoryScreenColors(
        background = tokens.background,
        panel = tokens.panel,
        panelHigh = tokens.panelHigh,
        row = tokens.row,
        rowSelected = tokens.rowSelected,
        border = tokens.border,
        selectedBorder = tokens.selectedBorder,
        accent = tokens.mediaAccent,
        onAccent = tokens.onMediaAccent,
        accentSoft = tokens.accentSoft,
        onAccentSoft = tokens.onAccentSoft,
        purple = tokens.comicAccent,
        text = tokens.text,
        muted = tokens.muted,
        noticeContainer = tokens.panelHigh,
        errorContainer = tokens.errorSurface,
        errorText = tokens.errorText,
        sourceBadgeContainer = tokens.accentSoft,
        sourceBadgeContent = tokens.onAccentSoft,
    )
}
```

- [ ] **Step 5: Update WebDAV browser color helper**

In `WebDavBrowserScreen.kt`, add:

```kotlin
import com.example.comicdav.ui.muBoxColorsFor
```

Replace `webDavScreenColors` with:

```kotlin
internal fun webDavScreenColors(colorScheme: ColorScheme): WebDavScreenColors {
    val tokens = muBoxColorsFor(colorScheme)
    return WebDavScreenColors(
        background = tokens.background,
        panel = tokens.panel,
        panelHigh = tokens.panelHigh,
        row = tokens.row,
        rowSelected = tokens.rowSelected,
        border = tokens.border,
        selectedBorder = tokens.selectedBorder,
        accent = tokens.mediaAccent,
        onAccent = tokens.onMediaAccent,
        accentSoft = tokens.accentSoft,
        onAccentSoft = tokens.onAccentSoft,
        purple = tokens.comicAccent,
        text = tokens.text,
        muted = tokens.muted,
        progressTrack = tokens.playerProgressTrack,
        errorText = colorScheme.error,
    )
}
```

- [ ] **Step 6: Migrate row visuals without changing behavior**

In `FileDirectoryScreen.kt`, keep `combinedClickable` blocks intact. Add this helper near `entryIconColors`:

```kotlin
internal fun fileDirectoryEntryTypeContentDescription(mediaKind: MediaKind): String =
    com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind)
```

Replace local icon content descriptions in `EntryTypeIcon` with:

```kotlin
contentDescription = fileDirectoryEntryTypeContentDescription(mediaKind)
```

In `WebDavBrowserScreen.kt`, add this helper near `webDavIconColors`:

```kotlin
internal fun webDavItemTypeContentDescription(mediaKind: MediaKind): String =
    com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind)
```

Replace the `contentDescription` `when` in `WebDavItemTypeIcon` with:

```kotlin
val contentDescription = webDavItemTypeContentDescription(mediaKind)
```

Keep row callbacks unchanged.

- [ ] **Step 7: Run browser tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.filedirectory.FileDirectoryScreenTest --tests com.example.comicdav.feature.webdav.WebDavBrowserScreenTest --tests com.example.comicdav.feature.filedirectory.FileDirectoryMediaFilterTest --tests com.example.comicdav.feature.filedirectory.FileDirectoryScreenBehaviorTest
```

Expected: PASS.

- [ ] **Step 8: Commit sources and WebDAV**

```bash
git add app/src/main/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreen.kt app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt app/src/test/java/com/example/comicdav/feature/filedirectory/FileDirectoryScreenTest.kt app/src/test/java/com/example/comicdav/feature/webdav/WebDavBrowserScreenTest.kt
git commit -m "feat: migrate source browsers to mubox media system"
```

---

### Task 4: Wave 1C Library And Video Library Cards

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt`
- Create: `app/src/test/java/com/example/comicdav/feature/library/LibraryScreenTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreenTest.kt`

- [ ] **Step 1: Add failing video poster-kind contract test**

In `VideoLibraryScreenTest`, add:

```kotlin
@Test
fun videoLibraryUsesVideoPosterKind() {
    assertEquals(com.example.comicdav.ui.MuBoxPosterKind.Video, videoLibraryPosterKind())
}
```

- [ ] **Step 2: Add failing comic poster-kind contract test**

Create `app/src/test/java/com/example/comicdav/feature/library/LibraryScreenTest.kt` with:

```kotlin
package com.example.comicdav.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScreenTest {
    @Test
    fun comicLibraryUsesComicPosterKind() {
        assertEquals(com.example.comicdav.ui.MuBoxPosterKind.Comic, libraryPosterKind())
    }
}
```

- [ ] **Step 3: Run library tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.videolibrary.VideoLibraryScreenTest --tests com.example.comicdav.feature.library.LibraryScreenTest
```

Expected: FAIL because `videoLibraryPosterKind` and `libraryPosterKind` do not exist.

- [ ] **Step 4: Update video library colors to MuBOX tokens**

In `VideoLibraryScreen.kt`, add:

```kotlin
import com.example.comicdav.ui.muBoxColorsFor
```

Replace `videoLibraryScreenColors` with:

```kotlin
internal fun videoLibraryScreenColors(colorScheme: ColorScheme): VideoLibraryScreenColors {
    val tokens = muBoxColorsFor(colorScheme)
    return VideoLibraryScreenColors(
        backgroundTop = tokens.background,
        backgroundBottom = colorScheme.surfaceContainerLowest,
        surface = tokens.panel,
        surfaceRaised = tokens.panelHigh,
        posterTop = colorScheme.surfaceVariant,
        posterBottom = colorScheme.surfaceContainerLowest,
        accent = tokens.mediaAccent,
        onAccent = tokens.onMediaAccent,
        text = tokens.text,
        muted = tokens.muted,
        errorSurface = tokens.errorSurface,
        errorText = tokens.errorText,
        border = tokens.border,
        thumbnailScrim = colorScheme.scrim,
        onThumbnailScrim = colorScheme.inverseOnSurface,
    )
}
```

- [ ] **Step 5: Add and use video poster-kind helper**

In `VideoLibraryScreen.kt`, add this helper near `videoLibraryCountLabel`:

```kotlin
internal fun videoLibraryPosterKind(): com.example.comicdav.ui.MuBoxPosterKind =
    com.example.comicdav.ui.MuBoxPosterKind.Video
```

Then in `VideoLibraryCard`, replace:

```kotlin
.aspectRatio(16f / 9f)
```

with:

```kotlin
.aspectRatio(com.example.comicdav.ui.muBoxPosterAspectRatio(videoLibraryPosterKind()))
```

- [ ] **Step 6: Add and use comic poster-kind helper**

In `LibraryScreen.kt`, add this helper near `libraryCountLabel`:

```kotlin
internal fun libraryPosterKind(): com.example.comicdav.ui.MuBoxPosterKind =
    com.example.comicdav.ui.MuBoxPosterKind.Comic
```

Then in `LibraryCard`, replace:

```kotlin
.aspectRatio(0.72f)
```

with:

```kotlin
.aspectRatio(com.example.comicdav.ui.muBoxPosterAspectRatio(libraryPosterKind()))
```

- [ ] **Step 7: Refresh message panels using shared component**

In both `LibraryScreen.kt` and `VideoLibraryScreen.kt`, replace the custom `Surface` message block with:

```kotlin
com.example.comicdav.ui.MuBoxMessagePanel(
    text = uiState.error ?: uiState.message.orEmpty(),
    isError = uiState.error != null,
    onDismiss = onDismissMessage,
)
```

Keep the `if (uiState.message != null || uiState.error != null)` guard unchanged.

- [ ] **Step 8: Run library tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.videolibrary.VideoLibraryScreenTest --tests com.example.comicdav.feature.library.LibraryScreenTest
```

Expected: PASS.

- [ ] **Step 9: Commit library migration**

```bash
git add app/src/main/java/com/example/comicdav/feature/library/LibraryScreen.kt app/src/main/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreen.kt app/src/test/java/com/example/comicdav/feature/library/LibraryScreenTest.kt app/src/test/java/com/example/comicdav/feature/videolibrary/VideoLibraryScreenTest.kt
git commit -m "feat: migrate media libraries to mubox poster system"
```

---

### Task 5: Wave 1D Settings Panels

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt`

- [ ] **Step 1: Add failing settings row metric contract test**

In `SettingsScreenUiTest`, add this test:

```kotlin
@Test
fun settingsRowsExposeStableControlPanelMetrics() {
    assertEquals(64, settingsControlRowMinHeightDp())
    assertEquals(58, settingsStaticRowMinHeightDp())
}
```

- [ ] **Step 2: Run settings UI tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: FAIL because `settingsControlRowMinHeightDp` and `settingsStaticRowMinHeightDp` do not exist.

- [ ] **Step 3: Migrate settings page shell to MuBOX colors**

In `SettingsScreen.kt`, add:

```kotlin
import com.example.comicdav.ui.MuBoxSettingsGroup
import com.example.comicdav.ui.rememberMuBoxColors
```

In `SettingsPageShell`, add:

```kotlin
val colors = rememberMuBoxColors()
```

Replace:

```kotlin
.background(MaterialTheme.colorScheme.background)
```

with:

```kotlin
.background(colors.background)
```

- [ ] **Step 4: Migrate settings group wrapper**

Replace the body of private `SettingsGroup` with:

```kotlin
MuBoxSettingsGroup(
    title = title,
    modifier = modifier,
    content = content,
)
```

Keep the `SettingsGroup` function name so all existing call sites remain unchanged.

- [ ] **Step 5: Use MuBOX text colors in setting rows**

Add these helpers near `SettingsGroup`:

```kotlin
internal fun settingsControlRowMinHeightDp(): Int = 64

internal fun settingsStaticRowMinHeightDp(): Int = 58
```

In `NavigationRow`, `SwitchRow`, `StaticInfoRow`, and `CacheActionRow`, add local:

```kotlin
val colors = rememberMuBoxColors()
```

Use `colors.text` for primary text and `colors.muted` for subtitles. Keep all callbacks, row heights, and controls unchanged.

Replace hardcoded row minimum heights with the helpers:

```kotlin
.heightIn(min = settingsControlRowMinHeightDp().dp)
```

for interactive rows, and:

```kotlin
.heightIn(min = settingsStaticRowMinHeightDp().dp)
```

for static rows.

- [ ] **Step 6: Run settings tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest --tests com.example.comicdav.feature.settings.SettingsScreenTest
```

Expected: PASS.

- [ ] **Step 7: Commit settings migration**

```bash
git add app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt
git commit -m "feat: migrate settings to mubox control panels"
```

---

### Task 6: Wave 1E Video Player Overlay

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`

- [ ] **Step 1: Add failing player token contract test**

In `PlayerOptionPanelUiTest`, add:

```kotlin
@Test
fun playerSizingMatchesMuBoxMediaTokens() {
    assertEquals(com.example.comicdav.ui.MuBoxMetrics.PlayerCenterControlTouchDp, PLAYER_CENTER_PLAY_BUTTON_TOUCH_SIZE_DP)
    assertEquals(com.example.comicdav.ui.MuBoxMetrics.PlayerCenterControlVisualDp, PLAYER_CENTER_PLAY_BUTTON_VISUAL_SIZE_DP)
    assertEquals(com.example.comicdav.ui.MuBoxMetrics.PlayerPanelCornerDp, PLAYER_PANEL_CORNER_DP)
}
```

- [ ] **Step 2: Run player UI test and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest
```

Expected: FAIL because `PLAYER_PANEL_CORNER_DP` does not exist.

- [ ] **Step 3: Add player panel token constant**

In `VideoPlayerControls.kt`, add:

```kotlin
internal const val PLAYER_PANEL_CORNER_DP = com.example.comicdav.ui.MuBoxMetrics.PlayerPanelCornerDp
```

Replace `RoundedCornerShape(22.dp)` usages for player bottom controls and option sheet with:

```kotlin
RoundedCornerShape(PLAYER_PANEL_CORNER_DP.dp)
```

- [ ] **Step 4: Map player colors through MuBOX token names**

Replace the player color constants at the bottom of `VideoPlayerControls.kt` with:

```kotlin
internal val PlayerOverlayColor = Color(0xB30A1628)
internal val PlayerSheetColor = Color(0xE60B1729)
internal val PlayerAccentColor = Color(0xFF22D3EE)
internal val PlayerOnAccentColor = Color(0xFF03131D)
internal val PlayerCenterPlayButtonColor = Color(0xB30A1E32)
internal val PlayerProgressTrackColor = Color(0x4DE0F7FF)
internal val PlayerProgressColor = Color(0xFF38E8FF)
internal val PlayerChipColor = Color(0x66142A46)
internal val PlayerChipSelectedColor = PlayerAccentColor
```

This keeps existing behavior stable while making the selected chip explicitly share the player accent role.

- [ ] **Step 5: Use shared player panel primitive**

In `PlayerBottomControls`, replace:

```kotlin
Surface(
    modifier = Modifier.fillMaxWidth(),
    color = PlayerSheetColor,
    contentColor = Color.White,
    shape = RoundedCornerShape(PLAYER_PANEL_CORNER_DP.dp),
    border = BorderStroke(1.dp, PlayerAccentColor.copy(alpha = 0.18f)),
) {
```

with:

```kotlin
com.example.comicdav.ui.MuBoxPlayerPanel(
    modifier = Modifier.fillMaxWidth(),
) {
```

Keep the existing `Column` content unchanged.

In `PlayerOptionSheet`, replace the outer `Surface` with:

```kotlin
com.example.comicdav.ui.MuBoxPlayerPanel(
    modifier = modifier.widthIn(min = 220.dp, max = 360.dp),
) {
```

Keep the existing `Column` content unchanged.

- [ ] **Step 6: Run player tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest
```

Expected: PASS.

- [ ] **Step 7: Commit player overlay migration**

```bash
git add app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt
git commit -m "feat: migrate player overlay to mubox media tokens"
```

---

### Task 7: Wave 2 Integration And Verification

**Files:**
- Inspect: all files changed by Tasks 1-6
- Modify: only files needed to resolve compile errors from shared APIs

- [ ] **Step 1: Review git history and current status**

Run:

```bash
git log --oneline -8
git status --short
```

Expected: the task commits are present and the worktree is clean except for intentional in-progress integration edits.

- [ ] **Step 2: Run full JVM test suite**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: PASS. If tests fail, fix only the failing contract or compile issue. Do not change behavior expectations unless the spec explicitly allowed the behavior change.

- [ ] **Step 3: Run debug build**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

Expected: PASS. If Android SDK, NDK, or Rust toolchain configuration blocks the build, capture the exact error and continue with the full JVM test result.

- [ ] **Step 4: Inspect direct Material default hotspots**

Run:

```bash
rg -n "OutlinedButton\\(|Button\\(|Surface\\(|NavigationBar\\(|NavigationBarItem\\(|Color\\(0x" app/src/main/java/com/example/comicdav -g '*.kt'
```

Expected: remaining usages are either Material primitives wrapped by MuBOX styling, feature-specific controls that intentionally keep native behavior, or player/video primitives with MuBOX token colors.

- [ ] **Step 5: Commit integration fixes**

If integration edits were needed, commit them:

```bash
git add app/src/main/java app/src/test/java
git commit -m "chore: integrate mubox media design system migration"
```

If no integration edits were needed, do not create an empty commit.

- [ ] **Step 6: Record verification summary**

Prepare a concise summary with:

```text
Verification:
- :app:testDebugUnitTest: PASS
- :app:assembleDebug: PASS

Changed areas:
- Shared MuBOX UI tokens/components
- App shell
- Sources/WebDAV
- Library/video library
- Settings
- Player overlay
```

If `assembleDebug` was blocked, replace the PASS line with the exact blocker.

---

## Parallel Execution Map

Use this map when dispatching subagents:

- Foundation subagent: Task 1 only.
- App shell subagent: Task 2 only.
- Sources/WebDAV subagent: Task 3 only.
- Library/video library subagent: Task 4 only.
- Settings subagent: Task 5 only.
- Player overlay subagent: Task 6 only.
- Integration subagent or coordinator: Task 7 only.

Wave 1 subagents can run concurrently after Task 1 has been integrated. They must not edit `MuBoxDesignSystem.kt` or `MuBoxComponents.kt` unless the coordinator explicitly reopens foundation work.
