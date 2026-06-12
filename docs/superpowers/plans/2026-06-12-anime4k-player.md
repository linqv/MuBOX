# Anime4K Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add global Anime4K defaults and active-session Anime4K controls to the mpv-backed video player.

**Architecture:** Keep Anime4K in the existing player/settings pipeline: DataStore persists defaults, video open intents carry those defaults, `MuBoxMpvView.initOptions()` applies startup renderer and shader options before media load, and `MpvController` performs compatible runtime `glsl-shaders` changes only. Shader-chain construction is pure and testable; asset copying is isolated in `Anime4KManager(applicationContext)`.

**Tech Stack:** Kotlin, Android DataStore Preferences, Jetpack Compose, Android assets, libmpv Android wrapper, JUnit/Robolectric, Gradle.

---

### Task 1: Add Anime4K Types And Shader Chain Builder

**Files:**
- Create: `app/src/main/java/com/example/comicdav/video/player/Anime4KManager.kt`
- Create: `app/src/test/java/com/example/comicdav/video/player/Anime4KManagerTest.kt`

- [ ] **Step 1: Write failing shader-chain tests**

Create `app/src/test/java/com/example/comicdav/video/player/Anime4KManagerTest.kt`:

```kotlin
package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Anime4KManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun modeABalancedBuildsExpectedShaderChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames.forEach { File(shaderDir, it).writeText("// $it") }

        val chain = anime4kShaderChain(
            enabled = true,
            mode = Anime4KMode.A,
            quality = Anime4KQuality.BALANCED,
            shaderDir = shaderDir,
        )

        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
            ).joinToString(":") { File(shaderDir, it).absolutePath },
            chain,
        )
    }

    @Test
    fun disabledOrOffModeReturnsEmptyShaderChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames.forEach { File(shaderDir, it).writeText("// $it") }

        assertEquals("", anime4kShaderChain(false, Anime4KMode.A, Anime4KQuality.FAST, shaderDir))
        assertEquals("", anime4kShaderChain(true, Anime4KMode.OFF, Anime4KQuality.FAST, shaderDir))
    }

    @Test
    fun missingShaderReturnsEmptyChainInsteadOfPartialChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames
            .filterNot { it == "Anime4K_Upscale_CNN_x2_L.glsl" }
            .forEach { File(shaderDir, it).writeText("// $it") }

        assertEquals("", anime4kShaderChain(true, Anime4KMode.A, Anime4KQuality.HIGH, shaderDir))
    }

    @Test
    fun staleAnime4KFilesAreIdentifiedForCleanup() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        val stale = File(shaderDir, "Anime4K_Old_Filter.glsl").apply { writeText("// stale") }
        val unrelated = File(shaderDir, "custom_shader.glsl").apply { writeText("// keep") }

        assertEquals(listOf(stale), staleAnime4KShaderFiles(shaderDir, expectedAnime4KShaderAssetNames).toList())
        assertTrue(unrelated.exists())
    }
}
```

- [ ] **Step 2: Run shader tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.Anime4KManagerTest
```

Expected: compilation fails because `Anime4KMode`, `Anime4KQuality`, `expectedAnime4KShaderAssetNames`, `anime4kShaderChain`, and `staleAnime4KShaderFiles` do not exist.

- [ ] **Step 3: Add Anime4K enums and pure chain builder**

Create `app/src/main/java/com/example/comicdav/video/player/Anime4KManager.kt` with:

```kotlin
package com.example.comicdav.video.player

import android.content.Context
import java.io.File

enum class Anime4KMode(val label: String) {
    OFF("关闭"),
    A("A"),
    B("B"),
    C("C"),
    A_PLUS("A+"),
    B_PLUS("B+"),
    C_PLUS("C+"),
}

enum class Anime4KQuality(val label: String, val suffix: String) {
    FAST("Fast", "S"),
    BALANCED("Balanced", "M"),
    HIGH("High", "L"),
}

data class Anime4KSettings(
    val enabled: Boolean = false,
    val mode: Anime4KMode = Anime4KMode.A,
    val quality: Anime4KQuality = Anime4KQuality.FAST,
)

internal val expectedAnime4KShaderAssetNames: List<String> =
    listOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_L.glsl",
        "Anime4K_Restore_CNN_Soft_S.glsl",
        "Anime4K_Restore_CNN_Soft_M.glsl",
        "Anime4K_Restore_CNN_Soft_L.glsl",
        "Anime4K_Upscale_CNN_x2_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
        "Anime4K_Upscale_CNN_x2_L.glsl",
        "Anime4K_Upscale_Denoise_CNN_x2_S.glsl",
        "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
        "Anime4K_Upscale_Denoise_CNN_x2_L.glsl",
    )

internal fun anime4kShaderChain(
    enabled: Boolean,
    mode: Anime4KMode,
    quality: Anime4KQuality,
    shaderDir: File,
): String {
    if (!enabled || mode == Anime4KMode.OFF) return ""
    val names = anime4kShaderNames(mode, quality)
    val files = names.map { File(shaderDir, it) }
    if (files.any { !it.isFile }) return ""
    return files.joinToString(":") { it.absolutePath }
}

internal fun anime4kShaderNames(mode: Anime4KMode, quality: Anime4KQuality): List<String> {
    if (mode == Anime4KMode.OFF) return emptyList()
    val q = quality.suffix
    return buildList {
        add("Anime4K_Clamp_Highlights.glsl")
        when (mode) {
            Anime4KMode.A -> {
                add("Anime4K_Restore_CNN_$q.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
                add("Anime4K_AutoDownscalePre_x2.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
            }
            Anime4KMode.B -> {
                add("Anime4K_Restore_CNN_Soft_$q.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
                add("Anime4K_AutoDownscalePre_x2.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
            }
            Anime4KMode.C -> {
                add("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl")
                add("Anime4K_AutoDownscalePre_x2.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
            }
            Anime4KMode.A_PLUS -> {
                add("Anime4K_Restore_CNN_$q.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
                add("Anime4K_AutoDownscalePre_x2.glsl")
                add("Anime4K_Restore_CNN_$q.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
            }
            Anime4KMode.B_PLUS -> {
                add("Anime4K_Restore_CNN_Soft_$q.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
                add("Anime4K_AutoDownscalePre_x2.glsl")
                add("Anime4K_Restore_CNN_Soft_$q.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
            }
            Anime4KMode.C_PLUS -> {
                add("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl")
                add("Anime4K_AutoDownscalePre_x2.glsl")
                add("Anime4K_Restore_CNN_$q.glsl")
                add("Anime4K_Upscale_CNN_x2_$q.glsl")
            }
            Anime4KMode.OFF -> Unit
        }
    }
}

internal fun staleAnime4KShaderFiles(shaderDir: File, expectedNames: List<String>): Sequence<File> =
    shaderDir.listFiles().orEmpty()
        .asSequence()
        .filter { it.isFile }
        .filter { it.name.startsWith("Anime4K_") && it.name.endsWith(".glsl") }
        .filterNot { it.name in expectedNames }

class Anime4KManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val shaderDir = File(appContext.filesDir, SHADER_DIR)

    fun shaderChain(settings: Anime4KSettings): String {
        if (!settings.enabled || settings.mode == Anime4KMode.OFF) return ""
        if (!initialize()) return ""
        return anime4kShaderChain(
            enabled = settings.enabled,
            mode = settings.mode,
            quality = settings.quality,
            shaderDir = shaderDir,
        )
    }

    fun initialize(): Boolean =
        runCatching {
            shaderDir.mkdirs()
            removeStaleShaders()
            expectedAnime4KShaderAssetNames.forEach(::copyShaderIfChanged)
            true
        }.getOrElse { false }

    private fun removeStaleShaders() {
        staleAnime4KShaderFiles(shaderDir, expectedAnime4KShaderAssetNames).forEach { it.delete() }
    }

    private fun copyShaderIfChanged(fileName: String) {
        val assetBytes = appContext.assets.open("$SHADER_ASSET_DIR/$fileName").use { it.readBytes() }
        val destination = File(shaderDir, fileName)
        if (destination.isFile && destination.readBytes().contentEquals(assetBytes)) return
        destination.writeBytes(assetBytes)
    }

    private companion object {
        const val SHADER_ASSET_DIR = "shaders"
        const val SHADER_DIR = "shaders"
    }
}
```

- [ ] **Step 4: Run shader tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.Anime4KManagerTest
```

Expected: `Anime4KManagerTest` passes.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/Anime4KManager.kt app/src/test/java/com/example/comicdav/video/player/Anime4KManagerTest.kt
git commit -m "feat: add anime4k shader chain builder"
```

---

### Task 2: Persist Anime4K Defaults And Expose Settings UI

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt`
- Modify: `app/src/main/java/com/example/comicdav/AppContentRoutes.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenTest.kt`

- [ ] **Step 1: Write failing DataStore tests**

Add imports to `AppSettingsStoreTest.kt`:

```kotlin
import com.example.comicdav.video.player.Anime4KMode
import com.example.comicdav.video.player.Anime4KQuality
```

Add tests:

```kotlin
@Test
fun anime4kDefaultsAreDisabledWithFastModeA() = runTest {
    val store = createStore("anime4k_defaults.preferences_pb")

    val settings = store.settings.first()

    assertFalse(settings.anime4kEnabled)
    assertEquals(Anime4KMode.A, settings.anime4kMode)
    assertEquals(Anime4KQuality.FAST, settings.anime4kQuality)
}

@Test
fun anime4kSettingsCanBeUpdatedAndReadBack() = runTest {
    val store = createStore("anime4k_updates.preferences_pb")

    store.updateAnime4KEnabled(true)
    store.updateAnime4KMode(Anime4KMode.C_PLUS)
    store.updateAnime4KQuality(Anime4KQuality.HIGH)

    val settings = store.settings.first()
    assertTrue(settings.anime4kEnabled)
    assertEquals(Anime4KMode.C_PLUS, settings.anime4kMode)
    assertEquals(Anime4KQuality.HIGH, settings.anime4kQuality)
}
```

- [ ] **Step 2: Run DataStore tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest
```

Expected: compilation fails because `AppSettings` does not expose Anime4K settings and `AppSettingsStore` has no update methods.

- [ ] **Step 3: Add persistence**

Modify `AppSettingsStore.kt` imports:

```kotlin
import com.example.comicdav.video.player.Anime4KMode
import com.example.comicdav.video.player.Anime4KQuality
```

Extend `AppSettings`:

```kotlin
val anime4kEnabled: Boolean = false,
val anime4kMode: Anime4KMode = Anime4KMode.A,
val anime4kQuality: Anime4KQuality = Anime4KQuality.FAST,
```

In `settings` mapping add:

```kotlin
anime4kEnabled = preferences[ANIME4K_ENABLED] ?: false,
anime4kMode = preferences[ANIME4K_MODE].toEnumOrDefault(Anime4KMode.A),
anime4kQuality = preferences[ANIME4K_QUALITY].toEnumOrDefault(Anime4KQuality.FAST),
```

Add update methods:

```kotlin
suspend fun updateAnime4KEnabled(enabled: Boolean) {
    dataStore.edit { preferences ->
        preferences[ANIME4K_ENABLED] = enabled
    }
}

suspend fun updateAnime4KMode(mode: Anime4KMode) {
    dataStore.edit { preferences ->
        preferences[ANIME4K_MODE] = mode.name
    }
}

suspend fun updateAnime4KQuality(quality: Anime4KQuality) {
    dataStore.edit { preferences ->
        preferences[ANIME4K_QUALITY] = quality.name
    }
}
```

Add preference keys:

```kotlin
val ANIME4K_ENABLED = booleanPreferencesKey("anime4k_enabled")
val ANIME4K_MODE = stringPreferencesKey("anime4k_mode")
val ANIME4K_QUALITY = stringPreferencesKey("anime4k_quality")
```

- [ ] **Step 4: Run DataStore tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest
```

Expected: `AppSettingsStoreTest` passes.

- [ ] **Step 5: Write failing settings UI tests**

In `app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenTest.kt` or `SettingsScreenUiTest.kt`, add a source-level test consistent with the existing file style:

```kotlin
@Test
fun videoSettingsExposeAnime4KControlsNearRendererOptions() {
    val source = settingsSourceFile().readText()

    assertTrue(source.contains("title = \"Anime4K\""))
    assertTrue(source.contains("checked = settings.anime4kEnabled"))
    assertTrue(source.contains("selected = settings.anime4kMode"))
    assertTrue(source.contains("options = Anime4KMode.entries"))
    assertTrue(source.contains("selected = settings.anime4kQuality"))
    assertTrue(source.contains("options = Anime4KQuality.entries"))
    assertTrue(source.indexOf("title = \"GPU API\"") < source.indexOf("title = \"Anime4K\""))
    assertTrue(source.indexOf("title = \"Anime4K\"") < source.indexOf("title = \"默认解码器\""))
}
```

If the selected test file lacks `settingsSourceFile()`, add:

```kotlin
private fun settingsSourceFile(): java.io.File =
    listOf(
        java.io.File("src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt"),
        java.io.File("app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt"),
    ).first { it.isFile }
```

- [ ] **Step 6: Run settings UI tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.feature.settings.SettingsScreenTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: the new test fails because Settings UI does not contain Anime4K controls.

- [ ] **Step 7: Add settings UI plumbing**

Modify `SettingsScreen.kt` imports:

```kotlin
import com.example.comicdav.video.player.Anime4KMode
import com.example.comicdav.video.player.Anime4KQuality
```

Add top-level label helpers near other video label helpers:

```kotlin
private fun anime4kModeLabel(mode: Anime4KMode): String = mode.label

private fun anime4kQualityLabel(quality: Anime4KQuality): String = quality.label
```

Add callback parameters to `SettingsScreen` and `VideoSettingsPage`:

```kotlin
onAnime4KEnabledChange: (Boolean) -> Unit = {},
onAnime4KModeChange: (Anime4KMode) -> Unit = {},
onAnime4KQualityChange: (Anime4KQuality) -> Unit = {},
```

Add controls in `VideoSettingsPage` after GPU API and before default decoder:

```kotlin
MuBoxSwitchRow(
    title = "Anime4K",
    checked = settings.anime4kEnabled,
    onCheckedChange = onAnime4KEnabledChange,
    subtitle = "启用 Anime4K 动画画面实时放大；不兼容时播放器会自动关闭",
)
DropdownRow(
    title = "Anime4K 预设",
    selected = settings.anime4kMode,
    options = Anime4KMode.entries.filterNot { it == Anime4KMode.OFF },
    label = ::anime4kModeLabel,
    onSelected = onAnime4KModeChange,
)
DropdownRow(
    title = "Anime4K 质量",
    selected = settings.anime4kQuality,
    options = Anime4KQuality.entries,
    label = ::anime4kQualityLabel,
    onSelected = onAnime4KQualityChange,
)
```

Modify `AppContentRoutes.kt` `SettingsTabContent` to pass:

```kotlin
onAnime4KEnabledChange = { value ->
    scope.launch { appSettingsStore.updateAnime4KEnabled(value) }
},
onAnime4KModeChange = { value ->
    scope.launch { appSettingsStore.updateAnime4KMode(value) }
},
onAnime4KQualityChange = { value ->
    scope.launch { appSettingsStore.updateAnime4KQuality(value) }
},
```

- [ ] **Step 8: Run settings tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.data.AppSettingsStoreTest --tests com.example.comicdav.feature.settings.SettingsScreenTest --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: DataStore and settings UI tests pass.

- [ ] **Step 9: Commit Task 2**

Run:

```bash
git add app/src/main/java/com/example/comicdav/data/AppSettingsStore.kt app/src/main/java/com/example/comicdav/AppContentRoutes.kt app/src/main/java/com/example/comicdav/feature/settings/SettingsScreen.kt app/src/test/java/com/example/comicdav/data/AppSettingsStoreTest.kt app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenTest.kt app/src/test/java/com/example/comicdav/feature/settings/SettingsScreenUiTest.kt
git commit -m "feat: add anime4k video settings"
```

---

### Task 3: Pass Anime4K Defaults Into Player Intents

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntentTest.kt`

- [ ] **Step 1: Write failing intent tests**

Add imports to `VideoPlayerActivityIntentTest.kt`:

```kotlin
import com.example.comicdav.video.player.Anime4KMode
import com.example.comicdav.video.player.Anime4KQuality
```

Extend `appSettingsDefaultToAutomaticVideoBackendModes()`:

```kotlin
assertFalse(settings.anime4kEnabled)
assertEquals(Anime4KMode.A, settings.anime4kMode)
assertEquals(Anime4KQuality.FAST, settings.anime4kQuality)
```

Extend `localIntentCarriesConfiguredVideoBackendModes()`:

```kotlin
anime4kEnabled = true,
anime4kMode = Anime4KMode.B_PLUS,
anime4kQuality = Anime4KQuality.HIGH,
```

Add assertions:

```kotlin
assertEquals(true, intent.getBooleanExtra(VideoPlayerActivity.EXTRA_ANIME4K_ENABLED, false))
assertEquals(Anime4KMode.B_PLUS.name, intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_MODE))
assertEquals(Anime4KQuality.HIGH.name, intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_QUALITY))
```

Add a WebDAV-specific assertion:

```kotlin
@Test
fun webDavIntentCarriesAnime4KSettings() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val request = WebDavVideoOpenRequest(
        accountId = "account",
        remotePath = "/shows/03.mkv",
        displayName = "03.mkv",
        size = 3L,
        etag = "etag3",
        lastModified = 30L,
        mimeType = "video/x-matroska",
    )

    val intent = VideoPlayerActivity.webDavIntent(
        context = context,
        request = request,
        uri = "http://127.0.0.1:1234/stream/anime4k",
        subtitleUrls = emptyList(),
        streamIds = listOf("anime4k"),
        anime4kEnabled = true,
        anime4kMode = Anime4KMode.C,
        anime4kQuality = Anime4KQuality.BALANCED,
    )

    assertEquals(true, intent.getBooleanExtra(VideoPlayerActivity.EXTRA_ANIME4K_ENABLED, false))
    assertEquals(Anime4KMode.C.name, intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_MODE))
    assertEquals(Anime4KQuality.BALANCED.name, intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_QUALITY))
}
```

- [ ] **Step 2: Run intent tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntentTest
```

Expected: compilation fails because intent extras and factory parameters do not exist.

- [ ] **Step 3: Add intent extras and factory parameters**

In `VideoPlayerActivity.Companion`, add:

```kotlin
const val EXTRA_ANIME4K_ENABLED = "com.example.comicdav.video.extra.ANIME4K_ENABLED"
const val EXTRA_ANIME4K_MODE = "com.example.comicdav.video.extra.ANIME4K_MODE"
const val EXTRA_ANIME4K_QUALITY = "com.example.comicdav.video.extra.ANIME4K_QUALITY"
```

Add parameters to both `localIntent()` and `webDavIntent()`:

```kotlin
anime4kEnabled: Boolean = false,
anime4kMode: Anime4KMode = Anime4KMode.A,
anime4kQuality: Anime4KQuality = Anime4KQuality.FAST,
```

Add extras in both factories:

```kotlin
.putExtra(EXTRA_ANIME4K_ENABLED, anime4kEnabled)
.putExtra(EXTRA_ANIME4K_MODE, anime4kMode.name)
.putExtra(EXTRA_ANIME4K_QUALITY, anime4kQuality.name)
```

- [ ] **Step 4: Pass settings from all video launch call sites**

In every `VideoPlayerActivity.localIntent(` and `VideoPlayerActivity.webDavIntent(` call in `MainActivity.kt`, add:

```kotlin
anime4kEnabled = appSettings.anime4kEnabled,
anime4kMode = appSettings.anime4kMode,
anime4kQuality = appSettings.anime4kQuality,
```

Use `rg -n "VideoPlayerActivity\\.(localIntent|webDavIntent)" app/src/main/java/com/example/comicdav/MainActivity.kt` to verify every call site is updated.

- [ ] **Step 5: Run intent tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntentTest
```

Expected: `VideoPlayerActivityIntentTest` passes.

- [ ] **Step 6: Commit Task 3**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/main/java/com/example/comicdav/MainActivity.kt app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntentTest.kt
git commit -m "feat: pass anime4k defaults to player"
```

---

### Task 4: Apply Startup Renderer And Anime4K Options Before MPV Initialization

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/Anime4KManager.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/MpvController.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/MpvControllerAdvancedControlsTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntegrationTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/MuBoxMpvViewFactoryTest.kt`

- [ ] **Step 1: Write failing startup compatibility tests**

Add tests to `MpvControllerAdvancedControlsTest.kt`:

```kotlin
@Test
fun effectiveStartupVideoOutputFallsBackToGpuForAnime4KOnGpuNextWithoutVulkan() {
    val result = anime4kStartupCompatibility(
        settings = Anime4KSettings(enabled = true, mode = Anime4KMode.A, quality = Anime4KQuality.FAST),
        requestedVideoOutputMode = VideoOutputMode.GPU_NEXT,
        gpuApiMode = GpuApiMode.AUTO,
    )

    assertEquals(VideoOutputMode.AUTO, result.effectiveVideoOutputMode)
    assertEquals("Anime4K 与 gpu-next(OpenGL) 不兼容，已为本次播放使用 gpu", result.statusMessage)
}

@Test
fun effectiveStartupVideoOutputKeepsGpuNextWhenVulkanIsSelected() {
    val result = anime4kStartupCompatibility(
        settings = Anime4KSettings(enabled = true, mode = Anime4KMode.A, quality = Anime4KQuality.FAST),
        requestedVideoOutputMode = VideoOutputMode.GPU_NEXT,
        gpuApiMode = GpuApiMode.VULKAN,
    )

    assertEquals(VideoOutputMode.GPU_NEXT, result.effectiveVideoOutputMode)
    assertEquals(null, result.statusMessage)
}
```

- [ ] **Step 2: Write failing source-order tests**

In `VideoPlayerActivityIntegrationTest.kt`, replace the assertions that require post-prepare controller VO/GPU calls:

```kotlin
assertFalse(source.contains("controller.setVideoOutputMode(initialVideoOutputMode)"))
assertFalse(source.contains("controller.setGpuApiMode(initialGpuApiMode)"))
assertTrue(source.contains("mpvView.videoOutputMode = startupCompatibility.effectiveVideoOutputMode"))
assertTrue(source.contains("mpvView.gpuApiMode = initialGpuApiMode"))
assertTrue(source.contains("mpvView.videoDecoderMode = initialVideoDecoderMode"))
```

In `MuBoxMpvViewFactoryTest.kt`, add:

```kotlin
@Test
fun mpvViewAppliesRendererDecoderAndAnime4KInInitOptions() {
    val source = sourceFile().readText()
    val profileIndex = source.indexOf("MPVLib.setOptionString(\"profile\", mpvProfileMode.profile)")
    val gpuApiIndex = source.indexOf("MPVLib.setOptionString(\"gpu-api\", gpuApiMode.gpuApi)")
    val voIndex = source.indexOf("setVo(videoOutputMode.videoOutput)")
    val anime4kIndex = source.indexOf("applyStartupAnime4KShaders()")

    assertTrue(profileIndex >= 0)
    assertTrue(gpuApiIndex >= 0)
    assertTrue(voIndex >= 0)
    assertTrue(anime4kIndex >= 0)
    assertTrue(profileIndex < voIndex)
    assertTrue(gpuApiIndex < anime4kIndex)
    assertTrue(voIndex < anime4kIndex)
}
```

- [ ] **Step 3: Run startup tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.MpvControllerAdvancedControlsTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntegrationTest --tests com.example.comicdav.video.player.MuBoxMpvViewFactoryTest
```

Expected: tests fail because startup compatibility and `MuBoxMpvView` startup option application are missing.

- [ ] **Step 4: Add startup compatibility helper**

In `Anime4KManager.kt`, add:

```kotlin
data class Anime4KStartupCompatibility(
    val effectiveVideoOutputMode: VideoOutputMode,
    val statusMessage: String? = null,
)

internal fun anime4kStartupCompatibility(
    settings: Anime4KSettings,
    requestedVideoOutputMode: VideoOutputMode,
    gpuApiMode: GpuApiMode,
): Anime4KStartupCompatibility {
    val anime4kRequested = settings.enabled && settings.mode != Anime4KMode.OFF
    return if (anime4kRequested && requestedVideoOutputMode == VideoOutputMode.GPU_NEXT && gpuApiMode != GpuApiMode.VULKAN) {
        Anime4KStartupCompatibility(
            effectiveVideoOutputMode = VideoOutputMode.AUTO,
            statusMessage = "Anime4K 与 gpu-next(OpenGL) 不兼容，已为本次播放使用 gpu",
        )
    } else {
        Anime4KStartupCompatibility(effectiveVideoOutputMode = requestedVideoOutputMode)
    }
}
```

- [ ] **Step 5: Move startup options into MuBoxMpvView**

Modify `MuBoxMpvView.kt`:

```kotlin
var mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST
var videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO
var gpuApiMode: GpuApiMode = GpuApiMode.AUTO
var videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO
var anime4kSettings: Anime4KSettings = Anime4KSettings()
var anime4kManager: Anime4KManager? = null
```

Update `initOptions()` startup options:

```kotlin
MPVLib.setOptionString("profile", mpvProfileMode.profile)
MPVLib.setOptionString("gpu-api", gpuApiMode.gpuApi)
setVo(videoOutputMode.videoOutput)
MPVLib.setOptionString("hwdec", videoDecoderMode.hwdec)
MPVLib.setOptionString("hwdec-codecs", "all")
MPVLib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
MPVLib.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
MPVLib.setOptionString("msg-level", "all=warn")
MPVLib.setPropertyBoolean("keep-open", true)
MPVLib.setPropertyBoolean("input-default-bindings", true)
applyStartupAnime4KShaders()
```

Add:

```kotlin
private fun applyStartupAnime4KShaders() {
    val chain = anime4kManager?.shaderChain(anime4kSettings).orEmpty()
    if (chain.isBlank()) return
    if (gpuApiMode != GpuApiMode.VULKAN) {
        MPVLib.setOptionString("opengl-pbo", "yes")
        MPVLib.setOptionString("opengl-early-flush", "no")
    }
    MPVLib.setOptionString("vd-lavc-dr", "yes")
    MPVLib.setOptionString("glsl-shaders", chain)
}
```

- [ ] **Step 6: Wire startup settings in VideoPlayerActivity**

In `VideoPlayerActivity.onCreate`, parse Anime4K extras:

```kotlin
val initialAnime4KSettings = Anime4KSettings(
    enabled = intent.getBooleanExtra(EXTRA_ANIME4K_ENABLED, false),
    mode = intent.getStringExtra(EXTRA_ANIME4K_MODE).toEnumOrDefault(Anime4KMode.A),
    quality = intent.getStringExtra(EXTRA_ANIME4K_QUALITY).toEnumOrDefault(Anime4KQuality.FAST),
)
val startupCompatibility = anime4kStartupCompatibility(
    settings = initialAnime4KSettings,
    requestedVideoOutputMode = initialVideoOutputMode,
    gpuApiMode = initialGpuApiMode,
)
val anime4kManager = Anime4KManager(applicationContext)
```

After `mpvView = MuBoxMpvView.create(this)`, assign:

```kotlin
mpvView.mpvProfileMode = initialMpvProfileMode
mpvView.videoOutputMode = startupCompatibility.effectiveVideoOutputMode
mpvView.gpuApiMode = initialGpuApiMode
mpvView.videoDecoderMode = initialVideoDecoderMode
mpvView.anime4kSettings = initialAnime4KSettings
mpvView.anime4kManager = anime4kManager
controller = MpvController(
    ViewBackedMpvEngine(mpvView),
)
```

Remove these post-prepare calls from the load coroutine:

```kotlin
controller.setVideoOutputMode(initialVideoOutputMode)
controller.setGpuApiMode(initialGpuApiMode)
controller.setDecoderMode(initialVideoDecoderMode)
```

Add this state-only initialization method to `MpvController.kt`:

```kotlin
fun setStartupRendererState(
    videoOutputMode: VideoOutputMode,
    gpuApiMode: GpuApiMode,
    decoderMode: VideoDecoderMode,
) {
    _state.value = _state.value.copy(
        videoOutputMode = videoOutputMode,
        currentVideoOutput = videoOutputMode.videoOutput,
        gpuApiMode = gpuApiMode,
        currentGpuApi = gpuApiMode.gpuApi,
        decoderMode = decoderMode,
        currentHwdec = decoderMode.hwdec,
    )
}
```

Call it from `VideoPlayerActivity` after `prepareMpv()` and before loading media:

```kotlin
controller.setStartupRendererState(
    videoOutputMode = startupCompatibility.effectiveVideoOutputMode,
    gpuApiMode = initialGpuApiMode,
    decoderMode = initialVideoDecoderMode,
)
```

- [ ] **Step 7: Run startup tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.MpvControllerAdvancedControlsTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntegrationTest --tests com.example.comicdav.video.player.MuBoxMpvViewFactoryTest
```

Expected: startup compatibility, source-order, and view factory tests pass.

- [ ] **Step 8: Commit Task 4**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/Anime4KManager.kt app/src/main/java/com/example/comicdav/video/player/MpvController.kt app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/test/java/com/example/comicdav/video/player/MpvControllerAdvancedControlsTest.kt app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntegrationTest.kt app/src/test/java/com/example/comicdav/video/player/MuBoxMpvViewFactoryTest.kt
git commit -m "feat: apply anime4k at mpv startup"
```

---

### Task 5: Add Runtime Anime4K Controller Behavior

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/MpvController.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/MpvControllerAdvancedControlsTest.kt`

- [ ] **Step 1: Write failing runtime controller tests**

In `MpvControllerAdvancedControlsTest.kt`, add helper provider:

```kotlin
private class FakeAnime4KShaderProvider(
    private val chain: String = "/tmp/Anime4K_Clamp_Highlights.glsl:/tmp/Anime4K_Upscale_CNN_x2_S.glsl",
) : Anime4KShaderProvider {
    val requestedSettings = mutableListOf<Anime4KSettings>()

    override fun shaderChain(settings: Anime4KSettings): String {
        requestedSettings += settings
        return if (settings.enabled && settings.mode != Anime4KMode.OFF) chain else ""
    }
}
```

Add tests:

```kotlin
@Test
fun enablingAnime4KWritesGlslShadersPropertyOnCompatibleRenderer() {
    val engine = FakeMpvEngine()
    val provider = FakeAnime4KShaderProvider("shader-a:shader-b")
    val controller = MpvController(engine, anime4kShaderProvider = provider)
    controller.setStartupRendererState(
        videoOutputMode = VideoOutputMode.AUTO,
        gpuApiMode = GpuApiMode.AUTO,
        decoderMode = VideoDecoderMode.AUTO,
    )

    controller.setAnime4KEnabled(true)

    assertEquals(listOf("shader-a:shader-b"), engine.stringPropertyHistory("glsl-shaders"))
    assertEquals(true, controller.state.value.anime4kEnabled)
    assertEquals(null, controller.state.value.anime4kStatusMessage)
}

@Test
fun disablingAnime4KClearsGlslShadersProperty() {
    val engine = FakeMpvEngine()
    val controller = MpvController(engine, anime4kShaderProvider = FakeAnime4KShaderProvider())
    controller.setStartupRendererState(VideoOutputMode.AUTO, GpuApiMode.AUTO, VideoDecoderMode.AUTO)
    controller.setAnime4KEnabled(true)

    controller.setAnime4KEnabled(false)

    assertEquals(listOf("/tmp/Anime4K_Clamp_Highlights.glsl:/tmp/Anime4K_Upscale_CNN_x2_S.glsl", ""), engine.stringPropertyHistory("glsl-shaders"))
    assertEquals(false, controller.state.value.anime4kEnabled)
}

@Test
fun runtimeAnime4KOnGpuNextWithoutVulkanIsRejectedWithoutChangingVo() {
    val engine = FakeMpvEngine()
    val controller = MpvController(engine, anime4kShaderProvider = FakeAnime4KShaderProvider())
    controller.setStartupRendererState(VideoOutputMode.GPU_NEXT, GpuApiMode.AUTO, VideoDecoderMode.AUTO)

    controller.setAnime4KEnabled(true)

    assertEquals(emptyList<String>(), engine.optionHistory("vo"))
    assertEquals(listOf(""), engine.stringPropertyHistory("glsl-shaders"))
    assertEquals(false, controller.state.value.anime4kEnabled)
    assertEquals("Anime4K 与当前 gpu-next(OpenGL) 渲染器不兼容", controller.state.value.anime4kStatusMessage)
}

@Test
fun runtimeAnime4KModeAndQualityChangesRewriteOnlyGlslShaders() {
    val engine = FakeMpvEngine()
    val provider = FakeAnime4KShaderProvider("shader-chain")
    val controller = MpvController(engine, anime4kShaderProvider = provider)
    controller.setStartupRendererState(VideoOutputMode.GPU_NEXT, GpuApiMode.VULKAN, VideoDecoderMode.AUTO)
    controller.setAnime4KEnabled(true)

    controller.setAnime4KMode(Anime4KMode.C_PLUS)
    controller.setAnime4KQuality(Anime4KQuality.HIGH)

    assertEquals(emptyList<String>(), engine.optionHistory("vo"))
    assertEquals(listOf("shader-chain", "shader-chain", "shader-chain"), engine.stringPropertyHistory("glsl-shaders"))
    assertEquals(Anime4KMode.C_PLUS, controller.state.value.anime4kMode)
    assertEquals(Anime4KQuality.HIGH, controller.state.value.anime4kQuality)
}
```

- [ ] **Step 2: Run controller tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.MpvControllerAdvancedControlsTest
```

Expected: compilation fails because runtime Anime4K controller APIs and `Anime4KShaderProvider` do not exist.

- [ ] **Step 3: Add shader provider interface**

In `Anime4KManager.kt`, add:

```kotlin
interface Anime4KShaderProvider {
    fun shaderChain(settings: Anime4KSettings): String
}
```

Change class declaration:

```kotlin
class Anime4KManager(
    context: Context,
) : Anime4KShaderProvider {
```

Add a no-op provider:

```kotlin
object EmptyAnime4KShaderProvider : Anime4KShaderProvider {
    override fun shaderChain(settings: Anime4KSettings): String = ""
}
```

- [ ] **Step 4: Extend player state and controller constructor**

In `MpvController.kt`, extend `MpvPlayerState`:

```kotlin
val anime4kEnabled: Boolean = false,
val anime4kMode: Anime4KMode = Anime4KMode.A,
val anime4kQuality: Anime4KQuality = Anime4KQuality.FAST,
val anime4kStatusMessage: String? = null,
```

Change constructor:

```kotlin
class MpvController(
    private val engine: MpvEngine,
    private val anime4kShaderProvider: Anime4KShaderProvider = EmptyAnime4KShaderProvider,
    initialAnime4KSettings: Anime4KSettings = Anime4KSettings(),
    initialAnime4KStatusMessage: String? = null,
) {
```

Initialize `_state` with:

```kotlin
private val _state = MutableStateFlow(
    MpvPlayerState(
        anime4kEnabled = initialAnime4KSettings.enabled && initialAnime4KSettings.mode != Anime4KMode.OFF,
        anime4kMode = initialAnime4KSettings.mode,
        anime4kQuality = initialAnime4KSettings.quality,
        anime4kStatusMessage = initialAnime4KStatusMessage,
    ),
)
```

In `VideoPlayerActivity.onCreate`, update controller construction to pass the runtime provider and initial session state:

```kotlin
controller = MpvController(
    engine = ViewBackedMpvEngine(mpvView),
    anime4kShaderProvider = anime4kManager,
    initialAnime4KSettings = initialAnime4KSettings,
    initialAnime4KStatusMessage = startupCompatibility.statusMessage,
)
```

- [ ] **Step 5: Add runtime methods**

In `MpvController.kt`, add:

```kotlin
fun setAnime4KEnabled(enabled: Boolean) {
    if (!canWriteEngine()) return
    applyAnime4KSettings(_state.value.currentAnime4KSettings().copy(enabled = enabled))
}

fun setAnime4KMode(mode: Anime4KMode) {
    if (!canWriteEngine()) return
    applyAnime4KSettings(_state.value.currentAnime4KSettings().copy(mode = mode, enabled = mode != Anime4KMode.OFF && _state.value.anime4kEnabled))
}

fun setAnime4KQuality(quality: Anime4KQuality) {
    if (!canWriteEngine()) return
    applyAnime4KSettings(_state.value.currentAnime4KSettings().copy(quality = quality))
}

private fun applyAnime4KSettings(settings: Anime4KSettings) {
    if (!isRuntimeAnime4KRendererCompatible()) {
        engine.setPropertyString("glsl-shaders", "")
        _state.value = _state.value.copy(
            anime4kEnabled = false,
            anime4kMode = settings.mode,
            anime4kQuality = settings.quality,
            anime4kStatusMessage = "Anime4K 与当前 gpu-next(OpenGL) 渲染器不兼容",
        )
        return
    }
    val chain = anime4kShaderProvider.shaderChain(settings)
    engine.setPropertyString("glsl-shaders", chain)
    _state.value = _state.value.copy(
        anime4kEnabled = settings.enabled && settings.mode != Anime4KMode.OFF && chain.isNotBlank(),
        anime4kMode = settings.mode,
        anime4kQuality = settings.quality,
        anime4kStatusMessage = if (settings.enabled && settings.mode != Anime4KMode.OFF && chain.isBlank()) "Anime4K shader 加载失败" else null,
    )
}

private fun isRuntimeAnime4KRendererCompatible(): Boolean =
    _state.value.videoOutputMode != VideoOutputMode.GPU_NEXT || _state.value.gpuApiMode == GpuApiMode.VULKAN

private fun MpvPlayerState.currentAnime4KSettings(): Anime4KSettings =
    Anime4KSettings(
        enabled = anime4kEnabled,
        mode = anime4kMode,
        quality = anime4kQuality,
    )
```

- [ ] **Step 6: Run controller tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.MpvControllerAdvancedControlsTest
```

Expected: `MpvControllerAdvancedControlsTest` passes.

- [ ] **Step 7: Commit Task 5**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/Anime4KManager.kt app/src/main/java/com/example/comicdav/video/player/MpvController.kt app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/test/java/com/example/comicdav/video/player/MpvControllerAdvancedControlsTest.kt
git commit -m "feat: add runtime anime4k controller controls"
```

---

### Task 6: Add Anime4K Controls To Player Menu

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt`
- Modify: `app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt`
- Test: `app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntegrationTest.kt`

- [ ] **Step 1: Write failing player menu tests**

In `PlayerOptionPanelUiTest.kt`, change `scalePanelOnlyContainsPerPlaybackVisualControls()` expected labels:

```kotlin
assertEquals(
    listOf("画面", "Anime4K", "预设", "质量"),
    scaleModeControlGroupLabels(),
)
```

Add:

```kotlin
@Test
fun anime4kMenuOptionsExposeExpectedSessionControls() {
    assertEquals(listOf("关", "开"), anime4kEnabledControlLabels())
    assertEquals(listOf("A", "B", "C", "A+", "B+", "C+"), anime4kModeControlLabels())
    assertEquals(listOf("Fast", "Balanced", "High"), anime4kQualityControlLabels())
}
```

In `VideoPlayerActivityIntegrationTest.kt`, extend `screenExposesVisibleAlternativesForAdvancedPlaybackControls()`:

```kotlin
assertTrue(source.contains("onAnime4KEnabledSelected = controller::setAnime4KEnabled"))
assertTrue(source.contains("onAnime4KModeSelected = controller::setAnime4KMode"))
assertTrue(source.contains("onAnime4KQualitySelected = controller::setAnime4KQuality"))
```

- [ ] **Step 2: Run UI tests and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntegrationTest
```

Expected: tests fail because the menu does not expose Anime4K controls.

- [ ] **Step 3: Add PlayerMenuPanel parameters**

In `VideoPlayerControls.kt`, add parameters to `PlayerMenuPanel`:

```kotlin
onAnime4KEnabledSelected: (Boolean) -> Unit,
onAnime4KModeSelected: (Anime4KMode) -> Unit,
onAnime4KQualitySelected: (Anime4KQuality) -> Unit,
```

Below the "画面" group, add:

```kotlin
ControlGroup("Anime4K") {
    anime4kEnabledControlOptions().forEach { (label, enabled) ->
        CompactTextButton(label, state.anime4kEnabled == enabled) { onAnime4KEnabledSelected(enabled) }
    }
}
if (!state.anime4kStatusMessage.isNullOrBlank()) {
    Text(
        state.anime4kStatusMessage,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
ControlGroup("预设") {
    Anime4KMode.entries.filterNot { it == Anime4KMode.OFF }.forEach { mode ->
        CompactTextButton(mode.label, state.anime4kMode == mode) { onAnime4KModeSelected(mode) }
    }
}
ControlGroup("质量") {
    Anime4KQuality.entries.forEach { quality ->
        CompactTextButton(quality.label, state.anime4kQuality == quality) { onAnime4KQualitySelected(quality) }
    }
}
```

Add helpers near existing test helpers:

```kotlin
internal fun anime4kEnabledControlOptions(): List<Pair<String, Boolean>> = listOf("关" to false, "开" to true)

internal fun anime4kEnabledControlLabels(): List<String> =
    anime4kEnabledControlOptions().map { it.first }

internal fun anime4kModeControlLabels(): List<String> =
    Anime4KMode.entries.filterNot { it == Anime4KMode.OFF }.map { it.label }

internal fun anime4kQualityControlLabels(): List<String> =
    Anime4KQuality.entries.map { it.label }

internal fun scaleModeControlGroupLabels(): List<String> = listOf("画面", "Anime4K", "预设", "质量")
```

- [ ] **Step 4: Wire callbacks through VideoPlayerActivity**

In `VideoPlayerActivity.VideoPlayerScreen`, add parameters:

```kotlin
onAnime4KEnabledSelected: (Boolean) -> Unit,
onAnime4KModeSelected: (Anime4KMode) -> Unit,
onAnime4KQualitySelected: (Anime4KQuality) -> Unit,
```

At the `VideoPlayerScreen(` call in `onCreate`, pass:

```kotlin
onAnime4KEnabledSelected = controller::setAnime4KEnabled,
onAnime4KModeSelected = controller::setAnime4KMode,
onAnime4KQualitySelected = controller::setAnime4KQuality,
```

At the `PlayerMenuPanel(` call, pass the same callbacks.

- [ ] **Step 5: Run UI tests and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest --tests com.example.comicdav.video.player.VideoPlayerActivityIntegrationTest
```

Expected: menu and integration tests pass.

- [ ] **Step 6: Commit Task 6**

Run:

```bash
git add app/src/main/java/com/example/comicdav/video/player/VideoPlayerControls.kt app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt app/src/test/java/com/example/comicdav/video/player/PlayerOptionPanelUiTest.kt app/src/test/java/com/example/comicdav/video/player/VideoPlayerActivityIntegrationTest.kt
git commit -m "feat: expose anime4k player controls"
```

---

### Task 7: Add Bundled Anime4K Shader Assets

**Files:**
- Create: `app/src/main/assets/shaders/Anime4K_Clamp_Highlights.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_AutoDownscalePre_x2.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Restore_CNN_S.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Restore_CNN_M.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Restore_CNN_L.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Restore_CNN_Soft_S.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Restore_CNN_Soft_M.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Restore_CNN_Soft_L.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_S.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_M.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_L.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Upscale_Denoise_CNN_x2_S.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Upscale_Denoise_CNN_x2_M.glsl`
- Create: `app/src/main/assets/shaders/Anime4K_Upscale_Denoise_CNN_x2_L.glsl`
- Create: `app/src/test/java/com/example/comicdav/video/player/Anime4KShaderAssetsTest.kt`

- [ ] **Step 1: Write failing asset inventory test**

Create `Anime4KShaderAssetsTest.kt`:

```kotlin
package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime4KShaderAssetsTest {
    @Test
    fun bundledShaderAssetsMatchExpectedInventory() {
        val shaderDir = listOf(
            File("src/main/assets/shaders"),
            File("app/src/main/assets/shaders"),
        ).first { it.isDirectory || it.parentFile?.isDirectory == true }

        val actualNames = shaderDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("Anime4K_") && it.name.endsWith(".glsl") }
            .map { it.name }
            .sorted()

        assertEquals(expectedAnime4KShaderAssetNames.sorted(), actualNames)
        expectedAnime4KShaderAssetNames.forEach { name ->
            val file = File(shaderDir, name)
            assertTrue("$name should not be empty", file.length() > 0L)
        }
    }
}
```

- [ ] **Step 2: Run asset test and verify RED**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.Anime4KShaderAssetsTest
```

Expected: test fails because `app/src/main/assets/shaders` does not contain the expected shader files.

- [ ] **Step 3: Copy shader assets from mpvEx**

Run:

```bash
mkdir -p app/src/main/assets/shaders
cp /home/lin/MuBOX-pro/mpvEx/app/src/main/assets/shaders/Anime4K_Clamp_Highlights.glsl app/src/main/assets/shaders/
cp /home/lin/MuBOX-pro/mpvEx/app/src/main/assets/shaders/Anime4K_AutoDownscalePre_x2.glsl app/src/main/assets/shaders/
cp /home/lin/MuBOX-pro/mpvEx/app/src/main/assets/shaders/Anime4K_Restore_CNN_{S,M,L}.glsl app/src/main/assets/shaders/
cp /home/lin/MuBOX-pro/mpvEx/app/src/main/assets/shaders/Anime4K_Restore_CNN_Soft_{S,M,L}.glsl app/src/main/assets/shaders/
cp /home/lin/MuBOX-pro/mpvEx/app/src/main/assets/shaders/Anime4K_Upscale_CNN_x2_{S,M,L}.glsl app/src/main/assets/shaders/
cp /home/lin/MuBOX-pro/mpvEx/app/src/main/assets/shaders/Anime4K_Upscale_Denoise_CNN_x2_{S,M,L}.glsl app/src/main/assets/shaders/
```

Do not edit shader contents.

- [ ] **Step 4: Run asset test and verify GREEN**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests com.example.comicdav.video.player.Anime4KShaderAssetsTest
```

Expected: `Anime4KShaderAssetsTest` passes.

- [ ] **Step 5: Commit Task 7**

Run:

```bash
git add app/src/main/assets/shaders app/src/test/java/com/example/comicdav/video/player/Anime4KShaderAssetsTest.kt
git commit -m "feat: bundle anime4k shader assets"
```

---

### Task 8: Focused And Full Verification

**Files:**
- No planned source edits. If verification fails, fix the failing behavior in the owning task's files and commit that focused fix before repeating verification.

- [ ] **Step 1: Run focused Anime4K and player tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest \
  --tests com.example.comicdav.video.player.Anime4KManagerTest \
  --tests com.example.comicdav.video.player.Anime4KShaderAssetsTest \
  --tests com.example.comicdav.video.player.MpvControllerAdvancedControlsTest \
  --tests com.example.comicdav.video.player.PlayerOptionPanelUiTest \
  --tests com.example.comicdav.video.player.VideoPlayerActivityIntentTest \
  --tests com.example.comicdav.video.player.VideoPlayerActivityIntegrationTest \
  --tests com.example.comicdav.video.player.MuBoxMpvViewFactoryTest \
  --tests com.example.comicdav.data.AppSettingsStoreTest \
  --tests com.example.comicdav.feature.settings.SettingsScreenTest \
  --tests com.example.comicdav.feature.settings.SettingsScreenUiTest
```

Expected: all focused tests pass.

- [ ] **Step 2: Run all app unit tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest
```

Expected: all app debug unit tests pass.

- [ ] **Step 3: Inspect diff for scope**

Run:

```bash
git diff --stat HEAD~7..HEAD
git diff --name-only HEAD~7..HEAD
```

Expected: changed files are limited to Anime4K manager/assets, player controller/view/activity, settings store/screen/routes, MainActivity launch plumbing, and focused tests.

- [ ] **Step 4: Commit verification note if fixes were needed**

If Step 1 or Step 2 required fixes, commit them:

```bash
git add app/src/main app/src/test
git commit -m "fix: stabilize anime4k player integration"
```

If no fixes were needed, do not create an empty commit.
