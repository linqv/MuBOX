# ComicDav

Android comic reader for WebDAV libraries. Android/Kotlin owns UI and networking. Rust owns CBZ/ZIP parsing and page extraction.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

## Test Rust Core

```bash
cd comic-core
cargo test
```

## Supported ABI Targets

- arm64-v8a
- x86_64

Phase 0 configures the Android ABI filters and builds the Rust smoke library on the host. Android Rust cross-compilation is added in the JNI phase.
