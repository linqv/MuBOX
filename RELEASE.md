# MuBOX Release Checklist

## Before Building

- Confirm `README.md`, `LICENSE`, and `NOTICE` are included in the source package.
- Confirm release signing is configured through `MUBOX_RELEASE_*` properties or environment variables.
- Run unit tests: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest`.
- Run Rust tests: `cd comic-core && cargo test`.
- Build a release APK for the target ABI.

## Android Release Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleRelease -PtargetAbi=arm64-v8a
```

Release builds deliberately use the slowest, most aggressive optimization path:

- Rust `opt-level = 3`, Fat LTO across the full crate graph, one codegen unit,
  abort-on-panic, and stripped symbols.
- R8 full-mode code optimization, obfuscation, class repackaging, and integrated
  resource shrinking. AGP 9.2 enables these pipelines by default once release
  minification and resource shrinking are enabled.
- Uncompressed, page-aligned native libraries for direct loading on API 23+.

Do not use `target-cpu=native` for distribution builds: it optimizes for the
build host rather than the Android target device and can produce incompatible
binaries.

## Manual Smoke Test

- Launch MuBOX and confirm the launcher icon and app name are correct.
- Add or edit a WebDAV source.
- Open a remote comic from WebDAV.
- Open a local comic source.
- Verify settings, cache cleanup, and reader navigation.

## License Review

- MuBOX source code is released under GPLv3-or-later.
- Binary releases must provide corresponding source code and retain license notices.
- Third-party dependency licenses have been reviewed and documented in `NOTICE`.
  - mpvEx / mpv-android-lib: Apache-2.0 / MIT. libmpv is LGPL-2.1-or-later.
  - Android Gradle dependencies: Apache-2.0.
  - Rust crates (libcomic_core.so): MIT / Apache-2.0 / Zlib / BSD-3-Clause.
  - Full details are in `NOTICE`.
