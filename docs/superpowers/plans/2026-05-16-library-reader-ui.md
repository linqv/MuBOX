# Library Reader UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manga reader experience with separate manually saved file directories, a favorites-style Room-backed library, first-run data-folder selection, WebDAV/local browse flows, and a polished reader plus WebDAV UI.

**Architecture:** Room owns library metadata plus manually saved file directory source records. DataStore owns the selected app data-folder URI. Existing reader/opening code remains the execution path, with small adapters for library items and file directory entries. Compose screens are split into file directory, library, WebDAV browser, first-run gate, and reader UI.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android Room, DataStore Preferences, Coil 3, existing Rust/JNI CBZ reader.

---

## File Structure

- Create `app/src/main/java/com/example/comicdav/data/AppDataFolderStore.kt`: persists the selected document tree URI.
- Create `app/src/main/java/com/example/comicdav/data/library/LibraryEntities.kt`: Room entities, enums, and relation models.
- Create `app/src/main/java/com/example/comicdav/data/library/LibraryDao.kt`: DAO for library item CRUD and source lookup.
- Create `app/src/main/java/com/example/comicdav/data/library/LibraryDatabase.kt`: Room database definition.
- Create `app/src/main/java/com/example/comicdav/data/library/LibraryRepository.kt`: repository API for local/WebDAV add and list operations.
- Create `app/src/main/java/com/example/comicdav/feature/library/LibraryViewModel.kt`: screen state and actions.
- Create `app/src/main/java/com/example/comicdav/feature/library/LibraryScreen.kt`: library home grid and empty state.
- Create `app/src/main/java/com/example/comicdav/data/filedirectory/`: Room entities, DAO, and repository for manually added local/WebDAV directory sources only.
- Create `app/src/main/java/com/example/comicdav/feature/filedirectory/`: file directory view model, SAF local directory reader, and Compose screen for recursive on-demand local folder browsing.
- Create `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`: app theme tokens and MaterialTheme wrapper.
- Modify `app/build.gradle.kts`: add Room runtime, KSP or kapt compiler, and Room test dependency.
- Modify `app/src/main/java/com/example/comicdav/MainActivity.kt`: route first-run folder selection, library home, WebDAV browse, local add, and reader open.
- Modify `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`: redesign browse rows and expose add/open actions.
- Modify `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`: immersive reader controls and polished states.
- Add focused unit tests under `app/src/test/java/com/example/comicdav/data/` and `app/src/test/java/com/example/comicdav/feature/library/`.

## Task 1: Room Library Foundation

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/example/comicdav/data/library/LibraryEntities.kt`
- Create: `app/src/main/java/com/example/comicdav/data/library/LibraryDao.kt`
- Create: `app/src/main/java/com/example/comicdav/data/library/LibraryDatabase.kt`
- Create: `app/src/main/java/com/example/comicdav/data/library/LibraryRepository.kt`
- Test: `app/src/test/java/com/example/comicdav/data/LibraryRepositoryTest.kt`

- [ ] **Step 1: Write failing repository tests**

Create tests for:

```kotlin
@Test
fun addLocalComicStoresPersistedUriWithoutCopyingSource() = runTest {
    val repository = repository()

    val item = repository.addLocalComic(
        uri = "content://library/book.cbz",
        fileName = "book.cbz",
        size = 1234L,
        lastModified = 99L,
    )

    val library = repository.observeLibrary().first()
    assertEquals(item.id, library.single().item.id)
    assertEquals("book", library.single().item.title)
    assertEquals("content://library/book.cbz", library.single().localSource?.uri)
    assertEquals(SourceType.LOCAL, library.single().item.sourceType)
}

@Test
fun addWebDavComicStoresRemoteIdentityAndMetadata() = runTest {
    val repository = repository()

    val item = repository.addWebDavComic(
        accountId = "https://example.test/dav|lin",
        remotePath = "/manga/vol1.cbz",
        fileName = "vol1.cbz",
        size = 2048L,
        etag = "\"abc\"",
        lastModified = 100L,
    )

    val saved = repository.observeLibrary().first().single()
    assertEquals(item.id, saved.item.id)
    assertEquals(SourceType.WEBDAV, saved.item.sourceType)
    assertEquals("/manga/vol1.cbz", saved.webDavSource?.remotePath)
    assertEquals(OfflineState.NOT_DOWNLOADED, saved.item.offlineState)
}
```

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.data.LibraryRepositoryTest'`

Expected: compile failure because the Room classes do not exist.

- [ ] **Step 3: Add Room dependencies**

Add the Kotlin kapt plugin through the root and app Gradle files, using the same Kotlin version already used by the project:

```kotlin
// build.gradle.kts at repo root
plugins {
    id("org.jetbrains.kotlin.kapt") version "2.3.21" apply false
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.kapt")
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.compose.material:material-icons-extended")
    kapt("androidx.room:room-compiler:2.8.4")
    testImplementation("androidx.room:room-testing:2.8.4")
}
```

- [ ] **Step 4: Implement entities, DAO, database, and repository**

Use the schema in `docs/superpowers/specs/2026-05-16-library-reader-ui-design.md`. Keep entities in `data/library` and expose a repository method per add/list operation.

- [ ] **Step 5: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.data.LibraryRepositoryTest'`

Expected: tests pass.

## Task 2: First-Run Data Folder Gate

**Files:**
- Create: `app/src/main/java/com/example/comicdav/data/AppDataFolderStore.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/data/AppDataFolderStoreTest.kt`

- [ ] **Step 1: Write failing DataStore test**

```kotlin
@Test
fun savesAndLoadsSelectedDataFolderUri() = runTest {
    val store = AppDataFolderStore(dataStore("app-data-folder.preferences_pb"))

    store.saveFolderUri("content://tree/comicdav")

    assertEquals("content://tree/comicdav", store.loadFolderUri())
}
```

- [ ] **Step 2: Run test and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.data.AppDataFolderStoreTest'`

Expected: compile failure because `AppDataFolderStore` does not exist.

- [ ] **Step 3: Implement store**

Create a small DataStore-backed class with `saveFolderUri(uri: String)` and `loadFolderUri(): String?`.

- [ ] **Step 4: Add first-run gate in `MainActivity.kt`**

Use `ActivityResultContracts.OpenDocumentTree()`. Persist read/write URI permission. Show a Compose gate screen until a folder URI exists.

- [ ] **Step 5: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.data.AppDataFolderStoreTest'`

Expected: tests pass.

## Task 3: Library Home And Add Actions

**Files:**
- Create: `app/src/main/java/com/example/comicdav/feature/library/LibraryViewModel.kt`
- Create: `app/src/main/java/com/example/comicdav/feature/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/library/LibraryViewModelTest.kt`

- [ ] **Step 1: Write failing view model tests**

Cover empty library state, local add success, WebDAV add success, and open selection state.

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.feature.library.LibraryViewModelTest'`

Expected: compile failure because library feature classes do not exist.

- [ ] **Step 3: Implement view model**

Expose immutable state with items, loading flag, error, and selected open request. Delegate persistence to `LibraryRepository`.

- [ ] **Step 4: Implement Compose screen**

Use a cover grid, empty state, source badges, progress text, and add actions for local and WebDAV. Keep touch targets at least 48 dp.

- [ ] **Step 5: Wire app shell**

After the data-folder gate, show library home. Local add uses `OpenDocument`; WebDAV add routes through the browser.

- [ ] **Step 6: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.feature.library.LibraryViewModelTest'`

Expected: tests pass.

## Task 4: WebDAV Browser Redesign And Add-To-Library

**Files:**
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/webdav/WebDavViewModel.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Test: `app/src/test/java/com/example/comicdav/feature/webdav/WebDavViewModelTest.kt`

- [ ] **Step 1: Add failing tests for item selection**

Test that file rows can be selected for add/open actions and that directories still navigate.

- [ ] **Step 2: Run tests and confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.feature.webdav.WebDavViewModelTest'`

Expected: failure for missing or incomplete selection behavior.

- [ ] **Step 3: Redesign screen**

Add path header, folder/file row icons from `material-icons-extended`, metadata text, and two file actions: `Open` and `Add`.

- [ ] **Step 4: Wire add action**

For WebDAV file rows, call `LibraryRepository.addWebDavComic` through the app shell or library view model. Preserve immediate open behavior.

- [ ] **Step 5: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.feature.webdav.WebDavViewModelTest'`

Expected: tests pass.

## Task 5: Immersive Reader UI Polish

**Files:**
- Create: `app/src/main/java/com/example/comicdav/ui/ComicDavTheme.kt`
- Modify: `app/src/main/java/com/example/comicdav/MainActivity.kt`
- Modify: `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`

- [ ] **Step 1: Preserve reader behavior tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.feature.reader.*'`

Expected: existing tests pass before changes.

- [ ] **Step 2: Implement app theme**

Create a Material 3 theme with calm surfaces for library/browse and black reader canvas.

- [ ] **Step 3: Redesign reader screen**

Use full-screen black canvas, tap-to-toggle overlays, top close/log controls, bottom page counter/progress slider, refined loading progress, and actionable error state.

- [ ] **Step 4: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.comicdav.feature.reader.*'`

Expected: existing reader tests still pass.

## Final Verification

- [ ] Run `cargo test` from `comic-core`.
- [ ] Run `./gradlew :app:testDebugUnitTest` from the worktree root.
- [ ] Run `./gradlew :app:assembleDebug` if the Android SDK/NDK is available.
- [ ] Inspect `git status --short` and summarize changed files.
