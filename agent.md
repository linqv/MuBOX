# Agent Handoff

## Current State

- Repository root: `/home/lin/webcomic`
- Active worktree: `/home/lin/webcomic/.worktrees/phase-4-whole-file-mvp`
- Active branch: `feature/phase-4-whole-file-mvp`
- Base branch: `master`
- Base branch latest commit at handoff: `cb996c4 feat: connect rust core to android reader`
- Phase 3 has been fast-forward merged into `master`.
- Phase 4 is implemented on the feature branch and should remain isolated until reviewed.

## Completed Work

Phase 4 adds the whole-file cached remote-reading MVP:

- Added DataStore Preferences dependency.
- Added stable SHA-256 `ComicCacheKey` from account, remote path, size, and ETag/lastModified.
- Added `ReadingProgressStore` for saving/loading the current page by comic key.
- Added full-file streaming download support to `WebDavClient`.
- Implemented `OkHttpWebDavClient.download()` with streaming writes.
- Added `ComicDownloadCache` with `.tmp` writes, cancellation cleanup, final `.cbz` rename, and reuse when final size matches.
- Added `OpenComicUseCase` for `HEAD -> cache download -> Rust local session -> saved initial page`.
- Updated `ReaderViewModel` to open an existing native session and support initial page.
- Updated browser UI with download progress, cancel button, and visible remote-open errors.
- Wired remote comic row clicks to download and open through the local Rust reader.
- Saved remote reading progress on page changes.

## Important Files

- `app/src/main/java/com/example/comicdav/data/ComicDownloadCache.kt`
- `app/src/main/java/com/example/comicdav/data/ReadingProgressStore.kt`
- `app/src/main/java/com/example/comicdav/feature/reader/OpenComicUseCase.kt`
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- `app/src/main/java/com/example/comicdav/feature/webdav/WebDavBrowserScreen.kt`
- `app/src/main/java/com/example/comicdav/feature/webdav/WebDavViewModel.kt`
- `app/src/main/java/com/example/comicdav/network/WebDavClient.kt`
- `app/src/main/java/com/example/comicdav/network/OkHttpWebDavClient.kt`
- `app/src/main/java/com/example/comicdav/MainActivity.kt`
- `app/src/test/java/com/example/comicdav/data/ComicDownloadCacheTest.kt`
- `app/src/test/java/com/example/comicdav/data/ReadingProgressStoreTest.kt`
- `app/src/test/java/com/example/comicdav/feature/reader/OpenComicUseCaseTest.kt`
- `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`

## Environment Notes

Builds use rustup and Android SDK/NDK tooling:

```bash
export PATH="$HOME/.cargo/bin:$PATH"
```

Required Rust targets:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
```

Required Android NDK path:

```text
/home/lin/Android/Sdk/ndk/28.0.13004108
```

## Verification Already Run

From `/home/lin/webcomic/.worktrees/phase-4-whole-file-mvp/comic-core`:

```bash
PATH=/home/lin/.cargo/bin:$PATH cargo test
```

Result: passed.

- 4 library tests passed
- 3 `cbz_local` integration tests passed
- 2 `range_reader` integration tests passed
- 0 doc tests

From `/home/lin/webcomic/.worktrees/phase-4-whole-file-mvp`:

```bash
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:testDebugUnitTest
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:assembleDebug
```

Result: both passed.

APK path:

```text
/home/lin/webcomic/.worktrees/phase-4-whole-file-mvp/app/build/outputs/apk/debug/app-debug.apk
```

APK contents verified with `zipinfo`:

```text
lib/arm64-v8a/libcomic_core.so
lib/x86_64/libcomic_core.so
```

## Not Done

- Device install/open smoke for Phase 4 was not completed because ADB reported `no devices/emulators found` after the final APK build.
- Manual remote WebDAV verification is still needed: connect to a real WebDAV server, tap a remote `.cbz`/`.zip`, confirm download progress, page display, cancel behavior, and resume page after reopening.
- Remote range reader is not implemented; this belongs to Phase 5.
- ZIP64 full support, data descriptors, and GBK filename fallback remain future hardening work.

## Recommended Next Steps

1. Reconnect/authorize an Android device and install the APK:

```bash
cd /home/lin/webcomic/.worktrees/phase-4-whole-file-mvp
adb devices
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.comicdav/.MainActivity
```

2. Manually verify remote whole-file opening against a WebDAV account:

- Connect to WebDAV.
- Tap a remote `.cbz`/`.zip`.
- Confirm progress updates.
- Cancel one download and confirm no broken state.
- Reopen and confirm the cached file displays.
- Change page, close, reopen, and confirm progress resumes.

3. If manual verification passes, merge Phase 4 into `master`:

```bash
git -C /home/lin/webcomic merge --ff-only feature/phase-4-whole-file-mvp
```

4. Re-run verification on `master` after merge:

```bash
cd /home/lin/webcomic/comic-core
PATH=/home/lin/.cargo/bin:$PATH cargo test

cd /home/lin/webcomic
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:testDebugUnitTest
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:assembleDebug
```

5. Start Phase 5 from:

```text
/home/lin/webcomic/docs/superpowers/plans/2026-05-13-phase-5-remote-range-reader.md
```
