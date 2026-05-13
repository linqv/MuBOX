# Agent Handoff

## Current State

- Repository root: `/home/lin/webcomic`
- Active worktree: `/home/lin/webcomic/.worktrees/phase-3-jni-local-reader`
- Active branch: `feature/phase-3-jni-local-reader`
- Latest branch commit: `f89d70e feat: connect rust core to android reader` before the final amend
- Base branch: `master`
- Base branch latest commit at handoff: `076c430 docs: add agent handoff`
- Phase 2 has been fast-forward merged into `master`.
- Phase 3 is implemented on the feature branch and should remain isolated until reviewed.

## Completed Work

Phase 3 connects the Rust CBZ core to Android:

- Rust FFI/session handle table in `comic-core/src/ffi.rs`
- Native functions for open, page count, page extraction to file, close, and last error
- `JNI_OnLoad` native registration for `com/example/comicdav/nativebridge/ComicNative`
- Kotlin native facade and safe wrapper in `nativebridge`
- Reader ViewModel with page preloading for previous/current/next pages
- Compose `ReaderScreen` using `HorizontalPager` and Coil `AsyncImage`
- Local Android document picker entry point from the account screen
- Selected local files are copied into app cache before Rust opens them by path
- Debug APK packaging now builds and includes `libcomic_core.so` for `arm64-v8a` and `x86_64`

## Important Files

- `comic-core/src/ffi.rs`
- `comic-core/Cargo.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/example/comicdav/nativebridge/ComicNative.kt`
- `app/src/main/java/com/example/comicdav/nativebridge/ComicEngine.kt`
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderViewModel.kt`
- `app/src/main/java/com/example/comicdav/feature/reader/ReaderScreen.kt`
- `app/src/main/java/com/example/comicdav/MainActivity.kt`
- `app/src/main/java/com/example/comicdav/feature/webdav/WebDavAccountScreen.kt`
- `app/src/test/java/com/example/comicdav/nativebridge/ComicEngineTest.kt`
- `app/src/test/java/com/example/comicdav/feature/reader/ReaderViewModelTest.kt`

## Environment Changes Used

The final successful build used rustup and Android SDK/NDK tooling:

```bash
export PATH="$HOME/.cargo/bin:$PATH"
```

Required Rust targets:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
```

Required Android NDK path exists under:

```text
/home/lin/Android/Sdk/ndk/28.0.13004108
```

## Verification Already Run

From `/home/lin/webcomic/.worktrees/phase-3-jni-local-reader/comic-core`:

```bash
PATH=/home/lin/.cargo/bin:$PATH cargo test
```

Result: passed.

- 4 library tests passed
- 3 `cbz_local` integration tests passed
- 2 `range_reader` integration tests passed
- 0 doc tests

From `/home/lin/webcomic/.worktrees/phase-3-jni-local-reader`:

```bash
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:testDebugUnitTest
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:assembleDebug
```

Result: both passed.

APK path:

```text
/home/lin/webcomic/.worktrees/phase-3-jni-local-reader/app/build/outputs/apk/debug/app-debug.apk
```

APK contents verified with `zipinfo`:

```text
lib/arm64-v8a/libcomic_core.so
lib/x86_64/libcomic_core.so
```

Device smoke status:

```bash
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.comicdav/.MainActivity
adb shell pidof com.example.comicdav
```

Result: APK installed successfully, app launched, and process was running on attached device `QOZHROCM9HFQ8DZT`.

## Not Done

- Visual/manual confirmation that a selected local CBZ page appears on the device was not completed in this handoff.
- Remote WebDAV whole-file download into the local reader is not implemented; this belongs to Phase 4.
- Remote range reader is not implemented; this belongs to Phase 5.
- ZIP64 full support, data descriptors, and GBK filename fallback remain future hardening work.

## Recommended Next Steps

1. Manually open the app on device `QOZHROCM9HFQ8DZT`, tap `Open Local CBZ`, choose a small local `.cbz` or `.zip`, and confirm the first page renders.
2. If manual device verification passes, merge Phase 3 into `master`:

```bash
git -C /home/lin/webcomic merge --ff-only feature/phase-3-jni-local-reader
```

3. Re-run verification on `master` after merge:

```bash
cd /home/lin/webcomic/comic-core
PATH=/home/lin/.cargo/bin:$PATH cargo test

cd /home/lin/webcomic
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:testDebugUnitTest
PATH=/home/lin/.cargo/bin:$PATH ./gradlew :app:assembleDebug
```

4. After a successful merge and verification, remove old worktrees/branches as desired:

```bash
git -C /home/lin/webcomic worktree remove /home/lin/webcomic/.worktrees/phase-2-rust-cbz-core
git -C /home/lin/webcomic branch -d feature/phase-2-rust-cbz-core
git -C /home/lin/webcomic worktree remove /home/lin/webcomic/.worktrees/phase-3-jni-local-reader
git -C /home/lin/webcomic branch -d feature/phase-3-jni-local-reader
```

5. Start Phase 4 from:

```text
/home/lin/webcomic/docs/superpowers/plans/2026-05-13-phase-4-whole-file-mvp.md
```
