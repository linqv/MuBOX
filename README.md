# MuBOX

MuBOX is an Android comic reader for local folders and WebDAV libraries. Android/Kotlin owns UI and networking. Rust owns CBZ/ZIP parsing and page extraction.

Android release package ID: `org.mubox.reader`.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
```

## Optimized ARM64 Release Build

Create a local `keystore.properties` first. Do not commit this file.

```properties
storeFile=/absolute/path/to/mubox-release.jks
storePassword=your-store-password
keyAlias=mubox
keyPassword=your-key-password
```

The same values can also be supplied with `MUBOX_RELEASE_STORE_FILE`,
`MUBOX_RELEASE_STORE_PASSWORD`, `MUBOX_RELEASE_KEY_ALIAS`, and
`MUBOX_RELEASE_KEY_PASSWORD`.

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

## Release Preparation

See `RELEASE.md` before publishing an APK.

## License

MuBOX is free software distributed under the GNU General Public License v3.0 or later. See `LICENSE` and `NOTICE`.
