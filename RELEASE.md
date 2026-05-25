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
  - MuPDF (fitz-1.27.1.aar): AGPL-3.0-or-later. Source must be made available
    to recipients; see https://mupdf.com/downloads/ for MuPDF 1.27.1 source.
  - mpvEx / mpv-android-lib: Apache-2.0 / MIT. libmpv is LGPL-2.1-or-later.
  - Android Gradle dependencies: Apache-2.0.
  - Rust crates (libcomic_core.so): MIT / Apache-2.0 / Zlib / BSD-3-Clause.
  - Full details are in `NOTICE`.
