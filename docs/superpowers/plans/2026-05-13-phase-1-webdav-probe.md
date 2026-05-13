# Phase 1 WebDAV Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Android app test a WebDAV account, browse one directory, detect `.cbz` and `.zip`, and verify byte-range reads.

**Architecture:** OkHttp performs PROPFIND, HEAD, and Range GET. XML parsing stays in a small parser helper so response handling can be unit tested without Android UI. Compose screens call a ViewModel that exposes immutable state.

**Tech Stack:** Kotlin, OkHttp, XmlPullParser, Jetpack Compose, coroutines, JUnit.

---

## Files

- Modify: `/home/lin/webcomic/app/build.gradle.kts` - add OkHttp, coroutines, lifecycle ViewModel, navigation test dependencies.
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/WebDavModels.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/WebDavClient.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/OkHttpWebDavClient.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/network/WebDavXmlParser.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/webdav/WebDavViewModel.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/webdav/WebDavAccountScreen.kt`
- Create: `/home/lin/webcomic/app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- Create: `/home/lin/webcomic/app/src/test/java/com/example/comicdav/network/WebDavXmlParserTest.kt`
- Create: `/home/lin/webcomic/app/src/test/java/com/example/comicdav/network/WebDavRangeResponseTest.kt`

## Task 1: Add Network Models and Failing Parser Tests

- [ ] **Step 1: Add dependencies**

In `app/build.gradle.kts`, add:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 2: Create model file**

Create `WebDavModels.kt` with `WebDavItem`, `RemoteFileInfo`, `RangeReadResult`, and `WebDavException`.

- [ ] **Step 3: Write failing XML parser test**

Create `WebDavXmlParserTest.kt` with:

```kotlin
@Test
fun parsesDirectoryAndComicEntries() {
    val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
        <d:response><d:href>/comics/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        <d:response><d:href>/comics/01.cbz</d:href><d:propstat><d:prop><d:getcontentlength>123</d:getcontentlength><d:getetag>"abc"</d:getetag></d:prop></d:propstat></d:response>
    </d:multistatus>"""
    val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")
    assertEquals(listOf("01.cbz"), items.map { it.name })
    assertEquals(123L, items.single().size)
    assertFalse(items.single().isDirectory)
}
```

- [ ] **Step 4: Run failing test**

Run: `./gradlew :app:testDebugUnitTest --tests '*WebDavXmlParserTest*'`

Expected: fails because `WebDavXmlParser` is missing.

## Task 2: Implement XML Parser

- [ ] **Step 1: Implement parser**

Create `WebDavXmlParser.kt` using `XmlPullParserFactory.newInstance().newPullParser()`. Parse `href`, `collection`, `getcontentlength`, `getetag`, and `getlastmodified`. Exclude the current directory entry where normalized href equals `basePath`.

- [ ] **Step 2: Run parser tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*WebDavXmlParserTest*'`

Expected: parser tests pass.

## Task 3: Implement OkHttp Client and Range Validation

- [ ] **Step 1: Define interface**

Create `WebDavClient.kt`:

```kotlin
interface WebDavClient {
    suspend fun list(path: String): List<WebDavItem>
    suspend fun head(path: String): RemoteFileInfo
    suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray
}
```

- [ ] **Step 2: Write failing range validation tests**

In `WebDavRangeResponseTest.kt`, use MockWebServer to cover `206` with valid `Content-Range`, `206` with wrong total, and `200 OK` fallback.

- [ ] **Step 3: Implement `OkHttpWebDavClient`**

Use `PROPFIND` with `Depth: 1`, `HEAD`, and `GET` with header `Range: bytes=start-end`. For `206`, require `Content-Range` to match `bytes start-end/total`. For `200`, throw `WebDavException.RangeNotSupported`.

- [ ] **Step 4: Run client tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*WebDavRangeResponseTest*'`

Expected: all range validation tests pass.

## Task 4: Add Probe UI

- [ ] **Step 1: Implement ViewModel state**

Create `WebDavViewModel.kt` with fields for URL, username, password, current path, items, selected item, connection status, and diagnostic text.

- [ ] **Step 2: Implement account screen**

Create `WebDavAccountScreen.kt` with URL, username, password fields and a Test button that calls `viewModel.testConnection()`.

- [ ] **Step 3: Implement browser screen**

Create `WebDavBrowserScreen.kt` with folder rows, comic rows, and a Tail 64 KiB diagnostic action for selected `.cbz` or `.zip`.

- [ ] **Step 4: Wire MainActivity**

Modify `MainActivity.kt` to show `WebDavAccountScreen` first and `WebDavBrowserScreen` after a successful connection.

## Verification

- [ ] Run `./gradlew :app:testDebugUnitTest`.
- [ ] Run `./gradlew :app:assembleDebug`.
- [ ] Manually connect to one WebDAV service.
- [ ] Open a directory with one `.cbz` or `.zip`.
- [ ] Run the tail 64 KiB diagnostic and confirm Range support or a clear fallback error.
- [ ] Commit: `feat: add webdav browser and range probe`.
