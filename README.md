# ComicDav

Android comic reader for WebDAV libraries. Android/Kotlin owns UI and networking. Rust owns CBZ/ZIP parsing and page extraction.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

## Optimized ARM64 Release Build

Create a local `keystore.properties` first. Do not commit this file.

```properties
storeFile=/absolute/path/to/comicdav-release.jks
storePassword=your-store-password
keyAlias=comicdav
keyPassword=your-key-password
```

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleRelease -PtargetAbi=arm64-v8a
```

The release build fails if signing is not configured, instead of producing an unsigned APK.

## Test Rust Core

```bash
cd comic-core
cargo test
```

## Supported ABI Targets

- arm64-v8a
- x86_64

Use `-PtargetAbi=<abi>` to build a single ABI. Android builds cross-compile the Rust JNI library for the selected ABI.
