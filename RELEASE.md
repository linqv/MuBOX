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
- Third-party dependency licenses must be reviewed before distribution.
